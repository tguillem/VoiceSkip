// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.domain

import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voiceskip.TestDispatcherRule
import com.voiceskip.data.UserPreferences
import com.voiceskip.fake.FakeModelRepository
import com.voiceskip.fake.FakeSettingsRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var modelManager: ModelManager
    private lateinit var fakeRepository: FakeTranscriptionRepository
    private lateinit var fakeModelRepository: FakeModelRepository
    private lateinit var fakeSettingsRepository: FakeSettingsRepository
    private lateinit var mockUserPreferences: UserPreferences
    private lateinit var mockFileManager: FileManager
    private lateinit var mockAssets: AssetManager

    private val modelFlow = MutableStateFlow("ggml-base.en.bin")
    private val turboModeHasBeenSetFlow = MutableStateFlow(true)

    private fun setGpuEnabledSetting(enabled: Boolean) {
        fakeSettingsRepository.setSettings(
            fakeSettingsRepository.getPersistedSettings().copy(gpuEnabled = enabled)
        )
    }

    @Before
    fun setup() {
        fakeRepository = FakeTranscriptionRepository()
        fakeModelRepository = FakeModelRepository()
        fakeSettingsRepository = FakeSettingsRepository()

        mockUserPreferences = mockk(relaxed = true) {
            every { model } returns modelFlow
            every { turboModeHasBeenSet } returns turboModeHasBeenSetFlow
            every { isGpuInProgress() } returns false
            every { setGpuInProgress(any()) } just Runs
            every { isTurboLoadInProgress() } returns false
            every { setTurboLoadInProgress(any()) } just Runs
            every { shouldAutoEnableTurboForDevice() } returns false
        }

        mockFileManager = mockk(relaxed = true) {
            coEvery { copyAssets(any()) } just Runs
        }

        mockAssets = mockk(relaxed = true)

        modelManager = ModelManager(
            repository = fakeRepository,
            modelRepository = fakeModelRepository,
            settingsRepository = fakeSettingsRepository,
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
            assertThat((loadingState as ModelManager.ModelState.Loading).modelId)
                .isEqualTo("ggml-base.en.bin")
            assertThat(loadingState.useGpu).isTrue()

            val loadedState = awaitItem()
            assertThat(loadedState).isInstanceOf(ModelManager.ModelState.Loaded::class.java)
            assertThat((loadedState as ModelManager.ModelState.Loaded).modelId)
                .isEqualTo("ggml-base.en.bin")
        }
    }

    @Test
    fun `loadModel resolves bundled model to an asset path, not a file descriptor`() = runTest {
        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(fakeRepository.lastLoadModelPath).isEqualTo("models/ggml-base.en.bin")
        assertThat(fakeRepository.lastLoadModelFd).isEqualTo(-1)
    }

    @Test
    fun `loadModel loads an imported reference from its file descriptor`() = runTest {
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true) {
            every { fd } returns 7
        }
        fakeModelRepository.openDescriptor = { pfd }
        modelFlow.value = "content://docs/custom-model"

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(fakeRepository.lastLoadModelPath).isNull()
        assertThat(fakeRepository.lastLoadModelFd).isEqualTo(7)
    }

    @Test
    fun `successful imported main load clears its crash marker before turbo reload finishes`() = runTest {
        val mainPfd = mockk<ParcelFileDescriptor>(relaxed = true) {
            every { fd } returns 7
        }
        val turboPfd = mockk<ParcelFileDescriptor>(relaxed = true) {
            every { fd } returns 8
        }
        var openCount = 0
        fakeModelRepository.openDescriptor = {
            if (openCount++ == 0) mainPfd else turboPfd
        }
        modelFlow.value = "content://docs/custom-model"
        fakeRepository.setTurboModelLoaded(true)
        fakeRepository.loadTurboModelResultProvider = { awaitCancellation() }

        val loadJob = launch { modelManager.loadModel(mockAssets) }
        runCurrent()

        assertThat(fakeRepository.loadTurboModelCalled).isTrue()
        assertThat(modelManager.modelState.value)
            .isEqualTo(ModelManager.ModelState.Loaded("content://docs/custom-model", "Test GPU"))
        assertThat(fakeModelRepository.importedModelLoadInProgressState).isFalse()
        verify(exactly = 1) { mainPfd.close() }

        loadJob.cancelAndJoin()
    }

    @Test
    fun `loadModel falls back to default when an imported reference is unavailable`() = runTest {
        fakeModelRepository.openDescriptor = { null }
        every { mockUserPreferences.getDefaultModelForContext() } returns "ggml-small.bin"
        modelFlow.value = "content://docs/gone"

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        coVerify(exactly = 1) { mockUserPreferences.setModel("ggml-small.bin") }
        assertThat(modelManager.modelFallbackReason.value)
            .isEqualTo(ModelManager.ModelFallbackReason.UNAVAILABLE)
        assertThat(fakeRepository.lastLoadModelPath).isEqualTo("models/ggml-small.bin")
        assertThat(fakeRepository.lastLoadModelFd).isEqualTo(-1)
    }

    @Test
    fun `loadModel falls back to default when an imported model fails to load`() = runTest {
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true) { every { fd } returns 9 }
        fakeModelRepository.openDescriptor = { pfd }
        every { mockUserPreferences.getDefaultModelForContext() } returns "ggml-small.bin"
        modelFlow.value = "content://docs/not-a-model"
        fakeRepository.loadModelResultProvider = { call ->
            if (call.modelFd == 9) {
                Result.failure(RuntimeException("invalid model file"))
            } else {
                Result.success(true)
            }
        }

        assertThat(modelManager.loadModel(mockAssets).isSuccess).isTrue()
        advanceUntilIdle()

        coVerify(exactly = 1) { mockUserPreferences.setModel("ggml-small.bin") }
        assertThat(modelManager.modelFallbackReason.value)
            .isEqualTo(ModelManager.ModelFallbackReason.LOAD_FAILED)
        assertThat(modelManager.modelState.value)
            .isEqualTo(ModelManager.ModelState.Loaded("ggml-small.bin", "Test GPU"))
        assertThat(
            fakeRepository.loadModelCalls.map { it.modelPath to it.modelFd }
        ).containsExactly(
            null to 9,
            "models/ggml-small.bin" to -1
        ).inOrder()
        assertThat(fakeModelRepository.importedModelLoadInProgressState).isFalse()
        verify(exactly = 1) { pfd.close() }
    }

    @Test
    fun `loadModel stops when the default fallback also fails`() = runTest {
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true) { every { fd } returns 9 }
        fakeModelRepository.openDescriptor = { pfd }
        every { mockUserPreferences.getDefaultModelForContext() } returns "ggml-small.bin"
        modelFlow.value = "content://docs/not-a-model"
        fakeRepository.loadModelResultProvider = { call ->
            if (call.modelFd == 9) {
                Result.failure(RuntimeException("invalid model file"))
            } else {
                Result.failure(RuntimeException("default model failed"))
            }
        }

        val result = modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(result.exceptionOrNull()?.message).contains("default model failed")
        assertThat(modelManager.modelState.value)
            .isInstanceOf(ModelManager.ModelState.Error::class.java)
        assertThat(
            fakeRepository.loadModelCalls.map { it.modelPath to it.modelFd }
        ).containsExactly(
            null to 9,
            "models/ggml-small.bin" to -1
        ).inOrder()
        coVerify(exactly = 1) { mockUserPreferences.setModel("ggml-small.bin") }
        assertThat(fakeModelRepository.importedModelLoadInProgressState).isFalse()
        verify(exactly = 1) { pfd.close() }
    }

    @Test
    fun `loadModel keeps a bundled selection that fails to load`() = runTest {
        every { mockUserPreferences.getDefaultModelForContext() } returns "ggml-small.bin"
        modelFlow.value = "ggml-base.en.bin"
        fakeRepository.loadModelResult = Result.failure(RuntimeException("out of memory"))

        assertThat(modelManager.loadModel(mockAssets).isFailure).isTrue()
        advanceUntilIdle()

        coVerify(exactly = 0) { mockUserPreferences.setModel(any()) }
        assertThat(modelManager.modelFallbackReason.value).isNull()
    }

    @Test
    fun `loadModel falls back to default when the previous custom model load did not finish`() = runTest {
        fakeModelRepository.importedModelLoadInProgressState = true
        every { mockUserPreferences.getDefaultModelForContext() } returns "ggml-small.bin"
        modelFlow.value = "content://docs/hangs"

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        coVerify { mockUserPreferences.setModel("ggml-small.bin") }
        assertThat(modelManager.modelFallbackReason.value)
            .isEqualTo(ModelManager.ModelFallbackReason.LOAD_FAILED)
        assertThat(fakeRepository.lastLoadModelPath).isEqualTo("models/ggml-small.bin")
        assertThat(fakeRepository.lastLoadModelFd).isEqualTo(-1)
    }

    @Test
    fun `stale custom model flag does not disturb a bundled selection`() = runTest {
        fakeModelRepository.importedModelLoadInProgressState = true

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(fakeModelRepository.importedModelLoadInProgressState).isFalse()
        assertThat(modelManager.modelFallbackReason.value).isNull()
        assertThat(fakeRepository.lastLoadModelPath).isEqualTo("models/ggml-base.en.bin")
    }

    @Test
    fun `handled custom model failure does not reject the next custom model`() = runTest {
        every { mockUserPreferences.getDefaultModelForContext() } returns "ggml-small.bin"

        val brokenModelPfd = mockk<ParcelFileDescriptor>(relaxed = true) {
            every { fd } returns 7
        }
        val workingModelPfd = mockk<ParcelFileDescriptor>(relaxed = true) {
            every { fd } returns 8
        }
        fakeModelRepository.openDescriptor = {
            when (it) {
                "content://docs/broken" -> brokenModelPfd
                "content://docs/working" -> workingModelPfd
                else -> null
            }
        }

        modelFlow.value = "content://docs/broken"
        fakeRepository.loadModelResult = Result.failure(RuntimeException("Load failed"))

        assertThat(modelManager.loadModel(mockAssets).isFailure).isTrue()
        assertThat(modelManager.modelFallbackReason.value)
            .isEqualTo(ModelManager.ModelFallbackReason.LOAD_FAILED)

        // The UI clears the reason once it has shown the snackbar.
        modelManager.clearModelFallbackReason()

        modelFlow.value = "content://docs/working"
        fakeRepository.loadModelResult = Result.success(true)

        assertThat(modelManager.loadModel(mockAssets).isSuccess).isTrue()
        assertThat(modelManager.modelState.value)
            .isEqualTo(ModelManager.ModelState.Loaded("content://docs/working", "Test GPU"))
        assertThat(modelManager.modelFallbackReason.value).isNull()
        assertThat(fakeRepository.lastLoadModelPath).isNull()
        assertThat(fakeRepository.lastLoadModelFd).isEqualTo(8)
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

    @Test
    fun `cancelled load does not leave the state stuck on Loading`() = runTest {
        coEvery { mockFileManager.copyAssets(any()) } coAnswers { awaitCancellation() }

        val cancelled = launch { runCatching { modelManager.loadModel(mockAssets) } }
        runCurrent()
        cancelled.cancelAndJoin()

        assertThat(modelManager.modelState.value)
            .isEqualTo(ModelManager.ModelState.NotLoaded)
    }

    @Test
    fun `turbo crash recovery is not undone by auto-enable in the same load`() = runTest {
        every { mockUserPreferences.isTurboLoadInProgress() } returns true
        every { mockUserPreferences.shouldAutoEnableTurboForDevice() } returns true
        turboModeHasBeenSetFlow.value = false
        fakeSettingsRepository.onTurboDecisionRecorded = { turboModeHasBeenSetFlow.value = true }

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(fakeSettingsRepository.getCurrentSettings().turboModeEnabled).isFalse()
        assertThat(fakeRepository.loadTurboModelCalled).isFalse()
    }

    @Test
    fun `failed turbo load turns the setting off without discarding the main model`() = runTest {
        fakeSettingsRepository.setSettings(
            fakeSettingsRepository.getCurrentSettings().copy(turboModeEnabled = true)
        )

        assertThat(modelManager.loadModel(mockAssets).isSuccess).isTrue()
        fakeRepository.loadTurboModelResult =
            Result.failure(RuntimeException("Not enough memory"))

        assertThat(modelManager.updateTurboMode(mockAssets, enabled = true).isFailure).isTrue()
        assertThat(fakeSettingsRepository.getCurrentSettings().turboModeEnabled).isFalse()
        assertThat(modelManager.isTurboCpuLoaded()).isFalse()
        assertThat(modelManager.modelState.value)
            .isEqualTo(ModelManager.ModelState.Loaded("ggml-base.en.bin", "Test GPU"))
    }

    @Test
    fun `cancelled turbo load preserves cancellation and the main model`() = runTest {
        assertThat(modelManager.loadModel(mockAssets).isSuccess).isTrue()
        fakeRepository.loadTurboModelResult =
            Result.failure(CancellationException("Cancelled"))

        val cancellation = try {
            modelManager.updateTurboMode(mockAssets, enabled = true)
            null
        } catch (exception: CancellationException) {
            exception
        }

        assertThat(cancellation).isNotNull()
        assertThat(modelManager.modelState.value)
            .isEqualTo(ModelManager.ModelState.Loaded("ggml-base.en.bin", "Test GPU"))
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
        setGpuEnabledSetting(false)

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

    @Test
    fun `reload request forces reload when settings are unchanged`() = runTest {
        every { mockUserPreferences.turboModeEnabled } returns MutableStateFlow(false)

        modelManager.loadModel(mockAssets)
        modelManager.startObservingSettings(mockAssets, backgroundScope)
        advanceTimeBy(101)
        runCurrent()
        fakeRepository.resetCallTracking()

        modelManager.requestModelReload()
        advanceTimeBy(101)
        runCurrent()

        assertThat(
            fakeRepository.loadModelCalls.map { it.modelPath to it.forceReload }
        ).containsExactly("models/ggml-base.en.bin" to true)
    }

    @Test
    fun `settings observer does not reload a fallback that is already loaded`() = runTest {
        every { mockUserPreferences.getDefaultModelForContext() } returns "ggml-small.bin"
        every { mockUserPreferences.turboModeEnabled } returns MutableStateFlow(false)
        coEvery { mockUserPreferences.setModel("ggml-small.bin") } coAnswers {
            modelFlow.value = "ggml-small.bin"
        }
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true) { every { fd } returns 9 }
        fakeModelRepository.openDescriptor = { pfd }
        fakeRepository.loadModelResultProvider = { call ->
            if (call.modelFd == 9) {
                Result.failure(RuntimeException("invalid model file"))
            } else {
                Result.success(true)
            }
        }

        modelManager.loadModel(mockAssets)
        modelManager.startObservingSettings(mockAssets, backgroundScope)
        advanceTimeBy(101)
        runCurrent()
        fakeRepository.resetCallTracking()

        modelFlow.value = "content://docs/not-a-model"
        advanceTimeBy(101)
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertThat(
            fakeRepository.loadModelCalls.map { it.modelPath to it.modelFd }
        ).containsExactly(
            null to 9,
            "models/ggml-small.bin" to -1
        ).inOrder()
        assertThat(modelManager.modelState.value)
            .isEqualTo(ModelManager.ModelState.Loaded("ggml-small.bin", "Test GPU"))
    }

    @Test
    fun `settings observer does not reload on its first emission`() = runTest {
        modelManager.loadModel(mockAssets)
        advanceUntilIdle()
        fakeRepository.resetCallTracking()

        modelManager.startObservingSettings(mockAssets, backgroundScope)
        advanceTimeBy(101)
        runCurrent()

        assertThat(fakeRepository.loadModelCalled).isFalse()
    }

    @Test
    fun `settings observer reloads with CPU after GPU becomes unavailable`() = runTest {
        every { mockUserPreferences.turboModeEnabled } returns MutableStateFlow(false)

        modelManager.loadModel(mockAssets)
        modelManager.startObservingSettings(mockAssets, backgroundScope)
        advanceTimeBy(101)
        runCurrent()
        fakeRepository.resetCallTracking()
        fakeRepository.loadModelGpuResult = null

        modelFlow.value = "ggml-large.bin"
        advanceTimeBy(101)
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertThat(
            fakeRepository.loadModelCalls.map { it.modelPath to it.useGpu }
        ).containsExactly(
            "models/ggml-large.bin" to true,
            "models/ggml-large.bin" to false
        ).inOrder()
        assertThat(fakeSettingsRepository.getPersistedSettings().gpuEnabled).isFalse()
        assertThat(fakeSettingsRepository.gpuAvailableForCurrentProcess.value).isFalse()
    }

    @Test
    fun `settings observer unloads turbo without GPU and restores it when GPU returns`() = runTest {
        every { mockUserPreferences.turboModeEnabled } returns MutableStateFlow(true)

        modelManager.loadModel(mockAssets)
        fakeRepository.setTurboModelLoaded(true)
        modelManager.startObservingSettings(mockAssets, backgroundScope)
        advanceTimeBy(101)
        runCurrent()
        fakeRepository.resetCallTracking()

        setGpuEnabledSetting(false)
        advanceTimeBy(101)
        runCurrent()

        assertThat(fakeRepository.unloadTurboModelCalled).isTrue()
        assertThat(fakeRepository.loadTurboModelCalled).isFalse()
        assertThat(fakeRepository.isTurboModelLoaded()).isFalse()
        assertThat(fakeRepository.lastLoadModelUseGpu).isFalse()

        fakeRepository.resetCallTracking()
        setGpuEnabledSetting(true)
        advanceTimeBy(101)
        runCurrent()

        assertThat(fakeRepository.lastLoadModelUseGpu).isTrue()
        assertThat(fakeRepository.loadTurboModelCalled).isTrue()
        assertThat(fakeRepository.isTurboModelLoaded()).isTrue()
    }

    @Test
    fun `first settings emission unloads turbo when the persisted setting is off`() = runTest {
        every { mockUserPreferences.turboModeEnabled } returns MutableStateFlow(false)

        modelManager.loadModel(mockAssets)
        fakeRepository.setTurboModelLoaded(true)
        modelManager.startObservingSettings(mockAssets, backgroundScope)
        advanceTimeBy(101)
        runCurrent()

        assertThat(fakeRepository.unloadTurboModelCalled).isTrue()
        assertThat(fakeRepository.isTurboModelLoaded()).isFalse()
    }

    @Test
    fun `settings observer restores requested turbo after main model recovery`() = runTest {
        every { mockUserPreferences.turboModeEnabled } returns MutableStateFlow(true)
        fakeRepository.loadModelResult = Result.failure(RuntimeException("Load failed"))

        modelManager.loadModel(mockAssets)
        modelManager.startObservingSettings(mockAssets, backgroundScope)
        advanceTimeBy(101)
        runCurrent()

        fakeRepository.resetCallTracking()
        fakeRepository.loadModelResult = Result.success(true)
        modelFlow.value = "ggml-small.bin"
        advanceTimeBy(101)
        runCurrent()

        assertThat(modelManager.modelState.value)
            .isEqualTo(ModelManager.ModelState.Loaded("ggml-small.bin", "Test GPU"))
        assertThat(fakeRepository.loadTurboModelCalled).isTrue()
        assertThat(fakeRepository.isTurboModelLoaded()).isTrue()
    }

    @Test
    fun `turning turbo off after main load failure keeps it off through recovery`() = runTest {
        val turboModeEnabledFlow = MutableStateFlow(true)
        every { mockUserPreferences.turboModeEnabled } returns turboModeEnabledFlow

        modelManager.loadModel(mockAssets)
        fakeRepository.setTurboModelLoaded(true)
        modelManager.startObservingSettings(mockAssets, backgroundScope)
        advanceTimeBy(101)
        runCurrent()

        fakeRepository.loadModelResult = Result.failure(RuntimeException("Load failed"))
        modelFlow.value = "ggml-small.bin"
        advanceTimeBy(101)
        runCurrent()
        assertThat(modelManager.modelState.value)
            .isInstanceOf(ModelManager.ModelState.Error::class.java)

        turboModeEnabledFlow.value = false
        advanceTimeBy(101)
        runCurrent()

        assertThat(fakeRepository.unloadTurboModelCalled).isTrue()
        assertThat(fakeRepository.isTurboModelLoaded()).isFalse()

        fakeRepository.resetCallTracking()
        fakeRepository.loadModelResult = Result.success(true)
        modelFlow.value = "ggml-large.bin"
        advanceTimeBy(101)
        runCurrent()

        assertThat(modelManager.modelState.value)
            .isEqualTo(ModelManager.ModelState.Loaded("ggml-large.bin", "Test GPU"))
        assertThat(fakeRepository.loadTurboModelCalled).isFalse()
        assertThat(fakeRepository.isTurboModelLoaded()).isFalse()
    }

    // =========================================================================
    // GPU Fallback Tests
    // =========================================================================

    @Test
    fun `GPU fallback disables GPU for this process and the next launch`() = runTest {
        fakeRepository.loadModelGpuResult = null

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(fakeSettingsRepository.disableGpuAfterFailureCalled).isTrue()
        assertThat(fakeSettingsRepository.getPersistedSettings().gpuEnabled).isFalse()
        assertThat(modelManager.gpuFallbackReason.value)
            .isEqualTo(ModelManager.GpuFallbackReason.UNAVAILABLE)
    }

    @Test
    fun `no fallback when GPU works`() = runTest {
        fakeRepository.loadModelGpuResult = "Test GPU"

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        coVerify(exactly = 0) { mockUserPreferences.setGpuEnabled(false) }
        assertThat(fakeSettingsRepository.gpuAvailableForCurrentProcess.value).isTrue()
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
        setGpuEnabledSetting(true)
        every { mockUserPreferences.isGpuInProgress() } returns true

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        verify { mockUserPreferences.setGpuInProgress(false) }
        assertThat(fakeSettingsRepository.disableGpuAfterFailureCalled).isTrue()
        // Persisted, so the next launch does not retry the GPU load that killed this process.
        assertThat(fakeSettingsRepository.getPersistedSettings().gpuEnabled).isFalse()
        assertThat(modelManager.gpuFallbackReason.value)
            .isEqualTo(ModelManager.GpuFallbackReason.CRASH)
        assertThat(fakeRepository.lastLoadModelUseGpu).isFalse()
    }

    @Test
    fun `crash recovery persists the GPU disable before clearing the crash flag`() = runTest {
        every { mockUserPreferences.isGpuInProgress() } returns true
        val order = mutableListOf<String>()
        every { mockUserPreferences.setGpuInProgress(false) } answers { order += "clearCrashFlag" }
        fakeSettingsRepository.onGpuDisabledPersisted = { order += "persistGpuDisable" }

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        assertThat(order).containsExactly("persistGpuDisable", "clearCrashFlag").inOrder()
    }

    @Test
    fun `crash recovery keeps the crash flag when the GPU disable cannot be persisted`() = runTest {
        every { mockUserPreferences.isGpuInProgress() } returns true
        fakeSettingsRepository.disableGpuResult = Result.failure(IOException("datastore"))

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        // Nothing else would stop the next launch from retrying the crashing GPU load.
        verify(exactly = 0) { mockUserPreferences.setGpuInProgress(false) }
        assertThat(fakeSettingsRepository.getPersistedSettings().gpuEnabled).isTrue()
        assertThat(fakeRepository.lastLoadModelUseGpu).isFalse()
    }

    @Test
    fun `loadModel sets flag before GPU load and clears after`() = runTest {
        setGpuEnabledSetting(true)
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
        setGpuEnabledSetting(false)

        modelManager.loadModel(mockAssets)
        advanceUntilIdle()

        verify(exactly = 0) { mockUserPreferences.setGpuInProgress(true) }
    }
}
