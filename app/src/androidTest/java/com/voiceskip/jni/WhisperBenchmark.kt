// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.jni

import android.content.Context
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceskip.MainActivity
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.source.TranscriptionEvent
import com.voiceskip.data.source.WhisperDataSourceImpl
import com.voiceskip.media.FileAudioProvider
import com.voiceskip.util.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val BENCHMARK_TAG = "WhisperBenchmark"

@RunWith(AndroidJUnit4::class)
class WhisperBenchmark {

    private enum class Mode { CPU, GPU, TURBO }

    private lateinit var context: Context
    private var whisperDataSource: WhisperDataSourceImpl? = null
    private var testScope: CoroutineScope? = null
    private lateinit var wakeLockManager: WakeLockManager
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        wakeLockManager = WakeLockManager(context)
        wakeLockManager.acquire()
    }

    @After
    fun teardown() {
        whisperDataSource?.destroy()
        whisperDataSource = null
        testScope?.cancel()
        testScope = null
        scenario = null
        wakeLockManager.release()
    }

    private fun launchActivityIfNeeded(): Boolean {
        if (scenario != null) return true
        return try {
            val intent = android.content.Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_SKIP_MODEL_LOAD, true)
            }
            scenario = ActivityScenario.launch(intent)
            true
        } catch (e: IllegalStateException) {
            Log.w(BENCHMARK_TAG, "BENCHMARK: SKIPPED foreground tests (disable 'Don't keep activities' in developer options)")
            false
        }
    }

    @Test
    fun benchmark(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val hasCustomArgs = args.getString("turbo") != null ||
                            args.getString("gpu") != null ||
                            args.getString("foreground") != null

        if (hasCustomArgs) {
            val turbo = args.getString("turbo")?.toBoolean() ?: false
            val gpu = args.getString("gpu")?.toBoolean() ?: true
            val mode = when {
                turbo -> Mode.TURBO
                gpu -> Mode.GPU
                else -> Mode.CPU
            }
            val foreground = args.getString("foreground")?.toBoolean() ?: false

            if (foreground && !launchActivityIfNeeded()) return@runBlocking

            runBenchmark(WhisperTestUtils.MODEL_SMALL, WhisperTestUtils.AUDIO_LONG, mode, foreground)
        } else {
            runBenchmark(WhisperTestUtils.MODEL_SMALL, WhisperTestUtils.AUDIO_LONG, Mode.CPU, false)

            runBenchmark(WhisperTestUtils.MODEL_SMALL, WhisperTestUtils.AUDIO_LONG, Mode.GPU, false)

            runBenchmark(WhisperTestUtils.MODEL_SMALL, WhisperTestUtils.AUDIO_LONG, Mode.TURBO, false)

            // Foreground tests (launch activity once)
            if (launchActivityIfNeeded()) {
                runBenchmark(WhisperTestUtils.MODEL_SMALL, WhisperTestUtils.AUDIO_LONG, Mode.CPU, true)

                runBenchmark(WhisperTestUtils.MODEL_SMALL, WhisperTestUtils.AUDIO_LONG, Mode.GPU, true)

                runBenchmark(WhisperTestUtils.MODEL_SMALL, WhisperTestUtils.AUDIO_LONG, Mode.TURBO, true)
            }
        }
    }

    private fun extractModelName(modelPath: String): String =
        modelPath.substringAfter("ggml-").substringBefore("-q")

    private suspend fun runBenchmark(
        modelPath: String,
        audioAsset: String,
        mode: Mode,
        foreground: Boolean
    ) {
        val modelName = extractModelName(modelPath)
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        whisperDataSource = WhisperDataSourceImpl()

        val loadStart = System.nanoTime()

        val useGpu = mode != Mode.CPU
        whisperDataSource!!.loadModel(context.assets, modelPath, WhisperTestUtils.VAD_MODEL, useGpu)
        withTimeout(180_000) {
            whisperDataSource!!.events.first { it is TranscriptionEvent.ModelLoaded }
        }

        if (mode == Mode.TURBO) {
            whisperDataSource!!.setTurboMode(true, context.assets, modelPath, WhisperTestUtils.VAD_MODEL)
            withTimeout(180_000) {
                whisperDataSource!!.events.first { it is TranscriptionEvent.ModelLoaded && it.turbo }
            }
        }

        val loadMs = (System.nanoTime() - loadStart) / 1_000_000

        val tempFile = WhisperTestUtils.copyAssetToCache(context, audioAsset)
        val audioProvider = FileAudioProvider(file = tempFile)
        audioProvider.startDecoding()

        val durationMs = audioProvider.durationMs.first { it > 0 }
        whisperDataSource!!.setDuration(durationMs)

        val threads = when (mode) {
            Mode.CPU -> WhisperTestUtils.CPU_THREADS
            Mode.GPU -> WhisperTestUtils.GPU_THREADS
            Mode.TURBO -> WhisperTestUtils.TURBO_CPU_THREADS
        }

        val transcribeStart = System.nanoTime()

        whisperDataSource!!.startStream(
            audioProvider = audioProvider,
            numThreads = threads,
            language = "en",
            translate = false
        )

        withTimeout(600_000) {
            whisperDataSource!!.events.first { it is TranscriptionEvent.StreamComplete }
        }

        val transcribeMs = (System.nanoTime() - transcribeStart) / 1_000_000
        audioProvider.release()
        tempFile.delete()

        whisperDataSource?.destroy()
        whisperDataSource = null
        testScope?.cancel()
        testScope = null

        val logThreads = if (mode == Mode.TURBO) {
            WhisperTestUtils.GPU_THREADS + WhisperTestUtils.TURBO_CPU_THREADS
        } else threads
        val modeStr = "${mode.name} ${if (foreground) "foreground" else "background"}"
        val rtf = if (durationMs > 0) transcribeMs.toDouble() / durationMs else 0.0
        Log.i(BENCHMARK_TAG, "BENCHMARK: $modelName | ${durationMs}ms | $logThreads threads | $modeStr | load=${loadMs}ms | transcribe=${transcribeMs}ms | RTF=${"%.2f".format(rtf)}x")
    }
}
