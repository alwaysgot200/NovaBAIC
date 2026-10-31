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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaScript

/**
 * 模式确认门（方案 §7.4）：点「编辑」先确认运行模式与就绪状态。
 *
 * - **A11Y 硬前置**：选「无障碍」模式且无障碍服务未开启 → 「进入编辑」禁用，
 *   给出「去开启无障碍」直达系统设置（返回后宿主 `ON_RESUME` 自动复检徽标）。
 * - **双模 / 视觉缺项仅警告**：Shizuku 未就绪只提示「仅影响运行，不影响编辑」，
 *   不阻断进入编辑（阻运行归运行就绪门，与本门分工）。
 * - **就绪状态不向用户暴露技术细节（用户定案 2026-10-31）**：只显示无障碍 /
 *   Shizuku 两个徽标；录屏 / OCR 不展示——录屏抽帧由 Shizuku 在运行时自动拉起
 *   （appops 静默授权），就绪口径已并入 Shizuku，无需单独呈现条件。
 */
@Composable
internal fun RpaModeGateDialog(
    script: RpaScript,
    capabilities: RpaCapabilities,
    onDismiss: () -> Unit,
    onConfirm: (RpaMode) -> Unit,
    onRefreshCapabilities: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRetryA11yConnection: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    var mode by remember { mutableStateOf(script.mode) }
    val a11yBlocked = mode == RpaMode.A11Y && !capabilities.a11yReady
    // 无 a11y 线就绪口径（用户定案 2026-10-30）：Shizuku shell 正常即可；
    // 录屏抽帧走通 Shizuku 后自动完成，不设人工按钮、不进就绪条件。
    val visionUnready = !capabilities.shizukuReady

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_rpa_gate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RpaModeSelector(mode = mode, enabled = true, onMode = { mode = it })
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RpaSectionTitle(stringResource(R.string.float_rpa_gate_ready))
                    TextButton(onClick = onRefreshCapabilities) {
                        Text(stringResource(R.string.float_rpa_gate_refresh))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    RpaStatusBadge(stringResource(R.string.float_rpa_cap_a11y), capabilities.a11yReady)
                    RpaStatusBadge(stringResource(R.string.float_rpa_cap_shizuku), capabilities.shizukuReady)
                }
                // a11y 报因双态（§7.4）：未开启 →「去开启」；已启用未连接 →「重试连接」
                // 自愈重绑 + 「去开启无障碍」手动兜底。A11Y 模式阻编辑，双模仅警告。
                if (!capabilities.a11yReady && mode != RpaMode.VISION) {
                    RpaA11yNotice(
                        enabledInSettings = capabilities.a11yEnabledInSettings,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onRetryConnection = onRetryA11yConnection,
                        blocking = a11yBlocked,
                    )
                }
                if (visionUnready && mode != RpaMode.A11Y) {
                    Text(
                        text = stringResource(R.string.float_rpa_gate_warn_vision),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    // 无 a11y 线缺项修复入口统一指向 Shizuku 设置页。
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
                enabled = !a11yBlocked,
                onClick = { onConfirm(mode) },
            ) {
                Text(stringResource(R.string.float_rpa_gate_enter))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_cancel))
            }
        },
    )
}
