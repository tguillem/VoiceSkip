// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import com.voiceskip.data.source.SavedTranscriptionDataSource
import com.voiceskip.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SavedTranscriptionRepository {
    val savedTranscription: StateFlow<SavedTranscription?>
    suspend fun saveTranscription(state: TranscriptionState.Complete)
    suspend fun restoreTranscription(transcription: SavedTranscription)
    suspend fun loadSavedTranscription()
    suspend fun clearSavedTranscription()
}

class SavedTranscriptionRepositoryImpl(
    private val dataSource: SavedTranscriptionDataSource
) : SavedTranscriptionRepository {

    private val _savedTranscription = MutableStateFlow<SavedTranscription?>(null)
    override val savedTranscription: StateFlow<SavedTranscription?> = _savedTranscription.asStateFlow()

    override suspend fun saveTranscription(state: TranscriptionState.Complete) {
        val saved = SavedTranscription(
            text = state.text,
            timestamp = System.currentTimeMillis(),
            durationMs = state.processingTimeMs,
            audioLengthMs = state.audioLengthMs,
            detectedLanguage = state.detectedLanguage ?: "unknown",
            segments = state.segments.map { it.toSavedSegment() },
            audioUri = state.audioUri?.toString()
        )
        dataSource.save(saved)
        _savedTranscription.value = saved
    }

    override suspend fun restoreTranscription(transcription: SavedTranscription) {
        dataSource.save(transcription)
        _savedTranscription.value = transcription
    }

    override suspend fun loadSavedTranscription() {
        _savedTranscription.value = dataSource.load()
    }

    override suspend fun clearSavedTranscription() {
        dataSource.clear()
        _savedTranscription.value = null
    }

    private fun WhisperSegment.toSavedSegment() = SavedSegment(
        text = text,
        startMs = startMs,
        endMs = endMs,
        language = language
    )
}

fun SavedSegment.toWhisperSegment() = WhisperSegment(
    text = text,
    startMs = startMs,
    endMs = endMs,
    language = language
)
