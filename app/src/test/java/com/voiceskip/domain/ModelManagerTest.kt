// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.domain

import android.content.res.AssetManager
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voiceskip.TestDispatcherRule
import com.voiceskip.data.UserPreferences
import com.voiceskip.fake.FakeTranscriptionRepository
import com.voiceskip.ui.main.FileManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var modelManager: ModelManager
    private lateinit var fakeRepository: FakeTranscriptionRepository
    private lateinit var mockUserPreferences: UserPreferences
    private lateinit var mockFileManager: FileManager
    private lateinit var mockAssets: AssetManager

    private val modelFlow = MutableStateFlow("ggml-base.en.bin")
    private val gpuEnabledFlow = MutableStateFlow(true)
    private val turboModeHasBeenSetFlow = MutableStateFlow(true)

    @Before
    fun setup() {
        fakeRepository = FakeTranscriptionRepository()

        mockUserPreferences = mockk(relaxed = true) {
            every { model } returns modelFlow
            every { gpuEnabled } returns gpuEnabledFlow
            every { turboModeHasBeenSet } returns turboModeHasBeenSetFlow
            every { isGpuInProgress() } returns false
            every { setGpuInProgress(any()) } just Runs
            every { isTurboLoadInProgress() } returns false
            every { setTurboLoadInProgress(any()) } just Runs
            coEvery { setTurboModeEnabled(any(), any()) } just Runs
            every { shouldAutoEnableTurboForDevice() } returns false
        }

        mockFileManager = mockk(relaxed = true) {
            coEvery { copyAssets(any()) } just Runs
        }

        mockAssets = mockk(relaxed = true)

        modelManager = ModelManager(
            repository = fakeRepository,
            userPreferences = mockUserPreferences,
            fileManager = mockFileManager
        )
    }

    // =========================================================================
    // Load Model Tests
    // =========================================================================

    @Test
    fun `loadModel transitions to Loading then Loaded`() = runTest {
        modelManager.modelState.test {
            assertThat(awaitItem()).isEqualTo(ModelManager.ModelState.NotLoaded)

            modelManager.loadModel(mockAssets)
            advanceUntilIdle()

            val loadingState = awaitItem()
            assertThat(loadingState).isInstanceOf(ModelManager.ModelState.Loading::class.java)
            assertThat((loadingState as ModelManager.ModelState.Loading).modelPath)
                .isEqualTo("models/ggml-base.en.bin")
            assertThat(loadingState.useGpu).isTrue()

            val loadedState = awaitItem()
            assertThat(loadedState).isInstanceOf(ModelManager.ModelState.Loaded::class.java)
            assertThat((loadedState as ModelManager.ModelState.Loaded).modelPath)
                .isEqualTo("models/ggml-base.en.bin")
        }
    }

    // =========================================================================
    // Load Failure Tests
    // =========================================================================

    @Test
    fun `failed load transitions to Error`() = runTest {
        fakeRepository.loadModelResult = Result.failure(RuntimeException("Load failed"))

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(modelManager.modelState.value)
            .isInstanceOf(ModelManager.ModelState.Error::class.java)
    }

    // =========================================================================
    // Skip Loading Tests
    // =========================================================================

    @Test
    fun `loadModel skips if already loaded with same settings`() = runTest {
        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        fakeRepository.resetCallTracking()

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(fakeRepository.loadModelCalled).isFalse()
    }

    @Test
    fun `loadModel reloads if settings changed`() = runTest {
        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        fakeRepository.resetCallTracking()
        modelFlow.value = "ggml-large.bin"

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(fakeRepository.loadModelCalled).isTrue()
        assertThat(fakeRepository.lastLoadModelPath).isEqualTo("models/ggml-large.bin")
    }

    @Test
    fun `loadModel reloads if GPU setting changed`() = runTest {
        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        fakeRepository.resetCallTracking()
        gpuEnabledFlow.value = false

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(fakeRepository.loadModelCalled).isTrue()
        assertThat(fakeRepository.lastLoadModelUseGpu).isFalse()
    }

    @Test
    fun `forceReload ignores current state`() = runTest {
        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        fakeRepository.resetCallTracking()

        modelManager.loadModel(mockAssets, forceReload = true)
        advanceUntilIdle()

        assertThat(fakeRepository.loadModelCalled).isTrue()
    }

    // =========================================================================
    // GPU Fallback Tests
    // =========================================================================

    @Test
    fun `GPU fallback disables setting and shows message`() = runTest {
        fakeRepository.loadModelGpuResult = null

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        coVerify { mockUserPreferences.setGpuEnabled(false) }
        assertThat(modelManager.gpuFallbackReason.value)
            .isEqualTo(ModelManager.GpuFallbackReason.UNAVAILABLE)
    }

    @Test
    fun `no fallback when GPU works`() = runTest {
        fakeRepository.loadModelGpuResult = "Test GPU"

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        coVerify(exactly = 0) { mockUserPreferences.setGpuEnabled(false) }
        assertThat(modelManager.gpuFallbackReason.value).isNull()
    }

    @Test
    fun `clearGpuFallbackReason clears the reason`() = runTest {
        fakeRepository.loadModelGpuResult = null

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(modelManager.gpuFallbackReason.value).isNotNull()

        modelManager.clearGpuFallbackReason()

        assertThat(modelManager.gpuFallbackReason.value).isNull()
    }

    // =========================================================================
    // GPU Crash Recovery Tests
    // =========================================================================

    @Test
    fun `loadModel detects previous crash and falls back to CPU`() = runTest {
        gpuEnabledFlow.value = true
        every { mockUserPreferences.isGpuInProgress() } returns true

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        verify { mockUserPreferences.setGpuInProgress(false) }
        coVerify { mockUserPreferences.setGpuEnabled(false) }
        assertThat(modelManager.gpuFallbackReason.value)
            .isEqualTo(ModelManager.GpuFallbackReason.CRASH)
        assertThat(fakeRepository.lastLoadModelUseGpu).isFalse()
    }

    @Test
    fun `loadModel sets flag before GPU load and clears after`() = runTest {
        gpuEnabledFlow.value = true
        every { mockUserPreferences.isGpuInProgress() } returns false

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        verifyOrder {
            mockUserPreferences.setGpuInProgress(true)
            mockUserPreferences.setGpuInProgress(false)
        }
    }

    @Test
    fun `loadModel skips crash flag for CPU-only load`() = runTest {
        gpuEnabledFlow.value = false

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        verify(exactly = 0) { mockUserPreferences.setGpuInProgress(true) }
    }
}
