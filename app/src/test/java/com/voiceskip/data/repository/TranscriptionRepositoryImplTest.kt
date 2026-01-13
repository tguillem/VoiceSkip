// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.content.res.AssetManager
import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voiceskip.TestDispatcherRule
import com.voiceskip.data.source.TranscriptionEvent
import com.voiceskip.domain.usecase.FileTranscriptionUseCase
import com.voiceskip.domain.usecase.LiveTranscriptionUseCase
import com.voiceskip.fake.FakeSettingsRepository
import com.voiceskip.fake.FakeWhisperDataSource
import com.voiceskip.ui.main.FileManager
import com.voiceskip.whispercpp.whisper.WhisperSegment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionRepositoryImplTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var repository: TranscriptionRepositoryImpl
    private lateinit var fakeWhisperDataSource: FakeWhisperDataSource
    private lateinit var fakeSettingsRepository: FakeSettingsRepository
    private lateinit var mockFileManager: FileManager
    private lateinit var mockLiveTranscriptionUseCase: LiveTranscriptionUseCase
    private lateinit var mockFileTranscriptionUseCase: FileTranscriptionUseCase
    private lateinit var mockAssets: AssetManager

    @Before
    fun setup() {
        fakeWhisperDataSource = FakeWhisperDataSource()
        fakeSettingsRepository = FakeSettingsRepository()
        mockFileManager = mockk(relaxed = true)
        mockLiveTranscriptionUseCase = mockk(relaxed = true)
        mockFileTranscriptionUseCase = mockk(relaxed = true)
        mockAssets = mockk(relaxed = true)

        repository = TranscriptionRepositoryImpl(
            fileManager = mockFileManager,
            settingsRepository = fakeSettingsRepository,
            whisperDataSource = fakeWhisperDataSource,
            liveTranscriptionUseCase = mockLiveTranscriptionUseCase,
            fileTranscriptionUseCase = mockFileTranscriptionUseCase,
            defaultDispatcher = dispatcherRule.testDispatcher
        )
    }

    @Test
    fun `startRecording transitions to LiveRecording`() = runTest {
        every { mockLiveTranscriptionUseCase.execute(any(), any(), any(), any()) } returns flowOf()

        repository.state.test {
            assertThat(awaitItem()).isEqualTo(TranscriptionState.Idle)

            repository.startRecording()
            advanceUntilIdle()

            val state = awaitItem()
            assertThat(state).isInstanceOf(TranscriptionState.LiveRecording::class.java)
        }
    }

    @Test
    fun `startRecording does nothing if not in Idle state`() = runTest {
        every { mockLiveTranscriptionUseCase.execute(any(), any(), any(), any()) } returns flowOf()

        repository.startRecording()
        advanceUntilIdle()

        val stateBefore = repository.state.value

        repository.startRecording()
        advanceUntilIdle()

        assertThat(repository.state.value).isEqualTo(stateBefore)
    }

    @Test
    fun `live transcription emits Recording progress`() = runTest {
        val segments = listOf(WhisperSegment("Hello", 0, 1000, "en"))
        val recordingProgress = LiveTranscriptionUseCase.Progress.Recording(
            durationMs = 5000,
            amplitude = 0.5f,
            segments = segments,
            detectedLanguage = "en"
        )

        every { mockLiveTranscriptionUseCase.execute(any(), any(), any(), any()) } returns
            flowOf(recordingProgress)

        repository.state.test {
            assertThat(awaitItem()).isEqualTo(TranscriptionState.Idle)

            repository.startRecording()
            advanceUntilIdle()

            val liveState = expectMostRecentItem()
            assertThat(liveState).isInstanceOf(TranscriptionState.LiveRecording::class.java)

            val recording = liveState as TranscriptionState.LiveRecording
            assertThat(recording.durationMs).isEqualTo(5000)
            assertThat(recording.amplitude).isEqualTo(0.5f)
            assertThat(recording.segments).hasSize(1)
            assertThat(recording.detectedLanguage).isEqualTo("en")
        }
    }

    @Test
    fun `live transcription emits Finishing progress`() = runTest {
        val segments = listOf(WhisperSegment("Hello world", 0, 2000, "en"))
        val finishingProgress = LiveTranscriptionUseCase.Progress.Finishing(
            progressPercent = 75,
            segments = segments,
            detectedLanguage = "en"
        )

        every { mockLiveTranscriptionUseCase.execute(any(), any(), any(), any()) } returns
            flowOf(finishingProgress)

        repository.state.test {
            skipItems(1)

            repository.startRecording()
            advanceUntilIdle()

            val state = expectMostRecentItem()
            assertThat(state).isInstanceOf(TranscriptionState.FinishingTranscription::class.java)

            val finishing = state as TranscriptionState.FinishingTranscription
            assertThat(finishing.progress).isEqualTo(75)
        }
    }

    @Test
    fun `live transcription completes to Complete state`() = runTest {
        val segments = listOf(WhisperSegment("Hello world", 0, 2000, "en"))
        val completeProgress = LiveTranscriptionUseCase.Progress.Complete(
            segments = segments,
            detectedLanguage = "en",
            recordingDurationMs = 5000,
            processingTimeMs = 1000
        )

        every { mockLiveTranscriptionUseCase.execute(any(), any(), any(), any()) } returns
            flowOf(completeProgress)

        repository.state.test {
            skipItems(1)

            repository.startRecording()
            advanceUntilIdle()

            val state = expectMostRecentItem()
            assertThat(state).isInstanceOf(TranscriptionState.Complete::class.java)

            val complete = state as TranscriptionState.Complete
            assertThat(complete.text).isEqualTo("Hello world")
            assertThat(complete.segments).hasSize(1)
            assertThat(complete.detectedLanguage).isEqualTo("en")
        }
    }

    @Test
    fun `cancelRecording returns to Idle`() = runTest {
        every { mockLiveTranscriptionUseCase.execute(any(), any(), any(), any()) } returns flowOf()

        repository.startRecording()
        advanceUntilIdle()

        repository.cancelRecording()

        assertThat(repository.state.value).isEqualTo(TranscriptionState.Idle)
    }

    @Test
    fun `transcribeUri transitions to Transcribing`() = runTest {
        val uri = mockk<Uri>()

        every { mockFileTranscriptionUseCase.execute(any(), any(), any(), any(), any()) } returns
            flowOf()

        repository.state.test {
            assertThat(awaitItem()).isEqualTo(TranscriptionState.Idle)

            repository.transcribeUri(uri)
            advanceUntilIdle()

            val state = awaitItem()
            assertThat(state).isInstanceOf(TranscriptionState.Transcribing::class.java)
        }
    }

    @Test
    fun `transcribeUri does nothing if not in Idle`() = runTest {
        val uri = mockk<Uri>()

        every { mockLiveTranscriptionUseCase.execute(any(), any(), any(), any()) } returns flowOf()
        every { mockFileTranscriptionUseCase.execute(any(), any(), any(), any(), any()) } returns
            flowOf()

        repository.startRecording()
        advanceUntilIdle()

        val stateBefore = repository.state.value

        repository.transcribeUri(uri)
        advanceUntilIdle()

        assertThat(repository.state.value).isEqualTo(stateBefore)
    }

    @Test
    fun `file transcription emits Transcribing progress`() = runTest {
        val uri = mockk<Uri>()
        val segments = listOf(WhisperSegment("Test", 0, 1000))
        val transcribingProgress = FileTranscriptionUseCase.Progress.Transcribing(
            progressPercent = 50,
            segments = segments,
            detectedLanguage = "en"
        )

        every { mockFileTranscriptionUseCase.execute(any(), any(), any(), any(), any()) } returns
            flowOf(transcribingProgress)

        repository.state.test {
            skipItems(1)

            repository.transcribeUri(uri)
            advanceUntilIdle()

            val state = expectMostRecentItem()
            assertThat(state).isInstanceOf(TranscriptionState.Transcribing::class.java)

            val transcribing = state as TranscriptionState.Transcribing
            assertThat(transcribing.progress).isEqualTo(50)
            assertThat(transcribing.segments).hasSize(1)
        }
    }

    @Test
    fun `file transcription updates progress flow`() = runTest {
        val uri = mockk<Uri>()
        val transcribingProgress = FileTranscriptionUseCase.Progress.Transcribing(
            progressPercent = 75,
            segments = emptyList(),
            detectedLanguage = null
        )

        every { mockFileTranscriptionUseCase.execute(any(), any(), any(), any(), any()) } returns
            flowOf(transcribingProgress)

        repository.progress.test {
            assertThat(awaitItem()).isEqualTo(0)

            repository.transcribeUri(uri)
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(75)
        }
    }

    @Test
    fun `file transcription completes to Complete state`() = runTest {
        val uri = mockk<Uri>()
        val segments = listOf(WhisperSegment("Transcribed text", 0, 5000, "en"))
        val completeProgress = FileTranscriptionUseCase.Progress.Complete(
            segments = segments,
            detectedLanguage = "en",
            audioLengthMs = 10000,
            processingTimeMs = 2000
        )

        every { mockFileTranscriptionUseCase.execute(any(), any(), any(), any(), any()) } returns
            flowOf(completeProgress)

        repository.state.test {
            skipItems(1)

            repository.transcribeUri(uri)
            advanceUntilIdle()

            val state = expectMostRecentItem()
            assertThat(state).isInstanceOf(TranscriptionState.Complete::class.java)

            val complete = state as TranscriptionState.Complete
            assertThat(complete.text).isEqualTo("Transcribed text")
            assertThat(complete.audioLengthMs).isEqualTo(10000)
        }
    }

    @Test
    fun `cancelTranscription returns to Idle`() = runTest {
        val uri = mockk<Uri>()

        every { mockFileTranscriptionUseCase.execute(any(), any(), any(), any(), any()) } returns
            flowOf()

        repository.transcribeUri(uri)
        advanceUntilIdle()

        repository.cancelTranscription()

        assertThat(repository.state.value).isEqualTo(TranscriptionState.Idle)
        assertThat(fakeWhisperDataSource.stopCalled).isTrue()
    }

    @Test
    fun `clearState returns to Idle from Complete`() = runTest {
        val uri = mockk<Uri>()
        val completeProgress = FileTranscriptionUseCase.Progress.Complete(
            segments = emptyList(),
            detectedLanguage = null,
            audioLengthMs = 1000,
            processingTimeMs = 100
        )

        every { mockFileTranscriptionUseCase.execute(any(), any(), any(), any(), any()) } returns
            flowOf(completeProgress)

        repository.transcribeUri(uri)
        advanceUntilIdle()

        assertThat(repository.state.value).isInstanceOf(TranscriptionState.Complete::class.java)

        repository.clearState()

        assertThat(repository.state.value).isEqualTo(TranscriptionState.Idle)
    }

    @Test
    fun `clearState clears current source`() = runTest {
        val uri = mockk<Uri>()
        val completeProgress = FileTranscriptionUseCase.Progress.Complete(
            segments = emptyList(),
            detectedLanguage = null,
            audioLengthMs = 1000,
            processingTimeMs = 100
        )

        every { mockFileTranscriptionUseCase.execute(any(), any(), any(), any(), any()) } returns
            flowOf(completeProgress)

        repository.transcribeUri(uri)
        advanceUntilIdle()

        repository.clearState()

        assertThat(repository.getCurrentTranscriptionSource()).isNull()
    }

    @Test
    fun `clearState does nothing if not Complete or Error`() = runTest {
        every { mockLiveTranscriptionUseCase.execute(any(), any(), any(), any()) } returns flowOf()

        repository.startRecording()
        advanceUntilIdle()

        val stateBefore = repository.state.value

        repository.clearState()

        assertThat(repository.state.value).isEqualTo(stateBefore)
    }

    @Test
    fun `loadModel returns failure when datasource throws`() = runTest {
        fakeWhisperDataSource.loadModelShouldEmitLoaded = false
        fakeWhisperDataSource.loadModelResult = Result.failure(RuntimeException("Load failed"))

        val result = repository.loadModel(mockAssets, "models/test.bin", vadModelPath = null, useGpu = true)

        assertThat(result.isFailure).isTrue()
        assertThat(fakeWhisperDataSource.loadModelCalled).isTrue()
    }

    @Test
    fun `stopTranscription resets progress to 0`() = runTest {
        val uri = mockk<Uri>()
        val transcribingProgress = FileTranscriptionUseCase.Progress.Transcribing(
            progressPercent = 50,
            segments = emptyList(),
            detectedLanguage = null
        )

        every { mockFileTranscriptionUseCase.execute(any(), any(), any(), any(), any()) } returns
            flowOf(transcribingProgress)

        repository.transcribeUri(uri)
        advanceUntilIdle()

        repository.stopTranscription()
        advanceUntilIdle()

        assertThat(repository.progress.value).isEqualTo(0)
    }

}
