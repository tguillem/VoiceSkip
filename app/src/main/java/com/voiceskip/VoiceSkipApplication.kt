// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.voiceskip.data.UserPreferences
import com.voiceskip.util.AppLifecycleTrackerImpl
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VoiceSkipApplication : Application() {

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var appLifecycleTracker: AppLifecycleTrackerImpl

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleTracker)
    }
}
