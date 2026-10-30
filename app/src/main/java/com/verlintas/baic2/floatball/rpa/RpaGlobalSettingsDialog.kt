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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.rpa.model.RpaGlobalSetting
import com.verlintas.rpa.model.RpaLimits

/**
 * 脚本全局设置弹框（母规格 §7.7 图12）：墨板固定深色（§7.8），in-window 绘制，
 * 落盘目标是 [RpaScript.global]（[RpaGlobalSetting]）。
 *
 * - 默认等待（秒，**选填**）：空 = 不回退；步骤 delayAfterMs == 0 时引擎回退该值；
 * - 重复次数：整脚本循环次数（0 = 跑一遍，上限 [RpaLimits.MAX_REPEAT]）；
 * - ☑ 有动作失败立即暂停（参考默认勾选）；
 * - 全局监听动作 / 全局手势变形：P2 占位，禁用 + 「即将开放」徽标（禁假入口）。
 *
 * 确定前做行内校验（数字），不合法禁用「确定」并报红。
 */
@Composable
internal fun RpaGlobalSettingsDialog(
    global: RpaGlobalSetting,
    onConfirm: (RpaGlobalSetting) -> Unit,
    onDismiss: () -> Unit,
) {
    var waitSec by remember {
        mutableStateOf(global.defaultWaitSec?.toString().orEmpty())
    }
    var repeat by remember { mutableStateOf(global.repeat.toString()) }
    var stopOnFailure by remember { mutableStateOf(global.stopOnFailure) }

    val numberError = stringResource(R.string.float_rpa_stepdlg_error_number)
    val errors = buildList {
        if (waitSec.isNotBlank() && waitSec.trim().toDoubleOrNull() == null) add(numberError)
        if (repeat.isNotBlank() && repeat.trim().toIntOrNull() == null) add(numberError)
    }

    Baic2Theme(darkTheme = true) {
        Box(Modifier.fillMaxWidth()) {
            InkDialogShell(
                title = stringResource(R.string.float_rpa_global_title),
                onDismiss = onDismiss,
            ) {
                FieldRow(stringResource(R.string.float_rpa_global_default_wait)) {
                    InkUnitInput(
                        value = waitSec,
                        unit = stringResource(R.string.float_rpa_stepdlg_unit_sec),
                        onValueChange = { waitSec = it },
                        placeholder = stringResource(R.string.float_rpa_stepdlg_optional),
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
                FieldRow(stringResource(R.string.float_rpa_global_repeat)) {
                    InkTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 整脚本循环次数口径提示（0 = 跑一遍）。
                Text(
                    text = stringResource(R.string.float_rpa_global_repeat_hint),
                    style = InkTextStyles.Summary,
                    color = InkDialog.OnSurfaceMuted,
                    modifier = Modifier.padding(start = 64.dp, bottom = 4.dp),
                )
                InkCheckRow(
                    checked = stopOnFailure,
                    label = stringResource(R.string.float_rpa_global_stop_on_failure),
                    onCheckedChange = { stopOnFailure = it },
                )

                // P2 占位：全局监听动作 / 全局手势变形（存而不执行，禁假入口）。
                PlaceholderRow(
                    label = stringResource(R.string.float_rpa_global_listener),
                    placeholder = stringResource(R.string.float_rpa_cond_none),
                )
                PlaceholderRow(
                    label = stringResource(R.string.float_rpa_global_gesture),
                    placeholder = stringResource(R.string.float_rpa_stepdlg_optional),
                )

                ErrorList(errors)
                DialogButtons(
                    confirmEnabled = errors.isEmpty(),
                    onConfirm = {
                        onConfirm(
                            global.copy(
                                defaultWaitSec = waitSec.trim().toDoubleOrNull(),
                                repeat = repeat.trim().toIntOrNull()
                                    ?.coerceIn(0, RpaLimits.MAX_REPEAT) ?: 0,
                                stopOnFailure = stopOnFailure,
                            ),
                        )
                    },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/** P2 占位行：禁用输入 + 「即将开放」徽标（enabled=false，不响应输入）。 */
@Composable
private fun PlaceholderRow(label: String, placeholder: String) {
    FieldRow(label) {
        InkTextField(
            value = "",
            onValueChange = {},
            enabled = false,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        ComingSoonBadge()
    }
}

/** 「即将开放」徽标（口径复用属性页占位）。 */
@Composable
private fun ComingSoonBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(InkDialog.Outline)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.float_rpa_property_coming_soon),
            style = InkTextStyles.Badge,
            color = InkDialog.OnSurfaceMuted,
        )
    }
}
