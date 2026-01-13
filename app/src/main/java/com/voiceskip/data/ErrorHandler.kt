// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data

import android.util.Log
import java.io.IOException

object ErrorHandler {
    fun handleError(error: Throwable): VoiceSkipException {
        return when (error) {
            is VoiceSkipException -> error
            is IOException -> VoiceSkipException.FileException(error.message ?: "File operation failed", error)
            is IllegalStateException -> VoiceSkipException.AudioException(error.message ?: "Audio error", error)
            is SecurityException -> VoiceSkipException.FileException("Permission denied", error)
            else -> VoiceSkipException.TranscriptionException(error.message ?: "Unknown error", error)
        }
    }

    fun logError(tag: String, error: Throwable, critical: Boolean = false) {
        when {
            critical -> Log.e(tag, "CRITICAL ERROR", error)
            error is VoiceSkipException -> Log.w(tag, "Handled error", error)
            else -> Log.d(tag, "Minor error", error)
        }
    }
}
