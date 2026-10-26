/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.update

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.FloatBallService
import kotlinx.coroutines.launch

/**
 * 升级总门：
 * - 强制态优先于登录/主界面/悬浮球，整屏不可关、返回键无效；
 * - 非强制态只在主界面之上叠加轻量弹窗，不打断用户。
 */
@Composable
fun AdvisorUpdateGate(
    manager: AppUpdateManager,
    content: @Composable () -> Unit,
) {
    val state by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { manager.checkOnLaunch() }

    if (isForcedBlocked(manager, state)) {
        ForcedUpdateScreen(manager, state)
    } else {
        content()
        UpdateOptionalOverlay(manager)
    }
}

/** 设置页等独立界面复用：只展示可选更新/下载/安装/失败弹窗，不显示强制整屏。 */
@Composable
internal fun UpdateOptionalOverlay(manager: AppUpdateManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    when (val current = state) {
        is UpdateState.Optional -> OptionalUpdateDialog(manager, current.manifest)
        is UpdateState.Downloading -> if (!current.forced) DownloadProgressDialog(manager, current)
        is UpdateState.ReadyToInstall -> if (!current.forced) InstallReadyDialog(manager, current)
        is UpdateState.PermissionRequired -> if (!current.forced) InstallPermissionDialog(manager, current)
        is UpdateState.Failed -> if (!current.forced && current.manifest != null) {
            UpdateFailedDialog(manager, current)
        }

        else -> Unit
    }
}

private fun isForcedBlocked(manager: AppUpdateManager, state: UpdateState): Boolean =
    manager.forcedBlocked() || when (state) {
        is UpdateState.Forced -> true
        is UpdateState.Downloading -> state.forced
        is UpdateState.ReadyToInstall -> state.forced
        is UpdateState.PermissionRequired -> state.forced
        is UpdateState.Failed -> state.forced
        else -> false
    }

@Composable
private fun OptionalUpdateDialog(manager: AppUpdateManager, manifest: UpdateManifest) {
    val size = manifest.files.firstOrNull()?.size ?: 0L
    AlertDialog(
        onDismissRequest = { manager.dismissOptional() },
        title = { Text(stringResource(R.string.float_update_available_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.float_update_available_text,
                        versionLabel(manifest.version),
                        sizeText(size),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (manifest.notes.isNotBlank()) {
                    Text(
                        text = manifest.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { manager.startOptionalDownload(manifest) }) {
                Text(stringResource(R.string.float_update_now))
            }
        },
        dismissButton = {
            TextButton(onClick = { manager.dismissOptional() }) {
                Text(stringResource(R.string.float_update_later))
            }
        },
    )
}

@Composable
private fun DownloadProgressDialog(
    manager: AppUpdateManager,
    state: UpdateState.Downloading,
) {
    val progress = progressOf(state)
    AlertDialog(
        onDismissRequest = { manager.cancelOptionalDownload() },
        title = { Text(stringResource(R.string.float_update_downloading_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.float_update_downloading_text,
                        (progress * 100).toInt(),
                        sizeText(state.downloaded),
                        sizeText(state.total),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { manager.cancelOptionalDownload() }) {
                Text(stringResource(R.string.float_update_cancel))
            }
        },
    )
}

@Composable
private fun InstallReadyDialog(
    manager: AppUpdateManager,
    state: UpdateState.ReadyToInstall,
) {
    AlertDialog(
        onDismissRequest = { manager.dismissOptional() },
        title = { Text(stringResource(R.string.float_update_ready_title)) },
        text = { Text(stringResource(R.string.float_update_ready_text)) },
        confirmButton = {
            TextButton(onClick = { manager.retry(forced = false) }) {
                Text(stringResource(R.string.float_update_install))
            }
        },
        dismissButton = {
            TextButton(onClick = { manager.dismissOptional() }) {
                Text(stringResource(R.string.float_update_later))
            }
        },
    )
}

@Composable
private fun InstallPermissionDialog(
    manager: AppUpdateManager,
    state: UpdateState.PermissionRequired,
) {
    AlertDialog(
        onDismissRequest = { manager.dismissOptional() },
        title = { Text(stringResource(R.string.float_update_permission_title)) },
        text = { Text(stringResource(R.string.float_update_permission_text)) },
        confirmButton = {
            TextButton(onClick = { manager.openInstallPermissionSettings() }) {
                Text(stringResource(R.string.float_update_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = { manager.dismissOptional() }) {
                Text(stringResource(R.string.float_update_later))
            }
        },
    )
}

@Composable
private fun UpdateFailedDialog(
    manager: AppUpdateManager,
    state: UpdateState.Failed,
) {
    AlertDialog(
        onDismissRequest = { manager.dismissOptional() },
        title = { Text(stringResource(R.string.float_update_failed_title)) },
        text = { Text(failureText(state.reason)) },
        confirmButton = {
            TextButton(onClick = { manager.retry(forced = false) }) {
                Text(stringResource(R.string.float_update_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = { manager.dismissOptional() }) {
                Text(stringResource(R.string.float_update_later))
            }
        },
    )
}

@Composable
private fun ForcedUpdateScreen(
    manager: AppUpdateManager,
    state: UpdateState,
) {
    BackHandler(enabled = true) { /* 强制更新期间禁止返回 */ }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manifest = forcedManifest(state)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.float_update_required_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))

            if (manifest != null) {
                Text(
                    text = stringResource(
                        R.string.float_update_required_text,
                        versionLabel(manifest.version),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
            }

            when (state) {
                is UpdateState.Downloading -> {
                    val progress = progressOf(state)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.float_update_downloading_text,
                            (progress * 100).toInt(),
                            sizeText(state.downloaded),
                            sizeText(state.total),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is UpdateState.ReadyToInstall -> {
                    Text(
                        text = stringResource(R.string.float_update_ready_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { manager.retry(forced = true) }) {
                        Text(stringResource(R.string.float_update_reinstall))
                    }
                }

                is UpdateState.PermissionRequired -> {
                    Text(
                        text = stringResource(R.string.float_update_permission_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { manager.openInstallPermissionSettings() }) {
                        Text(stringResource(R.string.float_update_open_settings))
                    }
                }

                is UpdateState.Failed -> {
                    Text(
                        text = failureText(state.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { manager.retry(forced = true) }) {
                        Text(stringResource(R.string.float_update_retry))
                    }
                }

                is UpdateState.Forced -> {
                    Button(onClick = { manager.startForcedDownload(state.manifest) }) {
                        Text(stringResource(R.string.float_update_now))
                    }
                }

                is UpdateState.Optional -> {
                    // 理论上不会走到这里；仍给一个强制下载入口，避免死锁。
                    Button(onClick = { manager.startForcedDownload(state.manifest) }) {
                        Text(stringResource(R.string.float_update_now))
                    }
                }

                else -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.float_update_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { scope.launch { manager.checkNow() } }) {
                        Text(stringResource(R.string.float_update_retry))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = {
                    context.stopService(Intent(context, FloatBallService::class.java))
                    (context as? Activity)?.finishAffinity()
                },
            ) {
                Text(
                    text = stringResource(R.string.float_update_exit),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun forcedManifest(state: UpdateState): UpdateManifest? = when (state) {
    is UpdateState.Forced -> state.manifest
    is UpdateState.Downloading -> state.manifest
    is UpdateState.ReadyToInstall -> state.manifest
    is UpdateState.PermissionRequired -> state.manifest
    is UpdateState.Failed -> state.manifest
    is UpdateState.Optional -> state.manifest
    else -> null
}

private fun progressOf(state: UpdateState.Downloading): Float =
    if (state.total > 0L) {
        (state.downloaded.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

private fun versionLabel(version: String): String = "v${version.removePrefix("v")}"

@Composable
private fun sizeText(bytes: Long): String {
    if (bytes <= 0L) return stringResource(R.string.float_update_size_unknown)
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}

@Composable
private fun failureText(reason: UpdateFailure): String = stringResource(
    when (reason) {
        UpdateFailure.NETWORK -> R.string.float_update_error_network
        UpdateFailure.MANIFEST_INVALID -> R.string.float_update_error_manifest
        UpdateFailure.NO_SPACE -> R.string.float_update_error_space
        UpdateFailure.DOWNLOAD -> R.string.float_update_error_download
        UpdateFailure.SHA_MISMATCH -> R.string.float_update_error_sha
        UpdateFailure.ARCHIVE_INVALID -> R.string.float_update_error_archive
        UpdateFailure.PACKAGE_MISMATCH -> R.string.float_update_error_package
        UpdateFailure.VERSION_INVALID -> R.string.float_update_error_version
        UpdateFailure.SIGNATURE_MISMATCH -> R.string.float_update_error_signature
        UpdateFailure.LAUNCH_FAILED -> R.string.float_update_error_launch
    },
)
