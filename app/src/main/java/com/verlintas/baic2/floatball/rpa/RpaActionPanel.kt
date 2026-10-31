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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verlintas.baic2.R
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaFlowKind
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaVisionKind
import java.util.Locale

/**
 * 浮窗动作列表（母规格 §7.5）的墨板 UI：标题栏 + 步骤列表 + 底栏三段。
 *
 * - 窗口几何（宽 80% 定值居中、拖动、贴边吸附、收起圆球）归
 *   [RpaActionPanelController]，这里只发手势事件；步骤列表固定 3 行高，
 *   更多步骤内部滚动（§7.5「宽高均不可变」）；
 * - 业务动作全部回调化（[RpaActionPanelHost]），宿主 p2-6 接线，浮窗不做业务；
 * - 墨板观感固定深色（§7.8）：不随系统主题，圆角 16dp / elevation 8dp；
 * - 动作编辑浮窗（第二 overlay 窗口）的 chrome 见 [RpaActionEditWindow]。
 */
@Composable
internal fun RpaActionPanel(
    state: RpaActionPanelState,
    actions: RpaActionPanelActions,
    extraDialogs: @Composable () -> Unit = {},
) {
    // 墨板：固定深色，不随系统主题（§7.8）。
    Baic2Theme(darkTheme = true) {
        Box {
            if (state.collapsed) {
                PanelBall(actions = actions)
            } else {
                PanelChrome(state = state, actions = actions)
            }
        }
        extraDialogs()
    }
}

/** 浮窗动作列表的展示状态（运行态 / 脚本是外部状态，经 [RpaActionPanelController.update] 注入）。 */
internal data class RpaActionPanelState(
    val scriptName: String = "",
    val steps: List<RpaStep> = emptyList(),
    val selectedStepId: String? = null,
    val runState: RpaRunController.State = RpaRunController.State.Idle,
    val logsEnabled: Boolean = false,

    /** 收起为 44dp 圆球；由 controller 的 onCollapse/onExpand 翻转，宿主不要改。 */
    val collapsed: Boolean = false,
)

/** 宿主回调（p2-6 接线）：业务动作全回调化，浮窗自身不做业务。 */
internal interface RpaActionPanelHost {
    fun onOpenSettings()
    fun onClose()
    fun onEditStep(stepId: String)
    fun onMoveStep(from: Int, to: Int)
    fun onDuplicateStep(stepId: String)
    fun onToggleStep(stepId: String)
    fun onJumpTarget(stepId: String)
    fun onDeleteStep(stepId: String)
    fun onAddStep()
    fun onRun()
    fun onStop()
    fun onSave()
    fun onClearSteps()
    fun onToggleLogs()
    fun onOpenVariables()
    fun onExport()
    fun onImport()
}

/** 浮窗交互总回调：宿主动作 + 窗口手势（controller 实现窗口部分并转发宿主部分）。 */
internal interface RpaActionPanelActions : RpaActionPanelHost {
    fun onTitleDragBy(dx: Float, dy: Float)
    fun onTitleDragEnd()
    fun onCollapse()
    fun onExpand()
}

// ── 墨板固定色与字号（§7.8）────────────────────────────────────────────

/** 墨板固定色：#303030 系，不随系统主题。 */
private object InkBoard {
    val Surface = Color(0xFF303030)
    val SurfaceHigh = Color(0xFF3B3B3B)
    val OnSurface = Color(0xFFF2F2F2)
    val OnSurfaceMuted = Color(0xFFB0B0B0)
    val OnSurfaceDisabled = Color(0xFF757575)
    val Outline = Color(0xFF6B6B6B)

    /** 悬空跳转标红（与 InkDialog.Error 同色，§六-6b）。 */
    val Error = Color(0xFFEF9A9A)
}

/** 字号（§7.8）：标题 22sp SemiBold / 正文 16sp / 摘要 13sp / 徽标 11sp。 */
private val TitleTextStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
private val BodyTextStyle = TextStyle(fontSize = 16.sp)
private val SummaryTextStyle = TextStyle(fontSize = 13.sp)
private val BadgeTextStyle = TextStyle(fontSize = 11.sp)

private val PanelShape = RoundedCornerShape(16.dp)
private val RowShape = RoundedCornerShape(8.dp)

/** 阴影留白：elevation 8dp 的阴影需要窗口内的空间，避免被窗口边缘裁掉。 */
private val ShadowPad = 8.dp

/** 圆球直径（§7.5：收起缩为 44dp 圆球）。 */
private val BallSize = 44.dp

/** 步骤行视觉高 36dp + 透明 hit 扩展到 48dp，行间 4dp。 */
private val StepVisualHeight = 36.dp
private val StepHitHeight = 48.dp
private val StepGap = 4.dp

/** 「+ 添加动作」行与底栏按钮的紧凑高（用户定案 2026-10-31：不许大而贴边）。 */
private val CompactButtonHeight = 40.dp

/** 步骤列表固定高（§7.5）：3 × (48+4)dp = 156dp，更多步骤内部滚动。 */
private val StepListHeight = (StepHitHeight + StepGap) * 3

// ── 面板骨架 ──────────────────────────────────────────────────────────

@Composable
private fun PanelChrome(state: RpaActionPanelState, actions: RpaActionPanelActions) {
    Box(Modifier.padding(ShadowPad)) {
        Column(
            Modifier
                .shadow(8.dp, PanelShape)
                .clip(PanelShape)
                .background(InkBoard.Surface)
                .fillMaxWidth(),
        ) {
            PanelTitleBar(state = state, actions = actions)
            StepArea(state = state, actions = actions)
            PanelBottomBar(state = state, actions = actions)
        }
    }
}

/** 收起态：44dp 圆球，点按恢复面板（位置保留）。 */
@Composable
private fun PanelBall(actions: RpaActionPanelActions) {
    val ballDesc = stringResource(R.string.float_rpa_panel_expand)
    Box(Modifier.padding(ShadowPad)) {
        Box(
            Modifier
                .size(BallSize)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(InkBoard.Surface)
                .semantics { contentDescription = ballDesc }
                .clickable { actions.onExpand() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.float_rpa_panel_ball_label),
                style = BadgeTextStyle,
                color = InkBoard.OnSurface,
            )
        }
    }
}

/**
 * 动作编辑浮窗（第二 overlay 窗口，§7.5/§7.6）的墨板 chrome：标题栏（整条拖动 +
 * ✕ 取消当前步骤编辑）+ 内容区（[RpaStepDialog] 骨架，字段区内部滚动）。
 *
 * 窗口几何（等宽对齐列表窗、固定高 ≈ 屏高 50%、接壤吸附 / 跟随 / 独立拖动）归
 * [RpaActionPanelController]，这里只发手势事件与 ✕ 回调。
 */
@Composable
internal fun RpaActionEditWindow(
    title: String,
    onTitleDragBy: (dx: Float, dy: Float) -> Unit,
    onTitleDragEnd: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val closeDesc = stringResource(R.string.float_rpa_analyze_close)
    Baic2Theme(darkTheme = true) {
        Box(Modifier.padding(ShadowPad)) {
            Column(
                Modifier
                    .shadow(8.dp, PanelShape)
                    .clip(PanelShape)
                    .background(InkBoard.Surface)
                    .fillMaxSize(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(StepHitHeight)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { onTitleDragEnd() },
                                onDragCancel = { onTitleDragEnd() },
                            ) { _, dragAmount -> onTitleDragBy(dragAmount.x, dragAmount.y) }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = TitleTextStyle,
                        color = InkBoard.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    TitleIconButton(glyph = "✕", description = closeDesc, onClick = onClose)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

// ── 标题栏 ────────────────────────────────────────────────────────────

@Composable
private fun PanelTitleBar(state: RpaActionPanelState, actions: RpaActionPanelActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(StepHitHeight)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { actions.onTitleDragEnd() },
                    onDragCancel = { actions.onTitleDragEnd() },
                ) { _, dragAmount -> actions.onTitleDragBy(dragAmount.x, dragAmount.y) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleIconButton(
            glyph = "—",
            description = stringResource(R.string.float_rpa_panel_collapse),
        ) {
            actions.onCollapse()
        }
        Text(
            text = state.scriptName.ifBlank { stringResource(R.string.float_rpa_untitled) },
            style = TitleTextStyle,
            color = InkBoard.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        TitleIconButton(
            glyph = "⚙",
            description = stringResource(R.string.float_rpa_script_settings),
        ) {
            actions.onOpenSettings()
        }
        TitleIconButton(
            glyph = "✕",
            description = stringResource(R.string.float_rpa_analyze_close),
        ) {
            actions.onClose()
        }
    }
}

@Composable
private fun TitleIconButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(StepHitHeight)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = BodyTextStyle, color = InkBoard.OnSurfaceMuted)
    }
}

// ── 步骤区 ────────────────────────────────────────────────────────────

/** 一行步骤的展示入参（收拢成对象，避免多参函数）。 */
internal data class StepRowState(
    val step: RpaStep,
    val index: Int,
    val count: Int,
    val selected: Boolean,

    /** 全量步骤：摘要里解析「跳转到第 N 步」的目标步骤名用。 */
    val steps: List<RpaStep> = emptyList(),
)

@Composable
private fun StepArea(state: RpaActionPanelState, actions: RpaActionPanelActions, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 12.dp)) {
        // 列表固定 3 行高（§7.5）：更多步骤内部滚动，窗口高保持内容自适应。
        Box(Modifier.fillMaxWidth().height(StepListHeight)) {
            if (state.steps.isEmpty()) {
                Text(
                    text = stringResource(R.string.float_rpa_panel_steps_empty),
                    style = SummaryTextStyle,
                    color = InkBoard.OnSurfaceMuted,
                    modifier = Modifier.align(Alignment.CenterStart).padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(state.steps) { index, step ->
                        StepRow(
                            row = StepRowState(
                                step = step,
                                index = index,
                                count = state.steps.size,
                                selected = step.id == state.selectedStepId,
                                steps = state.steps,
                            ),
                            actions = actions,
                        )
                    }
                }
            }
        }
        AddStepRow(actions = actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepRow(row: StepRowState, actions: RpaActionPanelActions) {
    val step = row.step
    var menuExpanded by remember { mutableStateOf(false) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    val rowStridePx = with(LocalDensity.current) { (StepHitHeight + StepGap).toPx() }
    val stepName = step.label.ifBlank { stringResource(rpaActionLabelRes(step.action)) }
    val badgeDesc = stringResource(R.string.float_rpa_step_n, row.index + 1) + " " + stepName
    val gripDesc = stringResource(R.string.float_rpa_panel_drag_reorder)
    // 悬空跳转（GOTO 目标已被删）标红提示（§六-6b 选项②）。
    val danglingJump = step.action == RpaAction.CONTROL_FLOW &&
        step.params.flowKind == RpaFlowKind.GOTO &&
        step.params.gotoStepId?.let { id -> row.steps.none { it.id == id } } == true

    Box(Modifier.fillMaxWidth().padding(bottom = StepGap).height(StepHitHeight)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // 可点区：36dp 视觉行装进 48dp 透明 hit（点击改步骤，长按弹菜单）。
            Box(
                Modifier.weight(1f).fillMaxHeight().combinedClickable(
                    onClick = { actions.onEditStep(step.id) },
                    onLongClick = { menuExpanded = true },
                ),
            ) {
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(StepVisualHeight)
                        .graphicsLayer { translationY = dragDelta }
                        .clip(RowShape)
                        .background(
                            if (row.selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 序号圆徽 20dp，无障碍描述「第 N 步 动作名」。
                    Box(
                        Modifier
                            .padding(start = 4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(InkBoard.SurfaceHigh)
                            .semantics { contentDescription = badgeDesc },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (row.index + 1).toString(),
                            style = BadgeTextStyle,
                            color = InkBoard.OnSurfaceMuted,
                        )
                    }
                    Text(
                        text = stepLineText(step, row.steps),
                        style = BodyTextStyle,
                        color = when {
                            danglingJump -> InkBoard.Error
                            step.enabled -> InkBoard.OnSurface
                            else -> InkBoard.OnSurfaceDisabled
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                }
            }
            // 拖拽排序手柄：长按拖动，松手一次性回调 onMoveStep(from, to)。
            Box(
                Modifier
                    .size(StepHitHeight)
                    .semantics { contentDescription = gripDesc }
                    .pointerInput(step.id) {
                        detectDragGesturesAfterLongPress(
                            onDragEnd = {
                                val to = (row.index + Math.round(dragDelta / rowStridePx))
                                    .coerceIn(0, row.count - 1)
                                dragDelta = 0f
                                if (to != row.index) actions.onMoveStep(row.index, to)
                            },
                            onDragCancel = { dragDelta = 0f },
                        ) { _, dragAmount -> dragDelta += dragAmount.y }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "≡", style = BodyTextStyle, color = InkBoard.OnSurfaceDisabled)
            }
        }

        // 长按锚定小菜单（§7.3）：上移 / 下移 / 复制 / 停用（启用）/ 跳转到此步 / 删除。
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = InkBoard.SurfaceHigh,
        ) {
            PanelMenuItem(text = stringResource(R.string.float_rpa_move_up), enabled = row.index > 0) {
                menuExpanded = false
                actions.onMoveStep(row.index, row.index - 1)
            }
            PanelMenuItem(
                text = stringResource(R.string.float_rpa_move_down),
                enabled = row.index < row.count - 1,
            ) {
                menuExpanded = false
                actions.onMoveStep(row.index, row.index + 1)
            }
            PanelMenuItem(text = stringResource(R.string.float_rpa_duplicate)) {
                menuExpanded = false
                actions.onDuplicateStep(step.id)
            }
            PanelMenuItem(
                text = stringResource(
                    if (step.enabled) R.string.float_rpa_panel_step_disable
                    else R.string.float_rpa_prop_enabled,
                ),
            ) {
                menuExpanded = false
                actions.onToggleStep(step.id)
            }
            PanelMenuItem(text = stringResource(R.string.float_rpa_panel_jump_here)) {
                menuExpanded = false
                actions.onJumpTarget(step.id)
            }
            PanelMenuItem(text = stringResource(R.string.float_rpa_delete)) {
                menuExpanded = false
                actions.onDeleteStep(step.id)
            }
        }
    }
}

@Composable
private fun PanelMenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = text, style = BodyTextStyle) },
        onClick = onClick,
        enabled = enabled,
    )
}

/** 「+ 添加动作」虚线边框行。 */
@Composable
private fun AddStepRow(actions: RpaActionPanelActions) {
    val addDesc = stringResource(R.string.float_rpa_panel_add_step)
    Box(
        Modifier
            .fillMaxWidth()
            .height(CompactButtonHeight)
            .drawBehind {
                drawRoundRect(
                    color = InkBoard.Outline,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                    ),
                )
            }
            .semantics { contentDescription = addDesc }
            .clickable { actions.onAddStep() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+ " + stringResource(R.string.float_rpa_panel_add_step),
            style = BodyTextStyle,
            color = InkBoard.OnSurfaceMuted,
        )
    }
}

// ── 底栏三段：运行 | 录制（P2 置灰）| ••• ─────────────────────────────

@Composable
private fun PanelBottomBar(state: RpaActionPanelState, actions: RpaActionPanelActions) {
    val running = state.runState is RpaRunController.State.Running
    var moreExpanded by remember { mutableStateOf(false) }
    val moreDesc = stringResource(R.string.float_rpa_property_menu)

    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ▶ 运行 / ■ 停止：Baic2 主色 filled 主键。
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(CompactButtonHeight)
                .clickable { if (running) actions.onStop() else actions.onRun() },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (running) {
                        "■ " + stringResource(R.string.float_rpa_stop)
                    } else {
                        "▶ " + stringResource(R.string.float_rpa_run)
                    },
                    style = BodyTextStyle,
                )
            }
        }

        BottomDivider()

        // 录制：P2 置灰 + 「即将开放」，不做假入口。
        Column(
            Modifier
                .weight(1f)
                .height(CompactButtonHeight)
                .clickable(enabled = false, onClick = {}),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.float_rpa_record),
                style = BodyTextStyle,
                color = InkBoard.OnSurfaceDisabled,
            )
            Text(
                text = stringResource(R.string.float_rpa_property_coming_soon),
                style = BadgeTextStyle,
                color = InkBoard.OnSurfaceDisabled,
            )
        }

        BottomDivider()

        // ••• 锚定纵向菜单（§7.3）。
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(StepHitHeight)
                    .semantics { contentDescription = moreDesc }
                    .clickable { moreExpanded = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "•••", style = BodyTextStyle, color = InkBoard.OnSurfaceMuted)
            }
            DropdownMenu(
                expanded = moreExpanded,
                onDismissRequest = { moreExpanded = false },
                containerColor = InkBoard.SurfaceHigh,
            ) {
                PanelMenuItem(text = stringResource(R.string.float_rpa_panel_save_script)) {
                    moreExpanded = false
                    actions.onSave()
                }
                // 远程编辑：P2 置灰 + 「即将开放」。
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.float_rpa_panel_remote_edit), style = BodyTextStyle)
                            Text(
                                text = " " + stringResource(R.string.float_rpa_property_coming_soon),
                                style = BadgeTextStyle,
                            )
                        }
                    },
                    onClick = {},
                    enabled = false,
                )
                PanelMenuItem(text = stringResource(R.string.float_rpa_panel_clear_steps)) {
                    moreExpanded = false
                    actions.onClearSteps()
                }
                PanelMenuItem(text = stringResource(R.string.float_rpa_panel_shrink)) {
                    moreExpanded = false
                    actions.onCollapse()
                }
                PanelMenuItem(
                    text = stringResource(
                        if (state.logsEnabled) R.string.float_rpa_panel_logs_off
                        else R.string.float_rpa_panel_logs_on,
                    ),
                ) {
                    moreExpanded = false
                    actions.onToggleLogs()
                }
                PanelMenuItem(text = stringResource(R.string.float_rpa_panel_view_variables)) {
                    moreExpanded = false
                    actions.onOpenVariables()
                }
                PanelMenuItem(text = stringResource(R.string.float_rpa_export)) {
                    moreExpanded = false
                    actions.onExport()
                }
                PanelMenuItem(text = stringResource(R.string.float_rpa_import)) {
                    moreExpanded = false
                    actions.onImport()
                }
            }
        }
    }
}

@Composable
private fun BottomDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(24.dp)
            .background(InkBoard.Outline),
    )
}

// ── 步骤行文案：动作名(摘要) + ×N（拼法自持） ──

@Composable
private fun stepLineText(step: RpaStep, steps: List<RpaStep>): String {
    val name = step.label.ifBlank { stringResource(rpaActionLabelRes(step.action)) }
    val summary = stepSummary(step, steps)
    val base = if (summary.isBlank()) name else "$name($summary)"
    return if (step.repeat > 1) {
        "$base " + stringResource(R.string.float_rpa_panel_repeat_suffix, step.repeat)
    } else {
        base
    }
}

/**
 * 步骤摘要（§7.5 示例 `点击(79.9%, 100.9%)`）：
 * 定位动作给视觉比例坐标，其余给关键参数；空摘要省略括号。
 */
@Composable
private fun stepSummary(step: RpaStep, steps: List<RpaStep>): String = when (step.action) {
    RpaAction.LAUNCH_APP -> step.params.packageName.orEmpty()

    RpaAction.OPEN_CHAT -> listOfNotNull(step.params.imPackage, step.params.contactName)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    RpaAction.WAIT -> step.params.waitMs?.let(::formatSeconds).orEmpty()

    RpaAction.WAIT_FOR_TARGET,
    RpaAction.TAP,
    RpaAction.LONG_PRESS,
    RpaAction.TAP_COLOR,
    -> targetSummary(step)

    RpaAction.INPUT_TEXT -> step.params.text.orEmpty()

    RpaAction.PRESS_KEY -> step.params.key.orEmpty()

    RpaAction.SWIPE -> listOfNotNull(
        step.params.direction?.let { rpaDirectionLabel(it) },
        step.params.distanceRatio?.let { String.format(Locale.US, "%.0f%%", it * 100) },
    ).joinToString(" · ")

    RpaAction.SCROLL -> ""

    RpaAction.ASSERT_TEXT -> step.expect?.text.orEmpty()

    RpaAction.COMMENT -> step.note

    RpaAction.OPEN_LINK -> step.params.url.orEmpty()

    RpaAction.SET_VARIABLE -> step.params.assignments
        .map { it.variableName }
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    RpaAction.CONTROL_FLOW -> when (step.params.flowKind) {
        RpaFlowKind.STOP -> stringResource(R.string.float_rpa_stop)

        RpaFlowKind.GOTO -> step.params.gotoStepId?.let { targetId ->
            "→ " + (
                steps.firstOrNull { it.id == targetId }?.let { target ->
                    target.label.ifBlank { stringResource(rpaActionLabelRes(target.action)) }
                } ?: targetId
                )
        }.orEmpty()

        null -> ""
    }

    RpaAction.COMPOSITE -> step.params.customActionId.orEmpty()

    RpaAction.GESTURE_MULTI -> targetSummary(step)
}

/** 定位动作摘要：视觉比例坐标优先（§7.5 示例），其次 OCR 文本 / a11y 选择器。 */
@Composable
private fun targetSummary(step: RpaStep): String {
    val vision = step.target?.vision?.primary
    if (vision != null && vision.kind == RpaVisionKind.RATIO) {
        return String.format(Locale.US, "%.1f%%, %.1f%%", vision.ratioX * 100, vision.ratioY * 100)
    }
    vision?.ocrText?.takeIf { it.isNotBlank() }?.let { return it }
    val selector = step.target?.a11y?.selectors?.firstOrNull { !it.isEmpty() }
    return selector?.let { rpaSelectorSummary(it) }.orEmpty()
}

private fun formatSeconds(ms: Long): String =
    if (ms % 1000L == 0L) "${ms / 1000L}s" else String.format(Locale.US, "%.1fs", ms / 1000.0)
