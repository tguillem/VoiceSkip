// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.service

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.voiceskip.util.AppLifecycleTracker
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface ServiceLauncher {
    suspend fun startRecording(language: String? = null)
    suspend fun startFileTranscription(uri: Uri, language: String? = null)
}

@Singleton
class ServiceLauncherImpl @Inject constructor(
    private val application: Application,
    private val appLifecycleTracker: AppLifecycleTracker
) : ServiceLauncher {

    override suspend fun startRecording(language: String?) {
        appLifecycleTracker.isInForeground.first { it }
        val intent = Intent(application, TranscriptionService::class.java).apply {
            action = TranscriptionService.ACTION_RECORDING
            language?.let { putExtra(TranscriptionService.EXTRA_LANGUAGE, it) }
        }
        application.startForegroundService(intent)
    }

    override suspend fun startFileTranscription(uri: Uri, language: String?) {
        appLifecycleTracker.isInForeground.first { it }
        val intent = Intent(application, TranscriptionService::class.java).apply {
            action = TranscriptionService.ACTION_TRANSCRIBING
            putExtra(TranscriptionService.EXTRA_URI, uri)
            language?.let { putExtra(TranscriptionService.EXTRA_LANGUAGE, it) }
        }
        application.startForegroundService(intent)
    }
}
