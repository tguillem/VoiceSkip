// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import com.voiceskip.data.ErrorHandler
import com.voiceskip.di.MainDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "AudioPlaybackRepository"
private const val POSITION_UPDATE_INTERVAL_MS = 200L

@Singleton
class AudioPlaybackRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaPlayerFactory: MediaPlayerFactory,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher
) : AudioPlaybackRepository {

    private var mediaPlayer: MediaPlayer? = null
    private var positionUpdateJob: Job? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    override suspend fun preparePlayback(uri: Uri) = withContext(mainDispatcher) {
        releaseMediaPlayer()
        stopPositionUpdates()
        _playbackState.value = PlaybackState()

        try {
            mediaPlayer = mediaPlayerFactory.create().apply {
                setDataSource(context, uri)
                prepare()
                setOnCompletionListener {
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        currentPositionMs = 0
                    )
                    stopPositionUpdates()
                }
                setOnErrorListener { _, _, _ ->
                    _playbackState.value = PlaybackState()
                    stopPositionUpdates()
                    releaseMediaPlayer()
                    true
                }
            }
            _playbackState.value = PlaybackState(
                isPrepared = true,
                durationMs = mediaPlayer?.duration?.toLong() ?: 0
            )
        } catch (e: Exception) {
            val whisperError = ErrorHandler.handleError(e)
            ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            _playbackState.value = PlaybackState()
            releaseMediaPlayer()
            throw whisperError
        }
    }

    override fun play() {
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
                _playbackState.value = _playbackState.value.copy(isPlaying = true)
                startPositionUpdates()
            }
        }
    }

    override fun pause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
                stopPositionUpdates()
            }
        }
    }

    override fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                pause()
            } else {
                play()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer?.let { player ->
            val clampedPosition = positionMs.coerceIn(0, player.duration.toLong())
            player.seekTo(clampedPosition.toInt())
            _playbackState.value = _playbackState.value.copy(
                currentPositionMs = clampedPosition
            )
        }
    }

    override suspend fun stopPlayback() = withContext(mainDispatcher) {
        stopPositionUpdates()
        _playbackState.value = PlaybackState()
        releaseMediaPlayer()
    }

    override fun cleanup() {
        coroutineScope.cancel()
        stopPositionUpdates()
        _playbackState.value = PlaybackState()
        releaseMediaPlayer()
    }

    override fun getFileNameFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun takePersistablePermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Permission may not be persistable, continue anyway
        }
    }

    private fun releaseMediaPlayer() {
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

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateJob = coroutineScope.launch {
            while (isActive) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        _playbackState.value = _playbackState.value.copy(
                            currentPositionMs = player.currentPosition.toLong()
                        )
                    }
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }
}

interface MediaPlayerFactory {
    fun create(): MediaPlayer
}

class DefaultMediaPlayerFactory @Inject constructor() : MediaPlayerFactory {
    override fun create(): MediaPlayer = MediaPlayer()
}
