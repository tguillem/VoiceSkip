// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.domain

import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.voiceskip.data.ErrorHandler
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.ModelRepository
import com.voiceskip.data.repository.SettingsRepository
import com.voiceskip.data.repository.TranscriptionRepository
import com.voiceskip.ui.main.FileManager
import com.voiceskip.util.VoiceSkipLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "ModelManager"
private const val REFERENCE_SCHEME = "content://"

private data class ObservedModelSettings(
    val model: String,
    val gpuEnabled: Boolean,
    val turboEnabled: Boolean,
    val reloadGeneration: Long
)

@Singleton
class ModelManager @Inject constructor(
    private val repository: TranscriptionRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val userPreferences: UserPreferences,
    private val fileManager: FileManager
) {
    sealed class ModelState {
        object NotLoaded : ModelState()
        data class Loading(val modelId: String, val useGpu: Boolean) : ModelState()
        data class Loaded(val modelId: String, val gpuInfo: String?) : ModelState()
        data class Error(val exception: Throwable) : ModelState()
    }

    enum class GpuFallbackReason { CRASH, UNAVAILABLE }

    enum class TurboFallbackReason { CRASH }

    enum class ModelFallbackReason { UNAVAILABLE, LOAD_FAILED }

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _gpuFallbackReason = MutableStateFlow<GpuFallbackReason?>(null)
    val gpuFallbackReason: StateFlow<GpuFallbackReason?> = _gpuFallbackReason.asStateFlow()

    private val _turboFallbackReason = MutableStateFlow<TurboFallbackReason?>(null)
    val turboFallbackReason: StateFlow<TurboFallbackReason?> = _turboFallbackReason.asStateFlow()

    private val _modelFallbackReason = MutableStateFlow<ModelFallbackReason?>(null)
    val modelFallbackReason: StateFlow<ModelFallbackReason?> = _modelFallbackReason.asStateFlow()

    private val modelReloadGeneration = MutableStateFlow(0L)
    private var handledModelReloadGeneration = 0L

    fun clearGpuFallbackReason() {
        _gpuFallbackReason.value = null
    }

    fun clearTurboFallbackReason() {
        _turboFallbackReason.value = null
    }

    fun clearModelFallbackReason() {
        _modelFallbackReason.value = null
    }

    fun requestModelReload() {
        modelReloadGeneration.update { it + 1 }
    }

    fun getLoadedModelId(): String? = when (val state = _modelState.value) {
        is ModelState.Loaded -> state.modelId
        else -> null
    }

    fun getModelFlow(): Flow<String> = userPreferences.model

    private fun isReference(modelId: String) = modelId.startsWith(REFERENCE_SCHEME)

    /**
     * Resolves a model id to the arguments the native loader needs. A bundled model is an
     * asset path; an imported model is loaded from a file descriptor opened for the duration
     * of [block] (the descriptor must stay open until the native load completes).
     */
    private suspend fun <T> withModelSource(
        modelId: String,
        block: suspend (modelPath: String?, modelFd: Int) -> T
    ): T {
        if (!isReference(modelId)) {
            return block("models/$modelId", -1)
        }
        val pfd = modelRepository.openImportedModel(modelId)
            ?: throw IOException("Imported model is no longer available: $modelId")
        return pfd.use { block(null, it.fd) }
    }

    suspend fun loadModel(assets: AssetManager, forceReload: Boolean = false): Result<Unit> {
        var pfd: ParcelFileDescriptor? = null
        suspend fun finishImportedModelLoad() {
            val descriptor = pfd ?: return
            withContext(NonCancellable) {
                try {
                    modelRepository.setImportedModelLoadInProgress(false)
                } finally {
                    descriptor.close()
                    pfd = null
                }
            }
        }

        return runCatching {
            var requestedModel = userPreferences.model.first()
            var gpuEnabled = userPreferences.gpuEnabled.first()

            if (gpuEnabled && userPreferences.isGpuInProgress()) {
                VoiceSkipLogger.w("Previous GPU operation crashed, falling back to CPU")
                userPreferences.setGpuInProgress(false)
                userPreferences.setGpuEnabled(false)
                gpuEnabled = false
                _gpuFallbackReason.value = GpuFallbackReason.CRASH
            }

            // The flag survives a kill, so it is still set if loading the custom model hung or
            // took the process down. Retrying would hang again, so drop the selection.
            if (modelRepository.isImportedModelLoadInProgress()) {
                modelRepository.setImportedModelLoadInProgress(false)
                if (isReference(requestedModel)) {
                    VoiceSkipLogger.w("Previous custom model load failed, falling back to default model")
                    requestedModel = userPreferences.getDefaultModelForContext()
                    userPreferences.setModel(requestedModel)
                    _modelFallbackReason.value = ModelFallbackReason.LOAD_FAILED
                }
            }

            if (userPreferences.isTurboLoadInProgress()) {
                VoiceSkipLogger.w("Previous turbo CPU load crashed, disabling turbo mode")
                userPreferences.setTurboLoadInProgress(false)
                // Recording the choice is what stops maybeAutoEnableTurbo() from switching
                // turbo straight back on later in this same call and re-crashing.
                settingsRepository.updateTurboModeEnabled(
                    enabled = false,
                    isUserAction = true
                ).getOrThrow()
                _turboFallbackReason.value = TurboFallbackReason.CRASH
            }

            val currentState = _modelState.value

            if (!forceReload) {
                when (currentState) {
                    is ModelState.Loading -> {
                        if (currentState.modelId == requestedModel && currentState.useGpu == gpuEnabled) {
                            VoiceSkipLogger.d("Model already loading with same settings, skipping")
                            return@runCatching
                        }
                    }
                    is ModelState.Loaded -> {
                        if (currentState.modelId == requestedModel && (currentState.gpuInfo != null) == gpuEnabled) {
                            VoiceSkipLogger.d("Model already loaded with same settings, skipping")
                            return@runCatching
                        }
                    }
                    else -> {}
                }
            }

            // An imported reference opens a file descriptor; if the reference is gone (file
            // moved/deleted or permission revoked), fall back to the bundled default so the
            // app never gets stuck on an unusable selection.
            var modelId = requestedModel
            if (isReference(modelId)) {
                pfd = modelRepository.openImportedModel(modelId)
                if (pfd == null) {
                    VoiceSkipLogger.w("Imported model unavailable, falling back to default model")
                    modelId = userPreferences.getDefaultModelForContext()
                    userPreferences.setModel(modelId)
                    _modelFallbackReason.value = ModelFallbackReason.UNAVAILABLE
                }
            }
            val modelPath = if (pfd != null) null else "models/$modelId"
            val modelFd = pfd?.fd ?: -1

            _modelState.value = ModelState.Loading(modelId, gpuEnabled)

            if (gpuEnabled) {
                userPreferences.setGpuInProgress(true)
            }

            if (pfd != null) {
                modelRepository.setImportedModelLoadInProgress(true)
            }

            fileManager.copyAssets(assets)

            val vadModelPath = userPreferences.getVadModelPath()
            VoiceSkipLogger.i("Loading model: $modelId, vadModel: $vadModelPath, GPU: ${if (gpuEnabled) "enabled" else "disabled"}, forceReload: $forceReload")

            var result = repository.loadModel(
                assets,
                modelPath,
                vadModelPath,
                gpuEnabled,
                forceReload,
                modelFd
            )
            val importedModelFailure = result.exceptionOrNull()
            if (
                importedModelFailure != null &&
                importedModelFailure !is CancellationException &&
                isReference(modelId)
            ) {
                Log.w(LOG_TAG, importedModelFailure)
                VoiceSkipLogger.e("Failed to load imported model", importedModelFailure)

                modelId = userPreferences.getDefaultModelForContext()
                userPreferences.setModel(modelId)
                _modelFallbackReason.value = ModelFallbackReason.LOAD_FAILED
                finishImportedModelLoad()

                _modelState.value = ModelState.Loading(modelId, gpuEnabled)
                VoiceSkipLogger.i(
                    "Loading fallback model: $modelId, vadModel: $vadModelPath, " +
                        "GPU: ${if (gpuEnabled) "enabled" else "disabled"}"
                )
                result = repository.loadModel(
                    assets,
                    "models/$modelId",
                    vadModelPath,
                    gpuEnabled,
                    forceReload = true,
                    modelFd = -1
                )
            }
            val gpuInfo = result.getOrElse { e ->
                Log.w(LOG_TAG, e)
                VoiceSkipLogger.e("Failed to load model", e)
                _modelState.value = ModelState.Error(e)
                throw e
            }
            finishImportedModelLoad()

            if (gpuEnabled) {
                userPreferences.setGpuInProgress(false)
            }

            if (gpuEnabled && gpuInfo == null) {
                VoiceSkipLogger.w("GPU requested but unavailable, disabling GPU setting")
                userPreferences.setGpuEnabled(false)
                _gpuFallbackReason.value = GpuFallbackReason.UNAVAILABLE
            }
            _modelState.value = ModelState.Loaded(modelId, gpuInfo)

            if (repository.isTurboModelLoaded()) {
                VoiceSkipLogger.i("Reloading turbo CPU model: $modelId")
                val failure = loadTurboModel(assets, modelId, vadModelPath).exceptionOrNull()
                if (failure != null) {
                    disableTurboSettingAfterFailure()
                    VoiceSkipLogger.e("Failed to reload turbo CPU model", failure)
                }
            }

            maybeAutoEnableTurbo(assets, modelId, vadModelPath, gpuInfo)

            VoiceSkipLogger.d("Model state updated to: Loaded(id=$modelId, gpu=$gpuInfo)")
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { exception ->
                userPreferences.setGpuInProgress(false)
                if (exception is CancellationException) {
                    // ModelManager is a singleton and outlives the cancelled scope, so a
                    // leftover Loading state would make the next loadModel() hit the
                    // "already loading" guard and skip a load that will never happen.
                    if (_modelState.value is ModelState.Loading) {
                        _modelState.value = ModelState.NotLoaded
                    }
                    finishImportedModelLoad()
                    throw exception
                }
                val whisperError = ErrorHandler.handleError(exception)
                ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
                VoiceSkipLogger.e("Failed to initialize model loading", whisperError)
                _modelState.value = ModelState.Error(whisperError)
                Result.failure(whisperError)
            }
        ).also {
            finishImportedModelLoad()
        }
    }

    fun isModelLoaded(): Boolean = _modelState.value is ModelState.Loaded

    suspend fun updateTurboMode(assets: AssetManager, enabled: Boolean): Result<Unit> {
        return try {
            if (enabled) {
                val currentState = _modelState.value
                if (currentState !is ModelState.Loaded) {
                    VoiceSkipLogger.w("Cannot enable turbo mode: model not loaded")
                    return Result.success(Unit)
                }
                if (!repository.isTurboModelLoaded()) {
                    val vadModelPath = userPreferences.getVadModelPath()
                    VoiceSkipLogger.i("Loading CPU model for turbo mode: ${currentState.modelId}")
                    loadTurboModel(assets, currentState.modelId, vadModelPath).getOrThrow()
                    VoiceSkipLogger.i("CPU model loaded for turbo mode")
                }
            } else {
                if (repository.isTurboModelLoaded()) {
                    VoiceSkipLogger.i("Destroying CPU model (turbo mode disabled)")
                    repository.unloadTurboModel().getOrThrow()
                }
            }
            Result.success(Unit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            disableTurboSettingAfterFailure()
            val whisperError = ErrorHandler.handleError(exception)
            ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            VoiceSkipLogger.e("Failed to update turbo mode", whisperError)
            Result.failure(whisperError)
        }
    }

    fun isTurboCpuLoaded(): Boolean = repository.isTurboModelLoaded()

    private suspend fun loadTurboModel(
        assets: AssetManager,
        modelId: String,
        vadModelPath: String?
    ): Result<Unit> {
        userPreferences.setTurboLoadInProgress(true)
        return try {
            val result = withModelSource(modelId) { modelPath, modelFd ->
                repository.loadTurboModel(assets, modelPath, vadModelPath, modelFd)
            }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) {
                throw failure
            }
            result
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Result.failure(exception)
        } finally {
            userPreferences.setTurboLoadInProgress(false)
        }
    }

    private suspend fun disableTurboSettingAfterFailure() {
        // Recorded as a decision so the next cold start does not auto-enable turbo again
        // and repeat the load that just failed.
        val failure = settingsRepository.updateTurboModeEnabled(
            enabled = false,
            isUserAction = true
        ).exceptionOrNull() ?: return
        if (failure is CancellationException) {
            throw failure
        }
        VoiceSkipLogger.e("Failed to disable turbo setting", failure)
    }

    private suspend fun maybeAutoEnableTurbo(assets: AssetManager, modelId: String, vadModelPath: String?, gpuInfo: String?) {
        if (userPreferences.turboModeHasBeenSet.first()) return

        val gpuActive = gpuInfo != null
        if (!gpuActive) return

        if (!userPreferences.shouldAutoEnableTurboForDevice()) return

        VoiceSkipLogger.i("Auto-enabling turbo mode (first run, high-spec device)")
        settingsRepository.updateTurboModeEnabled(
            enabled = true,
            isUserAction = false
        ).getOrThrow()

        val failure = loadTurboModel(assets, modelId, vadModelPath).exceptionOrNull()
        if (failure != null) {
            disableTurboSettingAfterFailure()
            VoiceSkipLogger.e("Failed to auto-enable turbo mode", failure)
            return
        }
        VoiceSkipLogger.i("Turbo mode auto-enabled and CPU model loaded")
    }

    @OptIn(FlowPreview::class)
    fun startObservingSettings(assets: AssetManager, scope: CoroutineScope) {
        var previousModel: String? = null
        var previousGpuEnabled: Boolean? = null

        scope.launch {
            combine(
                userPreferences.model,
                userPreferences.gpuEnabled,
                userPreferences.turboModeEnabled,
                modelReloadGeneration
            ) { model, gpu, turbo, reloadGeneration ->
                ObservedModelSettings(model, gpu, turbo, reloadGeneration)
            }
                .debounce(100)
                .collect { settings ->
                    val model = settings.model
                    val gpuEnabled = settings.gpuEnabled
                    val turboEnabled = settings.turboEnabled
                    val isFirstEmission = previousModel == null
                    val modelOrGpuChanged = !isFirstEmission &&
                        (previousModel != model || previousGpuEnabled != gpuEnabled)
                    val reloadRequested =
                        handledModelReloadGeneration != settings.reloadGeneration
                    val shouldUseTurbo = turboEnabled && gpuEnabled

                    if (!shouldUseTurbo && repository.isTurboModelLoaded()) {
                        VoiceSkipLogger.i("Turbo mode no longer active, unloading")
                        updateTurboMode(assets, false)
                    }

                    val loadedState = _modelState.value as? ModelState.Loaded
                    val importedFallbackAlreadyLoaded =
                        previousModel?.let(::isReference) == true &&
                            !isReference(model) &&
                            previousGpuEnabled == gpuEnabled &&
                            loadedState?.modelId == model

                    if (
                        reloadRequested ||
                        (modelOrGpuChanged && !importedFallbackAlreadyLoaded)
                    ) {
                        VoiceSkipLogger.i("Model settings or source changed, forcing reload...")
                        loadModel(assets, forceReload = true)
                    }
                    handledModelReloadGeneration = settings.reloadGeneration

                    if (shouldUseTurbo && !repository.isTurboModelLoaded()) {
                        VoiceSkipLogger.i("Turbo mode enabled but not loaded, loading now")
                        updateTurboMode(assets, true)
                    }

                    previousModel = model
                    previousGpuEnabled = gpuEnabled
                }
        }
    }
}
