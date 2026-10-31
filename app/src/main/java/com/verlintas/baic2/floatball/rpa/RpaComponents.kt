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

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaActionCategory
import com.verlintas.rpa.model.RpaBounds
import com.verlintas.rpa.model.RpaDirection
import com.verlintas.rpa.model.RpaIssue
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaRect
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------- 文案映射 ----------------------------

@Composable
internal fun rpaModeLabel(mode: RpaMode): String = stringResource(
    when (mode) {
        RpaMode.A11Y -> R.string.float_rpa_mode_a11y
        RpaMode.VISION -> R.string.float_rpa_mode_vision
        RpaMode.DUAL -> R.string.float_rpa_mode_dual
    },
)

@Composable
internal fun rpaLineLabel(line: RpaLine): String = stringResource(
    when (line) {
        RpaLine.A11Y -> R.string.float_rpa_line_a11y
        RpaLine.VISION -> R.string.float_rpa_line_vision
    },
)

/** 动作名资源（非 Composable 场景也能取，如运行通知）。 */
internal fun rpaActionLabelRes(action: RpaAction): Int = when (action) {
    RpaAction.LAUNCH_APP -> R.string.float_rpa_action_launch_app
    RpaAction.OPEN_CHAT -> R.string.float_rpa_action_open_chat
    RpaAction.WAIT -> R.string.float_rpa_action_wait
    RpaAction.WAIT_FOR_TARGET -> R.string.float_rpa_action_wait_target
    RpaAction.TAP -> R.string.float_rpa_action_tap
    RpaAction.LONG_PRESS -> R.string.float_rpa_action_long_press
    RpaAction.INPUT_TEXT -> R.string.float_rpa_action_input_text
    RpaAction.PRESS_KEY -> R.string.float_rpa_action_press_key
    RpaAction.SWIPE -> R.string.float_rpa_action_swipe
    RpaAction.SCROLL -> R.string.float_rpa_action_scroll
    RpaAction.ASSERT_TEXT -> R.string.float_rpa_action_assert_text
    RpaAction.COMMENT -> R.string.float_rpa_action_comment
    RpaAction.OPEN_LINK -> R.string.float_rpa_action_open_link
    RpaAction.SET_VARIABLE -> R.string.float_rpa_action_set_variable
    RpaAction.CONTROL_FLOW -> R.string.float_rpa_action_control_flow
    RpaAction.COMPOSITE -> R.string.float_rpa_action_composite
    RpaAction.TAP_COLOR -> R.string.float_rpa_action_tap_color
    RpaAction.GESTURE_MULTI -> R.string.float_rpa_action_gesture_multi
}

@Composable
internal fun rpaActionLabel(action: RpaAction): String =
    stringResource(rpaActionLabelRes(action))

@Composable
internal fun rpaCategoryLabel(category: RpaActionCategory): String = stringResource(
    when (category) {
        RpaActionCategory.APP -> R.string.float_rpa_category_app
        RpaActionCategory.FLOW -> R.string.float_rpa_category_flow
        RpaActionCategory.TARGET -> R.string.float_rpa_category_target
        RpaActionCategory.INPUT -> R.string.float_rpa_category_input
        RpaActionCategory.GESTURE -> R.string.float_rpa_category_gesture
        RpaActionCategory.ASSERT -> R.string.float_rpa_category_assert
    },
)

@Composable
internal fun rpaMatchLabel(mode: RpaMatchMode): String = stringResource(
    when (mode) {
        RpaMatchMode.EXACT -> R.string.float_rpa_match_exact
        RpaMatchMode.CONTAINS -> R.string.float_rpa_match_contains
        RpaMatchMode.PREFIX -> R.string.float_rpa_match_prefix
        RpaMatchMode.REGEX -> R.string.float_rpa_match_regex
    },
)

@Composable
internal fun rpaPickLabel(pick: RpaPick): String = stringResource(
    when (pick) {
        RpaPick.FIRST -> R.string.float_rpa_pick_first
        RpaPick.TOP_MOST -> R.string.float_rpa_pick_top
        RpaPick.BOTTOM_MOST -> R.string.float_rpa_pick_bottom
        RpaPick.LARGEST -> R.string.float_rpa_pick_largest
        RpaPick.NEAREST -> R.string.float_rpa_pick_nearest
    },
)

@Composable
internal fun rpaVisionKindLabel(kind: RpaVisionKind): String = stringResource(
    when (kind) {
        RpaVisionKind.TEMPLATE -> R.string.float_rpa_vision_kind_template
        RpaVisionKind.OCR_TEXT -> R.string.float_rpa_vision_kind_ocr
        RpaVisionKind.RATIO -> R.string.float_rpa_vision_kind_ratio
    },
)

@Composable
internal fun rpaDirectionLabel(direction: RpaDirection): String = stringResource(
    when (direction) {
        RpaDirection.LEFT -> R.string.float_rpa_direction_left
        RpaDirection.RIGHT -> R.string.float_rpa_direction_right
        RpaDirection.UP -> R.string.float_rpa_direction_up
        RpaDirection.DOWN -> R.string.float_rpa_direction_down
    },
)

/** 校验 / 就绪门的稳定 code → 用户文案（未知 code 展示原始 code，便于上报）。 */
@Composable
internal fun rpaIssueText(issue: RpaIssue): String {
    val base = stringResource(
        when (issue.code) {
            "script_id_missing" -> R.string.float_rpa_issue_script_id
            "script_name_missing" -> R.string.float_rpa_issue_script_name
            "script_steps_empty" -> R.string.float_rpa_issue_steps_empty
            "step_id_duplicate" -> R.string.float_rpa_issue_step_duplicate
            "step_timeout_invalid" -> R.string.float_rpa_issue_timeout_invalid
            "step_timeout_over_limit" -> R.string.float_rpa_issue_timeout_over_limit
            "step_retries_invalid" -> R.string.float_rpa_issue_retries_invalid
            "step_retries_over_limit" -> R.string.float_rpa_issue_retries_over_limit
            "step_delay_over_limit" -> R.string.float_rpa_issue_delay_over_limit
            "param_wait_over_limit" -> R.string.float_rpa_issue_wait_over_limit
            "param_package_missing" -> R.string.float_rpa_issue_package
            "param_im_package_missing" -> R.string.float_rpa_issue_im_package
            "param_contact_missing" -> R.string.float_rpa_issue_contact
            "param_wait_missing" -> R.string.float_rpa_issue_wait
            "param_text_missing" -> R.string.float_rpa_issue_text
            "param_key_invalid" -> R.string.float_rpa_issue_key
            "param_direction_missing" -> R.string.float_rpa_issue_direction
            "param_expect_missing" -> R.string.float_rpa_issue_expect
            "target_missing" -> R.string.float_rpa_issue_target
            "mode_a11y_missing_a11y_target" -> R.string.float_rpa_issue_a11y_target
            "mode_vision_missing_vision_target" -> R.string.float_rpa_issue_vision_target
            "mode_dual_missing_a11y_target" -> R.string.float_rpa_issue_dual_a11y
            "mode_dual_missing_vision_target" -> R.string.float_rpa_issue_dual_vision
            "a11y_selector_empty" -> R.string.float_rpa_issue_selector_empty
            "a11y_selector_incomplete" -> R.string.float_rpa_issue_selector_incomplete
            "a11y_snapshot_only" -> R.string.float_rpa_issue_snapshot_only
            "vision_template_missing" -> R.string.float_rpa_issue_template_missing
            "vision_ocr_text_missing" -> R.string.float_rpa_issue_ocr_missing
            "vision_ratio_invalid" -> R.string.float_rpa_issue_ratio_invalid
            "vision_threshold_invalid" -> R.string.float_rpa_issue_threshold
            "vision_region_invalid" -> R.string.float_rpa_issue_region
            "variable_undefined" -> R.string.float_rpa_issue_variable
            "jump_target_missing" -> R.string.float_rpa_issue_jump_missing
            "param_goto_missing" -> R.string.float_rpa_issue_goto_missing
            "ready_a11y_missing" -> R.string.float_rpa_issue_ready_a11y
            "ready_vision_missing" -> R.string.float_rpa_issue_ready_vision
            "ready_vision_injection_missing" -> R.string.float_rpa_issue_ready_injection
            "ready_ocr_missing" -> R.string.float_rpa_issue_ready_ocr
            "ready_no_line" -> R.string.float_rpa_issue_ready_none
            "ready_dual_a11y_missing" -> R.string.float_rpa_issue_ready_dual_a11y
            "ready_dual_vision_missing" -> R.string.float_rpa_issue_ready_dual_vision
            "ready_template_missing" -> R.string.float_rpa_issue_ready_template
            else -> R.string.float_rpa_issue_generic
        },
        issue.code,
    )
    val detail = issue.detail
    return if (detail.isBlank()) base else "$base ($detail)"
}

// ---------------------------- 基础组件 ----------------------------

@Composable
internal fun RpaSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
internal fun RpaCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) { content() }
    }
}

@Composable
internal fun RpaModeBadge(mode: RpaMode) {
    val (container, onContainer) = when (mode) {
        RpaMode.A11Y -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        RpaMode.VISION -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        RpaMode.DUAL -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text = rpaModeLabel(mode),
            style = MaterialTheme.typography.labelMedium,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun RpaStatusBadge(text: String, ok: Boolean) {
    val container = if (ok) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = if (ok) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
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
 * a11y 缺项报因双态（§7.4 / §7.10）：对齐 `FloatBallService.showAnalyzeA11yNotice`
 * 既有语义——未开启 →「去开启无障碍」；已启用但服务未连接 →「重试连接」自愈重绑 +
 * 「去开启无障碍」手动兜底。显式报因不静默；报因文案与布局分析工具同源复用。
 *
 * [blocking]：`true`＝A11Y 模式硬前置（红、阻编辑）；`false`＝双模缺项警告（仅阻运行）。
 */
@Composable
internal fun RpaA11yNotice(
    enabledInSettings: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
    blocking: Boolean = true,
) {
    val tone = if (blocking) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(
                if (enabledInSettings) {
                    R.string.float_rpa_analyze_a11y_unbound
                } else {
                    R.string.float_rpa_gate_a11y_required
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = tone,
        )
        if (enabledInSettings) {
            OutlinedButton(onClick = onRetryConnection, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.float_rpa_analyze_rebind))
            }
        }
        OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.float_rpa_gate_go_a11y))
        }
    }
}

/**
 * 模式选择芯片组（规格 §4.1）：模式芯片恒显示；前置不具备的模式（[disabledModes]）
 * 置灰不可选，芯片下方逐模式给一行简洁原因（不进技术原理），修复入口由调用方提供。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RpaModeSelector(
    mode: RpaMode,
    enabled: Boolean = true,
    modes: List<RpaMode> = RpaMode.entries,
    disabledModes: Map<RpaMode, String> = emptyMap(),
    onMode: (RpaMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 窄屏（360dp）下三枚中文模式芯片一行放不下，FlowRow 自动换行。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { candidate ->
                FilterChip(
                    selected = mode == candidate,
                    onClick = { onMode(candidate) },
                    enabled = enabled && candidate !in disabledModes,
                    label = { Text(rpaModeLabel(candidate)) },
                )
            }
        }
        // 置灰原因行（§4.1）：格式「模式：原因」，只报简洁口径。
        modes.forEach { candidate ->
            val reason = disabledModes[candidate] ?: return@forEach
            Text(
                text = stringResource(
                    R.string.float_rpa_mode_disabled_reason,
                    rpaModeLabel(candidate),
                    reason,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun RpaIssueList(issues: List<RpaIssue>) {
    if (issues.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        issues.forEach { issue ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (issue.severity == com.verlintas.rpa.model.RpaIssueSeverity.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
                Text(
                    text = rpaIssueText(issue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

// ---------------------------- 截图 + 节点框选 ----------------------------

internal enum class FrameBoxKind {
    NODE,
    CLICKABLE,
    EDITABLE,
    SELECTED,
}

internal data class FrameBox(
    val bounds: RpaBounds,
    val kind: FrameBoxKind = FrameBoxKind.NODE,
)

/**
 * 模板匹配命中展示件（对齐以图找图结果画布口径，§7.9.2）：绿色命中框 +
 * 白十字中心 + 中心坐标 chip `(x, y)` + 命中尺寸标签。
 */
internal data class FrameHit(
    val bounds: RpaBounds,
    val centerX: Int,
    val centerY: Int,
    val width: Int,
    val height: Int,
)

private data class FitGeometry(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * 帧预览 + 节点框 + 框选：布局分析器与以图找图配置共用。
 * [draggable] 为 true 时拖拽生成归一化选区；否则点击回传像素坐标，用于选节点。
 * [hits] 是模板匹配命中（§7.9.2 结果画布展示件），可多命中叠加。
 */
@Composable
internal fun RpaFrameCanvas(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    previewHeight: Dp = 280.dp,
    boxes: List<FrameBox> = emptyList(),
    hits: List<FrameHit> = emptyList(),
    selection: RpaRect? = null,
    draggable: Boolean = false,
    onTapPixel: ((Int, Int) -> Unit)? = null,
    onSelection: ((RpaRect) -> Unit)? = null,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var canvasSize by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    var dragStart by remember(bitmap, draggable) { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember(bitmap, draggable) { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val fit = remember(canvasSize, bitmap.width, bitmap.height) {
        fitGeometry(canvasSize, bitmap.width, bitmap.height)
    }
    val borderWidth = with(density) { 1.dp.toPx() }
    val selectedWidth = with(density) { 2.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(previewHeight)
            .background(Color(0x22000000)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(bitmap, draggable, fit) {
                    if (draggable) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                dragCurrent = offset
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragCurrent = (dragCurrent ?: Offset.Zero) + amount
                            },
                            onDragEnd = {
                                val rect = selectionOf(dragStart, dragCurrent, fit, bitmap)
                                dragStart = null
                                dragCurrent = null
                                if (rect != null) onSelection?.invoke(rect)
                            },
                            onDragCancel = {
                                dragStart = null
                                dragCurrent = null
                            },
                        )
                    } else {
                        detectTapGestures { offset ->
                            val point = fit.toBitmap(offset, bitmap) ?: return@detectTapGestures
                            onTapPixel?.invoke(point.first, point.second)
                        }
                    }
                },
        ) {
            if (fit.scale > 0f) {
                val dstWidth = (bitmap.width * fit.scale).roundToInt()
                val dstHeight = (bitmap.height * fit.scale).roundToInt()
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
                    dstSize = IntSize(dstWidth, dstHeight),
                )
                boxes.forEach { box ->
                    if (box.bounds.isEmpty) return@forEach
                    val left = fit.offsetX + box.bounds.left * fit.scale
                    val top = fit.offsetY + box.bounds.top * fit.scale
                    val width = box.bounds.width * fit.scale
                    val height = box.bounds.height * fit.scale
                    val color = when (box.kind) {
                        FrameBoxKind.SELECTED -> Color(0xFF00E5FF)
                        FrameBoxKind.CLICKABLE -> Color(0xCCFFB300)
                        FrameBoxKind.EDITABLE -> Color(0xCC00E676)
                        FrameBoxKind.NODE -> Color(0x6600E5FF)
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(max(width, 1f), max(height, 1f)),
                        style = Stroke(
                            width = if (box.kind == FrameBoxKind.SELECTED) {
                                selectedWidth
                            } else {
                                borderWidth
                            },
                        ),
                    )
                }
                // 匹配命中展示件（对齐以图找图结果画布口径，§7.9.2）：
                // 绿色命中框（半透明填充 + 描边）+ 白十字中心 + 绿色中心点。
                val hitStroke = with(density) { 3.dp.toPx() }
                val hitArm = with(density) { 18.dp.toPx() }
                val hitDot = with(density) { 5.dp.toPx() }
                hits.forEach { hit ->
                    if (hit.bounds.isEmpty) return@forEach
                    val left = fit.offsetX + hit.bounds.left * fit.scale
                    val top = fit.offsetY + hit.bounds.top * fit.scale
                    val width = hit.bounds.width * fit.scale
                    val height = hit.bounds.height * fit.scale
                    val size = Size(max(width, 1f), max(height, 1f))
                    drawRect(
                        color = Color(0x3322C55E),
                        topLeft = Offset(left, top),
                        size = size,
                    )
                    drawRect(
                        color = Color(0xFF22C55E),
                        topLeft = Offset(left, top),
                        size = size,
                        style = Stroke(width = hitStroke),
                    )
                    val cx = fit.offsetX + hit.centerX * fit.scale
                    val cy = fit.offsetY + hit.centerY * fit.scale
                    drawLine(
                        color = Color.White,
                        start = Offset(cx - hitArm, cy),
                        end = Offset(cx + hitArm, cy),
                        strokeWidth = borderWidth * 2,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(cx, cy - hitArm),
                        end = Offset(cx, cy + hitArm),
                        strokeWidth = borderWidth * 2,
                    )
                    drawCircle(
                        color = Color(0xFF22C55E),
                        radius = hitDot,
                        center = Offset(cx, cy),
                    )
                }
                val live = liveSelection(dragStart, dragCurrent, fit, bitmap)
                val rect = live ?: selection
                if (rect != null) {
                    val left = fit.offsetX + (rect.left * bitmap.width) * fit.scale
                    val top = fit.offsetY + (rect.top * bitmap.height) * fit.scale
                    val width = (rect.widthRatio * bitmap.width) * fit.scale
                    val height = (rect.heightRatio * bitmap.height) * fit.scale
                    drawRect(
                        color = Color(0xFFFF3D71),
                        topLeft = Offset(left, top),
                        size = Size(max(width, 1f), max(height, 1f)),
                        style = Stroke(width = selectedWidth),
                    )
                }
            }
        }

        // 结果 chip（以图找图结果画布同款）：十字旁中心坐标、框右上角命中尺寸。
        if (fit.scale > 0f && hits.isNotEmpty()) {
            val coordChipWidth = with(density) { 96.dp.toPx() }
            val sizeChipWidth = with(density) { 118.dp.toPx() }
            val chipHeight = with(density) { 26.dp.toPx() }
            val chipGap = with(density) { 10.dp.toPx() }
            val chipInset = with(density) { 4.dp.toPx() }
            val viewWidth = canvasSize.width.toFloat()
            val viewHeight = canvasSize.height.toFloat()
            hits.forEach { hit ->
                val cx = fit.offsetX + hit.centerX * fit.scale
                val cy = fit.offsetY + hit.centerY * fit.scale
                val boxRight = fit.offsetX + hit.bounds.right * fit.scale
                val boxTop = fit.offsetY + hit.bounds.top * fit.scale
                var coordX = cx + chipGap
                if (coordX + coordChipWidth > viewWidth) coordX = cx - chipGap - coordChipWidth
                var coordY = cy + chipGap
                if (coordY + chipHeight > viewHeight) coordY = cy - chipGap - chipHeight
                ResultChip(
                    text = stringResource(
                        R.string.float_rpa_quickfind_result_coord,
                        hit.centerX,
                        hit.centerY,
                    ),
                    x = coordX.coerceIn(0f, (viewWidth - coordChipWidth).coerceAtLeast(0f)),
                    y = coordY.coerceIn(0f, (viewHeight - chipHeight).coerceAtLeast(0f)),
                )
                var sizeY = boxTop - chipHeight - chipInset
                if (sizeY < 0f) sizeY = boxTop + chipInset
                ResultChip(
                    text = stringResource(
                        R.string.float_rpa_quickfind_result_box,
                        hit.width,
                        hit.height,
                    ),
                    x = (boxRight - sizeChipWidth)
                        .coerceIn(0f, (viewWidth - sizeChipWidth).coerceAtLeast(0f)),
                    y = sizeY.coerceIn(0f, (viewHeight - chipHeight).coerceAtLeast(0f)),
                )
            }
        }
    }
}

/** 结果 chip（以图找图结果画布同款）：黑底 72%、圆角 6dp、白字 labelSmall。 */
@Composable
private fun ResultChip(text: String, x: Float, y: Float) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Black.copy(alpha = 0.72f),
        modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun fitGeometry(size: IntSize, bitmapWidth: Int, bitmapHeight: Int): FitGeometry {
    if (size.width <= 0 || size.height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return FitGeometry(0f, 0f, 0f)
    }
    val scale = min(size.width.toFloat() / bitmapWidth, size.height.toFloat() / bitmapHeight)
    val drawWidth = bitmapWidth * scale
    val drawHeight = bitmapHeight * scale
    return FitGeometry(
        scale = scale,
        offsetX = (size.width - drawWidth) / 2f,
        offsetY = (size.height - drawHeight) / 2f,
    )
}

private fun FitGeometry.toBitmap(offset: Offset, bitmap: Bitmap): Pair<Int, Int>? {
    if (scale <= 0f) return null
    val x = ((offset.x - offsetX) / scale).roundToInt()
    val y = ((offset.y - offsetY) / scale).roundToInt()
    if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return null
    return x to y
}

private fun liveSelection(
    start: Offset?,
    end: Offset?,
    fit: FitGeometry,
    bitmap: Bitmap,
): RpaRect? {
    if (start == null || end == null || fit.scale <= 0f) return null
    val a = fit.toBitmap(start, bitmap) ?: return null
    val b = fit.toBitmap(end, bitmap) ?: return null
    val left = min(a.first, b.first).toFloat() / bitmap.width
    val top = min(a.second, b.second).toFloat() / bitmap.height
    val right = max(a.first, b.first).toFloat() / bitmap.width
    val bottom = max(a.second, b.second).toFloat() / bitmap.height
    return RpaRect(left, top, right, bottom).normalized()
}

private fun selectionOf(
    start: Offset?,
    end: Offset?,
    fit: FitGeometry,
    bitmap: Bitmap,
): RpaRect? = liveSelection(start, end, fit, bitmap)?.takeIf { it.isValid() }

/** 选择器摘要：编辑器属性面板的展示口径。 */
internal fun rpaSelectorSummary(selector: RpaSelector): String = buildList {
    selector.text?.takeIf { it.isNotBlank() }?.let { add("text=\"$it\"") }
    selector.description?.takeIf { it.isNotBlank() }?.let { add("desc=\"$it\"") }
    selector.viewId?.takeIf { it.isNotBlank() }?.let { add("id=$it") }
    selector.className?.takeIf { it.isNotBlank() }?.let { add("class=$it") }
    if (selector.clickableOnly) add("clickable")
    if (selector.editableOnly) add("editable")
    if (!selector.enabledOnly) add("any-enabled")
}.joinToString(" & ").ifBlank { "∅" }

// ---------------------------- 脚本编辑辅助 ----------------------------

/** 布局分析器回填 a11y 定位：只改目标，不触碰视觉定位与其它属性。 */
internal fun RpaScript.withA11ySpec(stepId: String?, spec: RpaA11ySpec): RpaScript {
    if (stepId == null) return this
    return copy(
        steps = steps.map { step ->
            if (step.id != stepId) {
                step
            } else {
                val target = step.target ?: RpaTarget()
                step.copy(target = target.copy(a11y = spec.takeIf { it.isConfigured() }))
            }
        },
    )
}

/** 以图找图配置页回填视觉定位：兜底定位未配置时自动省略。 */
internal fun RpaScript.withVisionSpec(stepId: String?, spec: RpaVisionSpec): RpaScript {
    if (stepId == null) return this
    val normalized = spec.copy(
        fallback = spec.fallback?.takeIf { it.isConfigured() },
    )
    return copy(
        steps = steps.map { step ->
            if (step.id != stepId) {
                step
            } else {
                val target = step.target ?: RpaTarget()
                step.copy(
                    target = target.copy(
                        vision = normalized.takeIf { it.primary.isConfigured() },
                    ),
                )
            }
        },
    )
}
