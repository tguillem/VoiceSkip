// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceskip.data.ErrorHandler
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.SettingsRepository
import com.voiceskip.domain.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val LOG_TAG = "SettingsViewModel"

sealed class GpuStatus {
    object Disabled : GpuStatus()
    object Loading : GpuStatus()
    data class Active(val deviceInfo: String) : GpuStatus()
}

data class SettingsUiState(
    val showTimestamps: Boolean = false,
    val translateToEnglish: Boolean = false,
    val model: String = "",
    val gpuEnabled: Boolean = true,
    val turboModeEnabled: Boolean = false,
    val gpuStatus: GpuStatus = GpuStatus.Disabled,
    val numThreads: Int = 4,
    val defaultLanguage: String = UserPreferences.LANGUAGE_AUTO,
    val gpuFallbackReason: ModelManager.GpuFallbackReason? = null,
    val turboFallbackReason: ModelManager.TurboFallbackReason? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelManager: ModelManager
) : ViewModel() {

    private val gpuStatusFlow = modelManager.modelState.map { state ->
        when (state) {
            is ModelManager.ModelState.Loading -> {
                if (state.useGpu) GpuStatus.Loading else GpuStatus.Disabled
            }
            is ModelManager.ModelState.Loaded -> {
                state.gpuInfo?.let { GpuStatus.Active(it) } ?: GpuStatus.Disabled
            }
            else -> GpuStatus.Disabled
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.userSettings,
        gpuStatusFlow,
        modelManager.gpuFallbackReason,
        modelManager.turboFallbackReason
    ) { settings, gpuStatus, gpuFallbackReason, turboFallbackReason ->
        SettingsUiState(
            showTimestamps = settings.showTimestamps,
            translateToEnglish = settings.translateToEnglish,
            model = settings.model,
            gpuEnabled = settings.gpuEnabled,
            turboModeEnabled = settings.turboModeEnabled,
            gpuStatus = gpuStatus,
            numThreads = settings.numThreads,
            defaultLanguage = settings.defaultLanguage,
            gpuFallbackReason = gpuFallbackReason,
            turboFallbackReason = turboFallbackReason
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(
            model = settingsRepository.getDefaultModel(),
            numThreads = settingsRepository.getDefaultNumThreads()
        )
    )

    fun onGpuFallbackDismissed() {
        modelManager.clearGpuFallbackReason()
    }

    fun onTurboFallbackDismissed() {
        modelManager.clearTurboFallbackReason()
    }

    fun setShowTimestamps(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShowTimestamps(show).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }

    fun setTranslateToEnglish(translate: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateTranslateToEnglish(translate).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }

    fun setModel(model: String) {
        viewModelScope.launch {
            settingsRepository.updateModel(model).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }

    fun setGpuEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateGpuEnabled(enabled).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }

    fun setTurboModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateTurboModeEnabled(enabled).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }

    fun setNumThreads(numThreads: Int) {
        viewModelScope.launch {
            settingsRepository.updateNumThreads(numThreads).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }

    fun setDefaultLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.updateDefaultLanguage(language).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }
}
