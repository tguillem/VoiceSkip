// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.app.Application
import android.content.res.AssetManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.source.ImportedModelDocumentDataSource
import com.voiceskip.data.source.ImportedModelRef
import com.voiceskip.data.source.ImportedModelStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryImplTest {

    private enum class PermissionOperation {
        PERSIST,
        RELEASE
    }

    private lateinit var store: FakeImportedModelStore
    private lateinit var documentDataSource: FakeImportedModelDocumentDataSource
    private lateinit var repository: ModelRepositoryImpl
    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeImportedModelStore : ImportedModelStore {
        private val _models = MutableStateFlow<List<ImportedModelRef>>(emptyList())
        override val models: Flow<List<ImportedModelRef>> = _models
        var addFailure: Exception? = null
        var removeFailure: Exception? = null

        override suspend fun add(ref: ImportedModelRef) {
            addFailure?.let { throw it }
            _models.value = _models.value.filterNot { it.uri == ref.uri } + ref
        }

        override suspend fun remove(uri: String) {
            removeFailure?.let { throw it }
            _models.value = _models.value.filterNot { it.uri == uri }
        }
    }

    private class FakeImportedModelDocumentDataSource :
        ImportedModelDocumentDataSource {

        var displayName: String? = null
        var readable = true
        var readPermissionPersisted = false
        var displayNameFailure: Exception? = null
        var persistFailure: Exception? = null
        var descriptor: ParcelFileDescriptor? = null
        val permissionOperations = mutableListOf<Pair<PermissionOperation, String>>()

        override fun getDisplayName(uri: String): String? {
            displayNameFailure?.let { throw it }
            return displayName
        }

        override fun isReadable(uri: String): Boolean = readable

        override fun hasPersistedReadPermission(uri: String): Boolean =
            readPermissionPersisted

        override fun persistReadPermission(uri: String) {
            persistFailure?.let { throw it }
            permissionOperations += PermissionOperation.PERSIST to uri
            readPermissionPersisted = true
        }

        override fun releaseReadPermission(uri: String) {
            permissionOperations += PermissionOperation.RELEASE to uri
            readPermissionPersisted = false
        }

        override fun openReadFileDescriptor(uri: String): ParcelFileDescriptor? =
            descriptor
    }

    @Before
    fun setup() {
        val mockAssets = mockk<AssetManager> {
            every { list("models") } returns arrayOf(
                "ggml-tiny.bin",
                "ggml-small.bin",
                "ggml-silero-v6.bin"
            )
        }
        val mockApplication = mockk<Application> {
            every { assets } returns mockAssets
        }
        store = FakeImportedModelStore()
        documentDataSource = FakeImportedModelDocumentDataSource()
        repository = ModelRepositoryImpl(
            application = mockApplication,
            store = store,
            documentDataSource = documentDataSource,
            userPreferences = mockk<UserPreferences>(relaxed = true),
            ioDispatcher = dispatcher
        )
    }

    @Test
    fun `availableModels lists bundled models sorted by size, excluding VAD`() {
        val models = repository.availableModels.value

        assertThat(models.map { it.id })
            .containsExactly("ggml-tiny.bin", "ggml-small.bin").inOrder()
        assertThat(models.all { !it.isImported }).isTrue()
        assertThat(models.first().displayName).isEqualTo("tiny")
    }

    @Test
    fun `an imported reference appears as an imported model`() = runTest {
        store.add(ImportedModelRef("content://docs/large", "ggml-large-v3.bin"))

        val imported = repository.availableModels.value.filter { it.isImported }
        assertThat(imported).hasSize(1)
        assertThat(imported.single().id).isEqualTo("content://docs/large")
        assertThat(imported.single().displayName).isEqualTo("large-v3")
    }

    @Test
    fun `deleteModel removes the reference from availableModels`() = runTest {
        store.add(ImportedModelRef("content://docs/large", "ggml-large-v3.bin"))
        assertThat(repository.availableModels.value.any {
            it.id == "content://docs/large"
        }).isTrue()

        val result = repository.deleteModel("content://docs/large")

        assertThat(result.isSuccess).isTrue()
        assertThat(repository.availableModels.value.any {
            it.id == "content://docs/large"
        }).isFalse()
    }

    @Test
    fun `deleteModel releases the read grant for that model`() = runTest {
        val id = "content://docs/large"
        store.add(ImportedModelRef(id, "ggml-large-v3.bin"))
        documentDataSource.readPermissionPersisted = true

        assertThat(repository.deleteModel(id).isSuccess).isTrue()

        assertThat(documentDataSource.permissionOperations)
            .containsExactly(PermissionOperation.RELEASE to id)
        assertThat(documentDataSource.readPermissionPersisted).isFalse()
    }

    @Test
    fun `a successful import registers the model and keeps the grant`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        documentDataSource.displayName = "ggml-custom.bin"

        val id = repository.importModel(uri)

        assertThat(id).isEqualTo(uri.toString())
        assertThat(documentDataSource.permissionOperations)
            .containsExactly(PermissionOperation.PERSIST to uri.toString())
        assertThat(repository.importState.value).isEqualTo(ModelImportState.Idle)
        val imported = repository.availableModels.value.filter { it.isImported }
        assertThat(imported.map { it.id }).containsExactly(uri.toString())
        assertThat(imported.single().displayName).isEqualTo("custom")
    }

    @Test
    fun `a file that is not a bin is rejected before any grant is taken`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        documentDataSource.displayName = "notes.txt"

        val id = repository.importModel(uri)

        assertThat(id).isNull()
        assertThat(repository.importState.value)
            .isEqualTo(ModelImportState.Error(ImportError.NOT_A_BIN_FILE))
        assertThat(documentDataSource.permissionOperations).isEmpty()
        assertThat(repository.availableModels.value.none { it.isImported }).isTrue()
    }

    @Test
    fun `a blank display name is rejected rather than imported unnamed`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        documentDataSource.displayName = null

        val id = repository.importModel(uri)

        assertThat(id).isNull()
        assertThat(repository.importState.value)
            .isEqualTo(ModelImportState.Error(ImportError.NOT_A_BIN_FILE))
        assertThat(documentDataSource.permissionOperations).isEmpty()
    }

    @Test
    fun `failed deletion preserves the reference and its read grant`() = runTest {
        val id = "content://docs/large"
        store.add(ImportedModelRef(id, "ggml-large-v3.bin"))
        store.removeFailure = IOException("DataStore write failed")
        documentDataSource.readPermissionPersisted = true

        val result = repository.deleteModel(id)

        assertThat(result.isFailure).isTrue()
        assertThat(repository.availableModels.value.any { it.id == id }).isTrue()
        assertThat(documentDataSource.permissionOperations).isEmpty()
        assertThat(documentDataSource.readPermissionPersisted).isTrue()
    }

    @Test
    fun `provider failure is exposed as an unreadable import`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        documentDataSource.displayNameFailure =
            SecurityException("Permission revoked")

        val id = repository.importModel(uri)

        assertThat(id).isNull()
        assertThat(repository.importState.value)
            .isEqualTo(ModelImportState.Error(ImportError.UNREADABLE))
    }

    @Test
    fun `registry failure rolls back a newly persisted read grant`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        documentDataSource.displayName = "ggml-custom.bin"
        store.addFailure = IOException("DataStore write failed")

        val id = repository.importModel(uri)

        assertThat(id).isNull()
        assertThat(documentDataSource.permissionOperations.map { it.first })
            .containsExactly(
                PermissionOperation.PERSIST,
                PermissionOperation.RELEASE
            ).inOrder()
        assertThat(documentDataSource.readPermissionPersisted).isFalse()
        assertThat(repository.availableModels.value.none { it.isImported }).isTrue()
        assertThat(repository.importState.value)
            .isEqualTo(ModelImportState.Error(ImportError.UNREADABLE))
    }

    @Test
    fun `registry failure preserves a read grant that already existed`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        documentDataSource.displayName = "ggml-custom.bin"
        documentDataSource.readPermissionPersisted = true
        store.addFailure = IOException("DataStore write failed")

        repository.importModel(uri)

        assertThat(documentDataSource.permissionOperations.map { it.first })
            .containsExactly(PermissionOperation.PERSIST)
        assertThat(documentDataSource.readPermissionPersisted).isTrue()
    }

    @Test
    fun `cancelled registry update rolls back its grant and preserves cancellation`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        documentDataSource.displayName = "ggml-custom.bin"
        store.addFailure = CancellationException("Cancelled")

        val cancellation = try {
            repository.importModel(uri)
            null
        } catch (exception: CancellationException) {
            exception
        }

        assertThat(cancellation).isNotNull()
        assertThat(documentDataSource.permissionOperations.map { it.first })
            .containsExactly(
                PermissionOperation.PERSIST,
                PermissionOperation.RELEASE
            ).inOrder()
        assertThat(documentDataSource.readPermissionPersisted).isFalse()
    }
}
