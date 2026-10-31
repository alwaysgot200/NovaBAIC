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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.store.RpaExportFormat
import com.verlintas.rpa.store.RpaJsonCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地脚本详情页（方案 §7.2 图10）：
 *
 * `← | 本地脚本详情 | ⋮` 顶栏（[AdvisorPage] 统一骨架）；头部 64dp 图标 +
 * 名称 + 「N 个动作 · X KB」；区块依次为 脚本描述 → 运行环境（模式徽标）→
 * 基本信息；「脚本市场 · 云备份」按「即将开放」禁用占位（不放
 * 空区块、不做假入口）；底部固定操作条 `[分享][云备份][上传市场] + [编辑][运行]`。
 *
 * 「编辑」不直接进编辑器——先过 [RpaModeGateDialog] 模式确认门（§7.4），
 * 由宿主（RpaActivity）在确认后拉起编辑器 / 浮窗。
 *
 * 有意偏差（不做假数据）：模型无 `importSource` 字段、不记录目标分辨率，
 * 「导入来源」「分辨率 推荐」两行不渲染；分辨率只展示设备当前值。
 */
@Composable
internal fun RpaPropertyScreen(
    script: RpaScript,
    runState: RpaRunController.State,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onStopRun: () -> Unit,
    onShare: (RpaExportFormat) -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDescriptionChange: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDescriptionEdit by remember { mutableStateOf(false) }
    val running = runState is RpaRunController.State.Running

    AdvisorPage(
        title = stringResource(R.string.float_rpa_property_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.float_rpa_property_menu),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.float_rpa_rename)) },
                    onClick = {
                        menuExpanded = false
                        showRename = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.float_rpa_duplicate)) },
                    onClick = {
                        menuExpanded = false
                        onDuplicate()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.float_rpa_export)) },
                    onClick = {
                        menuExpanded = false
                        showExport = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.float_rpa_delete)) },
                    onClick = {
                        menuExpanded = false
                        showDelete = true
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PropertyHeader(script)
                PropertyDescription(script) { showDescriptionEdit = true }
                PropertyEnvironment(script)
                PropertyBasicInfo(script)
                PropertyComingSoonRows()
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider()
            PropertyActionBar(
                running = running,
                onShare = { showExport = true },
                onEdit = onEdit,
                onRun = onRun,
                onStopRun = onStopRun,
            )
        }
    }

    if (showRename) {
        RenameScriptDialog(
            initialName = script.name,
            onDismiss = { showRename = false },
            onConfirm = { name ->
                showRename = false
                onRename(name)
            },
        )
    }
    if (showExport) {
        ExportFormatDialog(
            onDismiss = { showExport = false },
            onConfirm = { format ->
                showExport = false
                onShare(format)
            },
        )
    }
    if (showDelete) {
        DeleteConfirmDialog(
            title = stringResource(R.string.float_rpa_delete_title),
            message = stringResource(R.string.float_rpa_delete_hint),
            confirmText = stringResource(R.string.float_rpa_delete),
            onConfirm = {
                showDelete = false
                onDelete()
            },
            onDismiss = { showDelete = false },
        )
    }
    if (showDescriptionEdit) {
        var text by remember { mutableStateOf(script.description) }
        AlertDialog(
            onDismissRequest = { showDescriptionEdit = false },
            title = { Text(stringResource(R.string.float_rpa_description_edit)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.float_rpa_script_description)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDescriptionEdit = false
                        onDescriptionChange(text.trim())
                    },
                ) {
                    Text(stringResource(R.string.float_rpa_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDescriptionEdit = false }) {
                    Text(stringResource(R.string.float_cancel))
                }
            },
        )
    }
}

/** 头部（图10）：64dp 圆形图标 + 名称 + 「N 个动作 · X KB」。 */
@Composable
private fun PropertyHeader(script: RpaScript) {
    val kb = remember(script.id, script.updatedAt, script.steps.size) {
        runCatching { RpaJsonCodec.encode(script).size / 1024.0 }.getOrDefault(0.0)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = script.name.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = script.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.float_rpa_script_meta,
                    stringResource(R.string.float_rpa_script_steps, script.steps.size),
                    stringResource(R.string.float_rpa_script_kb, kb),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 区块一：脚本描述（空则灰色提示；编辑走弹窗 → [onDescriptionChange]）。 */
@Composable
private fun PropertyDescription(script: RpaScript, onEditDescription: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RpaSectionTitle(stringResource(R.string.float_rpa_script_description))
        RpaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = script.description.ifBlank {
                        stringResource(R.string.float_rpa_description_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (script.description.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEditDescription) {
                    Text(stringResource(R.string.float_rpa_description_edit))
                }
            }
        }
    }
}

/** 区块二：运行环境（用户定案 2026-10-31）：只简洁显示脚本模式，技术细节只留方案文档。 */
@Composable
private fun PropertyEnvironment(script: RpaScript) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RpaSectionTitle(stringResource(R.string.float_rpa_property_env))
        RpaCard {
            RpaModeBadge(script.mode)
        }
    }
}

/** 区块三：基本信息（动作数量 / 分辨率·当前 / 创建 / 更新 / 数据版本）。 */
@Composable
private fun PropertyBasicInfo(script: RpaScript) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val resolution = stringResource(
        R.string.float_rpa_property_resolution_value,
        metrics.widthPixels,
        metrics.heightPixels,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RpaSectionTitle(stringResource(R.string.float_rpa_property_basic))
        RpaCard {
            PropertyInfoRow(
                label = stringResource(R.string.float_rpa_property_steps),
                value = stringResource(R.string.float_rpa_script_steps, script.steps.size),
            )
            PropertyInfoRow(
                label = stringResource(R.string.float_rpa_property_resolution),
                value = resolution,
            )
            PropertyInfoRow(
                label = stringResource(R.string.float_rpa_property_created),
                value = formatRpaTime(script.createdAt),
            )
            PropertyInfoRow(
                label = stringResource(R.string.float_rpa_property_updated),
                value = formatRpaTime(script.updatedAt),
            )
            PropertyInfoRow(
                label = stringResource(R.string.float_rpa_property_schema),
                value = script.schemaVersion.toString(),
            )
        }
    }
}

@Composable
private fun PropertyInfoRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 「脚本市场（上传 / 售卖）· 云备份」：按「即将开放」禁用占位
 * （方案 §7.2——不放空区块、不做假入口，整行不响应点击）。
 */
@Composable
private fun PropertyComingSoonRows() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RpaCard {
            ComingSoonRow(stringResource(R.string.float_rpa_property_market))
            Spacer(Modifier.height(8.dp))
            ComingSoonRow(stringResource(R.string.float_rpa_property_cloud_backup))
        }
    }
}

@Composable
private fun ComingSoonRow(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.float_rpa_property_coming_soon),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 底部固定操作条：`[分享][云备份(禁用)][上传市场(禁用)] … [编辑][运行/停止]`。 */
@Composable
private fun PropertyActionBar(
    running: Boolean,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onStopRun: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onShare) {
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = stringResource(R.string.float_rpa_property_share),
            )
        }
        IconButton(onClick = {}, enabled = false) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.float_rpa_property_cloud_backup),
            )
        }
        IconButton(onClick = {}, enabled = false) {
            Icon(
                imageVector = Icons.Outlined.ShoppingCart,
                contentDescription = stringResource(R.string.float_rpa_property_upload_market),
            )
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onEdit) {
            Text(stringResource(R.string.float_rpa_open))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = if (running) onStopRun else onRun) {
            Text(stringResource(if (running) R.string.float_rpa_stop else R.string.float_rpa_run))
        }
    }
}

private fun formatRpaTime(timeMs: Long): String =
    if (timeMs > 0) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMs))
    } else {
        ""
    }
