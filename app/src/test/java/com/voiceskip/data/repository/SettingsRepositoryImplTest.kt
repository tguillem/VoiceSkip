// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import com.google.common.truth.Truth.assertThat
import com.voiceskip.data.UserPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class SettingsRepositoryImplTest {

    private val savedGpuEnabled = MutableStateFlow(true)
    private val savedNumThreads = MutableStateFlow(1)

    private val userPreferences = mockk<UserPreferences>(relaxed = true) {
        every { listenModeEnabled } returns MutableStateFlow(false)
        every { translateToEnglish } returns MutableStateFlow(false)
        every { model } returns MutableStateFlow("ggml-small.bin")
        every { gpuEnabled } returns savedGpuEnabled
        every { turboModeEnabled } returns MutableStateFlow(true)
        every { vadEnabled } returns MutableStateFlow(true)
        every { numThreads } returns savedNumThreads
        every { defaultLanguage } returns MutableStateFlow(UserPreferences.LANGUAGE_AUTO)
    }

    /* Mirrors the preference side effects of the real UserPreferences.setGpuEnabled. */
    private fun persistGpuDisable() {
        coEvery { userPreferences.setGpuEnabled(false) } coAnswers {
            savedGpuEnabled.value = false
            savedNumThreads.value = 7
        }
    }

    @Test
    fun `GPU disable masks settings and persists for the next launch`() = runTest {
        persistGpuDisable()
        val repository = SettingsRepositoryImpl(userPreferences)

        repository.disableGpuAfterFailure()

        val settings = repository.userSettings.first()
        assertThat(settings.gpuEnabled).isFalse()
        assertThat(settings.turboModeEnabled).isFalse()
        assertThat(settings.numThreads).isEqualTo(7)

        val nextProcess = SettingsRepositoryImpl(userPreferences)
        assertThat(nextProcess.userSettings.first().gpuEnabled).isFalse()
    }

    @Test
    fun `GPU stays masked for this process when persisting fails`() = runTest {
        coEvery { userPreferences.setGpuEnabled(false) } throws IOException("datastore")
        val repository = SettingsRepositoryImpl(userPreferences)

        repository.disableGpuAfterFailure()

        val settings = repository.userSettings.first()
        assertThat(settings.gpuEnabled).isFalse()
        assertThat(settings.turboModeEnabled).isFalse()
    }

    @Test
    fun `re-enabling GPU is ignored once it is disabled for this process`() = runTest {
        persistGpuDisable()
        val repository = SettingsRepositoryImpl(userPreferences)
        repository.disableGpuAfterFailure()

        assertThat(repository.updateGpuEnabled(true).isSuccess).isTrue()

        assertThat(repository.userSettings.first().gpuEnabled).isFalse()
        assertThat(savedGpuEnabled.value).isFalse()
        coVerify(exactly = 0) { userPreferences.setGpuEnabled(true) }
    }

    @Test
    fun `thread count stays user-controlled after the GPU is disabled`() = runTest {
        persistGpuDisable()
        val repository = SettingsRepositoryImpl(userPreferences)
        repository.disableGpuAfterFailure()

        coEvery { userPreferences.setNumThreads(3) } coAnswers { savedNumThreads.value = 3 }
        repository.updateNumThreads(3)

        assertThat(repository.userSettings.first().numThreads).isEqualTo(3)
    }
}
