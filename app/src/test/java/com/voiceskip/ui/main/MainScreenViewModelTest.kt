// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.main

import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voiceskip.StartupConfig
import com.voiceskip.TestDispatcherRule
import com.voiceskip.data.repository.TranscriptionState
import com.voiceskip.fake.FakeSavedTranscriptionRepository
import com.voiceskip.fake.FakeSettingsRepository
import com.voiceskip.fake.FakeTranscriptionRepository
import com.voiceskip.util.getParcelableExtraCompat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import com.voiceskip.data.UserPreferences
import com.voiceskip.domain.ModelManager
import com.voiceskip.domain.usecase.FormatSentencesUseCase
import com.voiceskip.service.ServiceLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var viewModel: MainScreenViewModel
    private lateinit var fakeTranscriptionRepository: FakeTranscriptionRepository
    private lateinit var fakeSettingsRepository: FakeSettingsRepository
    private lateinit var fakeSavedTranscriptionRepository: FakeSavedTranscriptionRepository
    private lateinit var mockModelManager: ModelManager
    private lateinit var mockAudioManager: AudioPlaybackManager
    private lateinit var mockServiceLauncher: ServiceLauncher
    private lateinit var mockAssetManager: AssetManager
    private lateinit var startupConfig: StartupConfig
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var mockFormatSentencesUseCase: FormatSentencesUseCase

    private val modelStateFlow = MutableStateFlow<ModelManager.ModelState>(ModelManager.ModelState.NotLoaded)
    private val gpuFallbackReasonFlow = MutableStateFlow<ModelManager.GpuFallbackReason?>(null)
    private val turboFallbackReasonFlow = MutableStateFlow<ModelManager.TurboFallbackReason?>(null)

    @Before
    fun setup() {
        mockkStatic("com.voiceskip.util.IntentExtensionsKt")
        fakeTranscriptionRepository = FakeTranscriptionRepository()
        fakeSettingsRepository = FakeSettingsRepository()
        fakeSavedTranscriptionRepository = FakeSavedTranscriptionRepository()

        mockModelManager = mockk(relaxed = true) {
            every { modelState } returns modelStateFlow
            every { gpuFallbackReason } returns gpuFallbackReasonFlow
            every { turboFallbackReason } returns turboFallbackReasonFlow
            coEvery { loadModel(any()) } returns Result.success(Unit)
        }

        mockAudioManager = mockk(relaxed = true) {
            coEvery { stopPlayback() } just Runs
        }

        mockServiceLauncher = mockk(relaxed = true) {
            coEvery { startRecording(any()) } just Runs
            coEvery { startFileTranscription(any(), any()) } just Runs
        }

        mockAssetManager = mockk(relaxed = true)

        startupConfig = StartupConfig().apply {
            skipModelLoad = true
        }

        savedStateHandle = SavedStateHandle()

        mockFormatSentencesUseCase = FormatSentencesUseCase()

        createViewModel()
    }

    private fun createViewModel() {
        viewModel = MainScreenViewModel(
            repository = fakeTranscriptionRepository,
            settingsRepository = fakeSettingsRepository,
            savedTranscriptionRepository = fakeSavedTranscriptionRepository,
            modelManager = mockModelManager,
            audioManager = mockAudioManager,
            serviceLauncher = mockServiceLauncher,
            assetManager = mockAssetManager,
            startupConfig = startupConfig,
            savedStateHandle = savedStateHandle,
            formatSentencesUseCase = mockFormatSentencesUseCase
        )
    }

    // =========================================================================
    // Model Loading States Tests
    // =========================================================================

    @Test
    fun `uiState transitions to LoadingModel when model is loading`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loading("models/test.bin", true)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.LoadingModel)
        }
    }

    @Test
    fun `uiState transitions to Ready when model loaded`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", "Test GPU")
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.Ready)
            assertThat(state.canTranscribe).isTrue()
        }
    }

    @Test
    fun `uiState transitions to Error on model load failure`() = runTest {
        val exception = RuntimeException("Model load failed")
        modelStateFlow.value = ModelManager.ModelState.Error(exception)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isInstanceOf(TranscriptionUiState.Error::class.java)
            assertThat((state.screenState as TranscriptionUiState.Error).message)
                .contains("Model load failed")
        }
    }

    // =========================================================================
    // Transcription Actions Tests
    // =========================================================================

    @Test
    fun `uiState transitions to LiveRecording during recording`() = runTest {
        fakeTranscriptionRepository.setState(
            TranscriptionState.LiveRecording(
                durationMs = 5000,
                amplitude = 0.7f,
                progress = 25,
                currentSegment = null,
                segments = emptyList()
            )
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.LiveRecording)
            assertThat(state.isRecording).isTrue()
            assertThat(state.recordingDurationMs).isEqualTo(5000)
        }
    }

    @Test
    fun `uiState transitions to Transcribing during file transcription`() = runTest {
        fakeTranscriptionRepository.setState(
            TranscriptionState.Transcribing(
                progress = 50,
                currentSegment = null,
                segments = emptyList()
            )
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.Transcribing)
        }
    }

    // =========================================================================
    // Progress & Results Tests
    // =========================================================================

    @Test
    fun `completion transitions uiState to Complete`() = runTest {
        fakeTranscriptionRepository.setState(
            TranscriptionState.Complete(
                text = "Complete transcription",
                processingTimeMs = 5000,
                audioLengthMs = 10000,
                segments = emptyList(),
                detectedLanguage = "en"
            )
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.Complete)
            assertThat(state.transcriptionResult).isNotNull()
            assertThat(state.transcriptionResult?.text).isEqualTo("Complete transcription")
        }
    }

    @Test
    fun `error transitions uiState to Error with message`() = runTest {
        fakeTranscriptionRepository.setState(
            TranscriptionState.Error("Test error message", recoverable = false)
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isInstanceOf(TranscriptionUiState.Error::class.java)
            assertThat((state.screenState as TranscriptionUiState.Error).message)
                .isEqualTo("Test error message")
            assertThat(state.errorMessage).isEqualTo("Test error message")
        }
    }

    // =========================================================================
    // State Composition Tests
    // =========================================================================

    @Test
    fun `uiState combines model state and repo state`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", "Test GPU")
        fakeTranscriptionRepository.setState(TranscriptionState.Idle)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.Ready)
            assertThat(state.canTranscribe).isTrue()
        }
    }

    @Test
    fun `canTranscribe is false when model not loaded`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.NotLoaded
        fakeTranscriptionRepository.setState(TranscriptionState.Idle)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.canTranscribe).isFalse()
        }
    }

    @Test
    fun `canTranscribe is false when transcription in progress`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", "Test GPU")
        fakeTranscriptionRepository.setState(
            TranscriptionState.Transcribing(
                progress = 50,
                currentSegment = null,
                segments = emptyList()
            )
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.canTranscribe).isFalse()
        }
    }

    // =========================================================================
    // Intent Handling Tests
    // =========================================================================

    @Test
    fun `handleIncomingIntent waits for model before starting transcription`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.NotLoaded
        val testUri = mockk<Uri>()
        val intent = mockk<Intent> {
            every { action } returns Intent.ACTION_SEND
            every { getStringExtra("language") } returns null
            every { getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM) } returns testUri
        }

        viewModel.handleIncomingIntent(intent)
        advanceUntilIdle()

        // URI saved but service not started yet
        assertThat(savedStateHandle.get<Uri>("pending_uri")).isEqualTo(testUri)

        // Now load the model
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", "Test GPU")
        advanceUntilIdle()

        // Now transcription should start
        coVerify { mockServiceLauncher.startFileTranscription(testUri, any()) }
    }

    @Test
    fun `handleIncomingIntent does not start transcription on model error`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Error(RuntimeException("Failed"))
        val testUri = mockk<Uri>()
        val intent = mockk<Intent> {
            every { action } returns Intent.ACTION_SEND
            every { getStringExtra("language") } returns null
            every { getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM) } returns testUri
        }

        viewModel.handleIncomingIntent(intent)
        advanceUntilIdle()

        // URI saved but service not started due to model error
        assertThat(savedStateHandle.get<Uri>("pending_uri")).isEqualTo(testUri)
        coVerify(exactly = 0) { mockServiceLauncher.startFileTranscription(any(), any()) }
    }

}
