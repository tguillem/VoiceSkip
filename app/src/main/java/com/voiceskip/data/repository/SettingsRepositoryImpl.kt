// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import com.voiceskip.data.UserPreferences
import com.voiceskip.util.VoiceSkipLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

class SettingsRepositoryImpl(
    private val userPreferences: UserPreferences
) : SettingsRepository {

    private val _gpuAvailableForCurrentProcess = MutableStateFlow(true)
    override val gpuAvailableForCurrentProcess: StateFlow<Boolean> =
        _gpuAvailableForCurrentProcess.asStateFlow()

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
        userPreferences.vadEnabled,
        userPreferences.numThreads,
        userPreferences.defaultLanguage,
        gpuAvailableForCurrentProcess
    ) { partial, vad, threads, language, gpuAvailable ->
        val gpuEnabled = partial.gpuEnabled && gpuAvailable
        UserSettings(
            listenModeEnabled = partial.listenModeEnabled,
            translateToEnglish = partial.translateToEnglish,
            model = partial.model,
            gpuEnabled = gpuEnabled,
            turboModeEnabled = partial.turboModeEnabled && gpuEnabled,
            vadEnabled = vad,
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

    override suspend fun disableGpuAfterFailure(): Result<Unit> {
        _gpuAvailableForCurrentProcess.value = false
        return try {
            userPreferences.setGpuEnabled(false)
            Result.success(Unit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Reported rather than thrown: the in-process mask already holds, and a caller in
            // the middle of a model load must not fail over a preference write.
            VoiceSkipLogger.e("Failed to persist GPU disable", exception)
            Result.failure(exception)
        }
    }

    override suspend fun updateListenModeEnabled(enabled: Boolean): Result<Unit> = runCatching {
        userPreferences.setListenModeEnabled(enabled)
    }

    override suspend fun updateTranslateToEnglish(translate: Boolean): Result<Unit> = runCatching {
        userPreferences.setTranslateToEnglish(translate)
    }

    override suspend fun updateModel(model: String): Result<Unit> = runCatching {
        userPreferences.setModel(model)
    }

    override suspend fun updateGpuEnabled(enabled: Boolean): Result<Unit> {
        // Re-arming here would both hand a bad GPU context back to whisper and undo the
        // persisted disable, so the toggle stays inert until the process restarts.
        if (enabled && !_gpuAvailableForCurrentProcess.value) {
            return Result.success(Unit)
        }
        return runCatching {
            userPreferences.setGpuEnabled(enabled)
        }
    }

    override suspend fun updateTurboModeEnabled(
        enabled: Boolean,
        isUserAction: Boolean
    ): Result<Unit> = try {
        userPreferences.setTurboModeEnabled(enabled, isUserAction)
        Result.success(Unit)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    override suspend fun updateVadEnabled(enabled: Boolean): Result<Unit> = runCatching {
        userPreferences.setVadEnabled(enabled)
    }

    override suspend fun updateNumThreads(numThreads: Int): Result<Unit> = runCatching {
        userPreferences.setNumThreads(numThreads)
    }

    override suspend fun updateDefaultLanguage(language: String): Result<Unit> = runCatching {
        userPreferences.setDefaultLanguage(language)
    }
}
