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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.AdvisorPage
import com.verlintas.baic2.floatball.DeleteConfirmDialog
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.store.RpaExportFormat
import com.verlintas.rpa.store.RpaJsonCodec
import com.verlintas.rpa.store.RpaScriptPackage
import com.verlintas.rpa.store.RpaXmlCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RPA 脚本库（方案 §7.1）：行式列表 + 行内「运行」实体钮 + 长按脚本操作菜单 +
 * 顶栏 导入 / 搜索 / ＋。单击=属性页；长按=ModalBottomSheet（运行 / 编辑 /
 * 属性 / 复制 / 重命名 / 导出 / 删除，§7.3 第一套菜单）；空态给「录制（P2 置灰）
 * / 新建」主按钮。运行走后台 `RpaRunService`，列表只观察单例状态。
 * 发行版不提供任何 Demo 生成入口（7 步微信夹具只在 test source set）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun RpaLibraryScreen(
    scripts: List<RpaScript>,
    brokenScripts: Int,
    capabilities: RpaCapabilities,
    initialPackage: String?,
    runState: RpaRunController.State,
    onBack: () -> Unit,
    onRefreshCapabilities: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpen: (RpaScript) -> Unit,
    onCreate: (name: String, mode: RpaMode, appPackage: String) -> Unit,
    onRun: (RpaScript) -> Unit,
    onStopRun: () -> Unit,
    onDuplicate: (RpaScript) -> Unit,
    onDelete: (RpaScript) -> Unit,
    onEdit: (RpaScript) -> Unit,
    onRename: (RpaScript, String) -> Unit,
    onExport: (RpaScript, RpaExportFormat) -> Unit,
    onImport: (ByteArray) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var createGate by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RpaScript?>(null) }
    var renaming by remember { mutableStateOf<RpaScript?>(null) }
    var exporting by remember { mutableStateOf<RpaScript?>(null) }
    var menuTarget by remember { mutableStateOf<RpaScript?>(null) }
    var importDraft by remember { mutableStateOf<RpaImportDraft?>(null) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    val filtered = remember(scripts, query) { filterRpaScripts(scripts, query) }
    val running = runState as? RpaRunController.State.Running
    val finished = runState as? RpaRunController.State.Finished
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busyText = stringResource(R.string.float_rpa_run_already)
    val unreadableText = stringResource(R.string.float_rpa_import_unreadable)
    val a11yModeReady = capabilities.a11yModeReady()
    val visionModeReady = capabilities.visionModeReady()
    val anyReady = a11yModeReady || visionModeReady

    fun runAction(target: RpaScript) {
        val self = running?.takeIf { it.scriptId == target.id }
        when {
            self != null -> onStopRun()
            running != null -> Toast.makeText(context, busyText, Toast.LENGTH_SHORT).show()
            else -> onRun(target)
        }
    }

    /**
     * 新建前置门（用户定案）：a11y / 无 a11y 至少一条线路就绪即可新建；
     * 两条都缺才弹就绪门给直达修复入口。弹框内三枚模式芯片恒显示，未就绪的
     * 模式置灰 + 简洁原因 + 修复入口（规格 §4.1）。
     */
    fun requestCreate() {
        if (anyReady) {
            creating = true
        } else {
            createGate = true
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val draft = withContext(Dispatchers.IO) {
                    runCatching { readRpaImportDraft(context, uri) }.getOrNull()
                }
                if (draft == null) {
                    Toast.makeText(context, unreadableText, Toast.LENGTH_LONG).show()
                } else {
                    importDraft = draft
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        AdvisorPage(
            title = stringResource(R.string.float_rpa_title),
            onBack = onBack,
            actions = {
                TextButton(
                    onClick = { importLauncher.launch(IMPORT_MIME) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.float_rpa_import))
                }
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                }) {
                    Icon(
                        imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = stringResource(R.string.float_rpa_search),
                    )
                }
                Box {
                    IconButton(onClick = { addMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.float_rpa_add_menu),
                        )
                    }
                    DropdownMenu(
                        expanded = addMenuOpen,
                        onDismissRequest = { addMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.float_rpa_new)) },
                            onClick = {
                                addMenuOpen = false
                                requestCreate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.float_rpa_import)) },
                            onClick = {
                                addMenuOpen = false
                                importLauncher.launch(IMPORT_MIME)
                            },
                        )
                    }
                }
            },
        ) { inner ->
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
                        // 运行能力卡（用户定案）：只留两行模式状态，不堆解释文字。
                        RpaCapabilityCard(
                            capabilities = capabilities,
                            onRefresh = onRefreshCapabilities,
                        )
                        Text(
                            text = stringResource(R.string.float_rpa_scripts),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        if (searchOpen) {
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.float_rpa_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                // 录制是 P2（用户已确认③）：置灰标「规划中」，不做假入口。
                                OutlinedButton(onClick = {}, enabled = false) {
                                    Text(stringResource(R.string.float_rpa_record_planned))
                                }
                                Button(onClick = { requestCreate() }) {
                                    Text(stringResource(R.string.float_rpa_new))
                                }
                            }
                        }
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
                    RpaScriptRow(
                        script = script,
                        running = running?.takeIf { it.scriptId == script.id },
                        finished = finished?.takeIf { it.scriptId == script.id && running == null },
                        onOpen = { onOpen(script) },
                        onRun = { runAction(script) },
                        onLongPress = { menuTarget = script },
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
            capabilities = capabilities,
            onDismiss = { creating = false },
            onConfirm = { name, mode, pkg ->
                creating = false
                onCreate(name, mode, pkg)
            },
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onOpenShizuku = onOpenShizuku,
        )
    }

    if (createGate) {
        DualReadyGateDialog(
            capabilities = capabilities,
            onDismiss = { createGate = false },
            onRefresh = onRefreshCapabilities,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onOpenShizuku = onOpenShizuku,
        )
    }

    menuTarget?.let { target ->
        RpaScriptMenuSheet(
            script = target,
            runningNow = running?.scriptId == target.id,
            onDismiss = { menuTarget = null },
            onRun = {
                menuTarget = null
                runAction(target)
            },
            onEdit = {
                menuTarget = null
                onEdit(target)
            },
            onOpen = {
                menuTarget = null
                onOpen(target)
            },
            onDuplicate = {
                menuTarget = null
                onDuplicate(target)
            },
            onRename = {
                menuTarget = null
                renaming = target
            },
            onExport = {
                menuTarget = null
                exporting = target
            },
            onDelete = {
                menuTarget = null
                deleting = target
            },
        )
    }

    renaming?.let { target ->
        RenameScriptDialog(
            initialName = target.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRename(target, name)
            },
        )
    }

    exporting?.let { target ->
        ExportFormatDialog(
            onDismiss = { exporting = null },
            onConfirm = { format ->
                exporting = null
                onExport(target, format)
            },
        )
    }

    importDraft?.let { draft ->
        ImportConfirmDialog(
            draft = draft,
            onDismiss = { importDraft = null },
            onConfirm = {
                importDraft = null
                onImport(draft.bytes)
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

/** 导入文件选择的 MIME：三格式（.json / .xml / .rpa）统一只按内容嗅探（§八）。 */
internal val IMPORT_MIME = arrayOf("*/*")

/** 导入摘要（§九「摘要确认」）：解析后的预览 + 原始字节。 */
internal class RpaImportDraft(
    val bytes: ByteArray,
    val name: String,
    val stepCount: Int,
    val templateCount: Int,
    val p2Count: Int,
)

internal fun readRpaImportDraft(context: Context, uri: Uri): RpaImportDraft? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return decodeRpaImportDraft(bytes)
}

/** 与 `RpaScriptStore.importBytes` 同口径的格式嗅探，仅解码不落盘。 */
private fun decodeRpaImportDraft(bytes: ByteArray): RpaImportDraft? = runCatching {
    val isZip = bytes.size >= 4 &&
        bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    if (isZip) {
        val unpacked = RpaScriptPackage.unpack(bytes)
        RpaImportDraft(
            bytes = bytes,
            name = unpacked.script.name,
            stepCount = unpacked.script.steps.size,
            templateCount = unpacked.templates.size,
            p2Count = countP2Steps(unpacked.script),
        )
    } else {
        val head = String(bytes, Charsets.UTF_8).trimStart()
        val script = if (head.startsWith("<")) {
            RpaXmlCodec.decode(bytes)
        } else {
            RpaJsonCodec.decode(bytes)
        }
        RpaImportDraft(
            bytes = bytes,
            name = script.name,
            stepCount = script.steps.size,
            templateCount = 0,
            p2Count = countP2Steps(script),
        )
    }
}.getOrNull()

/** P2 占位动作（点击颜色 / 多指手势）：可导入可编辑，运行到该步显式报因（§八）。 */
private fun countP2Steps(script: RpaScript): Int {
    val p2 = setOf(RpaAction.TAP_COLOR, RpaAction.GESTURE_MULTI)
    return (script.steps + script.customActions.flatMap { it.steps })
        .count { it.action in p2 }
}

/** a11y 模式激活口径：无障碍服务连接即激活。 */
internal fun RpaCapabilities.a11yModeReady(): Boolean = a11yReady

/**
 * 无 a11y 模式激活口径（用户定案 2026-10-30）：条件就是 Shizuku shell 正常工作。
 * 录屏抽帧由软件在 Shizuku 就绪后自动完成（静默授权 + 运行时取投影），
 * 不设人工按钮、不进就绪条件；OCR 内置即就绪。失败报因在运行时收口（§4.5）。
 */
internal fun RpaCapabilities.visionModeReady(): Boolean = shizukuReady

@Composable
private fun RpaCapabilityCard(
    capabilities: RpaCapabilities,
    onRefresh: () -> Unit,
) {
    val a11yReady = capabilities.a11yModeReady()
    val visionReady = capabilities.visionModeReady()
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
        // 运行能力卡（用户定案）：只留两行模式状态 + 激活徽标，不堆 chips 与解释。
        ModeReadyRow(
            label = stringResource(R.string.float_rpa_mode_line_a11y),
            ready = a11yReady,
        )
        ModeReadyRow(
            label = stringResource(R.string.float_rpa_mode_line_vision),
            ready = visionReady,
        )
    }
}

/** 模式状态行：激活=主题色「已激活」，未激活=灰显「未激活」（不激活置灰显示）。 */
@Composable
private fun ModeReadyRow(label: String, ready: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (ready) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        CapChip(
            text = stringResource(
                if (ready) R.string.float_rpa_mode_status_ready
                else R.string.float_rpa_mode_status_missing,
            ),
            ready = ready,
        )
    }
}

/** 能力子项 chip：就绪=主题色，缺失=灰显（与模式行同一「未激活置灰」口径）。 */
@Composable
private fun CapChip(text: String, ready: Boolean) {
    val container = if (ready) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val onContainer = if (ready) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 新建前置门（用户定案 2026-10-30）：a11y / 无 a11y 至少一条线路就绪即可新建；
 * 两条都缺才弹本门。缺哪项列哪项，修复入口统一指向无障碍设置 / Shizuku 设置页
 * （Shizuku 走通后录屏抽帧自动完成，不设人工按钮）。
 */
@Composable
private fun DualReadyGateDialog(
    capabilities: RpaCapabilities,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_rpa_gate_dual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.float_rpa_gate_dual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!capabilities.a11yReady) {
                    Text(
                        text = stringResource(R.string.float_rpa_gate_dual_a11y),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.float_rpa_gate_go_a11y))
                    }
                }
                if (!capabilities.shizukuReady) {
                    Text(
                        text = stringResource(R.string.float_rpa_gate_dual_shizuku),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = onOpenShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.float_rpa_open_shizuku))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.float_rpa_gate_refresh))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_cancel))
            }
        },
    )
}

/**
 * 运行模式门（用户定案「单击运行先选可用运行模式」）：按脚本支持的模式给候选线
 * （VISION-only 只能无 a11y、A11Y-only 只能 a11y、DUAL 二选一），未就绪模式置灰
 * 不可选并给直达修复入口；确认即以选定线路锁线运行（引擎禁止换线）。
 */
@Composable
internal fun RunModeSelectDialog(
    scriptMode: RpaMode,
    capabilities: RpaCapabilities,
    onDismiss: () -> Unit,
    onConfirm: (RpaLine) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val candidates = when (scriptMode) {
        RpaMode.A11Y -> listOf(RpaLine.A11Y)
        RpaMode.VISION -> listOf(RpaLine.VISION)
        RpaMode.DUAL -> listOf(RpaLine.A11Y, RpaLine.VISION)
    }

    fun lineReady(line: RpaLine): Boolean = when (line) {
        RpaLine.A11Y -> capabilities.a11yModeReady()
        RpaLine.VISION -> capabilities.visionModeReady()
    }

    var selected by remember(scriptMode) { mutableStateOf(candidates.firstOrNull { lineReady(it) }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (scriptMode == RpaMode.DUAL) {
                        R.string.float_rpa_run_mode_title
                    } else {
                        R.string.float_rpa_run_gate_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { candidate ->
                    val ready = lineReady(candidate)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ready) { selected = candidate },
                    ) {
                        RadioButton(
                            selected = selected == candidate,
                            onClick = { if (ready) selected = candidate },
                            enabled = ready,
                        )
                        Text(
                            stringResource(
                                if (candidate == RpaLine.A11Y) {
                                    R.string.float_rpa_mode_line_a11y
                                } else {
                                    R.string.float_rpa_mode_line_vision
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ready) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        CapChip(
                            text = stringResource(
                                if (ready) {
                                    R.string.float_rpa_mode_status_ready
                                } else {
                                    R.string.float_rpa_mode_status_missing
                                },
                            ),
                            ready = ready,
                        )
                    }
                }
                // 缺项修复入口（与新建前置门同款）：只列候选模式缺的项；
                // 无 a11y 线缺项统一指向 Shizuku 设置页（录屏抽帧走通后自动完成）。
                if (RpaLine.A11Y in candidates && !capabilities.a11yReady) {
                    OutlinedButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.float_rpa_gate_go_a11y))
                    }
                }
                if (RpaLine.VISION in candidates && !capabilities.visionModeReady()) {
                    OutlinedButton(
                        onClick = onOpenShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.float_rpa_open_shizuku))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected?.let { lineReady(it) } == true,
                onClick = { selected?.let(onConfirm) },
            ) {
                Text(stringResource(R.string.float_rpa_run))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_cancel))
            }
        },
    )
}

/**
 * 脚本行（图1）：`[icon] 名称 [运行] ›` + 次行 `大小 · 修改时间`；
 * 运行中行整行着色并带 `第 N/M 步` 进度条，按钮变「停止」。
 * 单击=属性页；长按=触觉反馈 + 操作菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RpaScriptRow(
    script: RpaScript,
    running: RpaRunController.State.Running?,
    finished: RpaRunController.State.Finished?,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val runningNow = running != null
    val timeText = remember(script.id, script.updatedAt) {
        if (script.updatedAt > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(script.updatedAt))
        } else {
            ""
        }
    }
    val kb = remember(script.id, script.updatedAt) {
        runCatching { RpaJsonCodec.encode(script).size / 1024.0 }.getOrDefault(0.0)
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (runningNow) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 0.dp),
                )
                Text(
                    text = stringResource(R.string.float_rpa_script_steps, script.steps.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onRun,
                    colors = if (runningNow) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (runningNow) R.string.float_rpa_stop else R.string.float_rpa_run,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    R.string.float_rpa_script_meta,
                    stringResource(R.string.float_rpa_script_kb, kb),
                    timeText,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
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
        }
    }
}

/** 脚本长按菜单（§7.3 第一套）：ModalBottomSheet，行高 ≥48dp，删除为危险项。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RpaScriptMenuSheet(
    script: RpaScript,
    runningNow: Boolean,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = script.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            RpaMenuRow(
                text = stringResource(
                    if (runningNow) R.string.float_rpa_stop else R.string.float_rpa_run,
                ),
                onClick = onRun,
            )
            RpaMenuRow(text = stringResource(R.string.float_rpa_open), onClick = onEdit)
            RpaMenuRow(text = stringResource(R.string.float_rpa_property), onClick = onOpen)
            RpaMenuRow(
                text = stringResource(R.string.float_rpa_duplicate),
                enabled = !runningNow,
                onClick = onDuplicate,
            )
            RpaMenuRow(
                text = stringResource(R.string.float_rpa_rename),
                enabled = !runningNow,
                onClick = onRename,
            )
            RpaMenuRow(text = stringResource(R.string.float_rpa_export), onClick = onExport)
            RpaMenuRow(
                text = stringResource(R.string.float_rpa_delete),
                enabled = !runningNow,
                danger = true,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun RpaMenuRow(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun RenameScriptDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_rpa_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.float_rpa_rename_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) {
                Text(stringResource(R.string.float_rpa_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_cancel))
            }
        },
    )
}

/** 导出格式选择（决策①：三选一）。 */
@Composable
internal fun ExportFormatDialog(
    onDismiss: () -> Unit,
    onConfirm: (RpaExportFormat) -> Unit,
) {
    var format by remember { mutableStateOf(RpaExportFormat.JSON) }
    val options = listOf(
        RpaExportFormat.JSON to stringResource(R.string.float_rpa_export_json),
        RpaExportFormat.XML to stringResource(R.string.float_rpa_export_xml),
        RpaExportFormat.PACKAGE to stringResource(R.string.float_rpa_export_package),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_rpa_export_title)) },
        text = {
            Column {
                options.forEach { (option, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { format = option },
                    ) {
                        RadioButton(selected = format == option, onClick = { format = option })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(format) }) {
                Text(stringResource(R.string.float_rpa_export_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_cancel))
            }
        },
    )
}

/** 导入摘要确认（§九：步骤数 / 模板数 / 含规划中动作需提示）。 */
@Composable
internal fun ImportConfirmDialog(
    draft: RpaImportDraft,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_rpa_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = draft.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.float_rpa_import_meta,
                        draft.stepCount,
                        draft.templateCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.p2Count > 0) {
                    Text(
                        text = stringResource(R.string.float_rpa_import_p2_note, draft.p2Count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (draft.templateCount == 0) {
                    Text(
                        text = stringResource(R.string.float_rpa_import_no_template_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.float_rpa_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_cancel))
            }
        },
    )
}

/**
 * 新建脚本弹框（规格 §4.1）：三枚模式芯片恒显示，前置不具备的模式置灰不可选 +
 * 一行简洁原因 + 直达修复按钮（去开启无障碍 / 打开 Shizuku）；就绪变化经
 * 能力刷新自动解灰，「确定」只放行已就绪的模式。
 */
@Composable
private fun CreateScriptDialog(
    defaultPackage: String,
    capabilities: RpaCapabilities,
    onDismiss: () -> Unit,
    onConfirm: (name: String, mode: RpaMode, appPackage: String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val a11yReady = capabilities.a11yModeReady()
    val visionReady = capabilities.visionModeReady()
    val a11yReason = stringResource(R.string.float_rpa_mode_disabled_a11y)
    val visionReason = stringResource(R.string.float_rpa_mode_disabled_vision)
    val dualReason = stringResource(R.string.float_rpa_mode_disabled_dual)
    // 置灰集：A11Y 看无障碍、VISION 看 Shizuku、DUAL 需两条线路都就绪。
    val disabledModes = remember(a11yReady, visionReady, a11yReason, visionReason, dualReason) {
        buildMap {
            if (!a11yReady) put(RpaMode.A11Y, a11yReason)
            if (!visionReady) put(RpaMode.VISION, visionReason)
            if (!a11yReady || !visionReady) put(RpaMode.DUAL, dualReason)
        }
    }
    var name by remember { mutableStateOf("") }
    var mode by remember {
        mutableStateOf(
            when {
                a11yReady && visionReady -> RpaMode.DUAL
                a11yReady -> RpaMode.A11Y
                visionReady -> RpaMode.VISION
                // 弹框只在至少一条线路就绪时打开（requestCreate 门），此处兜底不空崩溃。
                else -> RpaMode.A11Y
            },
        )
    }
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
                RpaModeSelector(
                    mode = mode,
                    modes = RpaMode.entries,
                    disabledModes = disabledModes,
                    onMode = { mode = it },
                )
                // 修复入口（§4.1）：缺哪条线路就给哪条的直达按钮。
                if (!a11yReady) {
                    OutlinedButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.float_rpa_gate_go_a11y))
                    }
                }
                if (!visionReady) {
                    OutlinedButton(
                        onClick = onOpenShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.float_rpa_open_shizuku))
                    }
                }
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
                enabled = name.isNotBlank() && mode !in disabledModes,
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
