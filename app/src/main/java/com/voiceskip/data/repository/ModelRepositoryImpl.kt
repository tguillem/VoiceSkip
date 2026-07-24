// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.app.Application
import android.net.Uri
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.source.ImportedModelDocumentDataSource
import com.voiceskip.data.source.ImportedModelRef
import com.voiceskip.data.source.ImportedModelStore
import com.voiceskip.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val application: Application,
    private val store: ImportedModelStore,
    private val documentDataSource: ImportedModelDocumentDataSource,
    private val userPreferences: UserPreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ModelRepository {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _importState = MutableStateFlow<ModelImportState>(ModelImportState.Idle)
    override val importState: StateFlow<ModelImportState> = _importState.asStateFlow()

    override val availableModels: StateFlow<List<ModelInfo>> = store.models
        .map { imported -> bundledModels() + imported.map { it.toModelInfo() } }
        .stateIn(scope, SharingStarted.Eagerly, bundledModels())

    override suspend fun importModel(uri: Uri): String? = withContext(ioDispatcher) {
        val id = uri.toString()
        val name = try {
            documentDataSource.getDisplayName(id)
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.trim()
        } catch (exception: Exception) {
            if (exception is CancellationException) {
                throw exception
            }
            _importState.value = ModelImportState.Error(ImportError.UNREADABLE)
            return@withContext null
        }
        if (name.isNullOrBlank() || !name.endsWith(".bin", ignoreCase = true)) {
            _importState.value = ModelImportState.Error(ImportError.NOT_A_BIN_FILE)
            return@withContext null
        }

        var releaseGrantOnFailure = false
        try {
            if (!documentDataSource.isReadable(id)) {
                _importState.value = ModelImportState.Error(ImportError.UNREADABLE)
                return@withContext null
            }

            val grantAlreadyPersisted =
                documentDataSource.hasPersistedReadPermission(id)
            documentDataSource.persistReadPermission(id)
            releaseGrantOnFailure = !grantAlreadyPersisted
            store.add(ImportedModelRef(id, name))
        } catch (exception: Exception) {
            if (releaseGrantOnFailure) {
                runCatching { documentDataSource.releaseReadPermission(id) }
            }
            if (exception is CancellationException) {
                throw exception
            }
            _importState.value = ModelImportState.Error(ImportError.UNREADABLE)
            return@withContext null
        }

        _importState.value = ModelImportState.Idle
        id
    }

    override suspend fun deleteModel(id: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            store.remove(id)
            runCatching {
                documentDataSource.releaseReadPermission(id)
            }
            Result.success(Unit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Result.failure(exception)
        }
    }

    override suspend fun openImportedModel(id: String) = withContext(ioDispatcher) {
        try {
            documentDataSource.openReadFileDescriptor(id)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            null
        }
    }

    override suspend fun setImportedModelLoadInProgress(inProgress: Boolean) {
        withContext(ioDispatcher) {
            userPreferences.setCustomModelLoadInProgress(inProgress)
        }
    }

    override suspend fun isImportedModelLoadInProgress(): Boolean =
        withContext(ioDispatcher) {
            userPreferences.isCustomModelLoadInProgress()
        }

    override fun clearImportError() {
        if (_importState.value is ModelImportState.Error) {
            _importState.value = ModelImportState.Idle
        }
    }

    private fun bundledModels(): List<ModelInfo> =
        UserPreferences.getAvailableModelNames(application)
            .map { ModelInfo(id = it, displayName = displayNameOf(it), isImported = false) }

    private fun ImportedModelRef.toModelInfo() =
        ModelInfo(id = uri, displayName = displayNameOf(name), isImported = true)

    private fun displayNameOf(fileName: String): String =
        fileName.removePrefix("ggml-").removeSuffix(".bin")
}
