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

    private val turboCommands = TurboModeCommandCoordinator()

    private val whisperContext: WhisperContext = WhisperContext.create(
        onProgress = { progress -> scope.launch { _events.emit(TranscriptionEvent.Progress(progress)) } },
        onLoaded = { slotIndex, gpuInfo ->
            val isTurbo = slotIndex == 1
            if (isTurbo) {
                turboCommands.onModelLoaded()
            }
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

    override val isTurboEnabled: Boolean
        get() = turboCommands.isLoaded

    override fun loadModel(
        assets: AssetManager,
        modelPath: String?,
        vadModelPath: String?,
        useGpu: Boolean,
        modelFd: Int
    ) {
        whisperContext.loadModel(assets, modelPath, vadModelPath, useGpu, modelFd)
    }

    override fun setTurboMode(enabled: Boolean, assets: AssetManager, modelPath: String?, vadModelPath: String?, modelFd: Int) {
        if (enabled) {
            turboCommands.enqueueLoad {
                whisperContext.loadSecondModel(assets, modelPath, vadModelPath, modelFd)
            }
        } else {
            disableTurboMode()
        }
    }

    override fun disableTurboMode() {
        turboCommands.enqueueUnload {
            whisperContext.unloadSecondModel()
        }
    }

    override fun startStream(
        audioProvider: AudioProvider,
        threadCounts: TranscriptionThreadCounts,
        language: String?,
        translate: Boolean,
        live: Boolean,
        vadEnabled: Boolean
    ) {
        turboCommands.enqueueStart(threadCounts) { numThreads ->
            whisperContext.startStream(
                audioProvider = audioProvider,
                numThreads = numThreads,
                language = language,
                translate = translate,
                live = live,
                vadEnabled = vadEnabled
            )
        }
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

internal class TurboModeCommandCoordinator {
    private val lock = Any()
    private var requested = false
    private var loaded = false

    val isLoaded: Boolean
        get() = synchronized(lock) { loaded }

    fun onModelLoaded() {
        synchronized(lock) {
            // Native can still deliver a load callback after an unload was queued.
            if (requested) {
                loaded = true
            }
        }
    }

    fun enqueueLoad(command: () -> Unit) {
        enqueueModeChange(requested = true, command = command)
    }

    fun enqueueUnload(command: () -> Unit) {
        enqueueModeChange(requested = false, command = command)
    }

    fun enqueueStart(
        threadCounts: TranscriptionThreadCounts,
        command: (Int) -> Unit
    ) {
        synchronized(lock) {
            command(if (requested) threadCounts.turbo else threadCounts.standard)
        }
    }

    private fun enqueueModeChange(requested: Boolean, command: () -> Unit) {
        synchronized(lock) {
            val previousRequested = this.requested
            val previousLoaded = loaded
            this.requested = requested
            loaded = false
            try {
                command()
            } catch (throwable: Throwable) {
                this.requested = previousRequested
                loaded = previousLoaded
                throw throwable
            }
        }
    }
}
