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
 * - **双模 / 视觉缺项仅警告**：Shizuku / 录屏 / OCR 未就绪只提示「仅影响运行，
 *   不影响编辑」，不阻断进入编辑（阻运行归运行就绪门，与本门分工）。
 */
@Composable
internal fun RpaModeGateDialog(
    script: RpaScript,
    capabilities: RpaCapabilities,
    onDismiss: () -> Unit,
    onConfirm: (RpaMode) -> Unit,
    onRefreshCapabilities: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onGrantProjection: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    var mode by remember { mutableStateOf(script.mode) }
    val a11yBlocked = mode == RpaMode.A11Y && !capabilities.a11yReady
    val visionUnready = !capabilities.shizukuReady ||
        !capabilities.projectionReady ||
        !capabilities.ocrReady

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
                    RpaStatusBadge(
                        stringResource(R.string.float_rpa_cap_projection),
                        capabilities.projectionReady,
                    )
                    RpaStatusBadge(stringResource(R.string.float_rpa_cap_ocr), capabilities.ocrReady)
                }
                if (a11yBlocked) {
                    Text(
                        text = stringResource(R.string.float_rpa_gate_a11y_required),
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
                if (visionUnready && mode != RpaMode.A11Y) {
                    Text(
                        text = stringResource(R.string.float_rpa_gate_warn_vision),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    if (!capabilities.shizukuReady) {
                        OutlinedButton(
                            onClick = onOpenShizuku,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.float_rpa_open_shizuku))
                        }
                    }
                    if (!capabilities.projectionReady && !capabilities.shizukuReady) {
                        OutlinedButton(
                            onClick = onGrantProjection,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.float_rpa_grant_projection))
                        }
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
