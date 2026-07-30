// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.settings

import com.google.common.truth.Truth.assertThat
import com.voiceskip.TestDispatcherRule
import com.voiceskip.data.repository.GpuDisabledReason
import com.voiceskip.domain.ModelManager
import com.voiceskip.fake.FakeModelRepository
import com.voiceskip.fake.FakeSettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var modelRepository: FakeModelRepository
    private lateinit var modelManager: ModelManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        modelRepository = FakeModelRepository()

        modelManager = mockk<ModelManager>(relaxed = true) {
            every { modelState } returns MutableStateFlow(ModelManager.ModelState.NotLoaded)
            every { gpuFallbackReason } returns MutableStateFlow(null)
            every { turboFallbackReason } returns MutableStateFlow(null)
            every { modelFallbackReason } returns MutableStateFlow(null)
        }

        viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            modelManager = modelManager,
            modelRepository = modelRepository
        )
    }

    @Test
    fun `importing a model selects it, which is what triggers the load`() = runTest {
        modelRepository.importResult = "content://docs/custom"

        viewModel.importModel(mockk(relaxed = true))
        advanceUntilIdle()

        assertThat(settingsRepository.getCurrentSettings().model)
            .isEqualTo("content://docs/custom")
        verify(exactly = 0) { modelManager.requestModelReload() }
    }

    @Test
    fun `reimporting the selected model requests a reload`() = runTest {
        val modelId = "content://docs/custom"
        settingsRepository.setSettings(
            settingsRepository.getCurrentSettings().copy(model = modelId)
        )
        modelRepository.importResult = modelId

        viewModel.importModel(mockk(relaxed = true))
        advanceUntilIdle()

        verify(exactly = 1) { modelManager.requestModelReload() }
        assertThat(settingsRepository.updateModelCalled).isFalse()
    }

    @Test
    fun `a rejected import leaves the selected model alone`() = runTest {
        modelRepository.importResult = null
        val before = settingsRepository.getCurrentSettings().model

        viewModel.importModel(mockk(relaxed = true))
        advanceUntilIdle()

        assertThat(settingsRepository.getCurrentSettings().model).isEqualTo(before)
    }

    @Test
    fun `Vulkan below 1_2 disables GPU with the hardware reason`() = runTest {
        settingsRepository.gpuSupported = false
        viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            modelManager = modelManager,
            modelRepository = modelRepository
        )

        assertThat(viewModel.uiState.value.gpuEnabled).isFalse()
        assertThat(viewModel.uiState.value.gpuStatus).isEqualTo(GpuStatus.Disabled)
        assertThat(viewModel.uiState.value.gpuDisabledReason)
            .isEqualTo(GpuDisabledReason.VULKAN_1_2_UNSUPPORTED)
    }

    @Test
    fun `GPU failure disables GPU with the failure reason`() = runTest {
        settingsRepository.disableGpuAfterFailure()
        viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            modelManager = modelManager,
            modelRepository = modelRepository
        )

        assertThat(viewModel.uiState.value.gpuEnabled).isFalse()
        assertThat(viewModel.uiState.value.gpuDisabledReason)
            .isEqualTo(GpuDisabledReason.GPU_FAILED)
    }

    @Test
    fun `active imported model is retained until switching to default succeeds`() = runTest {
        val importedModel = "content://docs/custom"
        settingsRepository.setSettings(
            settingsRepository.getCurrentSettings().copy(model = importedModel)
        )
        settingsRepository.updateResult =
            Result.failure(IOException("DataStore write failed"))

        viewModel.deleteModel(importedModel)
        advanceUntilIdle()

        assertThat(settingsRepository.getCurrentSettings().model)
            .isEqualTo(importedModel)
        assertThat(modelRepository.deletedModelIds).isEmpty()

        settingsRepository.updateResult = Result.success(Unit)

        viewModel.deleteModel(importedModel)
        advanceUntilIdle()

        assertThat(settingsRepository.getCurrentSettings().model)
            .isEqualTo(settingsRepository.getDefaultModel())
        assertThat(modelRepository.deletedModelIds)
            .containsExactly(importedModel)
    }
}
