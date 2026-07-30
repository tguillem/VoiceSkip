// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import com.google.common.truth.Truth.assertThat
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.source.VulkanSupportDataSource
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
    private var vulkan12Supported = true
    private val vulkanSupportDataSource = object : VulkanSupportDataSource {
        override fun isVulkan12OrNewer(): Boolean = vulkan12Supported
    }

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

    private fun createRepository() =
        SettingsRepositoryImpl(userPreferences, vulkanSupportDataSource)

    @Test
    fun `GPU disable masks settings and persists for the next launch`() = runTest {
        persistGpuDisable()
        val repository = createRepository()

        repository.disableGpuAfterFailure()

        val settings = repository.userSettings.first()
        assertThat(settings.gpuEnabled).isFalse()
        assertThat(settings.turboModeEnabled).isFalse()
        assertThat(settings.numThreads).isEqualTo(7)
        assertThat(repository.gpuDisabledReason.value)
            .isEqualTo(GpuDisabledReason.GPU_FAILED)

        val nextProcess = createRepository()
        assertThat(nextProcess.userSettings.first().gpuEnabled).isFalse()
        assertThat(nextProcess.gpuDisabledReason.value).isNull()
    }

    @Test
    fun `GPU stays masked for this process when persisting fails`() = runTest {
        coEvery { userPreferences.setGpuEnabled(false) } throws IOException("datastore")
        val repository = createRepository()

        repository.disableGpuAfterFailure()

        val settings = repository.userSettings.first()
        assertThat(settings.gpuEnabled).isFalse()
        assertThat(settings.turboModeEnabled).isFalse()
    }

    @Test
    fun `re-enabling GPU is ignored once it is disabled for this process`() = runTest {
        persistGpuDisable()
        val repository = createRepository()
        repository.disableGpuAfterFailure()

        assertThat(repository.updateGpuEnabled(true).isSuccess).isTrue()

        assertThat(repository.userSettings.first().gpuEnabled).isFalse()
        assertThat(savedGpuEnabled.value).isFalse()
        coVerify(exactly = 0) { userPreferences.setGpuEnabled(true) }
    }

    @Test
    fun `thread count stays user-controlled after the GPU is disabled`() = runTest {
        persistGpuDisable()
        val repository = createRepository()
        repository.disableGpuAfterFailure()

        coEvery { userPreferences.setNumThreads(3) } coAnswers { savedNumThreads.value = 3 }
        repository.updateNumThreads(3)

        assertThat(repository.userSettings.first().numThreads).isEqualTo(3)
    }

    @Test
    fun `enabling GPU is ignored below Vulkan 1_2`() = runTest {
        vulkan12Supported = false
        savedGpuEnabled.value = false
        val repository = createRepository()

        assertThat(repository.updateGpuEnabled(true).isSuccess).isTrue()

        assertThat(repository.userSettings.first().gpuEnabled).isFalse()
        assertThat(repository.gpuDisabledReason.value)
            .isEqualTo(GpuDisabledReason.VULKAN_1_2_UNSUPPORTED)
        coVerify(exactly = 0) { userPreferences.setGpuEnabled(true) }
    }

    @Test
    fun `persisting unsupported GPU fallback keeps the Vulkan reason`() = runTest {
        vulkan12Supported = false
        persistGpuDisable()
        val repository = createRepository()

        repository.disableGpuAfterFailure()

        assertThat(repository.gpuDisabledReason.value)
            .isEqualTo(GpuDisabledReason.VULKAN_1_2_UNSUPPORTED)
    }
}
