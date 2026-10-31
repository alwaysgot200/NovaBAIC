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

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verlintas.baic2.R
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.shizuku.ShizukuGrantActivity
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaA11yLineParams
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaCatalog
import com.verlintas.rpa.model.RpaCompareOp
import com.verlintas.rpa.model.RpaFlowKind
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaInputChannel
import com.verlintas.rpa.model.RpaLimits
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaLineParams
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaModePolicy
import com.verlintas.rpa.model.RpaParams
import com.verlintas.rpa.model.RpaPickKind
import com.verlintas.rpa.model.RpaPickPurpose
import com.verlintas.rpa.model.RpaPickRequest
import com.verlintas.rpa.model.RpaRect
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaStepCondition
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaVarAssign
import com.verlintas.rpa.model.RpaVarSource
import com.verlintas.rpa.model.RpaVarType
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLineParams
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.store.RpaScriptStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

/**
 * 动作编辑浮窗内容（母规格 §7.6）：动作类型下拉 + 动态字段区 + 公共尾部。
 *
 * - **载体 = 动作编辑浮窗**（§7.5 双浮窗下位，第二 overlay 窗口）：窗口 chrome
 *   （标题栏拖动 / ✕ 取消编辑）由 [RpaActionEditWindow] 提供，这里只画内容；
 *   字段区内部滚动（固定高窗口内 [Column] + verticalScroll），底部按钮行固定；
 * - 字段区按四类参数三段式（规格 §一）：公共段（A 尾部 + B 载荷）→ a11y（无障碍）组
 *   → 无 a11y（shizuku）组，C/D 类分线参数各归各线，R1/R2 置灰（§4.3）；
 * - 动作类型下拉是 UI 概念清单（裁定⑦）：点击三兄弟 = `TAP` + 不同目标种类，
 *   模型枚举零增量；切换类型用 [RpaCatalog.defaultStep] 重建字段，保留公共尾部；
 * - 坐标 / 图片位置的「...」次级钮：屏幕取点走 [RpaPickRequest] 回调（宿主交给
 *   `RpaPickCoordinator.arm`，唯一拾取通道），找图走 [onOpenVisionPanel]（p2-5/6 接线）；
 * - 运行条件为子弹框（未设置 / 变量比较 / 出现图片 / 出现文字 / 上步成功 / 上步失败），
 *   选中即写回 [RpaStepCondition]，尾部回显摘要；
 * - 确定前做行内校验（URL scheme、跳转悬空、变量行等），不合法禁用「确定」并报红。
 */
@Composable
internal fun RpaStepDialog(
    step: RpaStep,
    script: RpaScript,
    onArmPick: (RpaPickRequest) -> Unit,
    onOpenVisionPanel: () -> Unit,
    onConfirm: (RpaStep) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var form by remember(step.id) { mutableStateOf(StepForm.of(step)) }
    var showCondition by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    val errors = formErrors(form, script)

    Baic2Theme(darkTheme = true) {
        Box(Modifier.fillMaxSize()) {
            // 窗口内容统一留白（用户定案 2026-10-31）：字段与底部按钮不许贴边。
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                ActionHeader(
                    form = form,
                    onFormChange = { form = it },
                    step = step,
                )
                InkDivider()
                // 字段区内部滚动（§7.6）：公共段 + a11y 组 + 无 a11y 组 + 校验报错。
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    StepFields(
                        form = form,
                        onFormChange = { form = it },
                        step = step,
                        script = script,
                        onArmPick = onArmPick,
                        onOpenVisionPanel = onOpenVisionPanel,
                        onOpenAppPicker = { showAppPicker = true },
                        onOpenCondition = { showCondition = true },
                    )
                    ErrorList(errors)
                }
                DialogButtons(
                    confirmEnabled = errors.isEmpty(),
                    onConfirm = { onConfirm(form.build(step)) },
                    onDismiss = onDismiss,
                )
            }

            // 运行条件子弹框（§7.6）。
            if (showCondition) {
                ConditionDialog(
                    condition = form.condition,
                    script = script,
                    onOpenVisionPanel = onOpenVisionPanel,
                    onCommit = {
                        form = form.copy(condition = it)
                        showCondition = false
                    },
                    onDismiss = { showCondition = false },
                )
            }

            // 选择 App 弹层（RpaInstalledApps 列表）。
            if (showAppPicker) {
                AppPickerDialog(
                    onPick = {
                        form = form.copy(packageName = it)
                        showAppPicker = false
                    },
                    onDismiss = { showAppPicker = false },
                )
            }
        }
    }
}

// ── 动作类型（UI 概念清单，裁定⑦）─────────────────────────────────────

/** 弹框动作概念：点击三兄弟共享 `TAP` 枚举，只按目标种类区分（裁定⑦）。 */
internal enum class StepConcept {
    LAUNCH_APP,
    TAP_POINT,
    TAP_IMAGE,
    TAP_TEXT,
    SET_VARIABLE,
    CONTROL_FLOW,
    OPEN_LINK,
    COMPOSITE,

    /** 概念清单之外的动作（等待 / 输入 / 按键等）：参数不动，只裁剪公共尾部。 */
    OTHER,
}

private fun conceptOf(step: RpaStep): StepConcept = when (step.action) {
    RpaAction.LAUNCH_APP -> StepConcept.LAUNCH_APP
    RpaAction.SET_VARIABLE -> StepConcept.SET_VARIABLE
    RpaAction.CONTROL_FLOW -> StepConcept.CONTROL_FLOW
    RpaAction.OPEN_LINK -> StepConcept.OPEN_LINK
    RpaAction.COMPOSITE -> StepConcept.COMPOSITE
    RpaAction.TAP -> {
        val primary = step.target?.vision?.primary
        when {
            primary?.kind == RpaVisionKind.TEMPLATE -> StepConcept.TAP_IMAGE
            primary?.kind == RpaVisionKind.OCR_TEXT -> StepConcept.TAP_TEXT
            // 坐标补 a11y 选择器后（§4.2 两侧分写）不得被 a11y 文字翻转成「点击文字」：
            // 只要视觉侧是比例坐标就保持「点击(坐标)」，视觉侧缺席才看 a11y 文字。
            primary?.kind == RpaVisionKind.RATIO -> StepConcept.TAP_POINT
            step.target?.a11y?.selectors?.any { !it.text.isNullOrBlank() } == true -> StepConcept.TAP_TEXT
            else -> StepConcept.TAP_POINT
        }
    }

    else -> StepConcept.OTHER
}

private fun StepConcept.modelAction(fallback: RpaAction): RpaAction = when (this) {
    StepConcept.LAUNCH_APP -> RpaAction.LAUNCH_APP
    StepConcept.TAP_POINT,
    StepConcept.TAP_IMAGE,
    StepConcept.TAP_TEXT,
    -> RpaAction.TAP

    StepConcept.SET_VARIABLE -> RpaAction.SET_VARIABLE
    StepConcept.CONTROL_FLOW -> RpaAction.CONTROL_FLOW
    StepConcept.OPEN_LINK -> RpaAction.OPEN_LINK
    StepConcept.COMPOSITE -> RpaAction.COMPOSITE
    StepConcept.OTHER -> fallback
}

/** 尾部裁剪（§7.6 字段矩阵「尾部」列）：设置变量无尾部、打开链接只有运行等待。 */
private val StepConcept.hasWaitTail: Boolean get() = this != StepConcept.SET_VARIABLE

private val StepConcept.hasRepeatTail: Boolean
    get() = this != StepConcept.SET_VARIABLE && this != StepConcept.OPEN_LINK

private val StepConcept.hasConditionTail: Boolean
    get() = when (this) {
        StepConcept.TAP_POINT,
        StepConcept.TAP_IMAGE,
        StepConcept.TAP_TEXT,
        StepConcept.CONTROL_FLOW,
        StepConcept.OTHER,
        -> true

        else -> false
    }

private val StepConcept.isTapFamily: Boolean
    get() = this == StepConcept.TAP_POINT || this == StepConcept.TAP_IMAGE ||
        this == StepConcept.TAP_TEXT

@Composable
private fun stepConceptLabel(concept: StepConcept, otherAction: RpaAction): String = when (concept) {
    StepConcept.LAUNCH_APP -> stringResource(R.string.float_rpa_stepdlg_concept_launch_app)
    StepConcept.TAP_POINT -> stringResource(R.string.float_rpa_action_tap)
    StepConcept.TAP_IMAGE -> stringResource(R.string.float_rpa_stepdlg_concept_tap_image)
    StepConcept.TAP_TEXT -> stringResource(R.string.float_rpa_stepdlg_concept_tap_text)
    StepConcept.SET_VARIABLE -> stringResource(R.string.float_rpa_action_set_variable)
    StepConcept.CONTROL_FLOW -> stringResource(R.string.float_rpa_stepdlg_concept_control_flow)
    StepConcept.OPEN_LINK -> stringResource(R.string.float_rpa_action_open_link)
    StepConcept.COMPOSITE -> stringResource(R.string.float_rpa_action_composite)
    StepConcept.OTHER -> stringResource(rpaActionLabelRes(otherAction))
}

@Composable
private fun stepConceptDesc(concept: StepConcept, otherAction: RpaAction): String = when (concept) {
    StepConcept.LAUNCH_APP -> stringResource(R.string.float_rpa_stepdlg_desc_launch_app)
    StepConcept.TAP_POINT -> stringResource(R.string.float_rpa_stepdlg_desc_tap_point)
    StepConcept.TAP_IMAGE -> stringResource(R.string.float_rpa_stepdlg_desc_tap_image)
    StepConcept.TAP_TEXT -> stringResource(R.string.float_rpa_stepdlg_desc_tap_text)
    StepConcept.SET_VARIABLE -> stringResource(R.string.float_rpa_stepdlg_desc_set_variable)
    StepConcept.CONTROL_FLOW -> stringResource(R.string.float_rpa_stepdlg_desc_control_flow)
    StepConcept.OPEN_LINK -> stringResource(R.string.float_rpa_stepdlg_desc_open_link)
    StepConcept.COMPOSITE -> stringResource(R.string.float_rpa_stepdlg_desc_composite)
    StepConcept.OTHER -> stringResource(R.string.float_rpa_param_new_editor_hint)
}

// ── 表单状态（字符串进 UI，确认时合成 RpaStep）────────────────────────

/**
 * 步骤编辑表单：文本字段保留原文，确认时经 [build] 合成整步（含 repeat / condition）。
 *
 * 四类参数框架（规格 §一）分三段承载：公共段（A 尾部 + B 载荷）/ a11yPart（C 的
 * a11y 组 + D 的 a11y 字段）/ visionPart（C 的 vision 组 + D 的 vision 字段）。
 * 点击三兄弟两侧分写（§4.2）：a11y 首条选择器与视觉文字各归各线，不再一条文本混写。
 */
internal data class StepForm(
    val concept: StepConcept,
    val otherAction: RpaAction = RpaAction.TAP,
    // 打开应用
    val packageName: String = "",
    val restart: Boolean = false,
    val closeOnly: Boolean = false,
    // 点击三兄弟（按下时间为公共载荷）
    val posX: String = "",
    val posY: String = "",
    val holdMs: String = "",
    val templateId: String = "",
    /** a11y 侧首条选择器；null = 未动 → [build] 时保留原首条。 */
    val tapA11ySelector: RpaSelector? = null,
    /** 无 a11y 侧识别文字（OCR），与 a11y 侧文字分线各写。 */
    val tapVisionText: String = "",
    val tapVisionMatch: RpaMatchMode = RpaMatchMode.CONTAINS,
    val matchIndex: String = "",
    // 设置变量
    val assignments: List<RpaVarAssign> = emptyList(),
    // 控制运行
    val flowKind: RpaFlowKind = RpaFlowKind.GOTO,
    val gotoStepId: String? = null,
    // 打开链接
    val url: String = "",
    // 复合动作
    val customActionId: String = "",
    val customArgValues: Map<String, String> = emptyMap(),
    // D 类分线通道参数（规格 §五 RpaStep.lineParams）：a11y 侧
    val readySelector: RpaSelector? = null,
    val inputSelector: RpaSelector? = null,
    val appendMode: Boolean = false,
    val anchorSelector: RpaSelector? = null,
    val readScope: RpaSelector? = null,
    // D 类分线通道参数：无 a11y 侧（点/区域存百分比文本，空 = 未设置）
    val readyText: String = "",
    val inputChannel: RpaInputChannel = RpaInputChannel.CLIPBOARD_PASTE,
    val clearBeforeInput: Boolean = true,
    val focusX: String = "",
    val focusY: String = "",
    val startX: String = "",
    val startY: String = "",
    val endX: String = "",
    val endY: String = "",
    val ocrLeft: String = "",
    val ocrTop: String = "",
    val ocrRight: String = "",
    val ocrBottom: String = "",
    // 公共尾部
    val waitAfterSec: String = "0",
    val repeat: String = "",
    val condition: RpaStepCondition = RpaStepCondition.None,
) {

    /**
     * 合成整步：只替换本概念管辖的字段，其余（id / label / 超时等）沿用 [base]。
     * build() 按 §一 四类分写：B 载荷 → params、C 定位 → target.a11y / target.vision
     * 各归各线、D 通道 → lineParams（规格 §五）。
     */
    fun build(base: RpaStep): RpaStep {
        var params = base.params
        var target = base.target
        when (concept) {
            StepConcept.LAUNCH_APP -> params = base.params.copy(
                packageName = packageName.trim().ifBlank { null },
                restart = restart,
                closeOnly = closeOnly,
            )

            StepConcept.TAP_POINT -> {
                // 两侧分写（§4.2）：a11y 只写节点选择器，vision 只写比例坐标，互不混写。
                val visionBase = base.target?.vision ?: RpaVisionSpec()
                target = (base.target ?: RpaTarget()).copy(
                    a11y = mergedTapA11y(base.target?.a11y),
                    vision = visionBase.copy(
                        primary = (visionBase.primary).copy(
                            kind = RpaVisionKind.RATIO,
                            ratioX = textToRatio(posX),
                            ratioY = textToRatio(posY),
                            templateId = null,
                            ocrText = null,
                        ),
                    ),
                )
                params = base.params.copy(holdMs = holdMs.trim().toIntOrNull())
            }

            StepConcept.TAP_IMAGE -> {
                // 两侧分写：a11y 节点选择器补写，vision 只写模板定位。
                val visionBase = base.target?.vision ?: RpaVisionSpec()
                target = (base.target ?: RpaTarget()).copy(
                    a11y = mergedTapA11y(base.target?.a11y),
                    vision = visionBase.copy(
                        primary = (visionBase.primary).copy(
                            kind = RpaVisionKind.TEMPLATE,
                            templateId = templateId.trim().ifBlank { null },
                            ocrText = null,
                        ),
                    ),
                )
                params = base.params.copy(holdMs = holdMs.trim().toIntOrNull())
            }

            StepConcept.TAP_TEXT -> {
                // 两侧分写（§4.2）：a11y 首条选择器只写文字/匹配，vision 只写 OCR 识别
                // 文字，不再一条文本混写两线。
                val visionBase = base.target?.vision ?: RpaVisionSpec()
                target = (base.target ?: RpaTarget()).copy(
                    a11y = mergedTapA11y(base.target?.a11y),
                    vision = visionBase.copy(
                        primary = (visionBase.primary).copy(
                            kind = RpaVisionKind.OCR_TEXT,
                            ocrText = tapVisionText.trim().ifBlank { null },
                            ocrMatch = tapVisionMatch,
                            matchIndex = matchIndex.trim().toIntOrNull(),
                        ),
                    ),
                )
                params = base.params.copy(holdMs = holdMs.trim().toIntOrNull())
            }

            StepConcept.SET_VARIABLE -> params = base.params.copy(assignments = assignments)
            StepConcept.CONTROL_FLOW -> params = base.params.copy(
                flowKind = flowKind,
                gotoStepId = if (flowKind == RpaFlowKind.GOTO) {
                    gotoStepId?.takeIf { it.isNotBlank() }
                } else {
                    null
                },
            )

            StepConcept.OPEN_LINK -> params = base.params.copy(url = url.trim())
            StepConcept.COMPOSITE -> params = base.params.copy(
                customActionId = customActionId.trim().ifBlank { null },
                customArgValues = customArgValues,
            )

            // 概念清单之外的动作：参数原样保留，只裁剪公共尾部。
            StepConcept.OTHER -> params = base.params
        }
        return base.copy(
            action = concept.modelAction(base.action),
            params = params,
            target = target,
            lineParams = RpaLineParams(
                a11y = RpaA11yLineParams(
                    readySelector = readySelector?.takeIf { !it.isEmpty() },
                    inputSelector = inputSelector?.takeIf { !it.isEmpty() },
                    appendMode = appendMode,
                    anchorSelector = anchorSelector?.takeIf { !it.isEmpty() },
                    readScope = readScope?.takeIf { !it.isEmpty() },
                ),
                vision = RpaVisionLineParams(
                    readyText = readyText.trim().ifBlank { null },
                    inputChannel = inputChannel,
                    clearBeforeInput = clearBeforeInput,
                    focusPoint = pointFromPercents(focusX, focusY),
                    startPoint = pointFromPercents(startX, startY),
                    endPoint = pointFromPercents(endX, endY),
                    ocrRegion = rectFromPercents(ocrLeft, ocrTop, ocrRight, ocrBottom),
                ),
            ),
            delayAfterMs = if (concept.hasWaitTail) {
                secTextToMs(waitAfterSec)
            } else {
                base.delayAfterMs
            },
            repeat = if (concept.hasRepeatTail) {
                repeat.trim().toIntOrNull()?.coerceIn(1, RpaLimits.MAX_REPEAT) ?: 1
            } else {
                base.repeat
            },
            runCondition = if (concept.hasConditionTail) condition else base.runCondition,
        )
    }

    /**
     * 点击三兄弟 a11y 侧分写：null = 未动 → 保留原首条；非 null → 替换首条
     * （编辑成空选择器则删除首条），其余条原样保留。
     */
    private fun mergedTapA11y(base: RpaA11ySpec?): RpaA11ySpec? {
        val edited = tapA11ySelector ?: return base
        val selectors = base?.selectors ?: emptyList()
        val normalized = edited.copy(text = edited.text?.trim()?.ifBlank { null })
        val updated = if (normalized.isEmpty()) {
            selectors.drop(1)
        } else {
            listOf(normalized) + selectors.drop(1)
        }
        return (base ?: RpaA11ySpec()).copy(selectors = updated)
    }

    companion object {

        fun of(step: RpaStep): StepForm {
            val primary = step.target?.vision?.primary
            val lineParams = step.lineParams
            return StepForm(
                concept = conceptOf(step),
                otherAction = step.action,
                packageName = step.params.packageName.orEmpty(),
                restart = step.params.restart,
                closeOnly = step.params.closeOnly,
                posX = primary?.takeIf { it.kind == RpaVisionKind.RATIO }?.ratioX?.let(::ratioToText)
                    .orEmpty(),
                posY = primary?.takeIf { it.kind == RpaVisionKind.RATIO }?.ratioY?.let(::ratioToText)
                    .orEmpty(),
                // 按下时间默认 100 毫秒（图6/图7）。
                holdMs = step.params.holdMs?.toString() ?: "100",
                templateId = primary?.takeIf { it.kind == RpaVisionKind.TEMPLATE }?.templateId
                    .orEmpty(),
                tapA11ySelector = step.target?.a11y?.selectors?.firstOrNull(),
                tapVisionText = primary?.ocrText.orEmpty(),
                tapVisionMatch = primary?.ocrMatch ?: RpaMatchMode.CONTAINS,
                matchIndex = primary?.matchIndex?.toString().orEmpty(),
                assignments = step.params.assignments,
                flowKind = step.params.flowKind ?: RpaFlowKind.GOTO,
                gotoStepId = step.params.gotoStepId,
                url = step.params.url.orEmpty(),
                customActionId = step.params.customActionId.orEmpty(),
                customArgValues = step.params.customArgValues,
                readySelector = lineParams.a11y.readySelector,
                inputSelector = lineParams.a11y.inputSelector,
                appendMode = lineParams.a11y.appendMode,
                anchorSelector = lineParams.a11y.anchorSelector,
                readScope = lineParams.a11y.readScope,
                readyText = lineParams.vision.readyText.orEmpty(),
                inputChannel = lineParams.vision.inputChannel,
                clearBeforeInput = lineParams.vision.clearBeforeInput,
                focusX = lineParams.vision.focusPoint?.left?.let(::ratioToText).orEmpty(),
                focusY = lineParams.vision.focusPoint?.top?.let(::ratioToText).orEmpty(),
                startX = lineParams.vision.startPoint?.left?.let(::ratioToText).orEmpty(),
                startY = lineParams.vision.startPoint?.top?.let(::ratioToText).orEmpty(),
                endX = lineParams.vision.endPoint?.left?.let(::ratioToText).orEmpty(),
                endY = lineParams.vision.endPoint?.top?.let(::ratioToText).orEmpty(),
                ocrLeft = lineParams.vision.ocrRegion?.left?.let(::ratioToText).orEmpty(),
                ocrTop = lineParams.vision.ocrRegion?.top?.let(::ratioToText).orEmpty(),
                ocrRight = lineParams.vision.ocrRegion?.right?.let(::ratioToText).orEmpty(),
                ocrBottom = lineParams.vision.ocrRegion?.bottom?.let(::ratioToText).orEmpty(),
                waitAfterSec = msToSecText(step.delayAfterMs),
                repeat = if (step.repeat > 1) step.repeat.toString() else "",
                condition = step.runCondition,
            )
        }
    }
}

// ── 数值换算 ──────────────────────────────────────────────────────────

/** 比例（0..1 尺度）→ 百分比文本，去掉多余 0（如 0.799 → "79.9"）。 */
private fun ratioToText(ratio: Float): String {
    val text = String.format(Locale.US, "%.4f", ratio * 100.0)
    return text.trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

/** 百分比文本 → 比例；空 / 非法回 0.5（合法输入已在行内校验拦截）。 */
private fun textToRatio(text: String): Float =
    (text.trim().toDoubleOrNull() ?: 50.0).let { (it / 100.0).toFloat() }

private fun msToSecText(ms: Long): String =
    if (ms % 1000L == 0L) {
        (ms / 1000L).toString()
    } else {
        String.format(Locale.US, "%.3f", ms / 1000.0).trimEnd('0').trimEnd('.')
    }

private fun secTextToMs(text: String): Long =
    ((text.trim().toDoubleOrNull() ?: 0.0) * 1000.0).roundToLong()
        .coerceIn(0L, RpaLimits.MAX_DELAY_MS)

/**
 * 百分比坐标对 → 点（退化矩形：right=left、bottom=top，中心解释与坐标解释一致，
 * 与 T1 模型对齐口径）。两轴都空 = 未设置。
 */
private fun pointFromPercents(x: String, y: String): RpaRect? {
    if (x.isBlank() && y.isBlank()) return null
    val ratioX = textToRatio(x)
    val ratioY = textToRatio(y)
    return RpaRect(left = ratioX, top = ratioY, right = ratioX, bottom = ratioY)
}

/** 左上/右下百分比对 → 归一化区域（可越界，与 RpaRect 同口径）；全空 = 未设置。 */
private fun rectFromPercents(left: String, top: String, right: String, bottom: String): RpaRect? {
    if (left.isBlank() && top.isBlank() && right.isBlank() && bottom.isBlank()) return null
    return RpaRect(
        left = textToRatio(left),
        top = textToRatio(top),
        right = textToRatio(right),
        bottom = textToRatio(bottom),
    )
}

// ── 行内校验（不合法禁用「确定」）──────────────────────────────────────

@Composable
private fun formErrors(form: StepForm, script: RpaScript): List<String> {
    val errors = mutableListOf<String>()
    val numberError = stringResource(R.string.float_rpa_stepdlg_error_number)
    val ratioError = stringResource(R.string.float_rpa_stepdlg_error_ratio)
    fun checkNumber(text: String) {
        if (text.isNotBlank() && text.trim().toDoubleOrNull() == null) errors += numberError
    }
    fun checkPercent(text: String) {
        val value = text.trim().toDoubleOrNull()
        if (value == null) {
            errors += numberError
        } else if (value < RpaLimits.RATIO_MIN * 100 || value > RpaLimits.RATIO_MAX * 100) {
            errors += ratioError
        }
    }

    when (form.concept) {
        StepConcept.LAUNCH_APP ->
            if (form.packageName.isBlank()) {
                errors += stringResource(R.string.float_rpa_issue_package)
            }

        StepConcept.TAP_POINT -> {
            // R1 防死锁：视觉线不在脚本模式内时坐标段置灰无法改，跳过坐标校验。
            if (RpaModePolicy.lineEnabled(script.mode, RpaLine.VISION)) {
                checkPercent(form.posX)
                checkPercent(form.posY)
            }
            checkNumber(form.holdMs)
        }

        StepConcept.TAP_TEXT -> {
            // 两侧分写后两侧（各自可编辑侧）都空才报错，单侧有目标即放行。
            val a11yConfigured = form.tapA11ySelector?.isEmpty() == false
            if (!a11yConfigured && form.tapVisionText.isBlank()) {
                errors += stringResource(R.string.float_rpa_issue_text)
            }
            checkNumber(form.holdMs)
            checkNumber(form.matchIndex)
        }

        StepConcept.SET_VARIABLE -> {
            if (form.assignments.isEmpty()) {
                errors += stringResource(R.string.float_rpa_stepdlg_error_var_empty)
            } else if (form.assignments.any { !isVariableName(form.assignmentsName(it)) }) {
                errors += stringResource(R.string.float_rpa_stepdlg_error_var_row)
            }
        }

        StepConcept.CONTROL_FLOW ->
            if (form.flowKind == RpaFlowKind.GOTO) {
                when {
                    form.gotoStepId.isNullOrBlank() ->
                        errors += stringResource(R.string.float_rpa_stepdlg_error_goto_missing)

                    script.steps.none { it.id == form.gotoStepId } ->
                        errors += stringResource(R.string.float_rpa_stepdlg_goto_dangling)
                }
            }

        StepConcept.OPEN_LINK -> {
            val scheme = form.url.trim().substringBefore(':', missingDelimiterValue = "")
                .lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                errors += stringResource(R.string.float_rpa_stepdlg_error_url_scheme)
            }
        }

        StepConcept.COMPOSITE ->
            if (form.customActionId.isBlank()) {
                errors += stringResource(R.string.float_rpa_stepdlg_error_custom_missing)
            }

        StepConcept.TAP_IMAGE -> checkNumber(form.holdMs)
        StepConcept.OTHER -> Unit
    }
    // D 类通道可选百分比校验（规格 §一矩阵按动作取用）：非空才查，区间沿用 RpaLimits；
    // R1 同口径防死锁——视觉线不在模式内时整段置灰，不拦「确定」。
    fun checkOptionalPercentPair(x: String, y: String) {
        if (x.isBlank() && y.isBlank()) return
        checkPercent(x)
        checkPercent(y)
    }
    if (RpaModePolicy.lineEnabled(script.mode, RpaLine.VISION)) {
        when (form.concept.modelAction(form.otherAction)) {
            RpaAction.INPUT_TEXT -> checkOptionalPercentPair(form.focusX, form.focusY)
            RpaAction.SWIPE, RpaAction.SCROLL -> {
                checkOptionalPercentPair(form.startX, form.startY)
                checkOptionalPercentPair(form.endX, form.endY)
            }

            RpaAction.ASSERT_TEXT -> {
                checkOptionalPercentPair(form.ocrLeft, form.ocrTop)
                checkOptionalPercentPair(form.ocrRight, form.ocrBottom)
            }

            RpaAction.SET_VARIABLE ->
                if (form.assignments.any { it.source == RpaVarSource.UI_READ }) {
                    checkOptionalPercentPair(form.ocrLeft, form.ocrTop)
                    checkOptionalPercentPair(form.ocrRight, form.ocrBottom)
                }

            else -> Unit
        }
    }
    if (form.concept.hasWaitTail) checkNumber(form.waitAfterSec)
    if (form.concept.hasRepeatTail) checkNumber(form.repeat)
    return errors
}

private fun StepForm.assignmentsName(assign: RpaVarAssign): String = assign.variableName

/** 变量名字符集与校验器约定一致：`A-Z a-z 0-9 _ . -`。 */
private fun isVariableName(name: String): Boolean =
    name.isNotBlank() && name.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }

@Composable
internal fun ErrorList(errors: List<String>) {
    if (errors.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        errors.forEach { message ->
            Text(
                text = message,
                style = InkTextStyles.Summary,
                color = InkDialog.Error,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

// ── 弹框骨架与通用控件（RpaGlobalSettingsDialog.kt 复用）──────────────

/** 墨板固定色（§7.8：弹框固定「墨板」反色系，叠任何宿主都可读）。 */
internal object InkDialog {
    val Surface = Color(0xFF303030)
    val SurfaceHigh = Color(0xFF3B3B3B)
    val OnSurface = Color(0xFFF2F2F2)
    val OnSurfaceMuted = Color(0xFFB0B0B0)
    val OnSurfaceDisabled = Color(0xFF757575)
    val Outline = Color(0xFF6B6B6B)
    val Scrim = Color(0x99000000)
    val Error = Color(0xFFEF9A9A)
    val Warn = Color(0xFFFFCC80)
}

/** 字号（§7.8）：标题 22sp SemiBold / 正文 16sp / 摘要 13sp / 徽标 11sp。 */
internal object InkTextStyles {
    val Title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val Section = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 16.sp)
    val Summary = TextStyle(fontSize = 13.sp)
    val Badge = TextStyle(fontSize = 11.sp)
}

private val CardShape = RoundedCornerShape(20.dp)
private val FieldShape = RoundedCornerShape(8.dp)

/** 弹框动画：scale 0.96→1 + fade 120ms（§7.8 动效）。 */
private const val DIALOG_ANIM_MS = 120

/**
 * in-window 弹框骨架：遮罩 + 圆角 20dp 墨板卡片，纵向可滚动；
 * 直接画在面板 overlay 的 ComposeView 内，不用 android Dialog。
 */
@Composable
internal fun InkDialogShell(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = remember { Animatable(0.96f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(DIALOG_ANIM_MS)) }
        launch { alpha.animateTo(1f, tween(DIALOG_ANIM_MS)) }
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 遮罩：点按取消（无水波纹）。
        Box(
            Modifier
                .matchParentSize()
                .background(InkDialog.Scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
                .clip(CardShape)
                .background(InkDialog.Surface)
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.86f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = title,
                style = InkTextStyles.Title,
                color = InkDialog.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun InkDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(InkDialog.Outline),
    )
}

/** 字段行：左标签 + 右输入（窄面板下纵向堆叠也允许由调用方自行组合）。 */
@Composable
internal fun FieldRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = InkTextStyles.Body,
            color = InkDialog.OnSurfaceMuted,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Spacer(Modifier.width(8.dp))
        content()
    }
}

/** 墨板文本输入（占位符「选填」等由调用方传入）。 */
@Composable
internal fun InkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .height(40.dp)
            .clip(FieldShape)
            .background(InkDialog.SurfaceHigh)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                text = placeholder,
                style = InkTextStyles.Body,
                color = InkDialog.OnSurfaceDisabled,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = InkTextStyles.Body.copy(
                color = if (enabled) InkDialog.OnSurface else InkDialog.OnSurfaceDisabled,
            ),
            cursorBrush = SolidColor(InkDialog.OnSurface),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 数字输入 + 单位块（秒 / 毫秒，§7.6 横切约定）。 */
@Composable
internal fun InkUnitInput(
    value: String,
    unit: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Decimal,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        InkTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            keyboardType = keyboardType,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .height(40.dp)
                .clip(FieldShape)
                .background(InkDialog.Outline)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = unit, style = InkTextStyles.Summary, color = InkDialog.OnSurfaceMuted)
        }
    }
}

/** 勾选行（☐/☑ 自绘，墨板风格）。 */
@Composable
internal fun InkCheckRow(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) MaterialTheme.colorScheme.primary else InkDialog.SurfaceHigh)
                .border(1.dp, InkDialog.Outline, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    style = InkTextStyles.Badge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = InkTextStyles.Body,
            color = if (enabled) InkDialog.OnSurface else InkDialog.OnSurfaceDisabled,
        )
    }
}

/** 整宽下拉（动作类型 / 枚举选择通用）。 */
@Composable
internal fun <T> InkDropdown(
    selectedLabel: String,
    options: List<T>,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    plannedLabels: List<String> = emptyList(),
) {
    var expanded by remember { mutableStateOf(false) }
    val plannedText = stringResource(R.string.float_rpa_planned)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(FieldShape)
                .background(InkDialog.SurfaceHigh)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLabel,
                style = InkTextStyles.Body,
                color = if (enabled) InkDialog.OnSurface else InkDialog.OnSurfaceDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = "▾", style = InkTextStyles.Summary, color = InkDialog.OnSurfaceMuted)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = InkDialog.SurfaceHigh,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = labelOf(option),
                            style = InkTextStyles.Body,
                            color = InkDialog.OnSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
            // P2「规划中」置灰项（§7.10）：保留位置展示路线图，不可选中（≠ 假入口）。
            plannedLabels.forEach { label ->
                DropdownMenuItem(
                    enabled = false,
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = label,
                                style = InkTextStyles.Body,
                                color = InkDialog.OnSurfaceDisabled,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = plannedText,
                                style = InkTextStyles.Badge,
                                color = InkDialog.OnSurfaceDisabled,
                            )
                        }
                    },
                    onClick = {},
                )
            }
        }
    }
}

/** 「...」次级动作钮（屏幕取点 / 找图面板）；置灰时随组只读（OnSurfaceDisabled 色）。 */
@Composable
internal fun EllipsisButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .height(40.dp)
            .clip(FieldShape)
            .background(InkDialog.Outline)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "...",
            style = InkTextStyles.Body,
            color = if (enabled) InkDialog.OnSurface else InkDialog.OnSurfaceDisabled,
        )
    }
}

/** 底部按钮行：[取消] [确定]（确定在行内校验不通过时禁用）。 */
@Composable
internal fun DialogButtons(
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.float_cancel),
                style = InkTextStyles.Body,
                color = InkDialog.OnSurfaceMuted,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (confirmEnabled) MaterialTheme.colorScheme.primary else InkDialog.Outline,
                )
                .clickable(enabled = confirmEnabled, onClick = onConfirm)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.float_rpa_stepdlg_confirm),
                style = InkTextStyles.Body,
                color = if (confirmEnabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    InkDialog.OnSurfaceDisabled
                },
            )
        }
    }
}

// ── 分线参数组（T4：四类参数分段 + R1/R2 置灰，规格 §一/§4.3）────────

/** 取 [RpaAndroidDevice] 的 Hilt 入口：弹框不在注入容器宿主内，走 EntryPoint 自取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RpaDialogDeviceEntryPoint {
    fun rpaAndroidDevice(): RpaAndroidDevice
}

/**
 * 线路就绪快照（R2 依据）：订阅 [RpaAndroidDevice.readinessChanges] 刷新能力。
 * 取不到设备时返回 null = 不置灰（不因 UI 侧兜底拦住编辑）。
 */
@Composable
private fun rememberLineReadiness(): RpaCapabilities? {
    val context = LocalContext.current
    val device = remember(context) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                RpaDialogDeviceEntryPoint::class.java,
            ).rpaAndroidDevice()
        }.getOrNull()
    }
    var capabilities by remember(device) { mutableStateOf(device?.capabilities) }
    LaunchedEffect(device) {
        if (device != null) {
            device.readinessChanges.collect { capabilities = device.capabilities }
        }
    }
    return capabilities
}

/**
 * 分线参数组（§4.3 三段式）：组头口径固定「a11y（无障碍）/ 无 a11y（shizuku）」；
 * R1（模式不含该线）整组灰只读，R2（前置不具备）灰 + 「暂不可用」+ 修复入口。
 */
@Composable
private fun LineSection(
    line: RpaLine,
    greyed: Boolean,
    unready: Boolean,
    onFix: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    InkDivider()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (line == RpaLine.A11Y) {
                    R.string.float_rpa_mode_a11y
                } else {
                    R.string.float_rpa_mode_vision
                },
            ),
            style = InkTextStyles.Section,
            color = if (greyed) InkDialog.OnSurfaceDisabled else InkDialog.OnSurface,
        )
        if (unready) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.float_rpa_lineparam_unavailable),
                style = InkTextStyles.Badge,
                color = InkDialog.Warn,
            )
        }
        Spacer(Modifier.weight(1f))
        if (unready) {
            FixButton(
                label = stringResource(
                    if (line == RpaLine.A11Y) {
                        R.string.float_rpa_gate_go_a11y
                    } else {
                        R.string.float_rpa_open_shizuku
                    },
                ),
                onClick = onFix,
            )
        }
    }
    Column(Modifier.fillMaxWidth(), content = content)
}

/** R2 修复入口药丸钮（28dp 高，复用既有跳转目标的短文案）。 */
@Composable
private fun FixButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, InkDialog.Outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = InkTextStyles.Badge, color = InkDialog.OnSurface)
    }
}

/**
 * C 类选择器行（§4.2 两侧分写）：文字 + 匹配方式 + 可选尾随钮；空 = 未设置。
 * 匹配方式下拉固定宽（InkDropdown 内部行是 fillMaxWidth，弹性宽会挤掉尾随钮）。
 */
@Composable
private fun SelectorFields(
    label: String,
    selector: RpaSelector?,
    enabled: Boolean,
    onChange: (RpaSelector) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = selector ?: RpaSelector()
    FieldRow(label) {
        InkTextField(
            value = base.text.orEmpty(),
            onValueChange = { onChange(base.copy(text = it.ifBlank { null })) },
            enabled = enabled,
            placeholder = stringResource(R.string.float_rpa_stepdlg_optional),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        InkDropdown(
            selectedLabel = rpaMatchLabel(base.textMatch),
            options = RpaMatchMode.entries,
            labelOf = { rpaMatchLabel(it) },
            onSelect = { onChange(base.copy(textMatch = it)) },
            modifier = Modifier.width(96.dp),
            enabled = enabled,
        )
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

/** D 类百分比坐标对（先点位置 / 起止点 / 区域角点）：X% + Y% + 可选尾随钮；全空 = 未设置。 */
@Composable
private fun PercentPairRow(
    label: String,
    x: String,
    y: String,
    enabled: Boolean,
    onX: (String) -> Unit,
    onY: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    FieldRow(label) {
        InkTextField(
            value = x,
            onValueChange = onX,
            enabled = enabled,
            placeholder = stringResource(R.string.float_rpa_stepdlg_axis_x),
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        InkTextField(
            value = y,
            onValueChange = onY,
            enabled = enabled,
            placeholder = stringResource(R.string.float_rpa_stepdlg_axis_y),
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

/** 输入通道文案（D 类无 a11y 侧）。 */
@Composable
private fun inputChannelLabel(channel: RpaInputChannel): String = stringResource(
    when (channel) {
        RpaInputChannel.CLIPBOARD_PASTE -> R.string.float_rpa_input_channel_clipboard
        RpaInputChannel.SHELL_INPUT -> R.string.float_rpa_input_channel_shell
    },
)

// ── 弹框内容：动作类型行 + 动态字段区 + 公共尾部 ──────────────────────

@Composable
private fun ActionHeader(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    step: RpaStep,
) {
    var infoExpanded by remember { mutableStateOf(false) }
    val options = if (form.concept == StepConcept.OTHER) {
        StepConcept.entries
    } else {
        StepConcept.entries.filter { it != StepConcept.OTHER }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.float_rpa_stepdlg_action_type),
            style = InkTextStyles.Body,
            color = InkDialog.OnSurfaceMuted,
        )
        Spacer(Modifier.width(8.dp))
        InkDropdown(
            selectedLabel = stepConceptLabel(form.concept, form.otherAction),
            options = options,
            labelOf = { stepConceptLabel(it, form.otherAction) },
            onSelect = { concept ->
                if (concept != form.concept) onFormChange(form.rebuildFor(concept, step))
            },
            modifier = Modifier.weight(1f),
            // P2 动作类型（点击颜色 / 多指手势）置灰「规划中」，不可选中（§7.10）。
            plannedLabels = listOf(
                stringResource(R.string.float_rpa_action_tap_color),
                stringResource(R.string.float_rpa_action_gesture_multi),
            ),
        )
        Spacer(Modifier.width(4.dp))
        Box {
            val infoDesc = stringResource(R.string.float_rpa_stepdlg_action_info)
            Box(
                Modifier
                    .size(40.dp)
                    .semantics { contentDescription = infoDesc }
                    .clickable { infoExpanded = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "ⓘ", style = InkTextStyles.Body, color = InkDialog.OnSurfaceMuted)
            }
            // ⓘ 动作说明（小弹层）。
            DropdownMenu(
                expanded = infoExpanded,
                onDismissRequest = { infoExpanded = false },
                containerColor = InkDialog.SurfaceHigh,
            ) {
                Text(
                    text = stepConceptDesc(form.concept, form.otherAction),
                    style = InkTextStyles.Summary,
                    color = InkDialog.OnSurface,
                    modifier = Modifier.widthIn(max = 240.dp).padding(12.dp),
                )
            }
        }
    }
}

/** 动作类型切换：[RpaCatalog.defaultStep] 重建字段，保留公共尾部（§7.6）。 */
private fun StepForm.rebuildFor(concept: StepConcept, step: RpaStep): StepForm {
    val rebuilt = RpaCatalog.defaultStep(concept.modelAction(step.action), fallbackId = step.id)
    return StepForm.of(rebuilt).copy(
        concept = concept,
        // 公共尾部跨类型保留。
        waitAfterSec = waitAfterSec,
        repeat = repeat,
        condition = condition,
        // 点击三兄弟切换时按下时间不重置。
        holdMs = if (concept.isTapFamily && this.concept.isTapFamily) holdMs else {
            StepForm.of(rebuilt).holdMs
        },
    )
}

/**
 * 动态字段区（§一 四类参数三段式）：公共段（B 载荷 + A 尾部）→ a11y 组（C 定位 +
 * D a11y 通道）→ 无 a11y 组（C 定位 + D vision 通道）。R1/R2 置灰由 [LineSection]
 * 承载；D 字段按动作矩阵（§一）取用，触达入口 kind 随所在分线给（§4.2）。
 */
@Composable
private fun StepFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    step: RpaStep,
    script: RpaScript,
    onArmPick: (RpaPickRequest) -> Unit,
    onOpenVisionPanel: () -> Unit,
    onOpenAppPicker: () -> Unit,
    onOpenCondition: () -> Unit,
) {
    val context = LocalContext.current
    val capabilities = rememberLineReadiness()
    val effectiveAction = form.concept.modelAction(form.otherAction)
    val a11yInMode = RpaModePolicy.lineEnabled(script.mode, RpaLine.A11Y)
    val visionInMode = RpaModePolicy.lineEnabled(script.mode, RpaLine.VISION)
    val a11yReady = capabilities?.a11yModeReady() ?: true
    val visionReady = capabilities?.visionModeReady() ?: true
    // R1（模式不含该线）整组灰只读；R2（前置不具备）灰 + 「暂不可用」+ 修复入口（§4.3）。
    val a11yEditable = a11yInMode && a11yReady
    val visionEditable = visionInMode && visionReady
    // D 类分线通道按动作矩阵取用（§一）：LAUNCH_APP/OPEN_CHAT→就绪判定、
    // INPUT_TEXT→输入、SWIPE/SCROLL→锚定/起止点、ASSERT_TEXT/SET_VARIABLE(UI_READ)→读屏范围。
    val showReady = effectiveAction == RpaAction.LAUNCH_APP || effectiveAction == RpaAction.OPEN_CHAT
    val showInput = effectiveAction == RpaAction.INPUT_TEXT
    val showAnchor = effectiveAction == RpaAction.SWIPE || effectiveAction == RpaAction.SCROLL
    val showRead = effectiveAction == RpaAction.ASSERT_TEXT ||
        (effectiveAction == RpaAction.SET_VARIABLE &&
            form.assignments.any { it.source == RpaVarSource.UI_READ })
    val showLineFields = form.concept.isTapFamily || showReady || showInput || showAnchor || showRead
    // R2 修复入口（复用既有跳转）：a11y = 系统无障碍设置，无 a11y = Shizuku 授权页。
    val onFixA11y: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val onFixVision: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(context, ShizukuGrantActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ① 公共段：B 动作载荷 + A 尾部（TailFields 随公共段，规格 §一「A/B 在公共段」）。
    when (form.concept) {
        StepConcept.LAUNCH_APP -> LaunchAppFields(form, onFormChange, onOpenAppPicker)
        StepConcept.TAP_POINT,
        StepConcept.TAP_IMAGE,
        StepConcept.TAP_TEXT,
        -> HoldMsField(form, onFormChange)

        StepConcept.SET_VARIABLE -> SetVariableFields(form, onFormChange)
        StepConcept.CONTROL_FLOW -> ControlFlowFields(form, onFormChange, step, script)
        StepConcept.OPEN_LINK -> OpenLinkFields(form, onFormChange)
        StepConcept.COMPOSITE -> CompositeFields(form, onFormChange, script)
        StepConcept.OTHER -> Text(
            text = stringResource(R.string.float_rpa_param_new_editor_hint),
            style = InkTextStyles.Summary,
            color = InkDialog.OnSurfaceMuted,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    InkDivider()
    TailFields(form, onFormChange, onOpenCondition)
    if (!showLineFields) return

    // ② a11y（无障碍）组：C 的 a11y 选择器 + D 的 a11y 通道。
    LineSection(
        line = RpaLine.A11Y,
        greyed = !a11yEditable,
        unready = a11yInMode && !a11yReady,
        onFix = onFixA11y,
    ) {
        if (form.concept.isTapFamily) {
            TapA11yFields(form, onFormChange, step, script, onArmPick, enabled = a11yEditable)
        }
        if (showReady) {
            SelectorFields(
                label = stringResource(R.string.float_rpa_lineparam_ready),
                selector = form.readySelector,
                enabled = a11yEditable,
                onChange = { onFormChange(form.copy(readySelector = it)) },
            )
        }
        if (showInput) {
            SelectorFields(
                label = stringResource(R.string.float_rpa_lineparam_input_selector),
                selector = form.inputSelector,
                enabled = a11yEditable,
                onChange = { onFormChange(form.copy(inputSelector = it)) },
            )
            InkCheckRow(
                checked = form.appendMode,
                label = stringResource(R.string.float_rpa_lineparam_append_mode),
                onCheckedChange = { onFormChange(form.copy(appendMode = it)) },
                enabled = a11yEditable,
            )
        }
        if (showAnchor) {
            SelectorFields(
                label = stringResource(R.string.float_rpa_lineparam_anchor_selector),
                selector = form.anchorSelector,
                enabled = a11yEditable,
                onChange = { onFormChange(form.copy(anchorSelector = it)) },
            )
        }
        if (showRead) {
            SelectorFields(
                label = stringResource(R.string.float_rpa_lineparam_read_scope),
                selector = form.readScope,
                enabled = a11yEditable,
                onChange = { onFormChange(form.copy(readScope = it)) },
            )
            Text(
                text = stringResource(R.string.float_rpa_lineparam_scope_all),
                style = InkTextStyles.Badge,
                color = InkDialog.OnSurfaceDisabled,
                modifier = Modifier.padding(start = 64.dp, top = 2.dp, bottom = 4.dp),
            )
        }
    }

    // ③ 无 a11y（shizuku）组：C 的 vision 定位 + D 的 vision 通道。
    LineSection(
        line = RpaLine.VISION,
        greyed = !visionEditable,
        unready = visionInMode && !visionReady,
        onFix = onFixVision,
    ) {
        when (form.concept) {
            StepConcept.TAP_POINT -> TapPointFields(
                form, onFormChange, step, script, onArmPick, enabled = visionEditable,
            )

            StepConcept.TAP_IMAGE -> TapImageFields(
                form, onFormChange, step, script, onArmPick, onOpenVisionPanel,
                enabled = visionEditable,
            )

            StepConcept.TAP_TEXT -> TapTextVisionFields(form, onFormChange, enabled = visionEditable)
            else -> Unit
        }
        if (showReady) {
            FieldRow(stringResource(R.string.float_rpa_lineparam_ready)) {
                InkTextField(
                    value = form.readyText,
                    onValueChange = { onFormChange(form.copy(readyText = it)) },
                    enabled = visionEditable,
                    placeholder = stringResource(R.string.float_rpa_stepdlg_optional),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showInput) {
            FieldRow(stringResource(R.string.float_rpa_lineparam_input_channel)) {
                InkDropdown(
                    selectedLabel = inputChannelLabel(form.inputChannel),
                    options = RpaInputChannel.entries,
                    labelOf = { inputChannelLabel(it) },
                    onSelect = { onFormChange(form.copy(inputChannel = it)) },
                    modifier = Modifier.weight(1f),
                    enabled = visionEditable,
                )
            }
            InkCheckRow(
                checked = form.clearBeforeInput,
                label = stringResource(R.string.float_rpa_lineparam_clear_before),
                onCheckedChange = { onFormChange(form.copy(clearBeforeInput = it)) },
                enabled = visionEditable,
            )
            PercentPairRow(
                label = stringResource(R.string.float_rpa_lineparam_focus_point),
                x = form.focusX,
                y = form.focusY,
                enabled = visionEditable,
                onX = { onFormChange(form.copy(focusX = it)) },
                onY = { onFormChange(form.copy(focusY = it)) },
            )
        }
        if (showAnchor) {
            PercentPairRow(
                label = stringResource(R.string.float_rpa_lineparam_start_point),
                x = form.startX,
                y = form.startY,
                enabled = visionEditable,
                onX = { onFormChange(form.copy(startX = it)) },
                onY = { onFormChange(form.copy(startY = it)) },
            )
            PercentPairRow(
                label = stringResource(R.string.float_rpa_lineparam_end_point),
                x = form.endX,
                y = form.endY,
                enabled = visionEditable,
                onX = { onFormChange(form.copy(endX = it)) },
                onY = { onFormChange(form.copy(endY = it)) },
            )
        }
        if (showRead) {
            PercentPairRow(
                label = stringResource(R.string.float_rpa_lineparam_region_tl),
                x = form.ocrLeft,
                y = form.ocrTop,
                enabled = visionEditable,
                onX = { onFormChange(form.copy(ocrLeft = it)) },
                onY = { onFormChange(form.copy(ocrTop = it)) },
            )
            PercentPairRow(
                label = stringResource(R.string.float_rpa_lineparam_region_br),
                x = form.ocrRight,
                y = form.ocrBottom,
                enabled = visionEditable,
                onX = { onFormChange(form.copy(ocrRight = it)) },
                onY = { onFormChange(form.copy(ocrBottom = it)) },
            )
        }
    }
}

@Composable
private fun LaunchAppFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    onOpenAppPicker: () -> Unit,
) {
    val appLabel = form.packageName.ifBlank { stringResource(R.string.float_rpa_stepdlg_app_field) }
    FieldRow(stringResource(R.string.float_rpa_stepdlg_app_field)) {
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(FieldShape)
                .background(InkDialog.SurfaceHigh)
                .clickable(onClick = onOpenAppPicker)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = appLabel,
                style = InkTextStyles.Body,
                color = if (form.packageName.isBlank()) {
                    InkDialog.OnSurfaceDisabled
                } else {
                    InkDialog.OnSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        EllipsisButton(
            description = stringResource(R.string.float_rpa_app_picker_pick),
            onClick = onOpenAppPicker,
        )
    }
    InkCheckRow(
        checked = form.restart,
        label = stringResource(R.string.float_rpa_stepdlg_restart),
        onCheckedChange = { onFormChange(form.copy(restart = it)) },
    )
    InkCheckRow(
        checked = form.closeOnly,
        label = stringResource(R.string.float_rpa_stepdlg_close_only),
        onCheckedChange = { onFormChange(form.copy(closeOnly = it)) },
    )
}

/** 点击(坐标) vision 侧 C 定位（§4.2 两侧分写）：点击位置 + 屏幕取点（kind=VISION）。 */
@Composable
private fun TapPointFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    step: RpaStep,
    script: RpaScript,
    onArmPick: (RpaPickRequest) -> Unit,
    enabled: Boolean,
) {
    PercentPairRow(
        label = stringResource(R.string.float_rpa_stepdlg_tap_pos),
        x = form.posX,
        y = form.posY,
        enabled = enabled,
        onX = { onFormChange(form.copy(posX = it)) },
        onY = { onFormChange(form.copy(posY = it)) },
        trailing = {
            EllipsisButton(
                description = stringResource(R.string.float_rpa_stepdlg_pick_point),
                // 触达入口按所在分线给 kind（§4.2）：无 a11y 组 = 视觉取点；purpose=POINT
                // 回填 RATIO 坐标，保持「点击(坐标)」概念不翻转。
                onClick = {
                    onArmPick(
                        buildPickRequest(step, script, RpaPickPurpose.POINT, RpaPickKind.VISION),
                    )
                },
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun TapImageFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    step: RpaStep,
    script: RpaScript,
    onArmPick: (RpaPickRequest) -> Unit,
    onOpenVisionPanel: () -> Unit,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val store = remember(context) { RpaScriptStore(File(context.filesDir, "rpa")) }
    val visionDesc = stringResource(R.string.float_rpa_stepdlg_open_vision)
    val pickImageDesc = stringResource(R.string.float_rpa_stepdlg_pick_image)
    FieldRow(stringResource(R.string.float_rpa_stepdlg_image_pos)) {
        // 模板缩略图（脚本模板池，filesDir/rpa/templates/<scriptId>/）。
        val thumb = remember(form.templateId) { loadTemplateThumb(store, script.id, form.templateId) }
        Box(
            Modifier
                .size(40.dp)
                .clip(FieldShape)
                .background(InkDialog.SurfaceHigh)
                .border(1.dp, InkDialog.Outline, FieldShape),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Text(
                    text = form.templateId.ifBlank {
                        stringResource(R.string.float_rpa_stepdlg_template_none)
                    },
                    style = InkTextStyles.Badge,
                    color = InkDialog.OnSurfaceDisabled,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = form.templateId.ifBlank {
                    stringResource(R.string.float_rpa_stepdlg_template_none)
                },
                style = InkTextStyles.Summary,
                color = InkDialog.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 摘要（§7.9.2）：视觉定位参数速览「相似度 76% · 匹配第 1」。
            val primary = step.target?.vision?.primary ?: RpaVisionLocator()
            Text(
                text = stringResource(
                    R.string.float_rpa_stepdlg_vision_summary,
                    (primary.threshold * 100).roundToInt(),
                    primary.matchIndex ?: 1,
                ),
                style = InkTextStyles.Badge,
                color = InkDialog.OnSurfaceDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        // 图片位置双入口（§7.9）：「...」弹「图片拾取 / 找图面板」二选一。
        Box {
            var showEntry by remember { mutableStateOf(false) }
            EllipsisButton(description = visionDesc, onClick = { showEntry = true }, enabled = enabled)
            DropdownMenu(expanded = showEntry, onDismissRequest = { showEntry = false }) {
                DropdownMenuItem(
                    text = { Text(pickImageDesc) },
                    onClick = {
                        showEntry = false
                        // 图片拾取：直拉以图找图拾取模式（kind=VISION 视觉拾取，
                        // purpose=TEMPLATE 回填模板）。
                        onArmPick(
                            buildPickRequest(step, script, RpaPickPurpose.TEMPLATE, RpaPickKind.VISION),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(visionDesc) },
                    onClick = {
                        showEntry = false
                        onOpenVisionPanel()
                    },
                )
            }
        }
    }
}

@Composable
private fun HoldMsField(form: StepForm, onFormChange: (StepForm) -> Unit) {
    FieldRow(stringResource(R.string.float_rpa_stepdlg_hold_ms)) {
        InkUnitInput(
            value = form.holdMs,
            unit = stringResource(R.string.float_rpa_stepdlg_unit_ms),
            onValueChange = { onFormChange(form.copy(holdMs = it)) },
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 点击三兄弟 a11y 侧 C 定位（§4.2 两侧分写）：目标节点/目标文字选择器 +
 * 屏幕取点（触达入口 kind=A11Y 节点拾取，回填选择器）。
 */
@Composable
private fun TapA11yFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    step: RpaStep,
    script: RpaScript,
    onArmPick: (RpaPickRequest) -> Unit,
    enabled: Boolean,
) {
    SelectorFields(
        label = stringResource(
            if (form.concept == StepConcept.TAP_TEXT) {
                R.string.float_rpa_stepdlg_tap_text_a11y
            } else {
                R.string.float_rpa_stepdlg_tap_node
            },
        ),
        selector = form.tapA11ySelector,
        enabled = enabled,
        onChange = { onFormChange(form.copy(tapA11ySelector = it)) },
        trailing = {
            EllipsisButton(
                description = stringResource(R.string.float_rpa_stepdlg_pick_point),
                // 触达入口按所在分线给 kind（§4.2）：a11y 组 = 节点拾取。
                onClick = {
                    onArmPick(
                        buildPickRequest(step, script, RpaPickPurpose.POINT, RpaPickKind.A11Y),
                    )
                },
                enabled = enabled,
            )
        },
    )
}

/** 点击文字 vision 侧 C 定位（§4.2 两侧分写）：识别文字 + 匹配方式 + 匹配第几。 */
@Composable
private fun TapTextVisionFields(form: StepForm, onFormChange: (StepForm) -> Unit, enabled: Boolean) {
    FieldRow(stringResource(R.string.float_rpa_stepdlg_tap_text_vision)) {
        InkTextField(
            value = form.tapVisionText,
            onValueChange = { onFormChange(form.copy(tapVisionText = it)) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
    FieldRow(stringResource(R.string.float_rpa_prop_match)) {
        InkDropdown(
            selectedLabel = rpaMatchLabel(form.tapVisionMatch),
            options = RpaMatchMode.entries,
            labelOf = { rpaMatchLabel(it) },
            onSelect = { onFormChange(form.copy(tapVisionMatch = it)) },
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
    }
    FieldRow(stringResource(R.string.float_rpa_stepdlg_match_index)) {
        InkTextField(
            value = form.matchIndex,
            onValueChange = { onFormChange(form.copy(matchIndex = it)) },
            enabled = enabled,
            placeholder = stringResource(R.string.float_rpa_stepdlg_optional),
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SetVariableFields(form: StepForm, onFormChange: (StepForm) -> Unit) {
    // 变量列表多行 + 行删除（对齐图8 行结构）。
    form.assignments.forEachIndexed { index, assign ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(FieldShape)
                .background(InkDialog.SurfaceHigh)
                .padding(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkTextField(
                    value = assign.variableName,
                    onValueChange = { value ->
                        onFormChange(form.copy(assignments = form.assignments.updated(index) {
                            it.copy(variableName = value)
                        }))
                    },
                    placeholder = stringResource(R.string.float_rpa_stepdlg_var_name),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .height(40.dp)
                        .clip(FieldShape)
                        .clickable {
                            onFormChange(form.copy(assignments = form.assignments.filterIndexed {
                                    i, _ -> i != index
                            }))
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        style = InkTextStyles.Body,
                        color = InkDialog.OnSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkDropdown(
                    selectedLabel = stringResource(
                        if (assign.type == RpaVarType.NUMBER) {
                            R.string.float_rpa_stepdlg_var_number
                        } else {
                            R.string.float_rpa_stepdlg_var_string
                        },
                    ),
                    options = RpaVarType.entries,
                    labelOf = {
                        stringResource(
                            if (it == RpaVarType.NUMBER) {
                                R.string.float_rpa_stepdlg_var_number
                            } else {
                                R.string.float_rpa_stepdlg_var_string
                            },
                        )
                    },
                    onSelect = { value ->
                        onFormChange(form.copy(assignments = form.assignments.updated(index) {
                            it.copy(type = value)
                        }))
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                InkDropdown(
                    selectedLabel = varSourceLabel(assign.source),
                    options = RpaVarSource.entries,
                    labelOf = { varSourceLabel(it) },
                    onSelect = { value ->
                        onFormChange(form.copy(assignments = form.assignments.updated(index) {
                            it.copy(source = value)
                        }))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            InkTextField(
                value = assign.value,
                onValueChange = { value ->
                    onFormChange(form.copy(assignments = form.assignments.updated(index) {
                        it.copy(value = value)
                    }))
                },
                placeholder = stringResource(R.string.float_rpa_stepdlg_var_value),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(40.dp)
            .clip(FieldShape)
            .border(1.dp, InkDialog.Outline, FieldShape)
            .clickable {
                onFormChange(form.copy(assignments = form.assignments + RpaVarAssign()))
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+ " + stringResource(R.string.float_rpa_stepdlg_var_add),
            style = InkTextStyles.Body,
            color = InkDialog.OnSurfaceMuted,
        )
    }
}

private fun <T> List<T>.updated(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { i, item -> if (i == index) transform(item) else item }

@Composable
private fun varSourceLabel(source: RpaVarSource): String = stringResource(
    when (source) {
        RpaVarSource.LITERAL -> R.string.float_rpa_stepdlg_source_literal
        RpaVarSource.UI_READ -> R.string.float_rpa_stepdlg_source_ui_read
        RpaVarSource.VARIABLE_REF -> R.string.float_rpa_stepdlg_source_var_ref
        RpaVarSource.INCREMENT -> R.string.float_rpa_stepdlg_source_increment
    },
)

@Composable
private fun ControlFlowFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    step: RpaStep,
    script: RpaScript,
) {
    FieldRow(stringResource(R.string.float_rpa_stepdlg_flow_kind)) {
        InkDropdown(
            selectedLabel = stringResource(
                if (form.flowKind == RpaFlowKind.GOTO) {
                    R.string.float_rpa_stepdlg_flow_goto
                } else {
                    R.string.float_rpa_stepdlg_flow_stop
                },
            ),
            options = RpaFlowKind.entries,
            labelOf = {
                stringResource(
                    if (it == RpaFlowKind.GOTO) {
                        R.string.float_rpa_stepdlg_flow_goto
                    } else {
                        R.string.float_rpa_stepdlg_flow_stop
                    },
                )
            },
            onSelect = { onFormChange(form.copy(flowKind = it)) },
            modifier = Modifier.weight(1f),
        )
    }
    if (form.flowKind != RpaFlowKind.GOTO) return

    // 步骤选择器：stepId 绝对锚定（裁定⑤）。
    val gotoIndex = script.steps.indexOfFirst { it.id == form.gotoStepId }
    FieldRow(stringResource(R.string.float_rpa_stepdlg_goto_target)) {
        InkDropdown(
            selectedLabel = if (gotoIndex >= 0) {
                stringResource(
                    R.string.float_rpa_stepdlg_goto_preview,
                    gotoIndex + 1,
                    stepDisplayName(script.steps[gotoIndex]),
                )
            } else {
                stringResource(R.string.float_rpa_cond_none)
            },
            options = script.steps.mapIndexed { index, target -> index to target },
            labelOf = { (index, target) ->
                stringResource(
                    R.string.float_rpa_stepdlg_goto_preview,
                    index + 1,
                    stepDisplayName(target),
                )
            },
            onSelect = { (_, target) -> onFormChange(form.copy(gotoStepId = target.id)) },
            modifier = Modifier.weight(1f),
        )
    }

    // 相对步数糖：`+N`/`-N` 实时解析为 stepId，保存即锚定（裁定⑤）。
    var sugar by remember(step.id) { mutableStateOf("") }
    var sugarDangling by remember(step.id) { mutableStateOf(false) }
    FieldRow(stringResource(R.string.float_rpa_stepdlg_goto_relative)) {
        InkTextField(
            value = sugar,
            onValueChange = { value ->
                sugar = value
                val matched = RELATIVE_SUGAR.matchEntire(value.trim())
                if (matched == null) {
                    sugarDangling = value.isNotBlank()
                    return@InkTextField
                }
                val sign = if (matched.groupValues[1] == "-") -1 else 1
                val offset = matched.groupValues[2].toIntOrNull() ?: 0
                val baseIndex = script.steps.indexOfFirst { it.id == step.id }
                val targetIndex = baseIndex + sign * offset
                val resolved = script.steps.getOrNull(targetIndex)
                sugarDangling = resolved == null
                onFormChange(form.copy(gotoStepId = resolved?.id))
            },
            placeholder = stringResource(R.string.float_rpa_stepdlg_goto_relative_hint),
            keyboardType = KeyboardType.Text,
            modifier = Modifier.weight(1f),
        )
    }

    // 实时预览解析结果「→ 第 9 步 · 点击图片」；悬空显示警告色。
    val previewText = when {
        sugarDangling -> stringResource(R.string.float_rpa_stepdlg_goto_dangling)
        gotoIndex >= 0 -> stringResource(
            R.string.float_rpa_stepdlg_goto_preview,
            gotoIndex + 1,
            stepDisplayName(script.steps[gotoIndex]),
        )

        else -> null
    }
    if (previewText != null) {
        Text(
            text = previewText,
            style = InkTextStyles.Summary,
            color = if (sugarDangling || gotoIndex < 0) InkDialog.Warn else InkDialog.OnSurfaceMuted,
            modifier = Modifier.padding(start = 64.dp, top = 2.dp, bottom = 4.dp),
        )
    }
}

private val RELATIVE_SUGAR = Regex("^([+-])(\\d+)$")

/** 步骤显示名：label 优先，否则按目标种类给概念名（跳转预览「· 点击图片」）。 */
@Composable
private fun stepDisplayName(step: RpaStep): String =
    step.label.ifBlank { stepConceptLabel(conceptOf(step), step.action) }

@Composable
private fun OpenLinkFields(form: StepForm, onFormChange: (StepForm) -> Unit) {
    FieldRow(stringResource(R.string.float_rpa_stepdlg_url)) {
        InkTextField(
            value = form.url,
            onValueChange = { onFormChange(form.copy(url = it)) },
            placeholder = "https://",
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CompositeFields(form: StepForm, onFormChange: (StepForm) -> Unit, script: RpaScript) {
    val customActions = script.customActions
    val selected = customActions.firstOrNull { it.id == form.customActionId }
    FieldRow(stringResource(R.string.float_rpa_stepdlg_custom_action)) {
        InkDropdown(
            selectedLabel = selected?.name?.ifBlank { selected.id }
                ?: stringResource(R.string.float_rpa_cond_none),
            options = customActions,
            labelOf = { it.name.ifBlank { it.id } },
            onSelect = { onFormChange(form.copy(customActionId = it.id)) },
            modifier = Modifier.weight(1f),
        )
    }
    // 复合动作参数值（参数名 → 值，缺省用声明里的 default）。
    selected?.params?.forEach { param ->
        FieldRow(param.name.ifBlank { stringResource(R.string.float_rpa_stepdlg_custom_args) }) {
            InkTextField(
                value = form.customArgValues[param.name] ?: param.default,
                onValueChange = { value ->
                    onFormChange(
                        form.copy(customArgValues = form.customArgValues + (param.name to value)),
                    )
                },
                placeholder = stringResource(R.string.float_rpa_stepdlg_optional),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TailFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    onOpenCondition: () -> Unit,
) {
    val concept = form.concept
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (concept.hasWaitTail) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.float_rpa_stepdlg_wait_after),
                    style = InkTextStyles.Summary,
                    color = InkDialog.OnSurfaceMuted,
                )
                InkUnitInput(
                    value = form.waitAfterSec,
                    unit = stringResource(R.string.float_rpa_stepdlg_unit_sec),
                    onValueChange = { onFormChange(form.copy(waitAfterSec = it)) },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (concept.hasRepeatTail) {
            if (concept.hasWaitTail) Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.float_rpa_stepdlg_repeat),
                    style = InkTextStyles.Summary,
                    color = InkDialog.OnSurfaceMuted,
                )
                InkTextField(
                    value = form.repeat,
                    onValueChange = { onFormChange(form.copy(repeat = it)) },
                    placeholder = stringResource(R.string.float_rpa_stepdlg_optional),
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    if (concept.hasConditionTail) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.float_rpa_stepdlg_condition),
                style = InkTextStyles.Summary,
                color = InkDialog.OnSurfaceMuted,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(40.dp)
                    .clip(FieldShape)
                    .background(InkDialog.SurfaceHigh)
                    .clickable(onClick = onOpenCondition)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = conditionSummary(form.condition),
                    style = InkTextStyles.Body,
                    color = InkDialog.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "▸", style = InkTextStyles.Summary, color = InkDialog.OnSurfaceMuted)
            }
        }
    }
}

@Composable
private fun conditionSummary(condition: RpaStepCondition): String = when (condition) {
    RpaStepCondition.None -> stringResource(R.string.float_rpa_cond_none)
    is RpaStepCondition.Variable -> stringResource(R.string.float_rpa_cond_variable) +
        " ${condition.lhs} ${compareOpLabel(condition.op)} ${condition.rhs}"

    is RpaStepCondition.ImageVisible -> stringResource(R.string.float_rpa_cond_image) + " " +
        condition.templateId.ifBlank { stringResource(R.string.float_rpa_stepdlg_template_none) }

    is RpaStepCondition.TextVisible -> stringResource(R.string.float_rpa_cond_text) +
        " " + condition.text

    RpaStepCondition.PrevOk -> stringResource(R.string.float_rpa_cond_prev_ok)
    RpaStepCondition.PrevFail -> stringResource(R.string.float_rpa_cond_prev_fail)

    // L2 公式条件为后续批次：只回显，不提供编辑入口。
    is RpaStepCondition.Expr -> condition.expression
}

@Composable
private fun compareOpLabel(op: RpaCompareOp): String = stringResource(
    when (op) {
        RpaCompareOp.EQ -> R.string.float_rpa_cond_op_eq
        RpaCompareOp.NE -> R.string.float_rpa_cond_op_ne
        RpaCompareOp.GT -> R.string.float_rpa_cond_op_gt
        RpaCompareOp.GE -> R.string.float_rpa_cond_op_ge
        RpaCompareOp.LT -> R.string.float_rpa_cond_op_lt
        RpaCompareOp.LE -> R.string.float_rpa_cond_op_le
    },
)

// ── 运行条件子弹框（§7.6）─────────────────────────────────────────────

@Composable
private fun ConditionDialog(
    condition: RpaStepCondition,
    script: RpaScript,
    onOpenVisionPanel: () -> Unit,
    onCommit: (RpaStepCondition) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(condition) }
    val errors = conditionErrors(draft)

    InkDialogShell(title = stringResource(R.string.float_rpa_cond_title), onDismiss = onDismiss) {
        // 选项行：未设置 / 变量比较 / 出现图片 / 出现文字 / 上步成功 / 上步失败。
        ConditionOption(
            label = stringResource(R.string.float_rpa_cond_none),
            selected = draft is RpaStepCondition.None,
        ) { draft = RpaStepCondition.None }
        ConditionOption(
            label = stringResource(R.string.float_rpa_cond_variable),
            selected = draft is RpaStepCondition.Variable,
        ) {
            draft = (draft as? RpaStepCondition.Variable) ?: RpaStepCondition.Variable()
        }
        if (draft is RpaStepCondition.Variable) {
            val variable = draft as RpaStepCondition.Variable
            FieldRow(stringResource(R.string.float_rpa_stepdlg_var_name)) {
                InkTextField(
                    value = variable.lhs,
                    onValueChange = { draft = variable.copy(lhs = it) },
                    modifier = Modifier.weight(1f),
                )
            }
            FieldRow(stringResource(R.string.float_rpa_cond_op)) {
                InkDropdown(
                    selectedLabel = compareOpLabel(variable.op),
                    options = RpaCompareOp.entries,
                    labelOf = { compareOpLabel(it) },
                    onSelect = { draft = variable.copy(op = it) },
                    modifier = Modifier.weight(1f),
                )
            }
            FieldRow(stringResource(R.string.float_rpa_stepdlg_var_value)) {
                InkTextField(
                    value = variable.rhs,
                    onValueChange = { draft = variable.copy(rhs = it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ConditionOption(
            label = stringResource(R.string.float_rpa_cond_image),
            selected = draft is RpaStepCondition.ImageVisible,
        ) {
            draft = (draft as? RpaStepCondition.ImageVisible) ?: RpaStepCondition.ImageVisible()
        }
        if (draft is RpaStepCondition.ImageVisible) {
            val image = draft as RpaStepCondition.ImageVisible
            val context = LocalContext.current
            val store = remember(context) { RpaScriptStore(File(context.filesDir, "rpa")) }
            val templateIds = runCatching { store.listTemplateIds(script.id) }
                .getOrDefault(emptyList())
            FieldRow(stringResource(R.string.float_rpa_cond_template)) {
                InkDropdown(
                    selectedLabel = image.templateId.ifBlank {
                        stringResource(R.string.float_rpa_stepdlg_template_none)
                    },
                    options = templateIds,
                    labelOf = { it },
                    onSelect = { draft = image.copy(templateId = it) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                // 「...」：到图11 找图面板采集新模板（p2-5/6 接线）。
                EllipsisButton(
                    description = stringResource(R.string.float_rpa_stepdlg_open_vision),
                    onClick = onOpenVisionPanel,
                )
            }
        }
        ConditionOption(
            label = stringResource(R.string.float_rpa_cond_text),
            selected = draft is RpaStepCondition.TextVisible,
        ) {
            draft = (draft as? RpaStepCondition.TextVisible) ?: RpaStepCondition.TextVisible()
        }
        if (draft is RpaStepCondition.TextVisible) {
            val textVisible = draft as RpaStepCondition.TextVisible
            FieldRow(stringResource(R.string.float_rpa_stepdlg_text)) {
                InkTextField(
                    value = textVisible.text,
                    onValueChange = { draft = textVisible.copy(text = it) },
                    modifier = Modifier.weight(1f),
                )
            }
            FieldRow(stringResource(R.string.float_rpa_prop_match)) {
                InkDropdown(
                    selectedLabel = rpaMatchLabel(textVisible.match),
                    options = RpaMatchMode.entries,
                    labelOf = { rpaMatchLabel(it) },
                    onSelect = { draft = textVisible.copy(match = it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ConditionOption(
            label = stringResource(R.string.float_rpa_cond_prev_ok),
            selected = draft is RpaStepCondition.PrevOk,
        ) { draft = RpaStepCondition.PrevOk }
        ConditionOption(
            label = stringResource(R.string.float_rpa_cond_prev_fail),
            selected = draft is RpaStepCondition.PrevFail,
        ) { draft = RpaStepCondition.PrevFail }

        ErrorList(errors)
        DialogButtons(
            confirmEnabled = errors.isEmpty(),
            onConfirm = { onCommit(draft) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ConditionOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(FieldShape)
            .background(if (selected) InkDialog.SurfaceHigh else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, InkDialog.Outline, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = InkTextStyles.Body, color = InkDialog.OnSurface)
    }
}

@Composable
private fun conditionErrors(condition: RpaStepCondition): List<String> {
    val errors = mutableListOf<String>()
    when (condition) {
        is RpaStepCondition.Variable -> {
            if (condition.lhs.isBlank()) {
                errors += stringResource(R.string.float_rpa_cond_error_lhs)
            }
            if (condition.rhs.isBlank()) {
                errors += stringResource(R.string.float_rpa_cond_error_rhs)
            }
        }

        is RpaStepCondition.ImageVisible ->
            if (condition.templateId.isBlank()) {
                errors += stringResource(R.string.float_rpa_cond_error_template)
            }

        is RpaStepCondition.TextVisible ->
            if (condition.text.isBlank()) {
                errors += stringResource(R.string.float_rpa_cond_error_text)
            }

        else -> Unit
    }
    return errors
}

// ── 选择 App 弹层（RpaInstalledApps）───────────────────────────────────

@Composable
private fun AppPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember { runCatching { RpaInstalledApps.load(context) }.getOrDefault(emptyList()) }
    val filtered = remember(query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    InkDialogShell(
        title = stringResource(R.string.float_rpa_app_picker_title),
        onDismiss = onDismiss,
    ) {
        InkTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.float_rpa_app_picker_hint),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
            items(filtered) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(FieldShape)
                        .clickable { onPick(app.packageName) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = app.label,
                            style = InkTextStyles.Body,
                            color = InkDialog.OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = app.packageName,
                            style = InkTextStyles.Summary,
                            color = InkDialog.OnSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        DialogButtons(confirmEnabled = true, onConfirm = onDismiss, onDismiss = onDismiss)
    }
}

// ── 拾取请求（唯一拾取通道 = RpaPickCoordinator.arm，宿主接线）────────

/**
 * 构造拾取请求（v5 §7.9）：kind 按触达入口所在分线给（a11y 组 = 节点拾取 /
 * 无 a11y 组 = 视觉拾取，与 §4.2 两侧分写一致），交宿主 `RpaPickCoordinator.arm`。
 * [purpose] 决定回填目标种类——屏幕取点回填 RATIO 坐标（保持「点击(坐标)」概念
 * 不翻转），图片拾取回填 TEMPLATE 模板。
 */
private fun buildPickRequest(
    step: RpaStep,
    script: RpaScript,
    purpose: RpaPickPurpose,
    kind: RpaPickKind,
): RpaPickRequest = RpaPickRequest(
    requestId = RpaIds.newId("pick"),
    scriptId = script.id,
    stepId = step.id,
    kind = kind,
    scriptName = script.name,
    stepLabel = step.label,
    createdAt = System.currentTimeMillis(),
    purpose = purpose,
)

/** 模板缩略图（小图直接解码；失败返回 null，不挡编辑）。 */
private fun loadTemplateThumb(store: RpaScriptStore, scriptId: String, templateId: String) =
    runCatching {
        if (templateId.isBlank()) return@runCatching null
        val file = store.templateFile(scriptId, templateId)
        if (!file.isFile) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 96).coerceAtLeast(1)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    }.getOrNull()
