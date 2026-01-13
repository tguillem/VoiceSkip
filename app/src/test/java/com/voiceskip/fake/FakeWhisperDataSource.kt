// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.fake

import android.content.res.AssetManager
import com.voiceskip.data.source.TranscriptionEvent
import com.voiceskip.data.source.WhisperDataSource
import com.voiceskip.whispercpp.whisper.AudioProvider
import com.voiceskip.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeWhisperDataSource : WhisperDataSource {

    private val _events = MutableSharedFlow<TranscriptionEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    override val events: SharedFlow<TranscriptionEvent> = _events.asSharedFlow()

    var loadModelCalled = false
    var loadModelPath: String? = null
    var loadModelVadPath: String? = null
    var loadModelUseGpu: Boolean? = null
    var loadModelResult: Result<Unit> = Result.success(Unit)
    var loadModelGpuResult: String? = "Test GPU"
    var loadModelShouldEmitLoaded = true

    var startStreamCalled = false
    var startStreamCalls = mutableListOf<StartStreamCall>()
    var stopCalled = false
    var destroyCalled = false

    private var _isTurboEnabled = false
    override val isTurboEnabled: Boolean get() = _isTurboEnabled

    var setTurboModeCalled = false
    var setTurboModeEnabled: Boolean? = null

    var durationMs: Long = 0L

    data class StartStreamCall(
        val audioProvider: AudioProvider,
        val numThreads: Int,
        val language: String?,
        val translate: Boolean,
        val live: Boolean
    )

    override fun loadModel(
        assets: AssetManager,
        modelPath: String,
        vadModelPath: String?,
        useGpu: Boolean
    ) {
        loadModelCalled = true
        loadModelPath = modelPath
        loadModelVadPath = vadModelPath
        loadModelUseGpu = useGpu

        loadModelResult.getOrThrow()

        if (loadModelShouldEmitLoaded) {
            _events.tryEmit(TranscriptionEvent.ModelLoaded(loadModelGpuResult))
        }
    }

    override fun startStream(
        audioProvider: AudioProvider,
        numThreads: Int,
        language: String?,
        translate: Boolean,
        live: Boolean
    ) {
        startStreamCalled = true
        startStreamCalls.add(
            StartStreamCall(audioProvider, numThreads, language, translate, live)
        )
    }

    override fun stop() {
        stopCalled = true
    }

    override fun setDuration(durationMs: Long) {
        this.durationMs = durationMs
    }

    override fun updateLanguage(language: String?) {
    }

    override fun destroy() {
        destroyCalled = true
    }

    override fun setTurboMode(enabled: Boolean, assets: AssetManager, modelPath: String, vadModelPath: String?) {
        setTurboModeCalled = true
        setTurboModeEnabled = enabled
        _isTurboEnabled = enabled
    }

    override fun disableTurboMode() {
        _isTurboEnabled = false
    }

    suspend fun emitEvent(event: TranscriptionEvent) {
        _events.emit(event)
    }

    suspend fun emitSegment(text: String, startMs: Long, endMs: Long, language: String? = null) {
        _events.emit(
            TranscriptionEvent.Segment(
                WhisperSegment(text, startMs, endMs, language),
                language
            )
        )
    }

    suspend fun emitProgress(percent: Int) {
        _events.emit(TranscriptionEvent.Progress(percent))
    }

    suspend fun emitStreamComplete(success: Boolean = true) {
        _events.emit(TranscriptionEvent.StreamComplete(success))
    }

    suspend fun emitError(message: String) {
        _events.emit(TranscriptionEvent.Error(message))
    }

    fun reset() {
        loadModelCalled = false
        loadModelPath = null
        loadModelVadPath = null
        loadModelUseGpu = null
        loadModelResult = Result.success(Unit)
        loadModelGpuResult = "Test GPU"
        loadModelShouldEmitLoaded = true
        startStreamCalled = false
        startStreamCalls.clear()
        stopCalled = false
        destroyCalled = false
        _isTurboEnabled = false
        setTurboModeCalled = false
        setTurboModeEnabled = null
        durationMs = 0L
    }

    fun enableTurboModeForTesting() {
        _isTurboEnabled = true
    }
}
