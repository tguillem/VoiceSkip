// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import com.voiceskip.data.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class SettingsRepositoryImpl(
    private val userPreferences: UserPreferences
) : SettingsRepository {

    override fun getDefaultModel(): String = userPreferences.getDefaultModelForContext()

    override fun getDefaultNumThreads(): Int = userPreferences.getDefaultNumThreadsForContext(gpuEnabled = true)

    override val userSettings: Flow<UserSettings> = combine(
        combine(
            userPreferences.listenModeEnabled,
            userPreferences.translateToEnglish,
            userPreferences.model,
            userPreferences.gpuEnabled,
            userPreferences.turboModeEnabled
        ) { listenMode, translate, model, gpu, turbo ->
            PartialSettings(listenMode, translate, model, gpu, turbo)
        },
        userPreferences.numThreads,
        userPreferences.defaultLanguage
    ) { partial, threads, language ->
        UserSettings(
            listenModeEnabled = partial.listenModeEnabled,
            translateToEnglish = partial.translateToEnglish,
            model = partial.model,
            gpuEnabled = partial.gpuEnabled,
            turboModeEnabled = partial.turboModeEnabled,
            numThreads = threads,
            defaultLanguage = language
        )
    }

    private data class PartialSettings(
        val listenModeEnabled: Boolean,
        val translateToEnglish: Boolean,
        val model: String,
        val gpuEnabled: Boolean,
        val turboModeEnabled: Boolean
    )

    override suspend fun updateListenModeEnabled(enabled: Boolean): Result<Unit> = runCatching {
        userPreferences.setListenModeEnabled(enabled)
    }

    override suspend fun updateTranslateToEnglish(translate: Boolean): Result<Unit> = runCatching {
        userPreferences.setTranslateToEnglish(translate)
    }

    override suspend fun updateModel(model: String): Result<Unit> = runCatching {
        userPreferences.setModel(model)
    }

    override suspend fun updateGpuEnabled(enabled: Boolean): Result<Unit> = runCatching {
        userPreferences.setGpuEnabled(enabled)
    }

    override suspend fun updateTurboModeEnabled(enabled: Boolean): Result<Unit> = runCatching {
        userPreferences.setTurboModeEnabled(enabled)
    }

    override suspend fun updateNumThreads(numThreads: Int): Result<Unit> = runCatching {
        userPreferences.setNumThreads(numThreads)
    }

    override suspend fun updateDefaultLanguage(language: String): Result<Unit> = runCatching {
        userPreferences.setDefaultLanguage(language)
    }
}
