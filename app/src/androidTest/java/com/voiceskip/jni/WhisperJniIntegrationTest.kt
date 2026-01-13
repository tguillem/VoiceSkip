// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.jni

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.source.TranscriptionEvent
import com.voiceskip.data.source.WhisperDataSource
import com.voiceskip.data.source.WhisperDataSourceImpl
import com.voiceskip.media.FileAudioProvider
import com.voiceskip.util.WakeLockManager
import com.voiceskip.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CompletableDeferred
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

private const val TAG = "WhisperJniTest"

/**
 * Integration tests for whisper.cpp JNI bridge.
 */
@RunWith(AndroidJUnit4::class)
class WhisperJniIntegrationTest {

    private lateinit var context: Context
    private var whisperContext: WhisperContext? = null
    private var whisperDataSource: WhisperDataSource? = null
    private var testScope: CoroutineScope? = null
    private lateinit var wakeLockManager: WakeLockManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        wakeLockManager = WakeLockManager(context)
        wakeLockManager.acquire()
    }

    @After
    fun teardown() {
        whisperContext?.destroy()
        whisperContext = null
        whisperDataSource?.destroy()
        whisperDataSource = null
        testScope?.cancel()
        testScope = null
        wakeLockManager.release()
    }

    @Test
    fun loadModel_firesOnLoadedCallback(): Unit = runBlocking {
        Log.i(TAG, "loadModel: START")
        val loaded = CompletableDeferred<Boolean>()

        whisperContext = WhisperContext.create(
            onLoaded = { _, _ -> loaded.complete(true) },
            onError = { loaded.completeExceptionally(RuntimeException(it)) }
        )

        whisperContext!!.loadModel(
            context.assets,
            WhisperTestUtils.MODEL_BASE,
            useGpu = false
        )

        withTimeout(60_000) {
            assertTrue(loaded.await())
        }
        Log.i(TAG, "loadModel: DONE")
    }

    @Test
    fun loadAllModels_cpuAndGpu_succeeds(): Unit = runBlocking {
        Log.i(TAG, "loadAllModels: START")

        val models = listOf(
            Triple(WhisperTestUtils.MODEL_BASE, false, "base CPU"),
            Triple(WhisperTestUtils.MODEL_BASE, true, "base GPU"),
            Triple(WhisperTestUtils.MODEL_SMALL, false, "small CPU"),
            Triple(WhisperTestUtils.MODEL_SMALL, true, "small GPU"),
        )

        var loadCount = 0

        whisperContext = WhisperContext.create(
            onLoaded = { _, _ -> loadCount++ },
            onError = { throw RuntimeException(it) }
        )

        for ((modelPath, useGpu, description) in models) {
            whisperContext!!.loadModel(context.assets, modelPath, WhisperTestUtils.VAD_MODEL, useGpu)

            withTimeout(120_000) {
                while (loadCount < models.indexOf(Triple(modelPath, useGpu, description)) + 1) {
                    kotlinx.coroutines.delay(100)
                }
            }
            Log.i(TAG, "loadAllModels: $description loaded")
        }

        assertTrue("Expected all ${models.size} models to load", loadCount == models.size)
        Log.i(TAG, "loadAllModels: DONE")
    }

    @Test
    fun transcribeClearAudio_withSmallModel_cpuThenGpu_matchesExpectedText(): Unit = runBlocking {
        Log.i(TAG, "transcribeClearAudio: START")
        val expectedText = "Having complete focus on a recipe and not allowing yourself to be distracted by your thoughts can have a therapeutic effect."

        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        whisperDataSource = WhisperDataSourceImpl()

        val tempFile = WhisperTestUtils.copyAssetToCache(context, WhisperTestUtils.AUDIO_CLEAR)
        for ((useGpu, mode) in listOf(false to "CPU", true to "GPU")) {
            val threads = if (useGpu) WhisperTestUtils.GPU_THREADS else WhisperTestUtils.CPU_THREADS
            whisperDataSource!!.loadModel(context.assets, WhisperTestUtils.MODEL_SMALL, WhisperTestUtils.VAD_MODEL, useGpu)

            withTimeout(120_000) {
                whisperDataSource!!.events.first { it is TranscriptionEvent.ModelLoaded }
            }
            Log.i(TAG, "transcribeClearAudio: $mode model loaded")

            var lastError: AssertionError? = null
            for (attempt in 1..3) {
                val segments = mutableListOf<String>()
                val audioProvider = FileAudioProvider(file = tempFile)
                audioProvider.startDecoding()
                whisperDataSource!!.setDuration(audioProvider.durationMs.first { it > 0 })

                whisperDataSource!!.startStream(
                    audioProvider = audioProvider,
                    numThreads = threads,
                    language = "en",
                    translate = false
                )

                withTimeout(180_000) {
                    whisperDataSource!!.events.first { event ->
                        if (event is TranscriptionEvent.Segment) {
                            segments.add(event.segment.text.trim())
                        }
                        event is TranscriptionEvent.StreamComplete
                    }
                }

                audioProvider.release()

                val transcribedText = segments.joinToString(" ")
                Log.i(TAG, "transcribeClearAudio [$mode] attempt $attempt: '$transcribedText'")

                try {
                    assertEquals("$mode transcription mismatch", expectedText, transcribedText)
                    lastError = null
                    break
                } catch (e: AssertionError) {
                    Log.w(TAG, "$mode attempt $attempt/3 failed, expected: '$expectedText'")
                    lastError = e
                }
            }
            lastError?.let { throw it }
        }

        tempFile.delete()
        Log.i(TAG, "transcribeClearAudio: DONE")
    }

    @Test
    fun transcribeLongAudio_withBaseModel_returnsSegments(): Unit = runBlocking {
        Log.i(TAG, "transcribeLongAudio: START")

        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        whisperDataSource = WhisperDataSourceImpl()

        whisperDataSource!!.loadModel(context.assets, WhisperTestUtils.MODEL_BASE, WhisperTestUtils.VAD_MODEL, false)

        withTimeout(60_000) {
            whisperDataSource!!.events.first { it is TranscriptionEvent.ModelLoaded }
        }
        Log.i(TAG, "transcribeLongAudio: model loaded")

        val tempFile = WhisperTestUtils.copyAssetToCache(context, WhisperTestUtils.AUDIO_LONG)
        val segments = mutableListOf<TranscriptionEvent.Segment>()
        val audioProvider = FileAudioProvider(file = tempFile)
        audioProvider.startDecoding()
        whisperDataSource!!.setDuration(audioProvider.durationMs.first { it > 0 })

        whisperDataSource!!.startStream(
            audioProvider = audioProvider,
            numThreads = WhisperTestUtils.CPU_THREADS,
            language = null,
            translate = false
        )

        withTimeout(180_000) {
            whisperDataSource!!.events.first { event ->
                if (event is TranscriptionEvent.Segment) {
                    segments.add(event)
                }
                event is TranscriptionEvent.StreamComplete
            }
        }

        audioProvider.release()
        tempFile.delete()

        Log.i(TAG, "transcribeLongAudio: ${segments.size} segments, ${segments.sumOf { it.segment.text.length }} chars")
        assertTrue("Expected multiple segments for long audio", segments.size > 5)
        assertTrue("Expected substantial text", segments.sumOf { it.segment.text.length } > 50)
        Log.i(TAG, "transcribeLongAudio: DONE")
    }

    @Test
    fun invalidModel_firesOnErrorCallback(): Unit = runBlocking {
        Log.i(TAG, "invalidModel: START")
        val error = CompletableDeferred<String>()

        whisperContext = WhisperContext.create(
            onLoaded = { _, _ -> error.completeExceptionally(RuntimeException("Should not have loaded")) },
            onError = { error.complete(it) }
        )

        whisperContext!!.loadModel(context.assets, "invalid/nonexistent.bin", null, false)

        withTimeout(10_000) {
            val errorMessage = error.await()
            assertTrue("Expected error message", errorMessage.isNotEmpty())
        }
        Log.i(TAG, "invalidModel: DONE")
    }
}
