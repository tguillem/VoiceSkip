// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voiceskip.R
import com.voiceskip.data.repository.TranscriptionRepository
import com.voiceskip.data.repository.TranscriptionState
import com.voiceskip.util.AppLifecycleTracker
import com.voiceskip.util.WakeLockManager
import com.voiceskip.util.getParcelableExtraCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TranscriptionService : Service() {

    @Inject
    lateinit var repository: TranscriptionRepository

    @Inject
    lateinit var wakeLockManager: WakeLockManager

    @Inject
    lateinit var appLifecycleTracker: AppLifecycleTracker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null
    private var hasSeenActiveState = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeRepositoryState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECORDING -> {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (!tryStartForeground(createLiveRecordingNotification(0), serviceType)) {
                    return START_NOT_STICKY
                }
                wakeLockManager.acquire()
                val language = intent.getStringExtra(EXTRA_LANGUAGE)
                serviceScope.launch {
                    repository.startRecording(language)
                }
            }
            ACTION_TRANSCRIBING -> {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (!tryStartForeground(createTranscribingNotification(0), serviceType)) {
                    return START_NOT_STICKY
                }
                wakeLockManager.acquire()
                val uri = intent.getParcelableExtraCompat<android.net.Uri>(EXTRA_URI)
                val language = intent.getStringExtra(EXTRA_LANGUAGE)
                if (uri != null) {
                    serviceScope.launch {
                        repository.transcribeUri(uri, language)
                    }
                }
            }
            ACTION_CANCEL -> {
                when (repository.state.value) {
                    is TranscriptionState.LiveRecording -> repository.cancelRecording()
                    is TranscriptionState.FinishingTranscription -> repository.cancelTranscription()
                    is TranscriptionState.Transcribing -> repository.cancelTranscription()
                    else -> {}
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun tryStartForeground(notification: Notification, foregroundServiceType: Int): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Cannot start foreground service - app not in foreground", e)
            stopSelf()
            false
        }
    }

    private fun observeRepositoryState() {
        stateObserverJob = serviceScope.launch {
            repository.state.collect { state ->
                when (state) {
                    is TranscriptionState.LiveRecording -> {
                        hasSeenActiveState = true
                        wakeLockManager.ping()
                        updateNotification(createLiveRecordingNotification(state.durationMs))
                    }
                    is TranscriptionState.FinishingTranscription -> {
                        hasSeenActiveState = true
                        wakeLockManager.ping()
                        updateNotification(createFinishingNotification(state.progress))
                    }
                    is TranscriptionState.Transcribing -> {
                        hasSeenActiveState = true
                        wakeLockManager.ping()
                        updateNotification(createTranscribingNotification(state.progress))
                    }
                    is TranscriptionState.Complete -> {
                        if (hasSeenActiveState) {
                            stopSelfGracefully(createCompletionNotification())
                        }
                    }
                    is TranscriptionState.Error -> {
                        if (hasSeenActiveState) {
                            stopSelfGracefully(createErrorNotification())
                        }
                    }
                    TranscriptionState.Idle -> {
                        if (hasSeenActiveState) {
                            stopSelfGracefully()
                        }
                    }
                }
            }
        }
    }

    private fun stopSelfGracefully(resultNotification: Notification? = null) {
        wakeLockManager.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
        if (!appLifecycleTracker.isInForeground.value) {
            resultNotification?.let {
                manager.notify(RESULT_NOTIFICATION_ID, it)
            }
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VoiceSkip Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recording and transcription progress"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createLiveRecordingNotification(durationMs: Long): Notification {
        val duration = formatDuration(durationMs)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording & Transcribing")
            .setContentText(duration)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(createContentIntent())
            .addAction(createCancelAction())
            .build()
    }

    private fun createFinishingNotification(progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Finishing Transcription")
            .setContentText("$progress% complete")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(createContentIntent())
            .addAction(createCancelAction())
            .build()
    }

    private fun createContentIntent(): PendingIntent {
        val contentIntent = Intent(this, com.voiceskip.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createTranscribingNotification(progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.state_transcribing))
            .setContentText("$progress%")
            .setProgress(100, progress, false)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(createContentIntent())
            .addAction(createCancelAction())
            .build()
    }

    private fun createCancelAction(): NotificationCompat.Action {
        val intent = Intent(this, TranscriptionService::class.java).apply {
            action = ACTION_CANCEL
        }
        val pendingIntent = PendingIntent.getService(
            this, 1, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_delete, "Cancel", pendingIntent
        ).build()
    }

    private fun createCompletionContentIntent(): PendingIntent {
        val intent = Intent(this, com.voiceskip.MainActivity::class.java).apply {
            action = ACTION_VIEW_TRANSCRIPT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, REQUEST_CODE_COMPLETION, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCompletionNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_complete_title))
            .setContentText(getString(R.string.notification_complete_text))
            .setContentIntent(createCompletionContentIntent())
            .setAutoCancel(true)
            .build()
    }

    private fun createErrorNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_error_title))
            .setContentText(getString(R.string.notification_error_text))
            .setContentIntent(createContentIntent())
            .setAutoCancel(true)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return "%d:%02d".format(minutes, seconds)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLockManager.release()
        stateObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranscriptionService"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "whisper_processing"
        const val ACTION_RECORDING = "com.voiceskip.action.RECORDING"
        const val ACTION_TRANSCRIBING = "com.voiceskip.action.TRANSCRIBING"
        const val ACTION_VIEW_TRANSCRIPT = "com.voiceskip.action.VIEW_TRANSCRIPT"
        const val EXTRA_URI = "uri"
        const val EXTRA_LANGUAGE = "language"
        private const val ACTION_CANCEL = "com.voiceskip.action.CANCEL"
        private const val REQUEST_CODE_FOREGROUND = 0
        private const val REQUEST_CODE_COMPLETION = 1
        private const val REQUEST_CODE_ERROR = 2
    }
}
