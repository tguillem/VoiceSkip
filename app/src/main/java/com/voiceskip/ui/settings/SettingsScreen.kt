// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.voiceskip.R
import com.voiceskip.ui.theme.Spacing
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.voiceskip.data.UserPreferences
import com.voiceskip.domain.ModelManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
private fun ModelManager.GpuFallbackReason.toMessage(): String = when (this) {
    ModelManager.GpuFallbackReason.CRASH -> stringResource(R.string.msg_gpu_crash)
    ModelManager.GpuFallbackReason.UNAVAILABLE -> stringResource(R.string.msg_gpu_unavailable)
}

@Composable
private fun ModelManager.TurboFallbackReason.toMessage(): String = when (this) {
    ModelManager.TurboFallbackReason.CRASH -> stringResource(R.string.msg_turbo_crash)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

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

    val notificationPermissionState = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(permission = android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.large)
        ) {
            SectionHeader(title = stringResource(R.string.settings_section_transcription))
            Spacer(modifier = Modifier.height(Spacing.small))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.large),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_show_timestamps),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(Spacing.extraSmall))
                        Text(
                            text = stringResource(R.string.settings_show_timestamps_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.showTimestamps,
                        onCheckedChange = { viewModel.setShowTimestamps(it) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.medium))
            DefaultLanguageSelector(
                selectedLanguage = uiState.defaultLanguage,
                onLanguageSelected = { viewModel.setDefaultLanguage(it) }
            )

            Spacer(modifier = Modifier.height(Spacing.extraLarge))

            SectionHeader(title = stringResource(R.string.settings_section_notifications))
            Spacer(modifier = Modifier.height(Spacing.small))
            NotificationSettingsCard(
                notificationPermissionState = notificationPermissionState,
                onRequestPermission = {
                    notificationPermissionState?.launchPermissionRequest()
                }
            )

            Spacer(modifier = Modifier.height(Spacing.extraLarge))

            SectionHeader(title = stringResource(R.string.settings_section_model))
            Spacer(modifier = Modifier.height(Spacing.small))
            ModelSelector(
                selectedModel = uiState.model,
                onModelSelected = { viewModel.setModel(it) }
            )

            Spacer(modifier = Modifier.height(Spacing.extraLarge))

            SectionHeader(title = stringResource(R.string.settings_section_performance))
            Spacer(modifier = Modifier.height(Spacing.small))
            GpuSelector(
                gpuEnabled = uiState.gpuEnabled,
                gpuStatus = uiState.gpuStatus,
                onGpuEnabledChanged = { viewModel.setGpuEnabled(it) }
            )

            if (uiState.gpuStatus is GpuStatus.Active && UserPreferences.hasEnoughCoresForTurbo()) {
                Spacer(modifier = Modifier.height(Spacing.medium))

                TurboModeSelector(
                    turboModeEnabled = uiState.turboModeEnabled,
                    cpuThreads = remember { UserPreferences.getTurboCpuThreads() },
                    onTurboModeChanged = { viewModel.setTurboModeEnabled(it) }
                )
            }

            if (!uiState.gpuEnabled) {
                Spacer(modifier = Modifier.height(Spacing.medium))

                ThreadCountSelector(
                    gpuEnabled = uiState.gpuEnabled,
                    numThreads = uiState.numThreads,
                    onThreadsChanged = { viewModel.setNumThreads(it) }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.extraLarge))

            SectionHeader(title = stringResource(R.string.settings_section_about))
            Spacer(modifier = Modifier.height(Spacing.small))
            AboutSection()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ModelSelector(
    selectedModel: String,
    onModelSelected: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    fun extractModelName(filename: String): String {
        return filename.removePrefix("ggml-").removeSuffix(".bin")
    }

    val allModels = remember {
        UserPreferences.getAvailableModelNames(context)
    }

    val labelFaster = stringResource(R.string.settings_model_faster)
    val labelBalanced = stringResource(R.string.settings_model_balanced)
    val labelPrecise = stringResource(R.string.settings_model_precise)

    val models = allModels.mapIndexed { index, modelFile ->
        val label = when (index) {
            0 -> labelFaster
            allModels.size - 1 -> labelPrecise
            else -> labelBalanced
        }
        modelFile to label
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large)
        ) {
            models.forEach { (modelFile, mainLabel) ->
                val modelName = extractModelName(modelFile)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = selectedModel == modelFile,
                        onClick = { onModelSelected(modelFile) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = mainLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.settings_model_name, modelName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GpuSelector(
    gpuEnabled: Boolean,
    gpuStatus: GpuStatus,
    onGpuEnabledChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_gpu_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(Spacing.extraSmall))
                Text(
                    text = when (gpuStatus) {
                        is GpuStatus.Loading -> stringResource(R.string.settings_gpu_loading)
                        is GpuStatus.Active -> stringResource(R.string.settings_gpu_device, gpuStatus.deviceInfo)
                        is GpuStatus.Disabled -> stringResource(R.string.settings_gpu_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = gpuEnabled,
                onCheckedChange = onGpuEnabledChanged
            )
        }
    }
}

@Composable
private fun TurboModeSelector(
    turboModeEnabled: Boolean,
    cpuThreads: Int,
    onTurboModeChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_turbo_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(Spacing.extraSmall))
                Text(
                    text = stringResource(
                        if (turboModeEnabled) R.string.settings_turbo_desc_enabled
                        else R.string.settings_turbo_desc,
                        cpuThreads
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = turboModeEnabled,
                onCheckedChange = onTurboModeChanged
            )
        }
    }
}

@Composable
private fun ThreadCountSelector(
    gpuEnabled: Boolean,
    numThreads: Int,
    onThreadsChanged: (Int) -> Unit
) {
    val maxThreads = remember { UserPreferences.getMaxThreads() }
    val defaultThreads = remember(gpuEnabled) { UserPreferences.getDefaultNumThreads(gpuEnabled) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_threads_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(Spacing.extraSmall))
                    Text(
                        text = stringResource(
                            if (gpuEnabled) R.string.settings_threads_desc_gpu
                            else R.string.settings_threads_desc_cpu
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "$numThreads",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(Spacing.large))

            Slider(
                value = numThreads.toFloat(),
                onValueChange = { onThreadsChanged(it.roundToInt()) },
                valueRange = 1f..maxThreads.toFloat(),
                steps = (maxThreads - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.settings_threads_default, defaultThreads),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$maxThreads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun NotificationSettingsCard(
    notificationPermissionState: com.google.accompanist.permissions.PermissionState?,
    onRequestPermission: () -> Unit
) {
    val hasPermission = notificationPermissionState?.status?.isGranted ?: true

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_notifications_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(Spacing.extraSmall))
                Text(
                    text = stringResource(
                        if (hasPermission) R.string.settings_notifications_enabled
                        else R.string.settings_notifications_disabled
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hasPermission) {
                Text(
                    text = stringResource(R.string.settings_notifications_status_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Button(onClick = onRequestPermission) {
                    Text(stringResource(R.string.settings_notifications_enable))
                }
            }
        }
    }
}

@Composable
private fun DefaultLanguageSelector(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val languages = remember { UserPreferences.getLanguageListWithAbbreviations() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large)
        ) {
            Text(
                text = stringResource(R.string.settings_transcription_language),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(Spacing.medium))

            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = UserPreferences.getLanguageDisplayName(selectedLanguage),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    languages.forEach { (code, _) ->
                        DropdownMenuItem(
                            text = {
                                Text(UserPreferences.getLanguageDisplayName(code))
                            },
                            onClick = {
                                onLanguageSelected(code)
                                expanded = false
                            },
                            trailingIcon = if (code == selectedLanguage) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}
