// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.main

import android.media.MediaPlayer
import android.util.Log
import com.voiceskip.data.ErrorHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_TAG = "AudioPlaybackManager"

class AudioPlaybackManager(
    private val mediaPlayerFactory: () -> MediaPlayer = { MediaPlayer() }
) {
    private var mediaPlayer: MediaPlayer? = null

    suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        releaseMediaPlayer()

        try {
            mediaPlayer = mediaPlayerFactory().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    releaseMediaPlayer()
                }
                setOnErrorListener { _, _, _ ->
                    releaseMediaPlayer()
                    true
                }
                start()
            }
        } catch (e: Exception) {
            val whisperError = ErrorHandler.handleError(e)
            ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            releaseMediaPlayer()
            throw whisperError
        }
    }

    suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        releaseMediaPlayer()
    }

    fun releaseMediaPlayer() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                val whisperError = ErrorHandler.handleError(e)
                ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            }

            try {
                player.reset()
            } catch (e: Exception) {
                val whisperError = ErrorHandler.handleError(e)
                ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            }

            try {
                player.release()
            } catch (e: Exception) {
                val whisperError = ErrorHandler.handleError(e)
                ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            }
        }
        mediaPlayer = null
    }

    fun cleanup() {
        // MediaPlayer must be released first to prevent audio focus leaks
        releaseMediaPlayer()
    }
}