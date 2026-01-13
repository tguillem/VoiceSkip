// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface AppLifecycleTracker {
    val isInForeground: StateFlow<Boolean>
}

@Singleton
class AppLifecycleTrackerImpl @Inject constructor() : AppLifecycleTracker, DefaultLifecycleObserver {
    private val _isInForeground = MutableStateFlow(false)
    override val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _isInForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isInForeground.value = false
    }
}
