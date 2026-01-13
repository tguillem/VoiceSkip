// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.source

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voiceskip.data.repository.SavedTranscription
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.savedTranscriptionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "saved_transcription"
)

interface SavedTranscriptionDataSource {
    suspend fun save(transcription: SavedTranscription)
    suspend fun load(): SavedTranscription?
    suspend fun clear()
}

class SavedTranscriptionDataSourceImpl(
    private val context: Context
) : SavedTranscriptionDataSource {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val TRANSCRIPTION_KEY = stringPreferencesKey("last_transcription")
    }

    override suspend fun save(transcription: SavedTranscription) {
        val jsonString = json.encodeToString(transcription)
        context.savedTranscriptionDataStore.edit { preferences ->
            preferences[TRANSCRIPTION_KEY] = jsonString
        }
    }

    override suspend fun load(): SavedTranscription? {
        val preferences = context.savedTranscriptionDataStore.data.first()
        val jsonString = preferences[TRANSCRIPTION_KEY] ?: return null
        return runCatching {
            json.decodeFromString<SavedTranscription>(jsonString)
        }.getOrNull()
    }

    override suspend fun clear() {
        context.savedTranscriptionDataStore.edit { preferences ->
            preferences.remove(TRANSCRIPTION_KEY)
        }
    }
}
