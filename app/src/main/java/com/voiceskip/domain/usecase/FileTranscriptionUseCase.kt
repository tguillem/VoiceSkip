// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.domain.usecase

import android.content.Context
import android.net.Uri
import com.voiceskip.whispercpp.whisper.WhisperSegment
import com.voiceskip.data.VoiceSkipException.TranscriptionException
import com.voiceskip.data.source.TranscriptionEvent
import com.voiceskip.data.source.WhisperDataSource
import com.voiceskip.media.FileAudioProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

class FileTranscriptionUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whisperDataSource: WhisperDataSource
) {
    sealed interface Progress {
        data class Transcribing(
            val progressPercent: Int,
            val segments: List<WhisperSegment>,
            val detectedLanguage: String?
        ) : Progress

        data class Complete(
            val segments: List<WhisperSegment>,
            val detectedLanguage: String?,
            val audioLengthMs: Int,
            val processingTimeMs: Long
        ) : Progress

        data class Failed(
            val segments: List<WhisperSegment>,
            val detectedLanguage: String?,
            val gpuWasEnabled: Boolean
        ) : Progress
    }

    sealed interface Source {
        data class FromFile(val file: File) : Source
        data class FromUri(val uri: Uri) : Source
    }

    fun execute(
        source: Source,
        numThreads: Int,
        language: String?,
        translateToEnglish: Boolean,
        gpuEnabled: Boolean
    ): Flow<Progress> = channelFlow {
        val startTime = System.currentTimeMillis()
        var currentSegments = listOf<WhisperSegment>()
        var detectedLanguage: String? = null

        val audioProvider = when (source) {
            is Source.FromFile -> FileAudioProvider(file = source.file)
            is Source.FromUri -> FileAudioProvider(context = context, uri = source.uri)
        }

        try {
            audioProvider.startDecoding()

            val audioLengthMs = audioProvider.durationMs.first { it > 0 }.toInt()
            whisperDataSource.setDuration(audioLengthMs.toLong())

            val eventJob = launch {
                whisperDataSource.events.collect { event ->
                    when (event) {
                        is TranscriptionEvent.Segment -> {
                            currentSegments = (currentSegments + event.segment).sortedBy { it.startMs }
                            if (detectedLanguage == null) {
                                detectedLanguage = event.segment.language
                            }

                            val progress = if (audioLengthMs > 0) {
                                val maxEndMs = currentSegments.maxOfOrNull { it.endMs } ?: 0L
                                ((maxEndMs.toFloat() / audioLengthMs) * 100)
                                    .coerceIn(0f, 100f).toInt()
                            } else {
                                0
                            }

                            trySend(Progress.Transcribing(
                                progressPercent = progress,
                                segments = currentSegments,
                                detectedLanguage = detectedLanguage
                            ))
                        }
                        is TranscriptionEvent.Progress -> {
                            trySend(Progress.Transcribing(
                                progressPercent = event.percent,
                                segments = currentSegments,
                                detectedLanguage = detectedLanguage
                            ))
                        }
                        is TranscriptionEvent.StreamComplete -> {
                            if (event.success) {
                                val processingTime = System.currentTimeMillis() - startTime

                                send(Progress.Complete(
                                    segments = currentSegments,
                                    detectedLanguage = detectedLanguage,
                                    audioLengthMs = audioLengthMs,
                                    processingTimeMs = processingTime
                                ))
                            } else {
                                send(Progress.Failed(
                                    segments = currentSegments,
                                    detectedLanguage = detectedLanguage,
                                    gpuWasEnabled = gpuEnabled
                                ))
                            }
                            this@launch.cancel()
                        }
                        is TranscriptionEvent.Error -> {
                            throw TranscriptionException(event.message)
                        }
                        else -> {}
                    }
                }
            }

            whisperDataSource.startStream(
                audioProvider = audioProvider,
                numThreads = numThreads,
                language = language,
                translate = translateToEnglish,
                live = false
            )

            eventJob.join()
        } finally {
            audioProvider.release()
        }
    }
}
