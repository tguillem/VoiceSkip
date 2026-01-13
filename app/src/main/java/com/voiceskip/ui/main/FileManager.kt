// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.main

import android.content.res.AssetManager
import android.util.Log
import com.voiceskip.util.VoiceSkipLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_TAG = "FileManager"

class FileManager(
    private val modelsPath: File,
    private val samplesPath: File
) {

    suspend fun copyAssets(assets: AssetManager) = withContext(Dispatchers.IO) {
        modelsPath.mkdirs()
        samplesPath.mkdirs()
        copyData(assets, "samples", samplesPath) { fileName ->
            VoiceSkipLogger.logDataCopy(fileName)
        }
        VoiceSkipLogger.d("All data copied to working directory")
    }

    fun getModelsPath(): File = modelsPath

    fun getSamplesPath(): File = samplesPath

    private suspend fun copyData(
        assets: AssetManager,
        assetDirName: String,
        destDir: File,
        onFileCopy: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        assets.list(assetDirName)?.forEach { name ->
            val assetPath = "$assetDirName/$name"
            Log.v(LOG_TAG, "Processing $assetPath...")
            val destination = File(destDir, name)
            Log.v(LOG_TAG, "Copying $assetPath to $destination...")
            onFileCopy(name)
            assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.v(LOG_TAG, "Copied $assetPath to $destination")
        }
    }
}