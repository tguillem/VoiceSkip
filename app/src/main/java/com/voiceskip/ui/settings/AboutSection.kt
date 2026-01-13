// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.voiceskip.BuildConfig
import com.voiceskip.R
import com.voiceskip.ui.theme.Spacing

private val SOURCE_CODE_URL: String? = "https://github.com/tguillem/VoiceSkip"

@Composable
fun AboutSection() {
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showOssLicensesDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
                text = stringResource(R.string.settings_about_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.large))

            AboutItem(
                title = stringResource(R.string.settings_about_version),
                subtitle = BuildConfig.VERSION_NAME
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            AboutItemMultiline(
                title = stringResource(R.string.settings_about_license),
                lines = listOf(
                    stringResource(R.string.settings_about_license_name),
                    stringResource(R.string.settings_about_license_copyright)
                ),
                onClick = { showLicenseDialog = true }
            )

            if (SOURCE_CODE_URL != null) {
                Spacer(modifier = Modifier.height(Spacing.medium))

                AboutItem(
                    title = stringResource(R.string.settings_about_source),
                    subtitle = stringResource(R.string.settings_about_source_github),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_CODE_URL))
                        context.startActivity(intent)
                    }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.medium))

            AboutItem(
                title = stringResource(R.string.settings_about_licenses),
                subtitle = stringResource(R.string.settings_about_licenses_desc),
                onClick = { showOssLicensesDialog = true }
            )
        }
    }

    if (showLicenseDialog) {
        LicenseDialog(onDismiss = { showLicenseDialog = false })
    }

    if (showOssLicensesDialog) {
        OssLicensesDialog(onDismiss = { showOssLicensesDialog = false })
    }
}

@Composable
private fun AboutItem(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (onClick != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun AboutItemMultiline(
    title: String,
    lines: List<String>,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onClick != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.license_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.license_dialog_text1),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(
                    text = stringResource(R.string.license_dialog_text2),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(Spacing.medium))
                Text(
                    text = stringResource(R.string.license_dialog_text3),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource(R.string.license_dialog_url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html")
                        )
                        context.startActivity(intent)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        }
    )
}

@Composable
private fun OssLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.oss_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OssLicenseItem(
                    name = stringResource(R.string.oss_whisper_name),
                    copyright = stringResource(R.string.oss_whisper_copyright),
                    license = stringResource(R.string.oss_whisper_license),
                    url = "https://github.com/ggerganov/whisper.cpp",
                    onUrlClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/ggerganov/whisper.cpp")
                        )
                        context.startActivity(intent)
                    }
                )

                Spacer(modifier = Modifier.height(Spacing.large))
                Divider()
                Spacer(modifier = Modifier.height(Spacing.large))

                Text(
                    text = stringResource(R.string.oss_jetpack_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        }
    )
}

@Composable
private fun OssLicenseItem(
    name: String,
    copyright: String,
    license: String,
    url: String,
    onUrlClick: () -> Unit
) {
    Column {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(Spacing.extraSmall))
        Text(
            text = copyright,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onUrlClick)
        )
    }
}
