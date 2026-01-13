// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.main

import android.media.MediaPlayer
import com.google.common.truth.Truth.assertThat
import com.voiceskip.TestDispatcherRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlaybackManagerTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var manager: AudioPlaybackManager
    private lateinit var mockMediaPlayer: MediaPlayer
    private var capturedCompletionListener: MediaPlayer.OnCompletionListener? = null
    private var capturedErrorListener: MediaPlayer.OnErrorListener? = null

    @Before
    fun setup() {
        val completionListenerSlot = slot<MediaPlayer.OnCompletionListener>()
        val errorListenerSlot = slot<MediaPlayer.OnErrorListener>()

        mockMediaPlayer = mockk(relaxed = true) {
            every { setDataSource(any<String>()) } just Runs
            every { prepare() } just Runs
            every { start() } just Runs
            every { stop() } just Runs
            every { reset() } just Runs
            every { release() } just Runs
            every { isPlaying } returns true
            every { setOnCompletionListener(capture(completionListenerSlot)) } answers {
                capturedCompletionListener = completionListenerSlot.captured
            }
            every { setOnErrorListener(capture(errorListenerSlot)) } answers {
                capturedErrorListener = errorListenerSlot.captured
            }
        }

        manager = AudioPlaybackManager(
            mediaPlayerFactory = { mockMediaPlayer }
        )
    }

    // =========================================================================
    // Release MediaPlayer Tests
    // =========================================================================

    @Test
    fun `releaseMediaPlayer does not stop non-playing player`() = runTest {
        every { mockMediaPlayer.isPlaying } returns false
        manager.startPlayback(File("/path/to/audio.mp3"))

        manager.releaseMediaPlayer()

        verify(exactly = 0) { mockMediaPlayer.stop() }
    }

    @Test
    fun `releaseMediaPlayer handles stop exception`() = runTest {
        every { mockMediaPlayer.stop() } throws IllegalStateException("Already stopped")
        manager.startPlayback(File("/path/to/audio.mp3"))

        manager.releaseMediaPlayer()

        verify { mockMediaPlayer.reset() }
        verify { mockMediaPlayer.release() }
    }

    @Test
    fun `releaseMediaPlayer handles reset exception`() = runTest {
        every { mockMediaPlayer.reset() } throws IllegalStateException("Cannot reset")
        manager.startPlayback(File("/path/to/audio.mp3"))

        manager.releaseMediaPlayer()

        verify { mockMediaPlayer.release() }
    }

    @Test
    fun `releaseMediaPlayer handles release exception`() = runTest {
        every { mockMediaPlayer.release() } throws IllegalStateException("Cannot release")
        manager.startPlayback(File("/path/to/audio.mp3"))

        manager.releaseMediaPlayer()
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Test
    fun `startPlayback throws on setDataSource error`() = runTest {
        every { mockMediaPlayer.setDataSource(any<String>()) } throws
            java.io.IOException("File not found")

        var thrownException: Throwable? = null
        try {
            manager.startPlayback(File("/nonexistent/audio.mp3"))
        } catch (e: Throwable) {
            thrownException = e
        }

        assertThat(thrownException).isNotNull()
    }

    @Test
    fun `startPlayback throws on prepare error`() = runTest {
        every { mockMediaPlayer.prepare() } throws
            java.io.IOException("Cannot prepare")

        var thrownException: Throwable? = null
        try {
            manager.startPlayback(File("/path/to/audio.mp3"))
        } catch (e: Throwable) {
            thrownException = e
        }

        assertThat(thrownException).isNotNull()
    }
}
