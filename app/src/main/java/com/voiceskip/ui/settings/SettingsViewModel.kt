// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceskip.data.ErrorHandler
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.GpuDisabledReason
import com.voiceskip.data.repository.ModelImportState
import com.voiceskip.data.repository.ModelInfo
import com.voiceskip.data.repository.ModelRepository
import com.voiceskip.data.repository.SettingsRepository
import com.voiceskip.data.repository.UserSettings
import com.voiceskip.domain.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val translateToEnglish: Boolean = false,
    val model: String = "",
    val gpuEnabled: Boolean = true,
    val turboModeEnabled: Boolean = false,
    val vadEnabled: Boolean = true,
    val gpuStatus: GpuStatus = GpuStatus.Disabled,
    val gpuDisabledReason: GpuDisabledReason? = null,
    val numThreads: Int = 4,
    val defaultLanguage: String = UserPreferences.LANGUAGE_AUTO,
    val gpuFallbackReason: ModelManager.GpuFallbackReason? = null,
    val turboFallbackReason: ModelManager.TurboFallbackReason? = null,
    val modelFallbackReason: ModelManager.ModelFallbackReason? = null,
    val availableModels: List<ModelInfo> = emptyList(),
    val importState: ModelImportState = ModelImportState.Idle
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelManager: ModelManager,
    private val modelRepository: ModelRepository
) : ViewModel() {

    private data class SettingsWithGpuAvailability(
        val settings: UserSettings,
        val gpuDisabledReason: GpuDisabledReason?
    )

    private val settingsWithGpuAvailability = combine(
        settingsRepository.userSettings,
        settingsRepository.gpuDisabledReason
    ) { settings, gpuDisabledReason ->
        SettingsWithGpuAvailability(settings, gpuDisabledReason)
    }

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

    private val fallbackFlow = combine(
        modelManager.gpuFallbackReason,
        modelManager.turboFallbackReason,
        modelManager.modelFallbackReason
    ) { gpu, turbo, model -> Triple(gpu, turbo, model) }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsWithGpuAvailability,
        gpuStatusFlow,
        fallbackFlow,
        modelRepository.availableModels,
        modelRepository.importState
    ) { settingsWithGpu, gpuStatus, fallbacks, availableModels, importState ->
        val settings = settingsWithGpu.settings
        val gpuDisabledReason = settingsWithGpu.gpuDisabledReason
        SettingsUiState(
            translateToEnglish = settings.translateToEnglish,
            model = settings.model,
            gpuEnabled = settings.gpuEnabled && gpuDisabledReason == null,
            turboModeEnabled = settings.turboModeEnabled && gpuDisabledReason == null,
            vadEnabled = settings.vadEnabled,
            gpuStatus = if (gpuDisabledReason == null) gpuStatus else GpuStatus.Disabled,
            gpuDisabledReason = gpuDisabledReason,
            numThreads = settings.numThreads,
            defaultLanguage = settings.defaultLanguage,
            gpuFallbackReason = fallbacks.first,
            turboFallbackReason = fallbacks.second,
            modelFallbackReason = fallbacks.third,
            availableModels = availableModels,
            importState = importState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(
            model = settingsRepository.getDefaultModel(),
            gpuEnabled = settingsRepository.gpuDisabledReason.value == null,
            gpuDisabledReason = settingsRepository.gpuDisabledReason.value,
            numThreads = settingsRepository.getDefaultNumThreads(),
            availableModels = modelRepository.availableModels.value,
            importState = modelRepository.importState.value
        )
    )

    fun onGpuFallbackDismissed() {
        modelManager.clearGpuFallbackReason()
    }

    fun onTurboFallbackDismissed() {
        modelManager.clearTurboFallbackReason()
    }

    fun onModelFallbackDismissed() {
        modelManager.clearModelFallbackReason()
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

    fun setVadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateVadEnabled(enabled).onFailure { exception ->
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

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            val id = modelRepository.importModel(uri) ?: return@launch
            if (settingsRepository.userSettings.first().model == id) {
                modelManager.requestModelReload()
                return@launch
            }
            settingsRepository.updateModel(id).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }

    fun clearImportError() {
        modelRepository.clearImportError()
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            // Removing the active model would break the next load, so switch back to the
            // bundled default before forgetting the reference.
            if (id == settingsRepository.userSettings.first().model) {
                val switchResult =
                    settingsRepository.updateModel(settingsRepository.getDefaultModel())
                switchResult.onFailure { exception ->
                    ErrorHandler.logError(LOG_TAG, exception, critical = false)
                }
                if (switchResult.isFailure) return@launch
            }
            modelRepository.deleteModel(id).onFailure { exception ->
                ErrorHandler.logError(LOG_TAG, exception, critical = false)
            }
        }
    }
}
