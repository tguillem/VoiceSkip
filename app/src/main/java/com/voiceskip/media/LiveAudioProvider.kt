// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voiceskip.util.WHISPER_SAMPLE_RATE
import com.voiceskip.whispercpp.whisper.AudioProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * Live audio recording with streaming support for real-time transcription.
 *
 * Implements AudioProvider to feed audio samples to the streaming API.
 * Uses ArrayBlockingQueue for producer-consumer buffering between the
 * recording thread and the native transcription thread.
 */
class LiveAudioProvider : AudioProvider {
    companion object {
        private val EOF_MARKER = FloatArray(0)
        private const val MAX_QUEUE_DURATION_MS = 5 * 60 * 1000
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _stopRequested = AtomicBoolean(false)
    val stopRequested: AtomicBoolean get() = _stopRequested

    private val _queueLimitReached = AtomicBoolean(false)
    val queueLimitReached: Boolean get() = _queueLimitReached.get()

    private val _recordingEnded = MutableStateFlow(false)
    val recordingEnded: StateFlow<Boolean> = _recordingEnded.asStateFlow()

    private val queueReady = CountDownLatch(1)
    private lateinit var audioQueue: ArrayBlockingQueue<FloatArray>
    private var currentBuffer: FloatArray? = null
    private var bufferPosition = 0
    private var eofReached = false

    @SuppressLint("MissingPermission")
    suspend fun startRecording() = withContext(Dispatchers.IO) {
        val audioBufferSize = AudioRecord.getMinBufferSize(
            WHISPER_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 4

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            WHISPER_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            audioBufferSize
        )

        val shortBuffer = ShortArray(audioBufferSize / 2)
        val samplesPerBuffer = shortBuffer.size
        val samplesFor5Min = WHISPER_SAMPLE_RATE.toLong() * MAX_QUEUE_DURATION_MS / 1000
        val queueCapacity = (samplesFor5Min / samplesPerBuffer + 1).toInt()
        audioQueue = ArrayBlockingQueue(queueCapacity)
        queueReady.countDown()

        val startTime = System.currentTimeMillis()

        try {
            recorder.startRecording()

            while (!_stopRequested.get() && isActive) {
                val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    val elapsed = System.currentTimeMillis() - startTime

                    val floatSamples = FloatArray(read) { i ->
                        shortBuffer[i] / 32767.0f
                    }

                    if (audioQueue.remainingCapacity() <= 1) {
                        _queueLimitReached.set(true)
                        _stopRequested.set(true)
                        break
                    }
                    audioQueue.put(floatSamples)

                    val maxAmp = shortBuffer.take(read).maxOfOrNull { abs(it.toInt()) } ?: 0
                    _amplitude.value = maxAmp / 32768f

                    _durationMs.value = elapsed
                }
            }
        } finally {
            _recordingEnded.value = true
            recorder.stop()
            recorder.release()
            audioQueue.put(EOF_MARKER)
        }
    }

    @Synchronized
    override fun readAudio(buffer: FloatArray, maxSamples: Int): Int {
        queueReady.await()
        if (eofReached) return 0

        var filled = 0

        while (filled < maxSamples) {
            if (currentBuffer == null || bufferPosition >= currentBuffer!!.size) {
                /* Return what we have when no more data is immediately available */
                if (filled > 0 && audioQueue.isEmpty()) {
                    return filled
                }

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

    fun stop() {
        _stopRequested.set(true)
    }
}
