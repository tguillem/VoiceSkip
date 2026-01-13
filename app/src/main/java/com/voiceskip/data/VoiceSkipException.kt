// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data

sealed class VoiceSkipException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class TranscriptionException(override val message: String, override val cause: Throwable? = null) : VoiceSkipException(message, cause)
    data class AudioException(override val message: String, override val cause: Throwable? = null) : VoiceSkipException(message, cause)
    data class FileException(override val message: String, override val cause: Throwable? = null) : VoiceSkipException(message, cause)
}
