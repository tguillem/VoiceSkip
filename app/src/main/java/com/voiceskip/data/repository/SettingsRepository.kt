// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import kotlinx.coroutines.flow.Flow

data class UserSettings(
    val listenModeEnabled: Boolean,
    val translateToEnglish: Boolean,
    val model: String,
    val gpuEnabled: Boolean,
    val turboModeEnabled: Boolean,
    val vadEnabled: Boolean,
    val numThreads: Int,
    val defaultLanguage: String
)

interface SettingsRepository {
    val userSettings: Flow<UserSettings>

    fun getDefaultModel(): String
    fun getDefaultNumThreads(): Int

    /**
     * Masks the GPU for the rest of this process and persists the choice for the next launch.
     * The mask is one-way: a failed GPU context may be unusable until the process restarts, so
     * nothing — not even an explicit user toggle — re-arms it before then. The result reports
     * only whether the choice was persisted; the mask always applies.
     */
    suspend fun disableGpuAfterFailure(): Result<Unit>

    suspend fun updateListenModeEnabled(enabled: Boolean): Result<Unit>
    suspend fun updateTranslateToEnglish(translate: Boolean): Result<Unit>
    suspend fun updateModel(model: String): Result<Unit>
    suspend fun updateGpuEnabled(enabled: Boolean): Result<Unit>
    suspend fun updateTurboModeEnabled(
        enabled: Boolean,
        isUserAction: Boolean = true
    ): Result<Unit>
    suspend fun updateVadEnabled(enabled: Boolean): Result<Unit>
    suspend fun updateNumThreads(numThreads: Int): Result<Unit>
    suspend fun updateDefaultLanguage(language: String): Result<Unit>
}
