// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ErrorHandlerTest {

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any(), any<Throwable>()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `IOException mapped to FileException`() {
        val ioException = IOException("File not found")

        val result = ErrorHandler.handleError(ioException)

        assertThat(result).isInstanceOf(VoiceSkipException.FileException::class.java)
        assertThat(result.message).isEqualTo("File not found")
        assertThat(result.cause).isEqualTo(ioException)
    }

    @Test
    fun `IOException with null message gets default message`() {
        val ioException = IOException()

        val result = ErrorHandler.handleError(ioException)

        assertThat(result).isInstanceOf(VoiceSkipException.FileException::class.java)
        assertThat(result.message).isEqualTo("File operation failed")
    }

    @Test
    fun `IllegalStateException mapped to AudioException`() {
        val illegalStateException = IllegalStateException("Audio session error")

        val result = ErrorHandler.handleError(illegalStateException)

        assertThat(result).isInstanceOf(VoiceSkipException.AudioException::class.java)
        assertThat(result.message).isEqualTo("Audio session error")
        assertThat(result.cause).isEqualTo(illegalStateException)
    }

    @Test
    fun `IllegalStateException with null message gets default message`() {
        val illegalStateException = IllegalStateException()

        val result = ErrorHandler.handleError(illegalStateException)

        assertThat(result).isInstanceOf(VoiceSkipException.AudioException::class.java)
        assertThat(result.message).isEqualTo("Audio error")
    }

    @Test
    fun `SecurityException mapped to FileException with permission denied`() {
        val securityException = SecurityException("Access denied")

        val result = ErrorHandler.handleError(securityException)

        assertThat(result).isInstanceOf(VoiceSkipException.FileException::class.java)
        assertThat(result.message).isEqualTo("Permission denied")
        assertThat(result.cause).isEqualTo(securityException)
    }

    @Test
    fun `Unknown exception wrapped in TranscriptionException`() {
        val unknownException = RuntimeException("Something went wrong")

        val result = ErrorHandler.handleError(unknownException)

        assertThat(result).isInstanceOf(VoiceSkipException.TranscriptionException::class.java)
        assertThat(result.message).isEqualTo("Something went wrong")
        assertThat(result.cause).isEqualTo(unknownException)
    }

    @Test
    fun `Unknown exception with null message gets default message`() {
        val unknownException = RuntimeException()

        val result = ErrorHandler.handleError(unknownException)

        assertThat(result).isInstanceOf(VoiceSkipException.TranscriptionException::class.java)
        assertThat(result.message).isEqualTo("Unknown error")
    }

    @Test
    fun `VoiceSkipException passed through unchanged`() {
        val originalException = VoiceSkipException.TranscriptionException("Original error")

        val result = ErrorHandler.handleError(originalException)

        assertThat(result).isSameInstanceAs(originalException)
    }

    @Test
    fun `all VoiceSkipException subtypes passed through unchanged`() {
        val exceptions = listOf(
            VoiceSkipException.TranscriptionException("transcription"),
            VoiceSkipException.AudioException("audio"),
            VoiceSkipException.FileException("file")
        )

        exceptions.forEach { exception ->
            val result = ErrorHandler.handleError(exception)
            assertThat(result).isSameInstanceAs(exception)
        }
    }

    @Test
    fun `logError does not throw`() {
        val exception = RuntimeException("Test error")

        ErrorHandler.logError("TestTag", exception)
        ErrorHandler.logError("TestTag", exception, critical = true)
        ErrorHandler.logError("TestTag", VoiceSkipException.AudioException("test"))
    }
}
