// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.util

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages wakelocks for long-running transcription operations.
 * Uses PARTIAL_WAKE_LOCK to allow screen off while CPU continues processing.
 */
@Singleton
class WakeLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var wakeLock: PowerManager.WakeLock? = null
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val lock = Any()

    companion object {
        private const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 1000L
    }

    /**
     * Acquires a partial wakelock for transcription.
     * Allows screen to turn off while CPU continues processing.
     */
    fun acquire() = synchronized(lock) {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceSkip:TranscriptionWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }

        VoiceSkipLogger.i("Acquired wakelock for transcription")
    }

    /**
     * Resets the wakelock timeout. Call during progress updates.
     */
    fun ping() = synchronized(lock) {
        wakeLock?.let { wl ->
            if (wl.isHeld) {
                wl.acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
    }

    fun release() = synchronized(lock) {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                VoiceSkipLogger.i("Released wakelock")
            }
        }
        wakeLock = null
    }
}
