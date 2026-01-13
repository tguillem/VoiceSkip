// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.fake

import com.voiceskip.data.repository.SavedTranscription
import com.voiceskip.data.repository.SavedTranscriptionRepository
import com.voiceskip.data.repository.SavedSegment
import com.voiceskip.data.repository.TranscriptionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSavedTranscriptionRepository : SavedTranscriptionRepository {

    private val _savedTranscription = MutableStateFlow<SavedTranscription?>(null)
    override val savedTranscription: StateFlow<SavedTranscription?> = _savedTranscription.asStateFlow()

    var saveTranscriptionCalled = false
    var restoreTranscriptionCalled = false
    var loadSavedTranscriptionCalled = false
    var clearSavedTranscriptionCalled = false
    var lastSavedState: TranscriptionState.Complete? = null
    var lastRestoredTranscription: SavedTranscription? = null

    override suspend fun saveTranscription(state: TranscriptionState.Complete) {
        saveTranscriptionCalled = true
        lastSavedState = state
        _savedTranscription.value = SavedTranscription(
            text = state.text,
            timestamp = System.currentTimeMillis(),
            durationMs = state.processingTimeMs,
            audioLengthMs = state.audioLengthMs,
            detectedLanguage = state.detectedLanguage ?: "unknown",
            segments = state.segments.map { SavedSegment(it.text, it.startMs, it.endMs, it.language) }
        )
    }

    override suspend fun restoreTranscription(transcription: SavedTranscription) {
        restoreTranscriptionCalled = true
        lastRestoredTranscription = transcription
        _savedTranscription.value = transcription
    }

    override suspend fun loadSavedTranscription() {
        loadSavedTranscriptionCalled = true
    }

    override suspend fun clearSavedTranscription() {
        clearSavedTranscriptionCalled = true
        _savedTranscription.value = null
    }

    fun setSavedTranscription(transcription: SavedTranscription?) {
        _savedTranscription.value = transcription
    }

    fun reset() {
        saveTranscriptionCalled = false
        restoreTranscriptionCalled = false
        loadSavedTranscriptionCalled = false
        clearSavedTranscriptionCalled = false
        lastSavedState = null
        lastRestoredTranscription = null
        _savedTranscription.value = null
    }
}
