// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.fake

import android.content.res.AssetManager
import android.net.Uri
import com.voiceskip.data.repository.TranscriptionRepository
import com.voiceskip.data.repository.TranscriptionSource
import com.voiceskip.data.repository.TranscriptionState
import com.voiceskip.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class FakeTranscriptionRepository : TranscriptionRepository {

    private val _state = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    override val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _segments = MutableStateFlow<List<WhisperSegment>>(emptyList())
    override val segments: StateFlow<List<WhisperSegment>> = _segments.asStateFlow()

    private val _progress = MutableStateFlow(0)
    override val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _sessionLanguage = MutableStateFlow<String?>(null)
    override val sessionLanguage: StateFlow<String?> = _sessionLanguage.asStateFlow()

    var startRecordingCalled = false
    var stopRecordingCalled = false
    var cancelRecordingCalled = false
    var transcribeFileCalled = false
    var transcribeUriCalled = false
    var cancelTranscriptionCalled = false
    var clearStateCalled = false
    var loadModelCalled = false
    var stopTranscriptionCalled = false

    var lastTranscribedFile: File? = null
    var lastTranscribedUri: Uri? = null
    var lastTranscribeLanguage: String? = null
    var lastStartRecordingLanguage: String? = null
    var lastLoadModelPath: String? = null
    var lastLoadVadModelPath: String? = null
    var lastLoadModelUseGpu: Boolean? = null

    var loadModelResult: Result<Boolean> = Result.success(true)
    var loadModelGpuResult: String? = "Test GPU"

    private var _isModelLoaded = false
    private var _currentTranscriptionSource: TranscriptionSource? = null

    override suspend fun startRecording(language: String?) {
        startRecordingCalled = true
        lastStartRecordingLanguage = language
        _currentTranscriptionSource = TranscriptionSource.Recording
        _state.value = TranscriptionState.LiveRecording(
            durationMs = 0,
            amplitude = 0f,
            progress = 0,
            currentSegment = null,
            segments = emptyList()
        )
    }

    override fun stopRecording() {
        stopRecordingCalled = true
        _state.value = TranscriptionState.FinishingTranscription(
            progress = _progress.value,
            currentSegment = null,
            segments = _segments.value
        )
    }

    override fun cancelRecording() {
        cancelRecordingCalled = true
        _currentTranscriptionSource = null
        _state.value = TranscriptionState.Idle
    }

    override suspend fun transcribeFile(file: File) {
        transcribeFileCalled = true
        lastTranscribedFile = file
        _currentTranscriptionSource = TranscriptionSource.FileUri(Uri.fromFile(file))
        _state.value = TranscriptionState.Transcribing(
            progress = 0,
            currentSegment = null,
            segments = emptyList()
        )
    }

    override suspend fun transcribeUri(uri: Uri, language: String?) {
        transcribeUriCalled = true
        lastTranscribedUri = uri
        lastTranscribeLanguage = language
        _currentTranscriptionSource = TranscriptionSource.FileUri(uri)
        _state.value = TranscriptionState.Transcribing(
            progress = 0,
            currentSegment = null,
            segments = emptyList()
        )
    }

    override fun cancelTranscription() {
        cancelTranscriptionCalled = true
        _currentTranscriptionSource = null
        _state.value = TranscriptionState.Idle
    }

    override fun clearState() {
        clearStateCalled = true
        _state.value = TranscriptionState.Idle
        _segments.value = emptyList()
        _progress.value = 0
        _sessionLanguage.value = null
        _currentTranscriptionSource = null
    }

    override fun restoreCompletedState(
        text: String,
        segments: List<WhisperSegment>,
        detectedLanguage: String?,
        audioLengthMs: Int,
        processingTimeMs: Long,
        audioUri: Uri?
    ) {
        _state.value = TranscriptionState.Complete(
            text = text,
            segments = segments,
            detectedLanguage = detectedLanguage,
            audioLengthMs = audioLengthMs,
            processingTimeMs = processingTimeMs,
            audioUri = audioUri
        )
        _segments.value = segments
    }

    override suspend fun loadModel(
        assets: AssetManager,
        modelPath: String,
        vadModelPath: String?,
        useGpu: Boolean,
        forceReload: Boolean
    ): Result<String?> {
        loadModelCalled = true
        lastLoadModelPath = modelPath
        lastLoadVadModelPath = vadModelPath
        lastLoadModelUseGpu = useGpu

        return loadModelResult.map { loadModelGpuResult }.also {
            if (it.isSuccess) {
                _isModelLoaded = true
            }
        }
    }

    override suspend fun stopTranscription() {
        stopTranscriptionCalled = true
        _state.value = TranscriptionState.FinishingTranscription(
            progress = _progress.value,
            currentSegment = null,
            segments = _segments.value
        )
    }

    override fun getCurrentTranscriptionSource(): TranscriptionSource? {
        return _currentTranscriptionSource
    }

    override fun setSessionLanguage(language: String?) {
        _sessionLanguage.value = language
    }

    override fun updateLanguage(language: String?) {
        _sessionLanguage.value = language
    }

    override fun isModelLoaded(): Boolean = _isModelLoaded

    private var _isTurboModelLoaded = false
    var loadTurboModelCalled = false
    var unloadTurboModelCalled = false

    override suspend fun loadTurboModel(assets: AssetManager, modelPath: String, vadModelPath: String?): Result<Unit> {
        loadTurboModelCalled = true
        _isTurboModelLoaded = true
        return Result.success(Unit)
    }

    override suspend fun unloadTurboModel(): Result<Unit> {
        unloadTurboModelCalled = true
        _isTurboModelLoaded = false
        return Result.success(Unit)
    }

    override fun isTurboModelLoaded(): Boolean = _isTurboModelLoaded

    fun setTurboModelLoaded(loaded: Boolean) {
        _isTurboModelLoaded = loaded
    }

    fun setState(newState: TranscriptionState) {
        _state.value = newState
    }

    fun setSegments(newSegments: List<WhisperSegment>) {
        _segments.value = newSegments
    }

    fun setProgress(newProgress: Int) {
        _progress.value = newProgress
    }

    fun setModelLoaded(loaded: Boolean) {
        _isModelLoaded = loaded
    }

    fun setCurrentTranscriptionSource(source: TranscriptionSource?) {
        _currentTranscriptionSource = source
    }

    fun resetCallTracking() {
        startRecordingCalled = false
        stopRecordingCalled = false
        cancelRecordingCalled = false
        transcribeFileCalled = false
        transcribeUriCalled = false
        cancelTranscriptionCalled = false
        clearStateCalled = false
        loadModelCalled = false
        stopTranscriptionCalled = false
        loadTurboModelCalled = false
        unloadTurboModelCalled = false
        lastTranscribedFile = null
        lastTranscribedUri = null
        lastTranscribeLanguage = null
        lastStartRecordingLanguage = null
        lastLoadModelPath = null
        lastLoadVadModelPath = null
        lastLoadModelUseGpu = null
    }

    fun fullReset() {
        resetCallTracking()
        _state.value = TranscriptionState.Idle
        _segments.value = emptyList()
        _progress.value = 0
        _sessionLanguage.value = null
        _isModelLoaded = false
        _isTurboModelLoaded = false
        _currentTranscriptionSource = null
        loadModelResult = Result.success(true)
        loadModelGpuResult = "Test GPU"
    }
}
