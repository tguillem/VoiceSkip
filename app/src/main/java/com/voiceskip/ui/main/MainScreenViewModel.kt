// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.main

import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceskip.data.ErrorHandler
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.SavedTranscription
import com.voiceskip.data.repository.SavedTranscriptionRepository
import com.voiceskip.data.repository.SettingsRepository
import com.voiceskip.data.repository.TranscriptionRepository
import com.voiceskip.data.repository.TranscriptionSource
import com.voiceskip.data.repository.TranscriptionState
import com.voiceskip.data.repository.toWhisperSegment
import com.voiceskip.domain.ModelManager
import com.voiceskip.domain.usecase.FormatSentencesUseCase
import com.voiceskip.domain.usecase.LiveTranscriptionUseCase
import com.voiceskip.service.ServiceLauncher
import com.voiceskip.service.TranscriptionService
import com.voiceskip.StartupConfig
import com.voiceskip.util.VoiceSkipLogger
import com.voiceskip.util.getParcelableExtraCompat
import com.voiceskip.whispercpp.whisper.WhisperSegment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val LOG_TAG = "MainScreenViewModel"

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

sealed class TranscriptionUiState {
    object Idle : TranscriptionUiState()
    object LoadingModel : TranscriptionUiState()
    object Ready : TranscriptionUiState()
    object Transcribing : TranscriptionUiState()
    object Complete : TranscriptionUiState()
    object LiveRecording : TranscriptionUiState()
    object FinishingTranscription : TranscriptionUiState()
    data class Error(val message: String) : TranscriptionUiState()
}

enum class TranscriptionFailureReason {
    GENERIC_FAILURE,
    GPU_FAILURE_RETRYING
}

data class TranscriptionResult(
    val text: String,
    val timestamp: Long,
    val durationMs: Long,
    val audioLengthMs: Int,
    val detectedLanguage: String = "unknown"
) {
    val timestampFormatted: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val durationFormatted: String
        get() = formatDuration(durationMs)

    val audioLengthFormatted: String
        get() = formatDuration(audioLengthMs.toLong())
}

data class MainScreenUiState(
    val screenState: TranscriptionUiState = TranscriptionUiState.Idle,
    val transcriptionResult: TranscriptionResult? = null,
    val transcriptionSegments: List<WhisperSegment> = emptyList(),
    val transcriptionProgress: Int = 0,
    val detectedLanguage: String? = null,
    val canTranscribe: Boolean = false,
    val hasSavedTranscription: Boolean = false,
    val pendingDelete: SavedTranscription? = null,
    val isRecording: Boolean = false,
    val recordingDurationMs: Long = 0,
    val recordingAmplitude: Float = 0f,
    val errorMessage: String? = null,
    val selectedAudioPath: String? = null,
    val showFileSelector: Boolean = false,
    val language: String = UserPreferences.LANGUAGE_AUTO,
    val showTimestamps: Boolean = false,
    val translateToEnglish: Boolean = false,
    val model: String = "models/ggml-small-q8_0.bin",
    val gpuEnabled: Boolean = true,
    val numThreads: Int = UserPreferences.getDefaultNumThreads(true),
    val gpuFallbackReason: ModelManager.GpuFallbackReason? = null,
    val turboFallbackReason: ModelManager.TurboFallbackReason? = null,
    val queueLimitStopReason: LiveTranscriptionUseCase.StopReason? = null,
    val transcriptionFailureReason: TranscriptionFailureReason? = null
)

sealed class MainScreenAction {
    object ToggleRecord : MainScreenAction()
    data class SelectFile(val uri: Uri) : MainScreenAction()
    object RequestFileSelection : MainScreenAction()
    object StopTranscription : MainScreenAction()
    object ClearResult : MainScreenAction()
    object RetryLoadModel : MainScreenAction()
    object ViewLastTranscription : MainScreenAction()
    object DeleteSavedTranscription : MainScreenAction()
    object UndoDeleteSavedTranscription : MainScreenAction()
    object ConfirmDeleteSavedTranscription : MainScreenAction()
    data class SetShowTimestamps(val show: Boolean) : MainScreenAction()
    data class SetTranslateToEnglish(val translate: Boolean) : MainScreenAction()
    data class ChangeLanguage(val language: String) : MainScreenAction()
}

@HiltViewModel
class MainScreenViewModel @Inject constructor(
    private val repository: TranscriptionRepository,
    private val settingsRepository: SettingsRepository,
    private val savedTranscriptionRepository: SavedTranscriptionRepository,
    private val modelManager: ModelManager,
    private val audioManager: AudioPlaybackManager,
    private val serviceLauncher: ServiceLauncher,
    private val assetManager: AssetManager,
    private val startupConfig: StartupConfig,
    private val savedStateHandle: SavedStateHandle,
    private val formatSentencesUseCase: FormatSentencesUseCase
) : ViewModel() {

    private val _showFileSelector = MutableStateFlow(false)
    private val _pendingDelete = MutableStateFlow<SavedTranscription?>(null)
    private val _transcriptionFailureReason = MutableStateFlow<TranscriptionFailureReason?>(null)
    private val pendingUriMutex = Mutex()

    val uiState: StateFlow<MainScreenUiState> = combine(
        combine(
            repository.state,
            modelManager.modelState,
            modelManager.gpuFallbackReason,
            savedTranscriptionRepository.savedTranscription,
            _pendingDelete
        ) { a, b, c, d, e -> object { val repoState = a; val modelState = b; val gpuFallbackReason = c; val savedTx = d; val pendingDel = e } },
        settingsRepository.userSettings,
        repository.sessionLanguage,
        _showFileSelector,
        combine(modelManager.turboFallbackReason, _transcriptionFailureReason) { turbo, failure -> turbo to failure }
    ) { combined, settings, sessionLanguage, showFileSelector, turboAndFailure ->
        val repoState = combined.repoState
        val modelState = combined.modelState
        val gpuFallbackReason = combined.gpuFallbackReason
        val savedTranscription = combined.savedTx
        val pendingDelete = combined.pendingDel
        val (turboFallbackReason, transcriptionFailureReason) = turboAndFailure

        val canTranscribe = modelState is ModelManager.ModelState.Loaded && repoState is TranscriptionState.Idle
        val baseState = MainScreenUiState(
            canTranscribe = canTranscribe,
            hasSavedTranscription = savedTranscription != null,
            pendingDelete = pendingDelete,
            showFileSelector = showFileSelector,
            language = sessionLanguage ?: UserPreferences.LANGUAGE_AUTO,
            showTimestamps = settings.showTimestamps,
            translateToEnglish = settings.translateToEnglish,
            model = settings.model,
            gpuEnabled = settings.gpuEnabled,
            numThreads = settings.numThreads,
            gpuFallbackReason = gpuFallbackReason,
            turboFallbackReason = turboFallbackReason,
            transcriptionFailureReason = transcriptionFailureReason
        )

        when (repoState) {
            is TranscriptionState.Idle -> {
                val screenState = when {
                    modelState is ModelManager.ModelState.Loading -> TranscriptionUiState.LoadingModel
                    modelState is ModelManager.ModelState.Error ->
                        TranscriptionUiState.Error("Failed to load model: ${modelState.exception.localizedMessage}")
                    else -> TranscriptionUiState.Ready
                }
                baseState.copy(
                    screenState = screenState,
                    errorMessage = (screenState as? TranscriptionUiState.Error)?.message
                )
            }
            is TranscriptionState.LiveRecording -> baseState.copy(
                screenState = TranscriptionUiState.LiveRecording,
                isRecording = true,
                transcriptionSegments = repoState.segments,
                transcriptionProgress = repoState.progress,
                recordingDurationMs = repoState.durationMs,
                recordingAmplitude = repoState.amplitude,
                detectedLanguage = repoState.detectedLanguage
            )
            is TranscriptionState.FinishingTranscription -> baseState.copy(
                screenState = TranscriptionUiState.FinishingTranscription,
                transcriptionSegments = repoState.segments,
                transcriptionProgress = repoState.progress,
                detectedLanguage = repoState.detectedLanguage,
                queueLimitStopReason = repoState.stopReason
            )
            is TranscriptionState.Transcribing -> baseState.copy(
                screenState = TranscriptionUiState.Transcribing,
                transcriptionSegments = repoState.segments,
                transcriptionProgress = repoState.progress,
                detectedLanguage = repoState.detectedLanguage
            )
            is TranscriptionState.Complete -> baseState.copy(
                screenState = TranscriptionUiState.Complete,
                transcriptionResult = TranscriptionResult(
                    text = repoState.text,
                    timestamp = System.currentTimeMillis(),
                    durationMs = repoState.processingTimeMs,
                    audioLengthMs = repoState.audioLengthMs,
                    detectedLanguage = repoState.detectedLanguage ?: "unknown"
                ),
                transcriptionSegments = repoState.segments,
                transcriptionProgress = 100,
                detectedLanguage = repoState.detectedLanguage
            )
            is TranscriptionState.Error -> baseState.copy(
                screenState = TranscriptionUiState.Error(repoState.message),
                errorMessage = repoState.message
            )
        }
    }.stateIn(
        scope = viewModelScope,
        // Eagerly required: main screen must have computed state immediately available on resume,
        // WhileSubscribed would start with initialValue causing brief incorrect UI
        started = SharingStarted.Eagerly,
        initialValue = MainScreenUiState()
    )

    init {
        viewModelScope.launch {
            launch { savedTranscriptionRepository.loadSavedTranscription() }
            launch { collectCompletedTranscriptions() }
            launch { collectTranscriptionErrors() }

            if (!startupConfig.skipModelLoad) {
                loadModel(forceReload = false)
                modelManager.startObservingSettings(assetManager, viewModelScope)
            }

            savedStateHandle.get<Uri>("pending_uri")?.let { uri ->
                val language = savedStateHandle.get<String>("pending_language")
                handlePendingUri(uri, language)
            }
        }
    }

    private suspend fun collectTranscriptionErrors() {
        repository.state
            .filterIsInstance<TranscriptionState.Error>()
            .collect { error ->
                handleTranscriptionError(error)
            }
    }

    private fun handleTranscriptionError(error: TranscriptionState.Error) {
        if (error.gpuWasEnabled) {
            viewModelScope.launch {
                VoiceSkipLogger.w("GPU transcription failed, disabling GPU and retrying")
                _transcriptionFailureReason.value = TranscriptionFailureReason.GPU_FAILURE_RETRYING

                val source = repository.getCurrentTranscriptionSource()
                val language = repository.sessionLanguage.value

                repository.clearState()

                settingsRepository.updateGpuEnabled(false)

                // Wait for model to start reloading, then finish loading
                modelManager.modelState.first { it is ModelManager.ModelState.Loading }
                modelManager.modelState.first { it is ModelManager.ModelState.Loaded }

                retryTranscription(source, language)
            }
        } else {
            _transcriptionFailureReason.value = TranscriptionFailureReason.GENERIC_FAILURE
        }
    }

    private suspend fun retryTranscription(source: TranscriptionSource?, language: String?) {
        if (source == null) return

        when (source) {
            is TranscriptionSource.Recording -> {
                val defaultLang = language ?: settingsRepository.userSettings.first().defaultLanguage
                serviceLauncher.startRecording(language = defaultLang)
            }
            is TranscriptionSource.FileUri -> {
                val defaultLang = language ?: settingsRepository.userSettings.first().defaultLanguage
                serviceLauncher.startFileTranscription(source.uri, language = defaultLang)
            }
        }
    }

    private suspend fun collectCompletedTranscriptions() {
        repository.state
            .filterIsInstance<TranscriptionState.Complete>()
            .collect { completeState ->
                savedTranscriptionRepository.saveTranscription(completeState)
            }
    }

    fun handleIncomingIntent(intent: Intent?) {
        val language = intent?.getStringExtra("language")
        when (intent?.action) {
            TranscriptionService.ACTION_VIEW_TRANSCRIPT -> {
                viewLastTranscription()
            }
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                uri?.let {
                    savedStateHandle["pending_uri"] = uri
                    savedStateHandle["pending_language"] = language
                    handlePendingUri(uri, language)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    savedStateHandle["pending_uri"] = uri
                    savedStateHandle["pending_language"] = language
                    handlePendingUri(uri, language)
                }
            }
        }
    }

    private fun handlePendingUri(uri: Uri, language: String? = null) {
        viewModelScope.launch {
            val modelState = modelManager.modelState.first {
                it is ModelManager.ModelState.Loaded || it is ModelManager.ModelState.Error
            }
            if (modelState is ModelManager.ModelState.Error) {
                return@launch
            }

            val shouldProcess = pendingUriMutex.withLock {
                if (savedStateHandle.get<Uri>("pending_uri") != uri) {
                    false
                } else {
                    savedStateHandle["pending_uri"] = null
                    savedStateHandle["pending_language"] = null
                    true
                }
            }

            if (!shouldProcess) return@launch

            repository.cancelTranscription()
            repository.state.filterIsInstance<TranscriptionState.Idle>().first()

            VoiceSkipLogger.logFileSelected(uri.toString())
            val effectiveLanguage = language ?: settingsRepository.userSettings.first().defaultLanguage
            serviceLauncher.startFileTranscription(uri, language = effectiveLanguage)
        }
    }

    fun handleAction(action: MainScreenAction) {
        when (action) {
            is MainScreenAction.ToggleRecord -> toggleRecord()
            is MainScreenAction.SelectFile -> handleSelectedFile(action.uri)
            is MainScreenAction.RequestFileSelection -> requestFileSelection()
            is MainScreenAction.StopTranscription -> stopTranscription()
            is MainScreenAction.ClearResult -> clearResult()
            is MainScreenAction.RetryLoadModel -> retryLoadModel()
            is MainScreenAction.ViewLastTranscription -> viewLastTranscription()
            is MainScreenAction.DeleteSavedTranscription -> deleteSavedTranscription()
            is MainScreenAction.UndoDeleteSavedTranscription -> undoDeleteSavedTranscription()
            is MainScreenAction.ConfirmDeleteSavedTranscription -> confirmDeleteSavedTranscription()
            is MainScreenAction.SetShowTimestamps -> setShowTimestamps(action.show)
            is MainScreenAction.SetTranslateToEnglish -> setTranslateToEnglish(action.translate)
            is MainScreenAction.ChangeLanguage -> changeLanguage(action.language)
        }
    }

    private fun viewLastTranscription() {
        viewModelScope.launch {
            val saved = savedTranscriptionRepository.savedTranscription.value ?: return@launch
            repository.restoreCompletedState(
                text = saved.text,
                segments = saved.segments.map { it.toWhisperSegment() },
                detectedLanguage = saved.detectedLanguage,
                audioLengthMs = saved.audioLengthMs,
                processingTimeMs = saved.durationMs
            )
        }
    }

    private fun deleteSavedTranscription() {
        viewModelScope.launch {
            val current = savedTranscriptionRepository.savedTranscription.value ?: return@launch
            _pendingDelete.value = current
            savedTranscriptionRepository.clearSavedTranscription()
            repository.clearState()
        }
    }

    private fun undoDeleteSavedTranscription() {
        viewModelScope.launch {
            val pending = _pendingDelete.value ?: return@launch
            _pendingDelete.value = null
            savedTranscriptionRepository.restoreTranscription(pending)
        }
    }

    private fun confirmDeleteSavedTranscription() {
        _pendingDelete.value = null
    }

    private suspend fun loadModel(forceReload: Boolean = false) {
        modelManager.loadModel(assetManager, forceReload).onSuccess {
            savedStateHandle.get<Uri>("pending_uri")?.let { uri ->
                val language = savedStateHandle.get<String>("pending_language")
                handlePendingUri(uri, language)
            }
        }.onFailure { e ->
            VoiceSkipLogger.e("Failed to load model", e)
        }
    }

    fun toggleRecord() = viewModelScope.launch {
        val currentState = repository.state.value

        when (currentState) {
            is TranscriptionState.LiveRecording -> {
                repository.stopRecording()
            }
            is TranscriptionState.FinishingTranscription -> {
                repository.cancelTranscription()
            }
            TranscriptionState.Idle -> {
                runCatching {
                    audioManager.stopPlayback()
                    val defaultLang = settingsRepository.userSettings.first().defaultLanguage
                    serviceLauncher.startRecording(language = defaultLang)
                    VoiceSkipLogger.logRecordingStart()
                }.onFailure { exception ->
                    if (exception is CancellationException) throw exception

                    ErrorHandler.logError(LOG_TAG, exception, critical = false)
                    VoiceSkipLogger.e("Recording start failed", exception)
                }
            }
            else -> { }
        }
    }

    fun requestFileSelection() {
        _showFileSelector.value = true
    }

    fun onFileSelectorShown() {
        _showFileSelector.value = false
    }

    fun handleSelectedFile(uri: Uri) {
        if (!uiState.value.canTranscribe) {
            savedStateHandle["pending_uri"] = uri
            return
        }

        viewModelScope.launch {
            VoiceSkipLogger.logFileSelected(uri.toString())
            runCatching {
                val defaultLang = settingsRepository.userSettings.first().defaultLanguage
                serviceLauncher.startFileTranscription(uri, language = defaultLang)
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception

                ErrorHandler.logError(LOG_TAG, exception, critical = false)
                VoiceSkipLogger.e("Error loading file", exception)
            }
        }
    }

    fun stopTranscription() {
        viewModelScope.launch {
            VoiceSkipLogger.i("Stop button pressed")

            runCatching {
                repository.cancelTranscription()
            }.onSuccess {
                VoiceSkipLogger.i("Transcription stopped by user")
            }.onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
                VoiceSkipLogger.e("Error stopping transcription", exception)
            }
        }
    }

    fun clearResult() {
        viewModelScope.launch {
            repository.clearState()
        }
    }

    fun retryLoadModel() {
        viewModelScope.launch {
            loadModel(forceReload = true)
        }
    }

    fun setShowTimestamps(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShowTimestamps(show).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }

    fun setTranslateToEnglish(translate: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateTranslateToEnglish(translate).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }

    fun onGpuFallbackDismissed() {
        modelManager.clearGpuFallbackReason()
    }

    fun onTurboFallbackDismissed() {
        modelManager.clearTurboFallbackReason()
    }

    fun onTranscriptionFailureDismissed() {
        _transcriptionFailureReason.value = null
    }

    private fun changeLanguage(language: String) {
        repository.updateLanguage(language)
    }


    fun formatSentences(text: String): String = formatSentencesUseCase(text)

    override fun onCleared() {
        super.onCleared()
        audioManager.cleanup()
    }
}
