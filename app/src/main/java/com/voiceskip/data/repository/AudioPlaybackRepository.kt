// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

data class PlaybackState(
    val isPlaying: Boolean = false,
    val isPrepared: Boolean = false,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0
)

interface AudioPlaybackRepository {
    val playbackState: StateFlow<PlaybackState>

    suspend fun preparePlayback(uri: Uri)
    suspend fun stopPlayback()

    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun cleanup()

    /** Query the display name for a content URI */
    fun getFileNameFromUri(uri: Uri): String?

    /** Take persistable read permission for a URI if possible */
    fun takePersistablePermission(uri: Uri)
}
