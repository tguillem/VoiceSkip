// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.fake

import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.SettingsRepository
import com.voiceskip.data.repository.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeSettingsRepository : SettingsRepository {

    override fun getDefaultModel(): String = "ggml-base.en.bin"

    override fun getDefaultNumThreads(): Int = 4

    private val _userSettings = MutableStateFlow(
        UserSettings(
            listenModeEnabled = false,
            translateToEnglish = false,
            model = "ggml-base.en.bin",
            gpuEnabled = true,
            turboModeEnabled = false,
            numThreads = 4,
            defaultLanguage = UserPreferences.LANGUAGE_AUTO
        )
    )
    override val userSettings: Flow<UserSettings> = _userSettings.asStateFlow()

    var updateListenModeEnabledCalled = false
    var updateTranslateToEnglishCalled = false
    var updateModelCalled = false
    var updateGpuEnabledCalled = false
    var updateTurboModeEnabledCalled = false
    var updateNumThreadsCalled = false
    var updateDefaultLanguageCalled = false

    var updateResult: Result<Unit> = Result.success(Unit)

    override suspend fun updateListenModeEnabled(enabled: Boolean): Result<Unit> {
        updateListenModeEnabledCalled = true
        return updateResult.also {
            if (it.isSuccess) {
                _userSettings.update { settings -> settings.copy(listenModeEnabled = enabled) }
            }
        }
    }

    override suspend fun updateTranslateToEnglish(translate: Boolean): Result<Unit> {
        updateTranslateToEnglishCalled = true
        return updateResult.also {
            if (it.isSuccess) {
                _userSettings.update { settings -> settings.copy(translateToEnglish = translate) }
            }
        }
    }

    override suspend fun updateModel(model: String): Result<Unit> {
        updateModelCalled = true
        return updateResult.also {
            if (it.isSuccess) {
                _userSettings.update { settings -> settings.copy(model = model) }
            }
        }
    }

    override suspend fun updateGpuEnabled(enabled: Boolean): Result<Unit> {
        updateGpuEnabledCalled = true
        return updateResult.also {
            if (it.isSuccess) {
                val defaultThreads = if (enabled) 1 else (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
                _userSettings.update { settings ->
                    settings.copy(gpuEnabled = enabled, numThreads = defaultThreads)
                }
            }
        }
    }

    override suspend fun updateTurboModeEnabled(enabled: Boolean): Result<Unit> {
        updateTurboModeEnabledCalled = true
        return updateResult.also {
            if (it.isSuccess) {
                _userSettings.update { settings -> settings.copy(turboModeEnabled = enabled) }
            }
        }
    }

    override suspend fun updateNumThreads(numThreads: Int): Result<Unit> {
        updateNumThreadsCalled = true
        return updateResult.also {
            if (it.isSuccess) {
                _userSettings.update { settings -> settings.copy(numThreads = numThreads) }
            }
        }
    }

    override suspend fun updateDefaultLanguage(language: String): Result<Unit> {
        updateDefaultLanguageCalled = true
        return updateResult.also {
            if (it.isSuccess) {
                _userSettings.update { settings -> settings.copy(defaultLanguage = language) }
            }
        }
    }

    fun setSettings(settings: UserSettings) {
        _userSettings.value = settings
    }

    fun getCurrentSettings(): UserSettings = _userSettings.value

    fun reset() {
        _userSettings.value = UserSettings(
            listenModeEnabled = false,
            translateToEnglish = false,
            model = "ggml-base.en.bin",
            gpuEnabled = true,
            turboModeEnabled = false,
            numThreads = 4,
            defaultLanguage = UserPreferences.LANGUAGE_AUTO
        )
        updateListenModeEnabledCalled = false
        updateTranslateToEnglishCalled = false
        updateModelCalled = false
        updateGpuEnabledCalled = false
        updateTurboModeEnabledCalled = false
        updateNumThreadsCalled = false
        updateDefaultLanguageCalled = false
        updateResult = Result.success(Unit)
    }
}
