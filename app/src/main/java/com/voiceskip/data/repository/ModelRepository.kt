// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.StateFlow

/**
 * A whisper model available for transcription.
 *
 * @param id Identifier stored as the selected model: a file name for bundled models,
 *   or a content:// URI string for imported ones.
 * @param displayName Human-readable name shown in the UI.
 * @param isImported True for user-referenced models, false for models bundled in the APK.
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val isImported: Boolean
)

sealed class ModelImportState {
    object Idle : ModelImportState()
    data class Error(val reason: ImportError) : ModelImportState()
}

enum class ImportError {
    NOT_A_BIN_FILE,
    UNREADABLE
}

/**
 * The set of transcription models: those bundled in the APK plus any the user referenced from
 * device storage (e.g. a ggml model downloaded from Hugging Face). Imported models are referenced
 * by a persisted content:// URI, never copied.
 */
interface ModelRepository {
    val availableModels: StateFlow<List<ModelInfo>>
    val importState: StateFlow<ModelImportState>

    /** Returns the id of the newly referenced model, or null if it was rejected (see [importState]). */
    suspend fun importModel(uri: Uri): String?
    suspend fun deleteModel(id: String): Result<Unit>
    suspend fun openImportedModel(id: String): ParcelFileDescriptor?
    suspend fun setImportedModelLoadInProgress(inProgress: Boolean)
    suspend fun isImportedModelLoadInProgress(): Boolean
    fun clearImportError()
}
