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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.AdvisorPage
import com.verlintas.baic2.floatball.DeleteConfirmDialog
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaScript

/**
 * RPA 脚本库：能力快照 + 搜索 + 脚本列表（运行 / 新建 / 编辑 / 复制 / 删除）。
 * 模式在新建时先选定，进入编辑器后仍可调整，但动作的定位面板始终按当前模式
 * 约束可用线路。运行走后台 `RpaRunService`，列表只观察单例状态。
 * 发行版不提供任何 Demo 生成入口（7 步微信夹具只在 test source set）。
 */
@Composable
internal fun RpaLibraryScreen(
    scripts: List<RpaScript>,
    brokenScripts: Int,
    capabilities: RpaCapabilities,
    initialPackage: String?,
    runState: RpaRunController.State,
    onBack: () -> Unit,
    onRefreshCapabilities: () -> Unit,
    onGrantProjection: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpen: (RpaScript) -> Unit,
    onCreate: (name: String, mode: RpaMode, appPackage: String) -> Unit,
    onRun: (RpaScript) -> Unit,
    onStopRun: () -> Unit,
    onDuplicate: (RpaScript) -> Unit,
    onDelete: (RpaScript) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RpaScript?>(null) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(scripts, query) { filterRpaScripts(scripts, query) }
    val running = runState as? RpaRunController.State.Running
    val finished = runState as? RpaRunController.State.Finished
    val context = LocalContext.current
    val busyText = stringResource(R.string.float_rpa_run_already)

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        AdvisorPage(title = stringResource(R.string.float_rpa_title), onBack = onBack) { inner ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = inner,
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        RpaCapabilityCard(
                            capabilities = capabilities,
                            onRefresh = onRefreshCapabilities,
                            onGrantProjection = onGrantProjection,
                            onOpenShizuku = onOpenShizuku,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.float_rpa_scripts),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { creating = true }) {
                                Text(stringResource(R.string.float_rpa_new))
                            }
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.float_rpa_search_hint)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
                if (brokenScripts > 0) {
                    item {
                        Text(
                            text = stringResource(
                                R.string.float_rpa_broken_scripts,
                                brokenScripts,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                if (scripts.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.float_rpa_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.float_rpa_search_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
                items(filtered, key = { it.id }) { script ->
                    val scriptRunning = running?.takeIf { it.scriptId == script.id }
                    val otherRunning = running != null && running.scriptId != script.id
                    RpaScriptCard(
                        script = script,
                        running = scriptRunning,
                        finished = finished?.takeIf { it.scriptId == script.id },
                        onOpen = { onOpen(script) },
                        onRun = {
                            when {
                                scriptRunning != null -> onStopRun()
                                otherRunning -> Toast.makeText(
                                    context,
                                    busyText,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                else -> onRun(script)
                            }
                        },
                        onDuplicate = { onDuplicate(script) },
                        onDelete = { deleting = script },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.float_rpa_library_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    )
                }
            }
        }
    }

    if (creating) {
        CreateScriptDialog(
            defaultPackage = initialPackage.orEmpty(),
            onDismiss = { creating = false },
            onConfirm = { name, mode, pkg ->
                creating = false
                onCreate(name, mode, pkg)
            },
        )
    }

    deleting?.let { target ->
        DeleteConfirmDialog(
            title = stringResource(R.string.float_rpa_delete_title),
            message = stringResource(R.string.float_rpa_delete_hint),
            confirmText = stringResource(R.string.float_rpa_delete),
            onConfirm = {
                deleting = null
                onDelete(target)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RpaCapabilityCard(
    capabilities: RpaCapabilities,
    onRefresh: () -> Unit,
    onGrantProjection: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    RpaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RpaSectionTitle(
                text = stringResource(R.string.float_rpa_capabilities),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.float_rpa_cap_refresh))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            RpaStatusBadge(
                text = stringResource(R.string.float_rpa_cap_a11y),
                ok = capabilities.a11yReady,
            )
            RpaStatusBadge(
                text = stringResource(R.string.float_rpa_cap_shizuku),
                ok = capabilities.shizukuReady,
            )
            RpaStatusBadge(
                text = stringResource(R.string.float_rpa_cap_ocr),
                ok = capabilities.ocrReady,
            )
            RpaStatusBadge(
                text = stringResource(R.string.float_rpa_cap_projection),
                ok = capabilities.projectionReady,
            )
        }
        Text(
            text = stringResource(
                if (capabilities.visionReady) {
                    R.string.float_rpa_cap_vision_ready
                } else {
                    R.string.float_rpa_cap_vision_missing
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (capabilities.visionReady) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!capabilities.projectionReady && !capabilities.shizukuReady) {
            OutlinedButton(
                onClick = onGrantProjection,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(stringResource(R.string.float_rpa_grant_projection))
            }
        }
        if (!capabilities.shizukuReady) {
            OutlinedButton(
                onClick = onOpenShizuku,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(stringResource(R.string.float_rpa_open_shizuku))
            }
        }
    }
}

@Composable
private fun RpaScriptCard(
    script: RpaScript,
    running: RpaRunController.State.Running?,
    finished: RpaRunController.State.Finished?,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val runningNow = running != null
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (runningNow) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRun,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (runningNow) {
                            Icons.Filled.Close
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(
                            if (runningNow) {
                                R.string.float_rpa_stop
                            } else {
                                R.string.float_rpa_run
                            },
                        ),
                        tint = if (runningNow) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 0.dp),
                ) {
                    Text(
                        text = script.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.float_rpa_script_steps,
                            script.steps.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                RpaModeBadge(script.mode)
            }
            if (script.description.isNotBlank()) {
                Text(
                    text = script.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (running != null) {
                val label = running.stepLabel.ifBlank {
                    running.stepAction?.let { rpaActionLabel(it) }.orEmpty()
                }
                val stepText = if (label.isBlank()) {
                    stringResource(
                        R.string.float_rpa_run_step_plain,
                        running.displayIndex,
                        running.stepCount,
                    )
                } else {
                    stringResource(
                        R.string.float_rpa_run_step,
                        running.displayIndex,
                        running.stepCount,
                        label,
                    )
                }
                Text(
                    text = stepText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (running.stepCount > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (running.displayIndex.toFloat() / running.stepCount)
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }
            if (finished != null) {
                val resultText = if (finished.ok) {
                    stringResource(R.string.float_rpa_run_last_ok)
                } else {
                    stringResource(
                        R.string.float_rpa_run_last_failed,
                        finished.detail.ifBlank {
                            stringResource(R.string.float_rpa_run_finished_failed)
                        },
                    )
                }
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (finished.ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.float_rpa_open))
                }
                TextButton(onClick = onDuplicate, enabled = !runningNow) {
                    Text(stringResource(R.string.float_rpa_duplicate))
                }
                TextButton(onClick = onDelete, enabled = !runningNow) {
                    Text(
                        text = stringResource(R.string.float_rpa_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateScriptDialog(
    defaultPackage: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, mode: RpaMode, appPackage: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(RpaMode.DUAL) }
    var appPackage by remember { mutableStateOf(defaultPackage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_rpa_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.float_rpa_new_mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RpaModeSelector(mode = mode, onMode = { mode = it })
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.float_rpa_new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = appPackage,
                    onValueChange = { appPackage = it },
                    label = { Text(stringResource(R.string.float_rpa_new_package)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), mode, appPackage.trim()) },
            ) {
                Text(stringResource(R.string.float_rpa_new_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_cancel))
            }
        },
    )
}
