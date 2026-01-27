// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.content.res.AssetManager
import android.net.Uri
import com.voiceskip.whispercpp.whisper.WhisperSegment
import com.voiceskip.data.ErrorHandler
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.SettingsRepository
import com.voiceskip.data.source.WhisperDataSource
import com.voiceskip.data.source.TranscriptionEvent
import com.voiceskip.domain.usecase.FileTranscriptionUseCase
import com.voiceskip.domain.usecase.LiveTranscriptionUseCase
import com.voiceskip.ui.main.FileManager
import com.voiceskip.util.VoiceSkipLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptionRepositoryImpl @Inject constructor(
    private val fileManager: FileManager,
    private val settingsRepository: SettingsRepository,
    private val whisperDataSource: WhisperDataSource,
    private val liveTranscriptionUseCase: LiveTranscriptionUseCase,
    private val fileTranscriptionUseCase: FileTranscriptionUseCase,
    @com.voiceskip.di.DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : TranscriptionRepository {

    private val _state = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    override val state = _state.asStateFlow()

    private val _segments = MutableStateFlow<List<WhisperSegment>>(emptyList())
    override val segments = _segments.asStateFlow()

    private val _progress = MutableStateFlow(0)
    override val progress = _progress.asStateFlow()

    private val _sessionLanguage = MutableStateFlow<String?>(null)
    override val sessionLanguage = _sessionLanguage.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)
    private var currentJob: Job? = null
    private var eventCollectionJob: Job? = null
    private var currentSource: TranscriptionSource? = null

    private var _isModelLoaded = false

    private data class InternalTranscriptionState(
        val segments: List<WhisperSegment> = emptyList(),
        val startTime: Long = 0L,
        val audioLengthMs: Int = 0,
        val lastSegmentEndMs: Long = 0L
    )

    private val stateMutex = Mutex()
    private var internalState = InternalTranscriptionState()

    override suspend fun startRecording(language: String?) {
        if (_state.value != TranscriptionState.Idle) return
        currentSource = TranscriptionSource.Recording
        language?.let { _sessionLanguage.value = it }

        // Set state synchronously to prevent UI from briefly showing Ready screen
        _state.value = TranscriptionState.LiveRecording(
            durationMs = 0,
            amplitude = 0f,
            progress = 0,
            currentSegment = null,
            segments = emptyList(),
            detectedLanguage = null
        )

        currentJob = scope.launch {
            startLiveRecording()
        }
    }

    private suspend fun startLiveRecording() {
        try {
            val settings = settingsRepository.userSettings.first()
            val languageCode = _sessionLanguage.value?.takeIf { it != UserPreferences.LANGUAGE_AUTO }

            liveTranscriptionUseCase.execute(
                numThreads = getEffectiveThreadCount(settings.numThreads),
                language = languageCode,
                translateToEnglish = settings.translateToEnglish,
                gpuEnabled = settings.gpuEnabled
            ).collect { progress ->
                when (progress) {
                    is LiveTranscriptionUseCase.Progress.Recording -> {
                        _state.value = TranscriptionState.LiveRecording(
                            durationMs = progress.durationMs,
                            amplitude = progress.amplitude,
                            progress = calculateProgress(
                                progress.segments.maxOfOrNull { it.endMs } ?: 0,
                                progress.durationMs
                            ),
                            currentSegment = progress.segments.lastOrNull()?.text,
                            segments = progress.segments,
                            detectedLanguage = progress.detectedLanguage
                        )
                    }
                    is LiveTranscriptionUseCase.Progress.Finishing -> {
                        _state.value = TranscriptionState.FinishingTranscription(
                            progress = progress.progressPercent,
                            currentSegment = progress.segments.lastOrNull()?.text,
                            segments = progress.segments,
                            detectedLanguage = progress.detectedLanguage,
                            stopReason = progress.stopReason
                        )
                    }
                    is LiveTranscriptionUseCase.Progress.Complete -> {
                        _state.value = TranscriptionState.Complete(
                            text = progress.segments.joinToString(" ") { it.text },
                            segments = progress.segments,
                            detectedLanguage = progress.detectedLanguage,
                            audioLengthMs = progress.recordingDurationMs.toInt(),
                            processingTimeMs = progress.processingTimeMs
                        )
                    }
                    is LiveTranscriptionUseCase.Progress.Failed -> {
                        _state.value = TranscriptionState.Error(
                            message = "Transcription failed",
                            recoverable = true,
                            gpuWasEnabled = progress.gpuWasEnabled
                        )
                    }
                }
            }

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = TranscriptionState.Error(
                message = e.message ?: "Live transcription failed",
                recoverable = true
            )
        }
    }

    private fun calculateProgress(transcribedMs: Long, totalMs: Long): Int {
        return if (totalMs > 0) {
            ((transcribedMs.toFloat() / totalMs) * 100).coerceIn(0f, 100f).toInt()
        } else 0
    }

    override fun stopRecording() {
        liveTranscriptionUseCase.stop()
    }

    override fun cancelRecording() {
        liveTranscriptionUseCase.stop()
        currentJob?.cancel()
        _state.value = TranscriptionState.Idle
    }

    override suspend fun transcribeFile(file: File) {
        if (_state.value !is TranscriptionState.Idle) return
        transcribeInternal(file = file, uri = null)
    }

    override suspend fun transcribeUri(uri: Uri, language: String?) {
        if (_state.value !is TranscriptionState.Idle) return
        currentSource = TranscriptionSource.FileUri(uri)
        language?.let { _sessionLanguage.value = it }
        transcribeInternal(file = null, uri = uri)
    }

    private fun transcribeInternal(file: File?, uri: Uri?) {
        _state.value = TranscriptionState.Transcribing(
            progress = 0,
            currentSegment = null,
            segments = emptyList(),
            detectedLanguage = null
        )

        val source = when {
            file != null -> FileTranscriptionUseCase.Source.FromFile(file)
            uri != null -> FileTranscriptionUseCase.Source.FromUri(uri)
            else -> return
        }

        currentJob = scope.launch {
            try {
                val settings = settingsRepository.userSettings.first()
                val languageCode = _sessionLanguage.value?.takeIf { it != UserPreferences.LANGUAGE_AUTO }

                fileTranscriptionUseCase.execute(
                    source = source,
                    numThreads = getEffectiveThreadCount(settings.numThreads),
                    language = languageCode,
                    translateToEnglish = settings.translateToEnglish,
                    gpuEnabled = settings.gpuEnabled
                ).collect { progress ->
                    when (progress) {
                        is FileTranscriptionUseCase.Progress.Transcribing -> {
                            _state.value = TranscriptionState.Transcribing(
                                progress = progress.progressPercent,
                                currentSegment = progress.segments.lastOrNull()?.text,
                                segments = progress.segments,
                                detectedLanguage = progress.detectedLanguage
                            )
                            _progress.value = progress.progressPercent
                        }
                        is FileTranscriptionUseCase.Progress.Complete -> {
                            _state.value = TranscriptionState.Complete(
                                text = progress.segments.joinToString(" ") { it.text },
                                segments = progress.segments,
                                detectedLanguage = progress.detectedLanguage,
                                audioLengthMs = progress.audioLengthMs,
                                processingTimeMs = progress.processingTimeMs,
                                audioUri = uri
                            )
                        }
                        is FileTranscriptionUseCase.Progress.Failed -> {
                            _state.value = TranscriptionState.Error(
                                message = "Transcription failed",
                                recoverable = true,
                                gpuWasEnabled = progress.gpuWasEnabled
                            )
                        }
                    }
                }

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = TranscriptionState.Error(
                    message = e.message ?: "Transcription failed",
                    recoverable = true
                )
            }
        }
    }

    override fun cancelTranscription() {
        currentJob?.cancel()
        whisperDataSource.stop()
        _state.value = TranscriptionState.Idle
    }

    override fun clearState() {
        if (_state.value is TranscriptionState.Complete || _state.value is TranscriptionState.Error) {
            currentSource = null
            _state.value = TranscriptionState.Idle
        }
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
    }

    override suspend fun loadModel(assets: AssetManager, modelPath: String, vadModelPath: String?, useGpu: Boolean, forceReload: Boolean): Result<String?> = runCatching {
        if (!forceReload && _isModelLoaded) {
            VoiceSkipLogger.d("Model already loaded, skipping (forceReload=$forceReload)")
            return@runCatching null
        }

        if (forceReload) {
            VoiceSkipLogger.d("Force reloading model")
            _isModelLoaded = false
        }

        ensureEventCollectorRunning()

        whisperDataSource.loadModel(assets, modelPath, vadModelPath = vadModelPath, useGpu = useGpu)

        val event = whisperDataSource.events.first {
            it is TranscriptionEvent.ModelLoaded || it is TranscriptionEvent.Error
        }
        when (event) {
            is TranscriptionEvent.ModelLoaded -> {
                _isModelLoaded = true
                event.gpuInfo
            }
            is TranscriptionEvent.Error -> throw Exception(event.message)
            else -> error("Unexpected event")
        }
    }

    private fun ensureEventCollectorRunning() {
        if (eventCollectionJob?.isActive == true) return

        eventCollectionJob = scope.launch {
            whisperDataSource.events.collect { event ->
                when (event) {
                    is TranscriptionEvent.Segment -> {
                        updateInternalState { currentState ->
                            val sortedSegments = (currentState.segments + event.segment)
                                .sortedBy { it.startMs }

                            val adjustedSegments = mutableListOf<WhisperSegment>()
                            var lastEndMs = 0L

                            for (segment in sortedSegments) {
                                val adjusted = if (segment.startMs < lastEndMs) {
                                    segment.copy(
                                        startMs = lastEndMs,
                                        endMs = segment.endMs.coerceAtLeast(lastEndMs)
                                    )
                                } else {
                                    segment
                                }
                                adjustedSegments.add(adjusted)
                                lastEndMs = adjusted.endMs
                            }

                            val lastSegment = adjustedSegments.lastOrNull()
                            if (lastSegment != null) {
                                _progress.value = calculateTranscriptionProgress(lastSegment.endMs, currentState.audioLengthMs)
                            }

                            currentState.copy(
                                segments = adjustedSegments,
                                lastSegmentEndMs = lastEndMs
                            )
                        }
                    }
                    is TranscriptionEvent.Progress -> {
                        _progress.value = event.percent
                    }
                    is TranscriptionEvent.StreamComplete -> {
                        _progress.value = 100
                    }
                    else -> { }
                }
            }
        }
    }

    override suspend fun stopTranscription() {
        currentJob?.cancel()
        currentJob = null

        whisperDataSource.stop()

        updateInternalState { InternalTranscriptionState() }
        _progress.value = 0
    }

    override fun getCurrentTranscriptionSource(): TranscriptionSource? {
        return currentSource
    }

    override fun setSessionLanguage(language: String?) {
        _sessionLanguage.value = language
    }

    override fun updateLanguage(language: String?) {
        _sessionLanguage.value = language
        val langCode = language?.takeIf { it != UserPreferences.LANGUAGE_AUTO }
        whisperDataSource.updateLanguage(langCode)
    }

    override fun isModelLoaded(): Boolean = _isModelLoaded

    override suspend fun loadTurboModel(assets: AssetManager, modelPath: String, vadModelPath: String?): Result<Unit> = runCatching {
        whisperDataSource.setTurboMode(true, assets, modelPath, vadModelPath)
        whisperDataSource.events.first { it is TranscriptionEvent.ModelLoaded && it.turbo }
    }

    override suspend fun unloadTurboModel(): Result<Unit> = runCatching {
        whisperDataSource.disableTurboMode()
    }

    override fun isTurboModelLoaded(): Boolean = whisperDataSource.isTurboEnabled

    private fun getEffectiveThreadCount(settingsThreads: Int): Int =
        if (isTurboModelLoaded()) UserPreferences.getTurboCpuThreads() else settingsThreads

    private suspend fun updateInternalState(update: (InternalTranscriptionState) -> InternalTranscriptionState) {
        stateMutex.withLock {
            internalState = update(internalState)
            _segments.value = internalState.segments
        }
    }

    private fun calculateTranscriptionProgress(segmentEndMs: Long, audioLengthMs: Int): Int {
        return if (audioLengthMs > 0) {
            ((segmentEndMs.toFloat() / audioLengthMs) * 100)
                .coerceIn(0f, 100f)
                .toInt()
        } else {
            0
        }
    }
}
