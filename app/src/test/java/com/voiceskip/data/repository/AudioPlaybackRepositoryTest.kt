// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.media.MediaPlayer
import android.net.Uri
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

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlaybackRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var repository: AudioPlaybackRepositoryImpl
    private lateinit var mockMediaPlayer: MediaPlayer
    private lateinit var mockMediaPlayerFactory: MediaPlayerFactory
    private lateinit var mockContext: android.content.Context
    private lateinit var mockContentResolver: android.content.ContentResolver
    private lateinit var mockUri: Uri
    private var capturedCompletionListener: MediaPlayer.OnCompletionListener? = null
    private var capturedErrorListener: MediaPlayer.OnErrorListener? = null

    @Before
    fun setup() {
        val completionListenerSlot = slot<MediaPlayer.OnCompletionListener>()
        val errorListenerSlot = slot<MediaPlayer.OnErrorListener>()

        mockMediaPlayer = mockk(relaxed = true) {
            every { setDataSource(any<android.content.Context>(), any<Uri>()) } just Runs
            every { prepare() } just Runs
            every { start() } just Runs
            every { stop() } just Runs
            every { reset() } just Runs
            every { release() } just Runs
            every { isPlaying } returns true
            every { duration } returns 60000
            every { currentPosition } returns 0
            every { setOnCompletionListener(capture(completionListenerSlot)) } answers {
                capturedCompletionListener = completionListenerSlot.captured
            }
            every { setOnErrorListener(capture(errorListenerSlot)) } answers {
                capturedErrorListener = errorListenerSlot.captured
            }
        }

        mockMediaPlayerFactory = mockk {
            every { create() } returns mockMediaPlayer
        }

        mockContentResolver = mockk(relaxed = true)
        mockContext = mockk(relaxed = true) {
            every { contentResolver } returns mockContentResolver
        }

        mockUri = mockk(relaxed = true)

        repository = AudioPlaybackRepositoryImpl(
            context = mockContext,
            mediaPlayerFactory = mockMediaPlayerFactory,
            mainDispatcher = dispatcherRule.testDispatcher
        )
    }

    // =========================================================================
    // Release MediaPlayer Tests
    // =========================================================================

    @Test
    fun `cleanup does not stop non-playing player`() = runTest {
        every { mockMediaPlayer.isPlaying } returns false
        repository.preparePlayback(mockUri)

        repository.cleanup()

        verify(exactly = 0) { mockMediaPlayer.stop() }
    }

    @Test
    fun `cleanup handles stop exception`() = runTest {
        every { mockMediaPlayer.stop() } throws IllegalStateException("Already stopped")
        repository.preparePlayback(mockUri)

        repository.cleanup()

        verify { mockMediaPlayer.reset() }
        verify { mockMediaPlayer.release() }
    }

    @Test
    fun `cleanup handles reset exception`() = runTest {
        every { mockMediaPlayer.reset() } throws IllegalStateException("Cannot reset")
        repository.preparePlayback(mockUri)

        repository.cleanup()

        verify { mockMediaPlayer.release() }
    }

    @Test
    fun `cleanup handles release exception`() = runTest {
        every { mockMediaPlayer.release() } throws IllegalStateException("Cannot release")
        repository.preparePlayback(mockUri)

        repository.cleanup()
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Test
    fun `preparePlayback throws on setDataSource error`() = runTest {
        every { mockMediaPlayer.setDataSource(any<android.content.Context>(), any<Uri>()) } throws
            java.io.IOException("File not found")

        var thrownException: Throwable? = null
        try {
            repository.preparePlayback(mockUri)
        } catch (e: Throwable) {
            thrownException = e
        }

        assertThat(thrownException).isNotNull()
    }

    @Test
    fun `preparePlayback throws on prepare error`() = runTest {
        every { mockMediaPlayer.prepare() } throws
            java.io.IOException("Cannot prepare")

        var thrownException: Throwable? = null
        try {
            repository.preparePlayback(mockUri)
        } catch (e: Throwable) {
            thrownException = e
        }

        assertThat(thrownException).isNotNull()
    }
}
