// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.domain

import android.content.res.AssetManager
import android.util.Log
import com.voiceskip.data.ErrorHandler
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.TranscriptionRepository
import com.voiceskip.ui.main.FileManager
import com.voiceskip.util.VoiceSkipLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val LOG_TAG = "ModelManager"

class ModelManager(
    private val repository: TranscriptionRepository,
    private val userPreferences: UserPreferences,
    private val fileManager: FileManager
) {
    sealed class ModelState {
        object NotLoaded : ModelState()
        data class Loading(val modelPath: String, val useGpu: Boolean) : ModelState()
        data class Loaded(val modelPath: String, val gpuInfo: String?) : ModelState()
        data class Error(val exception: Throwable) : ModelState()
    }

    enum class GpuFallbackReason { CRASH, UNAVAILABLE }

    enum class TurboFallbackReason { CRASH }

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _gpuFallbackReason = MutableStateFlow<GpuFallbackReason?>(null)
    val gpuFallbackReason: StateFlow<GpuFallbackReason?> = _gpuFallbackReason.asStateFlow()

    private val _turboFallbackReason = MutableStateFlow<TurboFallbackReason?>(null)
    val turboFallbackReason: StateFlow<TurboFallbackReason?> = _turboFallbackReason.asStateFlow()

    fun clearGpuFallbackReason() {
        _gpuFallbackReason.value = null
    }

    fun clearTurboFallbackReason() {
        _turboFallbackReason.value = null
    }

    fun getLoadedModelPath(): String? = when (val state = _modelState.value) {
        is ModelState.Loaded -> state.modelPath
        else -> null
    }

    fun getModelFlow(): Flow<String> = userPreferences.model

    suspend fun loadModel(assets: AssetManager, forceReload: Boolean = false): Result<Unit> {
        return runCatching {
            val selectedModel = userPreferences.model.first()
            var gpuEnabled = userPreferences.gpuEnabled.first()
            val modelPath = "models/$selectedModel"

            if (gpuEnabled && userPreferences.isGpuInProgress()) {
                VoiceSkipLogger.w("Previous GPU operation crashed, falling back to CPU")
                userPreferences.setGpuInProgress(false)
                userPreferences.setGpuEnabled(false)
                gpuEnabled = false
                _gpuFallbackReason.value = GpuFallbackReason.CRASH
            }

            if (userPreferences.isTurboLoadInProgress()) {
                VoiceSkipLogger.w("Previous turbo CPU load crashed, disabling turbo mode")
                userPreferences.setTurboLoadInProgress(false)
                userPreferences.setTurboModeEnabled(false)
                _turboFallbackReason.value = TurboFallbackReason.CRASH
            }

            val currentState = _modelState.value

            if (!forceReload) {
                when (currentState) {
                    is ModelState.Loading -> {
                        if (currentState.modelPath == modelPath && currentState.useGpu == gpuEnabled) {
                            VoiceSkipLogger.d("Model already loading with same settings, skipping")
                            return@runCatching
                        }
                    }
                    is ModelState.Loaded -> {
                        if (currentState.modelPath == modelPath && (currentState.gpuInfo != null) == gpuEnabled) {
                            VoiceSkipLogger.d("Model already loaded with same settings, skipping")
                            return@runCatching
                        }
                    }
                    else -> {}
                }
            }

            _modelState.value = ModelState.Loading(modelPath, gpuEnabled)

            if (gpuEnabled) {
                userPreferences.setGpuInProgress(true)
            }

            fileManager.copyAssets(assets)

            val vadModelPath = userPreferences.getVadModelPath()
            VoiceSkipLogger.i("Loading model: $modelPath, vadModel: $vadModelPath, GPU: ${if (gpuEnabled) "enabled" else "disabled"}, forceReload: $forceReload")

            val result = repository.loadModel(assets, modelPath, vadModelPath, gpuEnabled, forceReload)
            val gpuInfo = result.getOrElse { e ->
                Log.w(LOG_TAG, e)
                VoiceSkipLogger.e("Failed to load model", e)
                _modelState.value = ModelState.Error(e)
                throw e
            }

            if (gpuEnabled) {
                userPreferences.setGpuInProgress(false)
            }

            if (gpuEnabled && gpuInfo == null) {
                VoiceSkipLogger.w("GPU requested but unavailable, disabling GPU setting")
                userPreferences.setGpuEnabled(false)
                _gpuFallbackReason.value = GpuFallbackReason.UNAVAILABLE
            }
            _modelState.value = ModelState.Loaded(modelPath, gpuInfo)

            if (repository.isTurboModelLoaded()) {
                VoiceSkipLogger.i("Reloading turbo CPU model: $modelPath")
                userPreferences.setTurboLoadInProgress(true)
                repository.loadTurboModel(assets, modelPath, vadModelPath)
                userPreferences.setTurboLoadInProgress(false)
            }

            maybeAutoEnableTurbo(assets, modelPath, vadModelPath, gpuInfo)

            VoiceSkipLogger.d("Model state updated to: Loaded(path=$modelPath, gpu=$gpuInfo)")
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { exception ->
                userPreferences.setGpuInProgress(false)
                val whisperError = ErrorHandler.handleError(exception)
                ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
                VoiceSkipLogger.e("Failed to initialize model loading", whisperError)
                _modelState.value = ModelState.Error(whisperError)
                Result.failure(whisperError)
            }
        )
    }

    fun isModelLoaded(): Boolean = _modelState.value is ModelState.Loaded

    suspend fun updateTurboMode(assets: AssetManager, enabled: Boolean): Result<Unit> {
        return runCatching {
            val currentState = _modelState.value
            if (currentState !is ModelState.Loaded) {
                VoiceSkipLogger.w("Cannot update turbo mode: model not loaded")
                return@runCatching
            }

            if (enabled) {
                if (!repository.isTurboModelLoaded()) {
                    val vadModelPath = userPreferences.getVadModelPath()
                    VoiceSkipLogger.i("Loading CPU model for turbo mode: ${currentState.modelPath}")
                    userPreferences.setTurboLoadInProgress(true)
                    repository.loadTurboModel(assets, currentState.modelPath, vadModelPath)
                    userPreferences.setTurboLoadInProgress(false)
                    VoiceSkipLogger.i("CPU model loaded for turbo mode")
                }
            } else {
                if (repository.isTurboModelLoaded()) {
                    VoiceSkipLogger.i("Destroying CPU model (turbo mode disabled)")
                    repository.unloadTurboModel()
                }
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { exception ->
                userPreferences.setTurboLoadInProgress(false)
                val whisperError = ErrorHandler.handleError(exception)
                ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
                VoiceSkipLogger.e("Failed to update turbo mode", whisperError)
                Result.failure(whisperError)
            }
        )
    }

    fun isTurboCpuLoaded(): Boolean = repository.isTurboModelLoaded()

    private suspend fun maybeAutoEnableTurbo(assets: AssetManager, modelPath: String, vadModelPath: String?, gpuInfo: String?) {
        if (userPreferences.turboModeHasBeenSet.first()) return

        val gpuActive = gpuInfo != null
        if (!gpuActive) return

        if (!userPreferences.shouldAutoEnableTurboForDevice()) return

        VoiceSkipLogger.i("Auto-enabling turbo mode (first run, high-spec device)")
        userPreferences.setTurboModeEnabled(true, isUserAction = false)

        userPreferences.setTurboLoadInProgress(true)
        repository.loadTurboModel(assets, modelPath, vadModelPath)
        userPreferences.setTurboLoadInProgress(false)
        VoiceSkipLogger.i("Turbo mode auto-enabled and CPU model loaded")
    }

    @OptIn(FlowPreview::class)
    fun startObservingSettings(assets: AssetManager, scope: CoroutineScope) {
        var previousModel: String? = null
        var previousGpuEnabled: Boolean? = null
        var previousTurboEnabled: Boolean? = null

        scope.launch {
            combine(
                userPreferences.model,
                userPreferences.gpuEnabled,
                userPreferences.turboModeEnabled
            ) { model, gpu, turbo -> Triple(model, gpu, turbo) }
                .debounce(100)
                .collect { (model, gpuEnabled, turboEnabled) ->
                    val isFirstEmission = previousModel == null
                    val modelOrGpuChanged = !isFirstEmission &&
                        (previousModel != model || previousGpuEnabled != gpuEnabled)
                    val turboChanged = !isFirstEmission &&
                        previousTurboEnabled != turboEnabled

                    if (turboChanged) {
                        VoiceSkipLogger.i("Turbo mode changed to: $turboEnabled")
                        updateTurboMode(assets, turboEnabled && gpuEnabled)
                    } else if (isFirstEmission && turboEnabled && gpuEnabled && !repository.isTurboModelLoaded()) {
                        VoiceSkipLogger.i("Turbo mode enabled in settings but not loaded, loading now")
                        updateTurboMode(assets, true)
                    }

                    if (modelOrGpuChanged) {
                        VoiceSkipLogger.i("Settings changed, forcing reload...")
                        loadModel(assets, forceReload = true)
                    }

                    previousModel = model
                    previousGpuEnabled = gpuEnabled
                    previousTurboEnabled = turboEnabled
                }
        }
    }
}
