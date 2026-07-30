// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.voiceskip.R
import com.voiceskip.ui.theme.Spacing
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.voiceskip.data.UserPreferences
import com.voiceskip.data.repository.GpuDisabledReason
import com.voiceskip.data.repository.ImportError
import com.voiceskip.data.repository.ModelImportState
import com.voiceskip.data.repository.ModelInfo
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
private fun GpuDisabledReason.toMessage(): String = when (this) {
    GpuDisabledReason.VULKAN_1_2_UNSUPPORTED ->
        stringResource(R.string.settings_gpu_requires_vulkan_1_2)
    GpuDisabledReason.GPU_FAILED ->
        stringResource(R.string.settings_gpu_failed)
}

@Composable
private fun rememberToastClickHandler(message: String?): (() -> Unit)? {
    val context = LocalContext.current
    var visible by remember(message) { mutableStateOf(false) }
    val toast = remember(context, message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT) }
    }

    DisposableEffect(toast) {
        val callback = object : Toast.Callback() {
            override fun onToastHidden() {
                visible = false
            }
        }
        toast?.addCallback(callback)
        onDispose {
            toast?.removeCallback(callback)
            toast?.cancel()
        }
    }

    return toast?.let {
        {
            if (!visible) {
                visible = true
                it.show()
            }
        }
    }
}

@Composable
private fun ModelManager.TurboFallbackReason.toMessage(): String = when (this) {
    ModelManager.TurboFallbackReason.CRASH -> stringResource(R.string.msg_turbo_crash)
}

@Composable
private fun ModelManager.ModelFallbackReason.toMessage(): String = when (this) {
    ModelManager.ModelFallbackReason.UNAVAILABLE -> stringResource(R.string.msg_model_unavailable)
    ModelManager.ModelFallbackReason.LOAD_FAILED -> stringResource(R.string.msg_model_load_failed)
}

@Composable
private fun ImportError.toMessage(): String = when (this) {
    ImportError.NOT_A_BIN_FILE -> stringResource(R.string.settings_import_error_not_bin)
    ImportError.UNREADABLE -> stringResource(R.string.settings_import_error_unreadable)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gpuDisabledMessage = uiState.gpuDisabledReason?.toMessage()
    val onGpuDisabledClick = rememberToastClickHandler(gpuDisabledMessage)

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

    uiState.modelFallbackReason?.let { reason ->
        val message = reason.toMessage()
        LaunchedEffect(reason) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            viewModel.onModelFallbackDismissed()
        }
    }

    val notificationPermissionState = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(permission = android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }

    // Storage Access Framework requires no permission; the grant is persisted so the reference survives restarts.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importModel(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
            DefaultLanguageSelector(
                selectedLanguage = uiState.defaultLanguage,
                onLanguageSelected = { viewModel.setDefaultLanguage(it) }
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            VadSelector(
                vadEnabled = uiState.vadEnabled,
                onVadEnabledChanged = { viewModel.setVadEnabled(it) }
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
                models = uiState.availableModels,
                selectedModel = uiState.model,
                onModelSelected = { viewModel.setModel(it) }
            )

            Spacer(modifier = Modifier.height(Spacing.extraLarge))

            SectionHeader(title = stringResource(R.string.settings_section_performance))
            Spacer(modifier = Modifier.height(Spacing.small))
            GpuSelector(
                gpuEnabled = uiState.gpuEnabled,
                gpuStatus = uiState.gpuStatus,
                gpuAvailable = uiState.gpuDisabledReason == null,
                onGpuEnabledChanged = { viewModel.setGpuEnabled(it) },
                onGpuDisabledClick = onGpuDisabledClick
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

            SectionHeader(title = stringResource(R.string.settings_section_advanced))
            Spacer(modifier = Modifier.height(Spacing.small))
            CustomModelCard(
                importState = uiState.importState,
                importedModels = uiState.availableModels.filter { it.isImported },
                selectedModel = uiState.model,
                onImportClick = {
                    viewModel.clearImportError()
                    importLauncher.launch(arrayOf("*/*"))
                },
                onDeleteModel = { viewModel.deleteModel(it) }
            )

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
    models: List<ModelInfo>,
    selectedModel: String,
    onModelSelected: (String) -> Unit
) {
    val labelFaster = stringResource(R.string.settings_model_faster)
    val labelBalanced = stringResource(R.string.settings_model_balanced)
    val labelPrecise = stringResource(R.string.settings_model_precise)
    val labelCustom = stringResource(R.string.settings_model_custom)

    val bundled = models.filterNot { it.isImported }

    fun labelFor(info: ModelInfo): String {
        if (info.isImported) return labelCustom
        return when (bundled.indexOfFirst { it.id == info.id }) {
            0 -> labelFaster
            bundled.lastIndex -> labelPrecise
            else -> labelBalanced
        }
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
            models.forEach { info ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = selectedModel == info.id,
                        onClick = { onModelSelected(info.id) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = labelFor(info),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.settings_model_name, info.displayName),
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
private fun CustomModelCard(
    importState: ModelImportState,
    importedModels: List<ModelInfo>,
    selectedModel: String,
    onImportClick: () -> Unit,
    onDeleteModel: (String) -> Unit
) {
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
                text = stringResource(R.string.settings_custom_model_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            Text(
                text = stringResource(R.string.settings_custom_model_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.medium))

            Button(
                onClick = onImportClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_import_model))
            }

            if (importState is ModelImportState.Error) {
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = importState.reason.toMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (importedModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.medium))
                importedModels.forEach { info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.extraSmall),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = info.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (info.id == selectedModel) {
                                Text(
                                    text = stringResource(R.string.settings_import_in_use),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = { onDeleteModel(info.id) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.settings_remove_model)
                            )
                        }
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
    gpuAvailable: Boolean,
    onGpuEnabledChanged: (Boolean) -> Unit,
    onGpuDisabledClick: (() -> Unit)?
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
                .then(
                    if (onGpuDisabledClick != null) {
                        Modifier.clickable(onClick = onGpuDisabledClick)
                    } else {
                        Modifier
                    }
                )
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
            Box {
                Switch(
                    checked = gpuEnabled,
                    onCheckedChange = onGpuEnabledChanged,
                    enabled = gpuAvailable
                )
                if (onGpuDisabledClick != null) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(onClick = onGpuDisabledClick)
                    )
                }
            }
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
private fun VadSelector(
    vadEnabled: Boolean,
    onVadEnabledChanged: (Boolean) -> Unit
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
                    text = stringResource(R.string.settings_vad_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(Spacing.extraSmall))
                Text(
                    text = stringResource(R.string.settings_vad_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = vadEnabled,
                onCheckedChange = onVadEnabledChanged
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
