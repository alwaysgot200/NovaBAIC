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

import android.graphics.BitmapFactory
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
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaCatalog
import com.verlintas.rpa.model.RpaCompareOp
import com.verlintas.rpa.model.RpaFlowKind
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaLimits
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaParams
import com.verlintas.rpa.model.RpaPickKind
import com.verlintas.rpa.model.RpaPickRequest
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaStepCondition
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaVarAssign
import com.verlintas.rpa.model.RpaVarSource
import com.verlintas.rpa.model.RpaVarType
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.store.RpaScriptStore
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

/**
 * 步骤编辑弹框（母规格 §7.6）：墨板固定深色、圆角 20dp、scale 0.96→1 + fade 120ms。
 *
 * - **in-window 绘制**：直接画在面板 overlay 的 ComposeView 里（遮罩 + 卡片），
 *   不用 android Dialog，避免 overlay 窗口 token 问题；
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
    isNew: Boolean,
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
        Box(Modifier.fillMaxWidth()) {
            InkDialogShell(
                title = stringResource(
                    if (isNew) R.string.float_rpa_panel_add_step else R.string.float_rpa_stepdlg_title,
                ),
                onDismiss = onDismiss,
            ) {
                ActionHeader(
                    form = form,
                    onFormChange = { form = it },
                    step = step,
                )
                InkDivider()
                StepFields(
                    form = form,
                    onFormChange = { form = it },
                    step = step,
                    script = script,
                    onArmPick = onArmPick,
                    onOpenVisionPanel = onOpenVisionPanel,
                    onOpenAppPicker = { showAppPicker = true },
                )
                InkDivider()
                TailFields(
                    form = form,
                    onFormChange = { form = it },
                    onOpenCondition = { showCondition = true },
                )
                ErrorList(errors)
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

/** 步骤编辑表单：文本字段保留原文，确认时经 [build] 合成整步（含 repeat / condition）。 */
internal data class StepForm(
    val concept: StepConcept,
    val otherAction: RpaAction = RpaAction.TAP,
    // 打开应用
    val packageName: String = "",
    val restart: Boolean = false,
    val closeOnly: Boolean = false,
    // 点击三兄弟
    val posX: String = "",
    val posY: String = "",
    val holdMs: String = "",
    val templateId: String = "",
    val tapText: String = "",
    val tapMatch: RpaMatchMode = RpaMatchMode.CONTAINS,
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
    // 公共尾部
    val waitAfterSec: String = "0",
    val repeat: String = "",
    val condition: RpaStepCondition = RpaStepCondition.None,
) {

    /** 合成整步：只替换本概念管辖的字段，其余（id / label / 超时等）沿用 [base]。 */
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
                val visionBase = base.target?.vision ?: RpaVisionSpec()
                target = (base.target ?: RpaTarget()).copy(
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
                val visionBase = base.target?.vision ?: RpaVisionSpec()
                target = (base.target ?: RpaTarget()).copy(
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
                // 文本目标：a11y 文本节点优先、OCR 文本兜底（§4.1 点击文字）。
                val a11yBase = base.target?.a11y
                val selectors = a11yBase?.selectors ?: emptyList()
                val first = (selectors.firstOrNull() ?: RpaSelector()).copy(
                    text = tapText.trim().ifBlank { null },
                    textMatch = tapMatch,
                )
                val visionBase = base.target?.vision ?: RpaVisionSpec()
                target = (base.target ?: RpaTarget()).copy(
                    a11y = (a11yBase ?: RpaA11ySpec()).copy(
                        selectors = listOf(first) + selectors.drop(1),
                    ),
                    vision = visionBase.copy(
                        primary = (visionBase.primary).copy(
                            kind = RpaVisionKind.OCR_TEXT,
                            ocrText = tapText.trim().ifBlank { null },
                            ocrMatch = tapMatch,
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

    companion object {

        fun of(step: RpaStep): StepForm {
            val primary = step.target?.vision?.primary
            val a11yText = step.target?.a11y?.selectors?.firstOrNull { !it.text.isNullOrBlank() }
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
                tapText = primary?.ocrText?.takeIf { it.isNotBlank() } ?: a11yText?.text.orEmpty(),
                tapMatch = primary?.ocrMatch ?: a11yText?.textMatch ?: RpaMatchMode.CONTAINS,
                matchIndex = primary?.matchIndex?.toString().orEmpty(),
                assignments = step.params.assignments,
                flowKind = step.params.flowKind ?: RpaFlowKind.GOTO,
                gotoStepId = step.params.gotoStepId,
                url = step.params.url.orEmpty(),
                customActionId = step.params.customActionId.orEmpty(),
                customArgValues = step.params.customArgValues,
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
            checkPercent(form.posX)
            checkPercent(form.posY)
            checkNumber(form.holdMs)
        }

        StepConcept.TAP_TEXT -> {
            if (form.tapText.isBlank()) errors += stringResource(R.string.float_rpa_issue_text)
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
) {
    var expanded by remember { mutableStateOf(false) }
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
        }
    }
}

/** 「...」次级动作钮（屏幕取点 / 找图面板）。 */
@Composable
internal fun EllipsisButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(40.dp)
            .clip(FieldShape)
            .background(InkDialog.Outline)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "...", style = InkTextStyles.Body, color = InkDialog.OnSurface)
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

@Composable
private fun StepFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    step: RpaStep,
    script: RpaScript,
    onArmPick: (RpaPickRequest) -> Unit,
    onOpenVisionPanel: () -> Unit,
    onOpenAppPicker: () -> Unit,
) {
    when (form.concept) {
        StepConcept.LAUNCH_APP -> LaunchAppFields(form, onFormChange, onOpenAppPicker)
        StepConcept.TAP_POINT -> TapPointFields(form, onFormChange, step, script, onArmPick)
        StepConcept.TAP_IMAGE -> TapImageFields(form, onFormChange, step, script, onOpenVisionPanel)
        StepConcept.TAP_TEXT -> TapTextFields(form, onFormChange)
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

@Composable
private fun TapPointFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    step: RpaStep,
    script: RpaScript,
    onArmPick: (RpaPickRequest) -> Unit,
) {
    val pickDesc = stringResource(R.string.float_rpa_stepdlg_pick_point)
    FieldRow(stringResource(R.string.float_rpa_stepdlg_tap_pos)) {
        InkTextField(
            value = form.posX,
            onValueChange = { onFormChange(form.copy(posX = it)) },
            placeholder = stringResource(R.string.float_rpa_stepdlg_axis_x),
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        InkTextField(
            value = form.posY,
            onValueChange = { onFormChange(form.copy(posY = it)) },
            placeholder = stringResource(R.string.float_rpa_stepdlg_axis_y),
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        EllipsisButton(
            description = pickDesc,
            // 屏幕取点：构造拾取请求交宿主 arm（唯一拾取通道 = RpaPickCoordinator.arm）。
            onClick = { onArmPick(buildPickRequest(step, script)) },
        )
    }
    HoldMsField(form, onFormChange)
}

@Composable
private fun TapImageFields(
    form: StepForm,
    onFormChange: (StepForm) -> Unit,
    step: RpaStep,
    script: RpaScript,
    onOpenVisionPanel: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { RpaScriptStore(File(context.filesDir, "rpa")) }
    val visionDesc = stringResource(R.string.float_rpa_stepdlg_open_vision)
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
        Text(
            text = form.templateId.ifBlank {
                stringResource(R.string.float_rpa_stepdlg_template_none)
            },
            style = InkTextStyles.Summary,
            color = InkDialog.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        EllipsisButton(description = visionDesc, onClick = onOpenVisionPanel)
    }
    HoldMsField(form, onFormChange)
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

@Composable
private fun TapTextFields(form: StepForm, onFormChange: (StepForm) -> Unit) {
    FieldRow(stringResource(R.string.float_rpa_stepdlg_text)) {
        InkTextField(
            value = form.tapText,
            onValueChange = { onFormChange(form.copy(tapText = it)) },
            modifier = Modifier.weight(1f),
        )
    }
    FieldRow(stringResource(R.string.float_rpa_prop_match)) {
        InkDropdown(
            selectedLabel = rpaMatchLabel(form.tapMatch),
            options = RpaMatchMode.entries,
            labelOf = { rpaMatchLabel(it) },
            onSelect = { onFormChange(form.copy(tapMatch = it)) },
            modifier = Modifier.weight(1f),
        )
    }
    FieldRow(stringResource(R.string.float_rpa_stepdlg_match_index)) {
        InkTextField(
            value = form.matchIndex,
            onValueChange = { onFormChange(form.copy(matchIndex = it)) },
            placeholder = stringResource(R.string.float_rpa_stepdlg_optional),
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
    }
    HoldMsField(form, onFormChange)
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
 * 构造拾取请求：坐标「...」屏幕取点用。kind 按脚本模式选线
 * （A11Y 脚本走 a11y 拾取，其余走视觉拾取），交宿主 `RpaPickCoordinator.arm`。
 */
private fun buildPickRequest(step: RpaStep, script: RpaScript): RpaPickRequest = RpaPickRequest(
    requestId = RpaIds.newId("pick"),
    scriptId = script.id,
    stepId = step.id,
    kind = if (script.mode == RpaMode.A11Y) RpaPickKind.A11Y else RpaPickKind.VISION,
    scriptName = script.name,
    stepLabel = step.label,
    createdAt = System.currentTimeMillis(),
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
