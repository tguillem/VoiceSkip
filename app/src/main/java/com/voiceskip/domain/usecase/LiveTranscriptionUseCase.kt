// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.domain.usecase

import com.voiceskip.whispercpp.whisper.WhisperSegment
import com.voiceskip.data.VoiceSkipException.TranscriptionException
import com.voiceskip.data.source.TranscriptionEvent
import com.voiceskip.data.source.WhisperDataSource
import com.voiceskip.media.LiveAudioProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

class LiveTranscriptionUseCase @Inject constructor(
    private val whisperDataSource: WhisperDataSource
) {
    private val currentProvider = MutableStateFlow<LiveAudioProvider?>(null)

    enum class StopReason {
        QUEUE_LIMIT_REACHED
    }

    sealed interface Progress {
        data class Recording(
            val durationMs: Long,
            val amplitude: Float,
            val segments: List<WhisperSegment>,
            val detectedLanguage: String?
        ) : Progress

        data class Finishing(
            val progressPercent: Int,
            val segments: List<WhisperSegment>,
            val detectedLanguage: String?,
            val stopReason: StopReason? = null
        ) : Progress

        data class Complete(
            val segments: List<WhisperSegment>,
            val detectedLanguage: String?,
            val recordingDurationMs: Long,
            val processingTimeMs: Long
        ) : Progress

        data class Failed(
            val segments: List<WhisperSegment>,
            val detectedLanguage: String?,
            val gpuWasEnabled: Boolean
        ) : Progress
    }

    companion object {
        private const val AUTO_STOP_GAP_MS = 300000L
    }

    private data class LiveState(
        var durationMs: Long = 0L,
        var amplitude: Float = 0f,
        var lastTranscribedMs: Long = 0L,
        var segments: List<WhisperSegment> = emptyList(),
        var detectedLanguage: String? = null,
        var isFinishing: Boolean = false
    )

    fun execute(
        numThreads: Int,
        language: String?,
        translateToEnglish: Boolean,
        gpuEnabled: Boolean
    ): Flow<Progress> = channelFlow {
        val startTime = System.currentTimeMillis()
        val state = LiveState()
        val audioProvider = LiveAudioProvider()
        currentProvider.value = audioProvider

        fun getStopReason() = if (audioProvider.queueLimitReached) StopReason.QUEUE_LIMIT_REACHED else null

        fun makeRecordingProgress() = Progress.Recording(
            durationMs = state.durationMs,
            amplitude = state.amplitude,
            segments = state.segments,
            detectedLanguage = state.detectedLanguage
        )

        fun makeFinishingProgress() = Progress.Finishing(
            progressPercent = calculateProgress(state.lastTranscribedMs, state.durationMs),
            segments = state.segments,
            detectedLanguage = state.detectedLanguage,
            stopReason = getStopReason()
        )

        try {
            val observerJob = launch {
                combine(
                    audioProvider.amplitude,
                    audioProvider.durationMs,
                    audioProvider.recordingEnded
                ) { amp, dur, ended ->
                    Triple(amp, dur, ended)
                }.collect { (amp, dur, ended) ->
                    state.amplitude = amp
                    state.durationMs = dur

                    if (ended && !state.isFinishing) {
                        state.isFinishing = true
                        send(makeFinishingProgress())
                        return@collect
                    }

                    if (state.durationMs - state.lastTranscribedMs > AUTO_STOP_GAP_MS) {
                        audioProvider.stop()
                    }

                    if (!state.isFinishing) {
                        send(makeRecordingProgress())
                    }
                }
            }

            val eventJob = launch {
                whisperDataSource.events.collect { event ->
                    when (event) {
                        is TranscriptionEvent.Segment -> {
                            state.lastTranscribedMs = event.segment.endMs
                            event.segment.language?.let { state.detectedLanguage = it }
                            state.segments = (state.segments + event.segment).sortedBy { it.startMs }

                            val progress = if (state.isFinishing) makeFinishingProgress() else makeRecordingProgress()
                            trySend(progress)
                        }
                        is TranscriptionEvent.StreamComplete -> {
                            if (event.success) {
                                send(Progress.Complete(
                                    segments = state.segments,
                                    detectedLanguage = state.detectedLanguage,
                                    recordingDurationMs = state.durationMs,
                                    processingTimeMs = System.currentTimeMillis() - startTime
                                ))
                            } else {
                                send(Progress.Failed(
                                    segments = state.segments,
                                    detectedLanguage = state.detectedLanguage,
                                    gpuWasEnabled = gpuEnabled
                                ))
                            }
                            this@launch.cancel()
                        }
                        is TranscriptionEvent.Error -> throw TranscriptionException(event.message)
                        else -> {}
                    }
                }
            }

            launch { audioProvider.startRecording() }

            whisperDataSource.startStream(
                audioProvider = audioProvider,
                numThreads = numThreads,
                language = language,
                translate = translateToEnglish,
                live = true
            )

            eventJob.join()
            observerJob.cancel()
        } finally {
            currentProvider.compareAndSet(audioProvider, null)
        }
    }

    private fun calculateProgress(transcribedMs: Long, totalMs: Long): Int {
        return if (totalMs > 0) {
            ((transcribedMs.toFloat() / totalMs) * 100).coerceIn(0f, 100f).toInt()
        } else 0
    }

    fun stop() {
        currentProvider.value?.stop()
    }
}
