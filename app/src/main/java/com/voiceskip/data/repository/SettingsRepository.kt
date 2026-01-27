// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import kotlinx.coroutines.flow.Flow

data class UserSettings(
    val listenModeEnabled: Boolean,
    val translateToEnglish: Boolean,
    val model: String,
    val gpuEnabled: Boolean,
    val turboModeEnabled: Boolean,
    val numThreads: Int,
    val defaultLanguage: String
)

interface SettingsRepository {
    val userSettings: Flow<UserSettings>

    fun getDefaultModel(): String
    fun getDefaultNumThreads(): Int

    suspend fun updateListenModeEnabled(enabled: Boolean): Result<Unit>
    suspend fun updateTranslateToEnglish(translate: Boolean): Result<Unit>
    suspend fun updateModel(model: String): Result<Unit>
    suspend fun updateGpuEnabled(enabled: Boolean): Result<Unit>
    suspend fun updateTurboModeEnabled(enabled: Boolean): Result<Unit>
    suspend fun updateNumThreads(numThreads: Int): Result<Unit>
    suspend fun updateDefaultLanguage(language: String): Result<Unit>
}
