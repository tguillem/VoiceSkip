// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.voiceskip.util.WHISPER_SAMPLE_RATE
import com.voiceskip.whispercpp.whisper.AudioProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Decodes audio files and implements AudioProvider for streaming transcription.
 *
 * Decoding happens in a background thread, samples are buffered to a queue,
 * and readAudio() pulls from the queue.
 */
class FileAudioProvider(
    private val context: Context? = null,
    private val file: File? = null,
    private val uri: Uri? = null
) : AudioProvider {
    companion object {
        private const val LOG_TAG = "FileAudioProvider"
        private const val QUEUE_CAPACITY = 64
        private val EOF_MARKER = FloatArray(0)
    }

    private val audioQueue = ArrayBlockingQueue<FloatArray>(QUEUE_CAPACITY)
    private var currentBuffer: FloatArray? = null
    private var bufferPosition = 0
    private val stopRequested = AtomicBoolean(false)
    private var eofReached = false

    private var handlerThread: HandlerThread? = null
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    init {
        require(file != null || (context != null && uri != null)) {
            "Either file or context+uri must be provided"
        }
    }

    /**
     * Start decoding in the background.
     * Call this before passing the provider to startStream().
     */
    fun startDecoding() {
        stopRequested.set(false)
        audioQueue.clear()
        currentBuffer = null
        bufferPosition = 0
        eofReached = false
        _durationMs.value = 0L

        handlerThread = HandlerThread("FileDecoder").apply { start() }
        val handler = Handler(handlerThread!!.looper)
        handler.post { decode() }
    }

    /**
     * Stop decoding early.
     */
    fun stop() {
        stopRequested.set(true)
        audioQueue.clear()
        audioQueue.offer(EOF_MARKER)
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        try { decoder?.stop() } catch (_: Exception) {}
        handlerThread?.quitSafely()
        handlerThread?.join()
        handlerThread = null
        decoder = null
        extractor = null
    }

    @Synchronized
    override fun readAudio(buffer: FloatArray, maxSamples: Int): Int {
        if (eofReached) return 0

        var filled = 0

        while (filled < maxSamples) {
            if (currentBuffer == null || bufferPosition >= currentBuffer!!.size) {
                currentBuffer = audioQueue.take()
                bufferPosition = 0

                if (currentBuffer === EOF_MARKER) {
                    eofReached = true
                    return filled
                }
            }

            val remaining = currentBuffer!!.size - bufferPosition
            val toCopy = min(remaining, maxSamples - filled)
            System.arraycopy(currentBuffer!!, bufferPosition, buffer, filled, toCopy)
            bufferPosition += toCopy
            filled += toCopy
        }

        return filled
    }

    private fun decode() {
        try {
            extractor = MediaExtractor()
            if (file != null) {
                extractor!!.setDataSource(file.absolutePath)
            } else {
                extractor!!.setDataSource(context!!, uri!!, null)
            }

            val trackInfo = findAudioTrack() ?: run {
                Log.e(LOG_TAG, "No audio track found")
                audioQueue.put(EOF_MARKER)
                return
            }

            _durationMs.value = trackInfo.durationMs
            Log.d(LOG_TAG, "Decoding: ${trackInfo.mime}, ${trackInfo.sampleRate}Hz, " +
                    "${trackInfo.channels} ch, duration: ${trackInfo.durationMs}ms")

            decoder = MediaCodec.createDecoderByType(trackInfo.mime)
            decoder!!.configure(trackInfo.format, null, null, 0)
            decoder!!.start()

            val inputBufferInfo = MediaCodec.BufferInfo()
            var inputEOS = false
            var outputEOS = false

            while (!outputEOS && !stopRequested.get()) {
                if (!inputEOS) {
                    val inputIndex = decoder!!.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder!!.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor!!.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            decoder!!.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEOS = true
                        } else {
                            decoder!!.queueInputBuffer(
                                inputIndex, 0, sampleSize,
                                extractor!!.sampleTime, 0
                            )
                            extractor!!.advance()
                        }
                    }
                }

                val outputIndex = decoder!!.dequeueOutputBuffer(inputBufferInfo, 10000)
                if (outputIndex >= 0) {
                    if (inputBufferInfo.size > 0) {
                        val outputBuffer = decoder!!.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            val pcmData = ByteArray(inputBufferInfo.size)
                            outputBuffer.get(pcmData)
                            outputBuffer.clear()

                            val samples = convertPcmToFloat(
                                pcmData, trackInfo.channels, trackInfo.sampleRate
                            )
                            audioQueue.put(samples)
                        }
                    }
                    decoder!!.releaseOutputBuffer(outputIndex, false)

                    if ((inputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEOS = true
                    }
                }
            }

            Log.d(LOG_TAG, "Decoding complete")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Decoding error", e)
        } finally {
            try {
                audioQueue.put(EOF_MARKER)
            } catch (e: InterruptedException) {
                Log.w(LOG_TAG, "Interrupted while adding EOF_MARKER, clearing queue", e)
                audioQueue.clear()
                audioQueue.offer(EOF_MARKER)
            }
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private data class AudioTrackInfo(
        val format: MediaFormat,
        val mime: String,
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Long
    )

    private fun findAudioTrack(): AudioTrackInfo? {
        for (i in 0 until extractor!!.trackCount) {
            val format = extractor!!.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                extractor!!.selectTrack(i)

                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    -1L
                }

                return AudioTrackInfo(
                    format = format,
                    mime = mime,
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    durationMs = if (durationUs > 0) durationUs / 1000 else -1L
                )
            }
        }
        return null
    }

    private fun convertPcmToFloat(
        pcmData: ByteArray,
        channels: Int,
        sourceSampleRate: Int
    ): FloatArray {
        val shortBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shortArray = ShortArray(shortBuffer.limit())
        shortBuffer.get(shortArray)

        val monoSamples = FloatArray(shortArray.size / channels) { i ->
            val sample = when (channels) {
                1 -> shortArray[i] / 32767.0f
                else -> (shortArray[i * channels] + shortArray[i * channels + 1]) / 32767.0f / 2.0f
            }
            sample.coerceIn(-1f..1f)
        }

        return if (sourceSampleRate != WHISPER_SAMPLE_RATE) {
            resample(monoSamples, sourceSampleRate, WHISPER_SAMPLE_RATE)
        } else {
            monoSamples
        }
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input

        val ratio = fromRate.toDouble() / toRate
        val outputLength = (input.size / ratio).toInt()

        return FloatArray(outputLength) { i ->
            val srcPos = i * ratio
            val idx = srcPos.toInt()

            if (idx + 1 < input.size) {
                val frac = (srcPos - idx).toFloat()
                input[idx] * (1 - frac) + input[idx + 1] * frac
            } else {
                input.getOrElse(idx) { 0f }
            }
        }
    }
}
