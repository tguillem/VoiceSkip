// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.source

import android.content.res.AssetManager
import com.voiceskip.whispercpp.whisper.AudioProvider
import com.voiceskip.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.flow.SharedFlow

sealed class TranscriptionEvent {
    data class ModelLoaded(val gpuInfo: String?, val turbo: Boolean = false) : TranscriptionEvent()
    data class Segment(val segment: WhisperSegment, val language: String?) : TranscriptionEvent()
    data class Progress(val percent: Int) : TranscriptionEvent()
    data class StreamComplete(val success: Boolean) : TranscriptionEvent()
    data class Error(val message: String) : TranscriptionEvent()
}

interface WhisperDataSource {
    val events: SharedFlow<TranscriptionEvent>

    fun loadModel(
        assets: AssetManager,
        modelPath: String,
        vadModelPath: String? = null,
        useGpu: Boolean = true
    )

    fun startStream(
        audioProvider: AudioProvider,
        numThreads: Int,
        language: String?,
        translate: Boolean,
        live: Boolean = false
    )

    fun stop()
    fun setDuration(durationMs: Long)
    fun updateLanguage(language: String?)
    fun destroy()

    val isTurboEnabled: Boolean
    fun setTurboMode(enabled: Boolean, assets: AssetManager, modelPath: String, vadModelPath: String?)
    fun disableTurboMode()
}
