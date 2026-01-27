// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.net.Uri
import com.voiceskip.domain.usecase.LiveTranscriptionUseCase
import com.voiceskip.whispercpp.whisper.WhisperSegment

sealed interface TranscriptionState {
    data object Idle : TranscriptionState

    data class LiveRecording(
        val durationMs: Long,
        val amplitude: Float,
        val progress: Int,
        val currentSegment: String?,
        val segments: List<WhisperSegment>,
        val detectedLanguage: String? = null
    ) : TranscriptionState

    data class FinishingTranscription(
        val progress: Int,
        val currentSegment: String?,
        val segments: List<WhisperSegment>,
        val detectedLanguage: String? = null,
        val stopReason: LiveTranscriptionUseCase.StopReason? = null
    ) : TranscriptionState

    data class Transcribing(
        val progress: Int,
        val currentSegment: String?,
        val segments: List<WhisperSegment>,
        val detectedLanguage: String? = null
    ) : TranscriptionState

    data class Complete(
        val text: String,
        val segments: List<WhisperSegment>,
        val detectedLanguage: String?,
        val audioLengthMs: Int,
        val processingTimeMs: Long,
        val audioUri: Uri? = null
    ) : TranscriptionState

    data class Error(
        val message: String,
        val recoverable: Boolean,
        val gpuWasEnabled: Boolean = false
    ) : TranscriptionState
}
