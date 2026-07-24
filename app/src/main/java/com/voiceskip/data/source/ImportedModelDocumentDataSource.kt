// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.source

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Documents are addressed by their URI string rather than a [Uri]: the registry persists the
 * string, and keeping the parse on this side of the boundary is what lets the repository be
 * exercised without the Android framework.
 */
interface ImportedModelDocumentDataSource {
    fun getDisplayName(uri: String): String?
    fun isReadable(uri: String): Boolean
    fun hasPersistedReadPermission(uri: String): Boolean
    fun persistReadPermission(uri: String)
    fun releaseReadPermission(uri: String)
    fun openReadFileDescriptor(uri: String): ParcelFileDescriptor?
}

@Singleton
class ImportedModelDocumentDataSourceImpl @Inject constructor(
    application: Application
) : ImportedModelDocumentDataSource {

    private val contentResolver = application.contentResolver

    override fun getDisplayName(uri: String): String? =
        contentResolver.query(
            Uri.parse(uri),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }

    override fun isReadable(uri: String): Boolean =
        openReadFileDescriptor(uri)?.use { true } ?: false

    override fun hasPersistedReadPermission(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        return contentResolver.persistedUriPermissions.any {
            it.uri == parsed && it.isReadPermission
        }
    }

    override fun persistReadPermission(uri: String) {
        contentResolver.takePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    override fun releaseReadPermission(uri: String) {
        contentResolver.releasePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    override fun openReadFileDescriptor(uri: String): ParcelFileDescriptor? =
        contentResolver.openFileDescriptor(Uri.parse(uri), "r")
}
