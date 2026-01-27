// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.voiceskip.R
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.PlaybackState
import com.voiceskip.domain.ModelManager
import com.voiceskip.domain.usecase.AudioListenUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Find the current segment index based on playback position.
 * Adds lookahead to match the seek offset used when clicking segments.
 */
private fun findCurrentSegmentIndex(
    positionMs: Long,
    segments: List<com.voiceskip.whispercpp.whisper.WhisperSegment>
): Int {
    if (segments.isEmpty()) return -1
    val lookAheadMs = positionMs + AudioListenUseCase.SEGMENT_SEEK_OFFSET_MS
    return segments.indexOfLast { lookAheadMs >= it.startMs && lookAheadMs < it.endMs }
        .takeIf { it >= 0 }
        ?: segments.indexOfLast { it.startMs <= lookAheadMs }
}

@Composable
private fun ModelManager.GpuFallbackReason.toMessage(): String = when (this) {
    ModelManager.GpuFallbackReason.CRASH -> stringResource(R.string.msg_gpu_crash)
    ModelManager.GpuFallbackReason.UNAVAILABLE -> stringResource(R.string.msg_gpu_unavailable)
}

@Composable
private fun ModelManager.TurboFallbackReason.toMessage(): String = when (this) {
    ModelManager.TurboFallbackReason.CRASH -> stringResource(R.string.msg_turbo_crash)
}

@Composable
private fun TranscriptionFailureReason.toMessage(): String = when (this) {
    TranscriptionFailureReason.GENERIC_FAILURE -> stringResource(R.string.msg_transcription_failed)
    TranscriptionFailureReason.GPU_FAILURE_RETRYING -> stringResource(R.string.msg_gpu_transcription_failed)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: MainScreenViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingFileUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        pendingFileUri = uri
    }

    LifecycleResumeEffect(pendingFileUri) {
        pendingFileUri?.let { uri ->
            viewModel.handleAction(MainScreenAction.SelectFile(uri))
            pendingFileUri = null
        }
        onPauseOrDispose { }
    }

    LaunchedEffect(uiState.showFileSelector) {
        if (uiState.showFileSelector) {
            filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
            viewModel.onFileSelectorShown()
        }
    }

    uiState.gpuFallbackReason?.let { reason ->
        val message = reason.toMessage()
        LaunchedEffect(reason) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.onGpuFallbackDismissed()
        }
    }

    uiState.turboFallbackReason?.let { reason ->
        val message = reason.toMessage()
        LaunchedEffect(reason) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.onTurboFallbackDismissed()
        }
    }

    uiState.queueLimitStopReason?.let { _ ->
        val message = stringResource(R.string.msg_queue_limit_reached)
        LaunchedEffect(Unit) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    uiState.transcriptionFailureReason?.let { reason ->
        val message = reason.toMessage()
        LaunchedEffect(reason) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.onTranscriptionFailureDismissed()
        }
    }

    val context = LocalContext.current
    LaunchedEffect(uiState.pendingDelete) {
        if (uiState.pendingDelete != null) {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.snackbar_deleted),
                actionLabel = context.getString(R.string.snackbar_undo),
                duration = SnackbarDuration.Short
            )
            when (result) {
                SnackbarResult.ActionPerformed -> viewModel.handleAction(MainScreenAction.UndoDeleteSavedTranscription)
                SnackbarResult.Dismissed -> viewModel.handleAction(MainScreenAction.ConfirmDeleteSavedTranscription)
            }
        }
    }

    MainScreenContent(
        uiState = uiState,
        onAction = viewModel::handleAction,
        onNavigateToSettings = onNavigateToSettings,
        onSelectFile = {
            viewModel.handleAction(MainScreenAction.RequestFileSelection)
        },
        snackbarHostState = snackbarHostState,
        formatText = viewModel::formatSentences
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
private fun MainScreenContent(
    uiState: MainScreenUiState,
    onAction: (MainScreenAction) -> Unit,
    onNavigateToSettings: () -> Unit,
    onSelectFile: () -> Unit,
    snackbarHostState: SnackbarHostState,
    formatText: (String) -> String
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val title = when (uiState.screenState) {
                        is TranscriptionUiState.LiveRecording -> stringResource(R.string.state_live_transcription)
                        else -> uiState.audioFileName ?: stringResource(R.string.app_name)
                    }
                    Text(title)
                },
                actions = {
                    if (uiState.screenState is TranscriptionUiState.Ready ||
                        uiState.screenState is TranscriptionUiState.Complete ||
                        uiState.screenState is TranscriptionUiState.Error) {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.action_settings)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState.screenState) {
                is TranscriptionUiState.Idle,
                is TranscriptionUiState.LoadingModel -> LoadingScreen()

                is TranscriptionUiState.Ready -> ReadyScreen(
                    canTranscribe = uiState.canTranscribe,
                    hasSavedTranscription = uiState.hasSavedTranscription,
                    onToggleRecord = { onAction(MainScreenAction.ToggleRecord) },
                    onSelectFile = onSelectFile,
                    onViewLastTranscription = { onAction(MainScreenAction.ViewLastTranscription) },
                    onDeleteSavedTranscription = { onAction(MainScreenAction.DeleteSavedTranscription) }
                )

                is TranscriptionUiState.LiveRecording,
                is TranscriptionUiState.FinishingTranscription -> RecordingScreen(
                    isFinishing = state is TranscriptionUiState.FinishingTranscription,
                    durationMs = uiState.recordingDurationMs,
                    progress = uiState.transcriptionProgress,
                    segments = uiState.transcriptionSegments,
                    showTimestamps = uiState.listenModeEnabled,
                    detectedLanguage = uiState.detectedLanguage,
                    currentLanguage = uiState.language,
                    onLanguageChange = { language ->
                        onAction(MainScreenAction.ChangeLanguage(language))
                    },
                    onStopOrCancel = { onAction(MainScreenAction.ToggleRecord) },
                    formatText = formatText
                )

                is TranscriptionUiState.Transcribing -> {
                    TranscribingScreen(
                        progress = uiState.transcriptionProgress,
                        segments = uiState.transcriptionSegments,
                        isComplete = false,
                        detectedLanguage = uiState.detectedLanguage,
                        currentLanguage = uiState.language,
                        onLanguageChange = { language ->
                            onAction(MainScreenAction.ChangeLanguage(language))
                        },
                        onStop = {
                            onAction(MainScreenAction.StopTranscription)
                        },
                        formatText = formatText,
                        listenModeEnabled = uiState.listenModeEnabled,
                        listenModeAvailable = uiState.listenModeAvailable,
                        onToggleListenMode = { enabled ->
                            onAction(MainScreenAction.ToggleListenMode(enabled))
                        },
                        playbackState = uiState.playbackState,
                        onPlayPause = { onAction(MainScreenAction.PlayPause) },
                        onSeek = { positionMs -> onAction(MainScreenAction.SeekTo(positionMs)) },
                        onSegmentClick = { segment -> onAction(MainScreenAction.SeekToSegment(segment)) }
                    )
                }

                is TranscriptionUiState.Complete -> {
                    BackHandler { onAction(MainScreenAction.ClearResult) }
                    uiState.transcriptionResult?.let { result ->
                        TranscribingScreen(
                            progress = 100,
                            segments = uiState.transcriptionSegments,
                            isComplete = true,
                            transcriptionResult = result,
                            onDelete = if (uiState.hasSavedTranscription) {
                                { onAction(MainScreenAction.DeleteSavedTranscription) }
                            } else null,
                            formatText = formatText,
                            listenModeEnabled = uiState.listenModeEnabled,
                            listenModeAvailable = uiState.listenModeAvailable,
                            onToggleListenMode = { enabled ->
                                onAction(MainScreenAction.ToggleListenMode(enabled))
                            },
                            playbackState = uiState.playbackState,
                            onPlayPause = { onAction(MainScreenAction.PlayPause) },
                            onSeek = { positionMs -> onAction(MainScreenAction.SeekTo(positionMs)) },
                            onSegmentClick = { segment -> onAction(MainScreenAction.SeekToSegment(segment)) }
                        )
                    }
                }

                is TranscriptionUiState.Error -> {
                    BackHandler { onAction(MainScreenAction.ClearResult) }
                    val message = state.message
                    ErrorScreen(message = message)
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.state_loading),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ReadyScreen(
    canTranscribe: Boolean,
    hasSavedTranscription: Boolean,
    onToggleRecord: () -> Unit,
    onSelectFile: () -> Unit,
    onViewLastTranscription: () -> Unit,
    onDeleteSavedTranscription: () -> Unit
) {
    val micPermissionState = rememberPermissionState(
        permission = android.Manifest.permission.RECORD_AUDIO
    )

    var startRecordingAfterPermission by remember { mutableStateOf(false) }

    LaunchedEffect(micPermissionState.status.isGranted) {
        if (startRecordingAfterPermission && micPermissionState.status.isGranted) {
            startRecordingAfterPermission = false
            onToggleRecord()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ActionCard(
            icon = Icons.Default.Mic,
            title = stringResource(R.string.action_record),
            description = stringResource(R.string.desc_record),
            enabled = canTranscribe,
            onClick = {
                if (!micPermissionState.status.isGranted) {
                    startRecordingAfterPermission = true
                    micPermissionState.launchPermissionRequest()
                } else {
                    onToggleRecord()
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        ActionCard(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.action_select_file),
            description = stringResource(R.string.desc_select_file),
            enabled = canTranscribe,
            onClick = onSelectFile
        )

        Spacer(modifier = Modifier.height(16.dp))

        ActionCard(
            icon = Icons.Default.History,
            title = stringResource(R.string.action_view_last),
            description = stringResource(R.string.desc_view_last),
            enabled = hasSavedTranscription,
            onClick = onViewLastTranscription,
            actionIcon = Icons.Default.Delete,
            onActionClick = onDeleteSavedTranscription
        )

        Spacer(modifier = Modifier.height(24.dp))

        TipCard(
            text = stringResource(R.string.tip_share_desc)
        )
    }
}

@Composable
private fun TipCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.5f
                    )
                )
            }

            if (actionIcon != null && onActionClick != null && enabled) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(1.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                IconButton(onClick = onActionClick) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.5f
                    )
                )
            }
        }
    }
}

@Composable
private fun TranscribingScreen(
    progress: Int,
    segments: List<com.voiceskip.whispercpp.whisper.WhisperSegment>,
    isComplete: Boolean,
    detectedLanguage: String? = null,
    currentLanguage: String = UserPreferences.LANGUAGE_AUTO,
    onLanguageChange: ((String) -> Unit)? = null,
    transcriptionResult: TranscriptionResult? = null,
    onStop: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    formatText: (String) -> String = { it },
    listenModeEnabled: Boolean = false,
    listenModeAvailable: Boolean = false,
    onToggleListenMode: ((Boolean) -> Unit)? = null,
    playbackState: PlaybackState = PlaybackState(),
    onPlayPause: (() -> Unit)? = null,
    onSeek: ((Long) -> Unit)? = null,
    onSegmentClick: ((com.voiceskip.whispercpp.whisper.WhisperSegment) -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (segments.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                SegmentsContent(
                    segments = segments,
                    showTimestamps = listenModeEnabled,
                    isComplete = isComplete,
                    formatText = formatText,
                    listenModeEnabled = listenModeEnabled,
                    playbackPositionMs = playbackState.currentPositionMs,
                    onSegmentClick = if (listenModeEnabled) onSegmentClick else null
                )
            }
        } else if (!isComplete) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.msg_transcription_delay_file),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (!isComplete) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SegmentedProgressBar(
                            progress = progress,
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                        )
                        Text(
                            text = "$progress%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.width(36.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onLanguageChange != null) {
                            LanguageDropdown(
                                currentLanguage = currentLanguage,
                                detectedLanguage = detectedLanguage,
                                onLanguageChange = onLanguageChange
                            )
                        }

                        if (listenModeAvailable && onToggleListenMode != null) {
                            ListenModeChip(
                                enabled = listenModeEnabled,
                                onToggle = { onToggleListenMode(!listenModeEnabled) }
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (onStop != null) {
                            FilledIconButton(
                                onClick = onStop,
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = stringResource(R.string.button_stop_short),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                }
            }

            if (listenModeEnabled && listenModeAvailable && onPlayPause != null && onSeek != null) {
                PlaybackControlsCard(
                    playbackState = playbackState,
                    segments = segments,
                    transcriptionProgress = progress,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onSegmentClick = onSegmentClick
                )
            }
        }

        if (isComplete && transcriptionResult != null) {
            val context = LocalContext.current

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${transcriptionResult.audioLengthFormatted} of audio completed in ${transcriptionResult.durationFormatted}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "${transcriptionResult.text.length} characters • ${UserPreferences.getLanguageDisplayName(transcriptionResult.detectedLanguage)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }

                    if (listenModeAvailable && onToggleListenMode != null) {
                        ListenModeChip(
                            enabled = listenModeEnabled,
                            onToggle = { onToggleListenMode(!listenModeEnabled) }
                        )
                    }
                }
                }
            }

            if (listenModeEnabled && listenModeAvailable && onPlayPause != null && onSeek != null) {
                PlaybackControlsCard(
                    playbackState = playbackState,
                    segments = segments,
                    transcriptionProgress = 100,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onSegmentClick = onSegmentClick
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_delete))
                    }
                }

                OutlinedButton(
                    onClick = {
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, formatText(transcriptionResult.text))
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_transcription)))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.button_share))
                }
            }
        }
    }
}

@Composable
private fun PlaybackControlsCard(
    playbackState: PlaybackState,
    segments: List<com.voiceskip.whispercpp.whisper.WhisperSegment>,
    transcriptionProgress: Int,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSegmentClick: ((com.voiceskip.whispercpp.whisper.WhisperSegment) -> Unit)?
) {
    Spacer(modifier = Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
            PlaybackControlsRow(
                playbackState = playbackState,
                segments = segments,
                transcriptionProgress = transcriptionProgress,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onSegmentClick = onSegmentClick
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackControlsRow(
    playbackState: PlaybackState,
    segments: List<com.voiceskip.whispercpp.whisper.WhisperSegment>,
    transcriptionProgress: Int = 100,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSegmentClick: ((com.voiceskip.whispercpp.whisper.WhisperSegment) -> Unit)? = null
) {
    val durationMs = playbackState.durationMs
    val currentPositionMs = playbackState.currentPositionMs
    val isPlaying = playbackState.isPlaying
    val isPrepared = playbackState.isPrepared

    val currentSegmentIndex = remember(currentPositionMs, segments) {
        findCurrentSegmentIndex(currentPositionMs, segments)
    }

    var sliderPosition by remember { mutableStateOf(currentPositionMs.toFloat()) }
    var isSliding by remember { mutableStateOf(false) }

    LaunchedEffect(currentPositionMs, isSliding) {
        if (!isSliding) {
            sliderPosition = currentPositionMs.toFloat()
        }
    }

    val onContainerColor = MaterialTheme.colorScheme.onPrimaryContainer
    val primaryColor = MaterialTheme.colorScheme.primary
    val disabledColor = onContainerColor.copy(alpha = 0.38f)
    val untranscribedColor = onContainerColor.copy(alpha = 0.12f)
    val transcribedColor = onContainerColor.copy(alpha = 0.3f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                if (currentSegmentIndex > 0 && segments.isNotEmpty()) {
                    onSegmentClick?.invoke(segments[currentSegmentIndex - 1])
                }
            },
            enabled = isPrepared && currentSegmentIndex > 0,
            modifier = Modifier.size(40.dp)  // 48dp touch target via padding
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.listen_previous_segment),
                modifier = Modifier.size(20.dp),
                tint = if (isPrepared && currentSegmentIndex > 0) onContainerColor else disabledColor
            )
        }

        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(36.dp),
            enabled = isPrepared
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.listen_pause else R.string.listen_play
                ),
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(
            onClick = {
                if (currentSegmentIndex < segments.size - 1 && segments.isNotEmpty()) {
                    onSegmentClick?.invoke(segments[currentSegmentIndex + 1])
                }
            },
            enabled = isPrepared && currentSegmentIndex < segments.size - 1,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.listen_next_segment),
                modifier = Modifier.size(20.dp),
                tint = if (isPrepared && currentSegmentIndex < segments.size - 1) onContainerColor else disabledColor
            )
        }

        Text(
            text = formatDuration(if (isSliding) sliderPosition.toLong() else currentPositionMs),
            style = MaterialTheme.typography.labelSmall,
            color = onContainerColor
        )

        val transcribedFraction = transcriptionProgress / 100f
        val playedFraction = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f

        Slider(
            value = if (isSliding) sliderPosition else currentPositionMs.toFloat(),
            onValueChange = { newValue ->
                isSliding = true
                sliderPosition = newValue
            },
            onValueChangeFinished = {
                onSeek(sliderPosition.toLong())
                isSliding = false
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            enabled = isPrepared,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            thumb = {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (isPrepared) primaryColor else disabledColor,
                                shape = CircleShape
                            )
                    )
                }
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    color = untranscribedColor,
                                    shape = CircleShape
                                )
                        )
                        if (transcribedFraction > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(transcribedFraction)
                                    .fillMaxHeight()
                                    .background(
                                        color = transcribedColor,
                                        shape = CircleShape
                                    )
                            )
                        }
                        if (playedFraction > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(playedFraction)
                                    .fillMaxHeight()
                                    .background(
                                        color = if (isPrepared) primaryColor else disabledColor,
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        )

        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = onContainerColor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenModeChip(
    enabled: Boolean,
    onToggle: () -> Unit
) {
    FilterChip(
        selected = enabled,
        onClick = onToggle,
        label = {
            Text(
                text = stringResource(if (enabled) R.string.listen_mode_active else R.string.listen_mode),
                style = MaterialTheme.typography.labelSmall
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Headphones,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    )
}

@Composable
private fun SegmentRow(
    segment: com.voiceskip.whispercpp.whisper.WhisperSegment,
    showTimestamps: Boolean,
    isHighlighted: Boolean = false,
    isClickable: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val backgroundColor = if (isHighlighted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .then(
                if (isClickable && onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        if (showTimestamps) {
            Text(
                text = segment.startTimeFormatted,
                style = MaterialTheme.typography.labelSmall,
                color = if (isHighlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Text(
            text = segment.text.trim(),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ErrorScreen(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.error_generic),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun RecordingScreen(
    isFinishing: Boolean,
    durationMs: Long,
    progress: Int,
    segments: List<com.voiceskip.whispercpp.whisper.WhisperSegment>,
    showTimestamps: Boolean,
    detectedLanguage: String?,
    currentLanguage: String,
    onLanguageChange: (String) -> Unit,
    onStopOrCancel: () -> Unit,
    formatText: (String) -> String = { it }
) {
    var pulseState by remember { mutableStateOf(false) }
    LaunchedEffect(isFinishing) {
        if (!isFinishing) {
            while (true) {
                pulseState = !pulseState
                delay(500)
            }
        }
    }

    val cardColor = if (isFinishing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }

    val contentColor = if (isFinishing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (segments.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                SegmentsContent(segments = segments, showTimestamps = showTimestamps, isComplete = false, formatText = formatText)
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.msg_transcription_delay),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                if (isFinishing) {
                    SegmentedProgressBar(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        filledColor = contentColor,
                        emptyColor = contentColor.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isFinishing) {
                            Icon(
                                imageVector = Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                tint = Color.Red.copy(alpha = if (pulseState) 1f else 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Column {
                            Text(
                                text = stringResource(if (isFinishing) R.string.state_finishing else R.string.state_recording),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                            Text(
                                text = if (isFinishing && progress > 0) "$progress%" else formatDuration(durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.7f)
                            )
                        }
                    }

                    FilledIconButton(
                        onClick = onStopOrCancel,
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(
                            if (isFinishing) Icons.Default.Close else Icons.Default.Stop,
                            contentDescription = stringResource(if (isFinishing) R.string.button_cancel else R.string.button_stop_short),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                LanguageDropdown(
                    currentLanguage = currentLanguage,
                    detectedLanguage = detectedLanguage,
                    onLanguageChange = onLanguageChange,
                    enabled = !isFinishing
                )
            }
        }
    }
}

@Composable
private fun SegmentsContent(
    segments: List<com.voiceskip.whispercpp.whisper.WhisperSegment>,
    showTimestamps: Boolean,
    isComplete: Boolean,
    formatText: (String) -> String = { it },
    listenModeEnabled: Boolean = false,
    playbackPositionMs: Long = 0,
    onSegmentClick: ((com.voiceskip.whispercpp.whisper.WhisperSegment) -> Unit)? = null
) {
    val currentSegmentIndex = remember(playbackPositionMs, segments, listenModeEnabled) {
        if (!listenModeEnabled) -1
        else findCurrentSegmentIndex(playbackPositionMs, segments)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // Auto-scroll to current segment during playback (show previous segment above)
        LaunchedEffect(currentSegmentIndex, listenModeEnabled) {
            if (listenModeEnabled && currentSegmentIndex >= 0) {
                val scrollToIndex = maxOf(0, currentSegmentIndex - 1)
                listState.animateScrollToItem(scrollToIndex)
            }
        }

        val isAtBottom by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val lastItem = layoutInfo.visibleItemsInfo.lastOrNull()
                    ?: return@derivedStateOf true
                val isLastItemVisible = lastItem.index == layoutInfo.totalItemsCount - 1
                val isBottomVisible = lastItem.offset + lastItem.size <= layoutInfo.viewportEndOffset
                isLastItemVisible && isBottomVisible
            }
        }

        val showNewContentIndicator = !isAtBottom && !isComplete && segments.isNotEmpty() && !listenModeEnabled

        Box(modifier = Modifier.fillMaxSize()) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 48.dp)
                ) {
                    if (showTimestamps) {
                        items(segments.size, key = { segments[it].startMs }) { index ->
                            val segment = segments[index]
                            val isCurrentSegment = index == currentSegmentIndex
                            SegmentRow(
                                segment = segment,
                                showTimestamps = true,
                                isHighlighted = isCurrentSegment && listenModeEnabled,
                                isClickable = onSegmentClick != null,
                                onClick = { onSegmentClick?.invoke(segment) }
                            )
                        }
                    } else {
                        item {
                            val fullText = segments.joinToString(" ") { it.text.trim() }
                            Text(
                                text = formatText(fullText),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            if (showNewContentIndicator) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .clickable {
                            scope.launch {
                                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                                if (lastIndex >= 0) {
                                    listState.animateScrollToItem(lastIndex, Int.MAX_VALUE)
                                }
                            }
                        },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.msg_scroll_to_bottom),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.msg_new_content),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    currentLanguage: String,
    detectedLanguage: String?,
    onLanguageChange: (String) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    val languagesWithAbbreviations = UserPreferences.getLanguageListWithAbbreviations()

    val chipLabel = if (currentLanguage == UserPreferences.LANGUAGE_AUTO && detectedLanguage != null) {
        UserPreferences.getLanguageDisplayName(detectedLanguage)
    } else {
        UserPreferences.getLanguageDisplayName(currentLanguage)
    }

    Box {
        FilterChip(
            selected = currentLanguage != UserPreferences.LANGUAGE_AUTO,
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            label = {
                Text(
                    text = chipLabel,
                    style = MaterialTheme.typography.labelSmall
                )
            },
            leadingIcon = if (currentLanguage == UserPreferences.LANGUAGE_AUTO && detectedLanguage != null) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else null,
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languagesWithAbbreviations.forEach { (code, _) ->
                DropdownMenuItem(
                    text = { Text(UserPreferences.getLanguageDisplayName(code)) },
                    onClick = {
                        if (code != currentLanguage) {
                            onLanguageChange(code)
                        }
                        expanded = false
                    },
                    leadingIcon = if (code == currentLanguage) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * Old-school segmented progress bar - discrete blocks.
 * When progress is 0: bouncing block animation (indeterminate).
 * When progress > 0: static filled segments (no animation to reduce GPU contention).
 */
@Composable
private fun SegmentedProgressBar(
    progress: Int,
    modifier: Modifier = Modifier,
    segmentCount: Int = 20,
    filledColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    emptyColor: Color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
) {
    val isIndeterminate = progress == 0

    val infiniteTransition = rememberInfiniteTransition(label = "progress")
    val animatedPosition by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = segmentCount.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    val bouncingSegment = animatedPosition.toInt().coerceIn(0, segmentCount - 1)
    val filledSegments = (progress * segmentCount / 100).coerceIn(0, segmentCount)

    Canvas(modifier = modifier) {
        val segmentWidth = size.width / segmentCount
        val gap = 2.dp.toPx()

        for (i in 0 until segmentCount) {
            val isFilled = if (isIndeterminate) i == bouncingSegment else i < filledSegments
            drawRect(
                color = if (isFilled) filledColor else emptyColor,
                topLeft = androidx.compose.ui.geometry.Offset(i * segmentWidth + gap / 2, 0f),
                size = androidx.compose.ui.geometry.Size(segmentWidth - gap, size.height)
            )
        }
    }
}
