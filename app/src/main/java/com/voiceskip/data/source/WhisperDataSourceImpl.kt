// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.source

import android.content.res.AssetManager
import com.voiceskip.whispercpp.whisper.AudioProvider
import com.voiceskip.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class WhisperDataSourceImpl : WhisperDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<TranscriptionEvent>(
        replay = 0,
        extraBufferCapacity = 128
    )
    override val events: SharedFlow<TranscriptionEvent> = _events.asSharedFlow()

    private val whisperContext: WhisperContext = WhisperContext.create(
        onProgress = { progress -> scope.launch { _events.emit(TranscriptionEvent.Progress(progress)) } },
        onLoaded = { slotIndex, gpuInfo ->
            val isTurbo = slotIndex == 1
            scope.launch { _events.emit(TranscriptionEvent.ModelLoaded(gpuInfo, turbo = isTurbo)) }
        },
        onSegment = { segment ->
            scope.launch { _events.emit(TranscriptionEvent.Segment(segment, segment.language)) }
        },
        onStreamComplete = { success ->
            scope.launch { _events.emit(TranscriptionEvent.StreamComplete(success)) }
        },
        onError = { errorMessage ->
            scope.launch { _events.emit(TranscriptionEvent.Error(errorMessage)) }
        }
    )

    private var _isTurboEnabled = false
    override val isTurboEnabled: Boolean get() = _isTurboEnabled

    override fun loadModel(
        assets: AssetManager,
        modelPath: String,
        vadModelPath: String?,
        useGpu: Boolean
    ) {
        whisperContext.loadModel(assets, modelPath, vadModelPath, useGpu)
    }

    override fun setTurboMode(enabled: Boolean, assets: AssetManager, modelPath: String, vadModelPath: String?) {
        if (enabled) {
            whisperContext.loadSecondModel(assets, modelPath, vadModelPath)
            _isTurboEnabled = true
        } else {
            disableTurboMode()
        }
    }

    override fun disableTurboMode() {
        whisperContext.unloadSecondModel()
        _isTurboEnabled = false
    }

    override fun startStream(
        audioProvider: AudioProvider,
        numThreads: Int,
        language: String?,
        translate: Boolean,
        live: Boolean
    ) {
        whisperContext.startStream(
            audioProvider = audioProvider,
            numThreads = numThreads,
            language = language,
            translate = translate,
            live = live
        )
    }

    override fun stop() {
        whisperContext.stop()
    }

    override fun setDuration(durationMs: Long) {
        whisperContext.setDuration(durationMs)
    }

    override fun updateLanguage(language: String?) {
        whisperContext.updateLanguage(language)
    }

    override fun destroy() {
        whisperContext.stop()
        whisperContext.destroy()
    }
}
