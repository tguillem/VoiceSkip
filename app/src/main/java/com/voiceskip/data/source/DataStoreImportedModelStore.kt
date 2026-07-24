// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.source

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voiceskip.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A persisted reference to a model the user imported from device storage. */
@Serializable
data class ImportedModelRef(val uri: String, val name: String)

/** Persistence for the imported-model registry; abstracted so it can be faked in tests. */
interface ImportedModelStore {
    val models: Flow<List<ImportedModelRef>>
    suspend fun add(ref: ImportedModelRef)
    suspend fun remove(uri: String)
}

/** Stores the imported-model registry as a JSON list in the app's DataStore. */
@Singleton
class DataStoreImportedModelStore @Inject constructor(
    private val application: Application
) : ImportedModelStore {

    private val json = Json { ignoreUnknownKeys = true }

    override val models: Flow<List<ImportedModelRef>> =
        application.dataStore.data.map { prefs -> decode(prefs[KEY]) }

    override suspend fun add(ref: ImportedModelRef) {
        application.dataStore.edit { prefs ->
            // Replace any existing entry for the same URI so re-importing doesn't duplicate.
            val updated = decode(prefs[KEY]).filterNot { it.uri == ref.uri } + ref
            prefs[KEY] = json.encodeToString(updated)
        }
    }

    override suspend fun remove(uri: String) {
        application.dataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(decode(prefs[KEY]).filterNot { it.uri == uri })
        }
    }

    private fun decode(raw: String?): List<ImportedModelRef> {
        if (raw == null) return emptyList()
        return runCatching { json.decodeFromString<List<ImportedModelRef>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY = stringPreferencesKey("imported_models")
    }
}
