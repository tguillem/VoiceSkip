// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileAudioProviderTest {

    @Test
    fun emptyFileReportsDecoderFailureWithoutHanging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val emptyFile = File.createTempFile("empty-audio", ".opus", context.cacheDir)
        val audioProvider = FileAudioProvider(file = emptyFile)

        try {
            audioProvider.startDecoding()

            assertThrows(IOException::class.java) {
                runBlocking {
                    withTimeout(5_000) {
                        audioProvider.awaitDuration()
                    }
                }
            }
        } finally {
            audioProvider.release()
            emptyFile.delete()
        }
    }
}
