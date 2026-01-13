// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voiceskip.data.source.SavedTranscriptionDataSource
import com.voiceskip.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SavedTranscriptionRepositoryImplTest {

    private lateinit var repository: SavedTranscriptionRepositoryImpl
    private lateinit var fakeDataSource: FakeSavedTranscriptionDataSource

    @Before
    fun setup() {
        fakeDataSource = FakeSavedTranscriptionDataSource()
        repository = SavedTranscriptionRepositoryImpl(fakeDataSource)
    }

    @Test
    fun `saveTranscription stores transcription and updates flow`() = runTest {
        val completeState = TranscriptionState.Complete(
            text = "Test transcription",
            segments = listOf(
                WhisperSegment("Test transcription", 0, 1000, "en")
            ),
            detectedLanguage = "en",
            audioLengthMs = 5000,
            processingTimeMs = 2000
        )

        repository.savedTranscription.test {
            assertThat(awaitItem()).isNull()

            repository.saveTranscription(completeState)

            val saved = awaitItem()
            assertThat(saved).isNotNull()
            assertThat(saved?.text).isEqualTo("Test transcription")
            assertThat(saved?.detectedLanguage).isEqualTo("en")
            assertThat(saved?.audioLengthMs).isEqualTo(5000)
            assertThat(saved?.durationMs).isEqualTo(2000)
            assertThat(saved?.segments).hasSize(1)
        }

        assertThat(fakeDataSource.savedTranscription).isNotNull()
    }

    @Test
    fun `loadSavedTranscription loads from data source`() = runTest {
        val saved = SavedTranscription(
            text = "Saved text",
            timestamp = 12345L,
            durationMs = 1000,
            audioLengthMs = 3000,
            detectedLanguage = "fr",
            segments = emptyList()
        )
        fakeDataSource.savedTranscription = saved

        repository.savedTranscription.test {
            assertThat(awaitItem()).isNull()

            repository.loadSavedTranscription()

            val loaded = awaitItem()
            assertThat(loaded).isNotNull()
            assertThat(loaded?.text).isEqualTo("Saved text")
            assertThat(loaded?.detectedLanguage).isEqualTo("fr")
        }
    }

    @Test
    fun `clearSavedTranscription clears data and updates flow`() = runTest {
        val saved = SavedTranscription(
            text = "To delete",
            timestamp = 12345L,
            durationMs = 1000,
            audioLengthMs = 3000,
            detectedLanguage = "en",
            segments = emptyList()
        )
        fakeDataSource.savedTranscription = saved
        repository.loadSavedTranscription()

        repository.savedTranscription.test {
            assertThat(awaitItem()).isNotNull()

            repository.clearSavedTranscription()

            assertThat(awaitItem()).isNull()
        }

        assertThat(fakeDataSource.savedTranscription).isNull()
        assertThat(fakeDataSource.clearCalled).isTrue()
    }

    @Test
    fun `segments are converted correctly`() = runTest {
        val completeState = TranscriptionState.Complete(
            text = "Hello world",
            segments = listOf(
                WhisperSegment("Hello", 0, 500, "en"),
                WhisperSegment("world", 500, 1000, null)
            ),
            detectedLanguage = "en",
            audioLengthMs = 1000,
            processingTimeMs = 500
        )

        repository.saveTranscription(completeState)

        val saved = repository.savedTranscription.value
        assertThat(saved?.segments).hasSize(2)
        assertThat(saved?.segments?.get(0)?.text).isEqualTo("Hello")
        assertThat(saved?.segments?.get(0)?.startMs).isEqualTo(0)
        assertThat(saved?.segments?.get(0)?.endMs).isEqualTo(500)
        assertThat(saved?.segments?.get(0)?.language).isEqualTo("en")
        assertThat(saved?.segments?.get(1)?.text).isEqualTo("world")
        assertThat(saved?.segments?.get(1)?.language).isNull()
    }
}

private class FakeSavedTranscriptionDataSource : SavedTranscriptionDataSource {
    var savedTranscription: SavedTranscription? = null
    var clearCalled = false

    override suspend fun save(transcription: SavedTranscription) {
        savedTranscription = transcription
    }

    override suspend fun load(): SavedTranscription? {
        return savedTranscription
    }

    override suspend fun clear() {
        clearCalled = true
        savedTranscription = null
    }
}
