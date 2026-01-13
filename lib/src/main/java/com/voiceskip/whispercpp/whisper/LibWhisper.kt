// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import java.io.File

private const val LOG_TAG = "LibWhisper"

/**
 * Represents a transcribed segment with text and timestamps.
 *
 * @param language Detected language code (e.g., "en", "fr"). Null means same as previous segment.
 */
data class WhisperSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val language: String? = null
) {
    val startTimeFormatted: String
        get() = formatTimestamp(startMs)

    val endTimeFormatted: String
        get() = formatTimestamp(endMs)

    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}

/**
 * Interface for providing audio samples to the streaming transcription.
 * Implementations should be thread-safe as readAudio is called from a native thread.
 */
interface AudioProvider {
    /**
     * Read audio samples into the buffer.
     * @param buffer Float array to fill with audio samples
     * @param maxSamples Maximum number of samples to read
     * @return Number of samples read (>0), 0 for EOF, negative for error
     */
    fun readAudio(buffer: FloatArray, maxSamples: Int): Int
}

/**
 * WhisperContext - Instance-based wrapper for Whisper C++ with lifecycle management
 *
 * Usage:
 * ```
 * val whisper = WhisperContext.create(
 *     onProgress = { progress -> Log.d(TAG, "Progress: $progress%") },
 *     onLoaded = { gpuUsed -> Log.d(TAG, "Model loaded! GPU: $gpuUsed") },
 *     onSegment = { segment -> Log.d(TAG, "Segment: ${segment.text}") },
 *     onStreamComplete = { success -> Log.d(TAG, "Stream complete: $success") }
 * )
 *
 * whisper.loadModel(assetManager, "models/ggml-base.en.bin")
 * whisper.startStream(audioProvider, numThreads = 4, language = "en")
 * whisper.stop()  // Abort streaming
 * whisper.destroy()
 * ```
 */
@Keep
class WhisperContext private constructor(
    private val progressCallback: ((Int) -> Unit)? = null,
    private val loadedCallback: ((slotIndex: Int, gpuInfo: String?) -> Unit)? = null,
    private val newSegmentCallback: ((WhisperSegment) -> Unit)? = null,
    private val streamCompleteCallback: ((Boolean) -> Unit)? = null,
    private val errorCallback: ((String) -> Unit)? = null
) {
    @Keep
    private var mInstance: Long = 0L

    private var audioProvider: AudioProvider? = null

    init {
        mInstance = nativeCreate()
        Log.d(LOG_TAG, "WhisperContext created with instance: $mInstance")
    }

    @Keep
    @Suppress("unused") // Called from JNI
    fun onProgress(progress: Int) {
        progressCallback?.invoke(progress)
    }

    @Keep
    @Suppress("unused") // Called from JNI
    fun onLoaded(slotIndex: Int, gpuInfo: String?) {
        loadedCallback?.invoke(slotIndex, gpuInfo)
    }

    @Keep
    @Suppress("unused") // Called from JNI
    fun onNewSegment(text: String, startMs: Long, endMs: Long, language: String?) {
        newSegmentCallback?.invoke(WhisperSegment(text, startMs, endMs, language))
    }

    @Keep
    @Suppress("unused") // Called from JNI
    fun onStreamComplete(success: Boolean) {
        streamCompleteCallback?.invoke(success)
    }

    @Keep
    @Suppress("unused") // Called from JNI
    fun onError(errorMessage: String) {
        Log.e(LOG_TAG, "JNI Error: $errorMessage")
        errorCallback?.invoke(errorMessage)
    }

    @Keep
    @Suppress("unused") // Called from JNI
    fun readAudio(buffer: FloatArray, maxSamples: Int): Int {
        return audioProvider?.readAudio(buffer, maxSamples) ?: 0
    }

    /**
     * Load model from asset in a background thread (C-side pthread)
     * The loadedCallback will be invoked when loading completes
     *
     * @param assetManager Android AssetManager for loading model files
     * @param modelPath Path to the model file within assets
     * @param vadModelPath Optional path to VAD model for silence detection
     * @param useGpu Whether to use GPU acceleration (Vulkan)
     */
    fun loadModel(
        assetManager: AssetManager,
        modelPath: String,
        vadModelPath: String? = null,
        useGpu: Boolean = true
    ) {
        require(mInstance != 0L) { "WhisperContext not initialized" }
        Log.d(LOG_TAG, "Loading model: $modelPath, vadModel: $vadModelPath, useGpu: $useGpu")
        nativeLoadModel(assetManager, modelPath, vadModelPath, useGpu)
    }

    /**
     * Load a second model for turbo mode (parallel CPU+GPU processing)
     */
    fun loadSecondModel(assetManager: AssetManager, modelPath: String, vadModelPath: String?) {
        require(mInstance != 0L) { "WhisperContext not initialized" }
        Log.d(LOG_TAG, "Loading second model for turbo: $modelPath, vad: $vadModelPath")
        nativeLoadSecondModel(assetManager, modelPath, vadModelPath)
    }

    /**
     * Unload the second model (disable turbo mode)
     */
    fun unloadSecondModel() {
        require(mInstance != 0L) { "WhisperContext not initialized" }
        Log.d(LOG_TAG, "Unloading second model")
        nativeLoadSecondModel(null, null, null)
    }

    /**
     * Start streaming transcription.
     * The stream will pull audio from the AudioProvider until it returns 0 (EOF) or stop() is called.
     *
     * @param audioProvider Provider that supplies audio samples
     * @param numThreads Number of threads for transcription
     * @param language Language code or null for auto-detect
     * @param translate If true, translate to English
     * @param live True for live recording, false for file transcription
     */
    fun startStream(
        audioProvider: AudioProvider,
        numThreads: Int,
        language: String? = null,
        translate: Boolean = false,
        live: Boolean = false
    ) {
        require(mInstance != 0L) { "WhisperContext not initialized" }
        this.audioProvider = audioProvider
        Log.d(LOG_TAG, "Starting stream: threads=$numThreads, lang=$language, " +
                "translate=$translate, live=$live")
        nativeStart(numThreads, language, translate, live)
    }

    /**
     * Stop ongoing streaming transcription.
     * The streamCompleteCallback will be called when the stream finishes.
     */
    fun stop() {
        require(mInstance != 0L) { "WhisperContext not initialized" }
        Log.d(LOG_TAG, "Stopping stream")
        nativeStop()
    }

    /**
     * Set total audio duration for progress calculation.
     * Can be called while streaming. Set to 0 to disable progress callbacks.
     */
    fun setDuration(durationMs: Long) {
        require(mInstance != 0L) { "WhisperContext not initialized" }
        nativeSetDuration(durationMs)
    }

    fun updateLanguage(language: String?) {
        require(mInstance != 0L) { "WhisperContext not initialized" }
        nativeUpdateLanguage(language)
    }

    /**
     * Destroy the context and free all resources
     * Must be called when done to prevent memory leaks
     */
    fun destroy() {
        if (mInstance != 0L) {
            Log.d(LOG_TAG, "Destroying WhisperContext instance: $mInstance")
            nativeDestroy()
            mInstance = 0L
            audioProvider = null
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeLoadModel(
        assetManager: AssetManager,
        modelPath: String,
        vadModelPath: String?,
        useGpu: Boolean
    )
    private external fun nativeLoadSecondModel(assetManager: AssetManager?, modelPath: String?, vadModelPath: String?)
    private external fun nativeStart(
        numThreads: Int,
        language: String?,
        translate: Boolean,
        live: Boolean
    )
    private external fun nativeStop()
    private external fun nativeSetDuration(durationMs: Long)
    private external fun nativeUpdateLanguage(language: String?)
    private external fun nativeDestroy()

    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var libraryLoaded = false

            if (isArmEabiV8a()) {
                try {
                    Log.d(LOG_TAG, "Trying to load libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                    libraryLoaded = true
                    Log.d(LOG_TAG, "Successfully loaded libwhisper_v8fp16_va.so")
                } catch (e: Throwable) {
                    Log.w(LOG_TAG, "Failed to load whisper_v8fp16_va: ${e.message}")
                }
            }

            if (!libraryLoaded && (isArmEabiV7a() || isArmEabiV8a())) {
                try {
                    Log.d(LOG_TAG, "Trying to load libwhisper_vfpv4.so")
                    System.loadLibrary("whisper_vfpv4")
                    libraryLoaded = true
                    Log.d(LOG_TAG, "Successfully loaded libwhisper_vfpv4.so")
                } catch (e: Throwable) {
                    Log.w(LOG_TAG, "Failed to load whisper_vfpv4: ${e.message}")
                }
            }

            if (!libraryLoaded) {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        /**
         * Create a new WhisperContext instance with optional callbacks
         *
         * @param onProgress Called during transcription with progress percentage (0-100)
         * @param onLoaded Called when model loading completes (slotIndex = 0 for main, 1 for turbo; gpuInfo = GPU device name if Vulkan active, null for CPU)
         * @param onSegment Called when a new segment is transcribed (includes detected language)
         * @param onStreamComplete Called when streaming transcription completes (success = true if no errors)
         * @param onError Called when an error occurs in the JNI layer
         */
        fun create(
            onProgress: ((Int) -> Unit)? = null,
            onLoaded: ((slotIndex: Int, gpuInfo: String?) -> Unit)? = null,
            onSegment: ((WhisperSegment) -> Unit)? = null,
            onStreamComplete: ((success: Boolean) -> Unit)? = null,
            onError: ((String) -> Unit)? = null
        ): WhisperContext {
            return WhisperContext(onProgress, onLoaded, onSegment, onStreamComplete, onError)
        }

        private fun isArmEabiV7a(): Boolean {
            return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
        }

        private fun isArmEabiV8a(): Boolean {
            return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
        }
    }
}
