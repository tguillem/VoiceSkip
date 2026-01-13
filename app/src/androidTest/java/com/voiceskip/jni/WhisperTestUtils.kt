// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.jni

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal object WhisperTestUtils {
    const val MODEL_BASE = "models/ggml-base-q5_1.bin"
    const val MODEL_SMALL = "models/ggml-small-q8_0.bin"
    const val VAD_MODEL = "models/ggml-silero-v6.2.0.bin"
    // Wikipedia (CC BY-SA 3.0)
    // https://upload.wikimedia.org/wikipedia/en/d/d4/En.henryfphillips.ogg
    const val AUDIO_LONG = "test_long_273s.opus"
    // OHF-Voice dataset (CC0)
    // https://github.com/OHF-Voice/voice-datasets/blob/master/en_US/joe/0000000001.mp3
    const val AUDIO_CLEAR = "test_clear_8s.opus"

    val CPU_THREADS = Runtime.getRuntime().availableProcessors()
    val GPU_THREADS = 1
    val TURBO_CPU_THREADS = (CPU_THREADS - 1).coerceAtMost(8)

    fun copyAssetToCache(context: Context, assetName: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val tempFile = File(context.cacheDir, assetName)

        testContext.assets.open(assetName).use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }
}
