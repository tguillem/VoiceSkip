// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.fake

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.voiceskip.data.repository.ModelImportState
import com.voiceskip.data.repository.ModelInfo
import com.voiceskip.data.repository.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeModelRepository : ModelRepository {

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    override val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _importState = MutableStateFlow<ModelImportState>(ModelImportState.Idle)
    override val importState: StateFlow<ModelImportState> = _importState.asStateFlow()

    var importedModelLoadInProgressState = false
    var openDescriptor: (String) -> ParcelFileDescriptor? = { null }
    var deleteResult: Result<Unit> = Result.success(Unit)
    val deletedModelIds = mutableListOf<String>()

    /** Id returned by [importModel]; null models a rejected file. */
    var importResult: String? = null
    val importedUris = mutableListOf<Uri>()
    var clearImportErrorCalled = false

    fun setAvailableModels(models: List<ModelInfo>) {
        _availableModels.value = models
    }

    fun setImportState(state: ModelImportState) {
        _importState.value = state
    }

    override suspend fun importModel(uri: Uri): String? {
        importedUris += uri
        return importResult
    }

    override suspend fun deleteModel(id: String): Result<Unit> {
        deletedModelIds += id
        return deleteResult
    }

    override suspend fun openImportedModel(id: String): ParcelFileDescriptor? =
        openDescriptor(id)

    override suspend fun setImportedModelLoadInProgress(inProgress: Boolean) {
        importedModelLoadInProgressState = inProgress
    }

    override suspend fun isImportedModelLoadInProgress(): Boolean =
        importedModelLoadInProgressState

    override fun clearImportError() {
        clearImportErrorCalled = true
        if (_importState.value is ModelImportState.Error) {
            _importState.value = ModelImportState.Idle
        }
    }
}
