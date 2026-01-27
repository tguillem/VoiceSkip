// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.domain.usecase

import android.net.Uri
import com.voiceskip.data.ErrorHandler
import com.voiceskip.data.repository.AudioPlaybackRepository
import com.voiceskip.data.repository.PlaybackState
import com.voiceskip.data.repository.SettingsRepository
import com.voiceskip.util.VoiceSkipLogger
import com.voiceskip.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

private const val LOG_TAG = "AudioListenUseCase"

class AudioListenUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioPlaybackRepository: AudioPlaybackRepository
) {
    val playbackState: StateFlow<PlaybackState> = audioPlaybackRepository.playbackState

    /**
     * Toggle listen mode on/off. When enabled, prepares playback for the given audio URI.
     * When disabled, stops any active playback.
     */
    suspend fun setListenModeEnabled(enabled: Boolean, audioUri: Uri?) {
        settingsRepository.updateListenModeEnabled(enabled).onFailure { exception ->
            ErrorHandler.logError(LOG_TAG, exception, critical = false)
        }

        if (enabled) {
            if (audioUri != null) {
                preparePlaybackSafely(audioUri)
            }
        } else {
            stopPlaybackSafely()
        }
    }

    /**
     * Prepares playback for the given URI if listen mode is currently enabled.
     * Call this when starting a file transcription or restoring a saved transcription.
     */
    suspend fun prepareIfListenModeEnabled(uri: Uri, listenModeEnabled: Boolean) {
        if (listenModeEnabled) {
            preparePlaybackSafely(uri)
        }
    }

    fun togglePlayPause() {
        audioPlaybackRepository.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        audioPlaybackRepository.seekTo(positionMs)
    }

    fun seekToSegment(segment: WhisperSegment) {
        audioPlaybackRepository.seekTo(maxOf(0L, segment.startMs - Companion.SEGMENT_SEEK_OFFSET_MS))
    }

    suspend fun stopPlayback() {
        audioPlaybackRepository.stopPlayback()
    }

    fun cleanup() {
        audioPlaybackRepository.cleanup()
    }

    /** Query the display name for a content URI */
    fun getFileNameFromUri(uri: Uri): String? {
        return audioPlaybackRepository.getFileNameFromUri(uri)
    }

    /** Take persistable read permission for a URI if possible */
    fun takePersistablePermission(uri: Uri) {
        audioPlaybackRepository.takePersistablePermission(uri)
    }

    private suspend fun preparePlaybackSafely(uri: Uri) {
        runCatching {
            audioPlaybackRepository.preparePlayback(uri)
        }.onFailure { exception ->
            if (exception is CancellationException) throw exception
            ErrorHandler.logError(LOG_TAG, exception, critical = false)
            VoiceSkipLogger.e("Failed to prepare audio playback", exception)
        }
    }

    private suspend fun stopPlaybackSafely() {
        runCatching {
            audioPlaybackRepository.stopPlayback()
        }.onFailure { exception ->
            if (exception is CancellationException) throw exception
            ErrorHandler.logError(LOG_TAG, exception, critical = false)
        }
    }

    companion object {
        /** Offset in ms to seek before segment start - exposed for UI calculations */
        const val SEGMENT_SEEK_OFFSET_MS = 300L
    }
}
