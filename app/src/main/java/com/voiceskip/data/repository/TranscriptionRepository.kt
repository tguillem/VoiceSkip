// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.content.res.AssetManager
import android.net.Uri
import com.voiceskip.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface TranscriptionRepository {
    val state: StateFlow<TranscriptionState>

    val segments: StateFlow<List<WhisperSegment>>
    val progress: StateFlow<Int>
    val sessionLanguage: StateFlow<String?>

    suspend fun startRecording(language: String? = null)
    fun stopRecording()
    fun cancelRecording()

    suspend fun transcribeFile(file: File)
    suspend fun transcribeUri(uri: Uri, language: String? = null)
    fun cancelTranscription()

    fun clearState()

    fun restoreCompletedState(
        text: String,
        segments: List<WhisperSegment>,
        detectedLanguage: String?,
        audioLengthMs: Int,
        processingTimeMs: Long,
        audioUri: Uri? = null
    )

    suspend fun loadModel(assets: AssetManager, modelPath: String, vadModelPath: String?, useGpu: Boolean = true, forceReload: Boolean = false): Result<String?>
    suspend fun loadTurboModel(assets: AssetManager, modelPath: String, vadModelPath: String?): Result<Unit>
    suspend fun unloadTurboModel(): Result<Unit>
    fun isTurboModelLoaded(): Boolean
    suspend fun stopTranscription()
    fun getCurrentTranscriptionSource(): TranscriptionSource?
    fun setSessionLanguage(language: String?)
    fun updateLanguage(language: String?)
    fun isModelLoaded(): Boolean
}

sealed class TranscriptionSource {
    data class FileUri(val uri: android.net.Uri) : TranscriptionSource()
    object Recording : TranscriptionSource()
}
