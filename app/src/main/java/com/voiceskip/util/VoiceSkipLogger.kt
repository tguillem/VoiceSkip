// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.util

import android.util.Log

object VoiceSkipLogger {
    private const val TAG = "VoiceSkip"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    fun logModelLoad(modelName: String, timeMs: Long) {
        i("Model '$modelName' loaded in ${timeMs}ms")
    }

    fun logTranscription(audioLengthMs: Int, textLength: Int, processingTimeMs: Long) {
        i("Transcription complete: ${audioLengthMs}ms audio, $textLength chars, processed in ${processingTimeMs}ms")
    }

    fun logDataCopy(fileName: String) {
        d("Copying $fileName...")
    }

    fun logRecordingStart() {
        i("Recording started")
    }

    fun logFileSelected(uri: String) {
        i("File selected: $uri")
    }
}
