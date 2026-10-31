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

package com.verlintas.rpa.model

/** 动作分类（编辑器动作库分组；UI 负责本地化）。 */
enum class RpaActionCategory {
    APP,
    FLOW,
    TARGET,
    INPUT,
    GESTURE,
    ASSERT,
}

enum class RpaParamKind {
    TEXT,
    MULTILINE,
    PACKAGE,
    DURATION_MS,
    NUMBER,
    RATIO,
    ENUM,
    BOOLEAN,
    URL,
    TARGET_STEP,
    VAR_LIST,
    CUSTOM_ACTION,
}

data class RpaParamSpec(
    val key: String,
    val kind: RpaParamKind,
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
)

data class RpaActionSpec(
    val action: RpaAction,
    val category: RpaActionCategory,
    val needsTarget: Boolean,
    val params: List<RpaParamSpec> = emptyList(),
)

/**
 * 动作目录：动作是什么、需要哪些参数、是否依赖目标定位。
 * 与 [RpaModePolicy] 组合即可推出「三模式」下属性面板的可用项。
 */
object RpaCatalog {

    val actions: List<RpaActionSpec> = listOf(
        RpaActionSpec(
            action = RpaAction.LAUNCH_APP,
            category = RpaActionCategory.APP,
            needsTarget = false,
            params = listOf(RpaParamSpec("packageName", RpaParamKind.PACKAGE, required = true)),
        ),
        RpaActionSpec(
            action = RpaAction.OPEN_CHAT,
            category = RpaActionCategory.APP,
            needsTarget = false,
            params = listOf(
                RpaParamSpec("imPackage", RpaParamKind.PACKAGE, required = true),
                RpaParamSpec("contactName", RpaParamKind.TEXT, required = true),
            ),
        ),
        RpaActionSpec(
            action = RpaAction.WAIT,
            category = RpaActionCategory.FLOW,
            needsTarget = false,
            params = listOf(RpaParamSpec("waitMs", RpaParamKind.DURATION_MS, required = true)),
        ),
        RpaActionSpec(
            action = RpaAction.WAIT_FOR_TARGET,
            category = RpaActionCategory.FLOW,
            needsTarget = true,
        ),
        RpaActionSpec(
            action = RpaAction.TAP,
            category = RpaActionCategory.TARGET,
            needsTarget = true,
        ),
        RpaActionSpec(
            action = RpaAction.LONG_PRESS,
            category = RpaActionCategory.TARGET,
            needsTarget = true,
        ),
        RpaActionSpec(
            action = RpaAction.INPUT_TEXT,
            category = RpaActionCategory.INPUT,
            needsTarget = false,
            params = listOf(
                RpaParamSpec("text", RpaParamKind.MULTILINE, required = true),
                RpaParamSpec("clearFirst", RpaParamKind.BOOLEAN),
            ),
        ),
        RpaActionSpec(
            action = RpaAction.PRESS_KEY,
            category = RpaActionCategory.INPUT,
            needsTarget = false,
            params = listOf(
                RpaParamSpec(
                    key = "key",
                    kind = RpaParamKind.ENUM,
                    required = true,
                    enumValues = listOf("back", "home", "recents", "notifications", "enter"),
                ),
            ),
        ),
        RpaActionSpec(
            action = RpaAction.SWIPE,
            category = RpaActionCategory.GESTURE,
            needsTarget = false,
            params = listOf(
                RpaParamSpec(
                    key = "direction",
                    kind = RpaParamKind.ENUM,
                    required = true,
                    enumValues = RpaDirection.entries.map { it.name },
                ),
                RpaParamSpec("distanceRatio", RpaParamKind.RATIO),
                RpaParamSpec("durationMs", RpaParamKind.DURATION_MS),
            ),
        ),
        RpaActionSpec(
            action = RpaAction.SCROLL,
            category = RpaActionCategory.GESTURE,
            needsTarget = false,
            params = listOf(
                RpaParamSpec(
                    key = "direction",
                    kind = RpaParamKind.ENUM,
                    enumValues = RpaDirection.entries.map { it.name },
                ),
                RpaParamSpec("distanceRatio", RpaParamKind.RATIO),
                RpaParamSpec("durationMs", RpaParamKind.DURATION_MS),
            ),
        ),
        RpaActionSpec(
            action = RpaAction.ASSERT_TEXT,
            category = RpaActionCategory.ASSERT,
            needsTarget = false,
        ),
        RpaActionSpec(
            action = RpaAction.COMMENT,
            category = RpaActionCategory.FLOW,
            needsTarget = false,
        ),
        RpaActionSpec(
            action = RpaAction.OPEN_LINK,
            category = RpaActionCategory.APP,
            needsTarget = false,
            params = listOf(RpaParamSpec("url", RpaParamKind.URL, required = true)),
        ),
        RpaActionSpec(
            action = RpaAction.SET_VARIABLE,
            category = RpaActionCategory.INPUT,
            needsTarget = false,
            params = listOf(RpaParamSpec("assignments", RpaParamKind.VAR_LIST, required = true)),
        ),
        RpaActionSpec(
            action = RpaAction.CONTROL_FLOW,
            category = RpaActionCategory.FLOW,
            needsTarget = false,
            params = listOf(
                RpaParamSpec(
                    key = "flowKind",
                    kind = RpaParamKind.ENUM,
                    required = true,
                    enumValues = RpaFlowKind.entries.map { it.name },
                ),
                RpaParamSpec("gotoStepId", RpaParamKind.TARGET_STEP),
            ),
        ),
        RpaActionSpec(
            action = RpaAction.COMPOSITE,
            category = RpaActionCategory.FLOW,
            needsTarget = false,
            params = listOf(
                RpaParamSpec("customActionId", RpaParamKind.CUSTOM_ACTION, required = true),
            ),
        ),
        // P2 占位（裁定③）：目录可见、UI 置灰标「规划中」，运行显式报 unsupported_action。
        RpaActionSpec(
            action = RpaAction.TAP_COLOR,
            category = RpaActionCategory.TARGET,
            needsTarget = true,
        ),
        RpaActionSpec(
            action = RpaAction.GESTURE_MULTI,
            category = RpaActionCategory.GESTURE,
            needsTarget = false,
        ),
    )

    private val byAction: Map<RpaAction, RpaActionSpec> = actions.associateBy { it.action }

    fun specOf(action: RpaAction): RpaActionSpec = byAction.getValue(action)

    fun needsTarget(action: RpaAction): Boolean = byAction[action]?.needsTarget == true

    fun defaultStep(action: RpaAction, fallbackId: String = RpaIds.newId("step")): RpaStep {
        val base = RpaStep(id = fallbackId, action = action)
        return when (action) {
            // 发行版不预填任何 App：由应用选择器 / 用户输入决定目标包名，
            // 避免把测试夹具里的微信包名当成产品默认值。
            RpaAction.LAUNCH_APP -> base
            RpaAction.OPEN_CHAT -> base.copy(
                params = RpaParams(contactName = "\${contactName}"),
                timeoutMs = 20_000,
            )
            RpaAction.WAIT -> base.copy(params = RpaParams(waitMs = 800), timeoutMs = 2_000)
            RpaAction.WAIT_FOR_TARGET -> base.copy(timeoutMs = 10_000)
            RpaAction.INPUT_TEXT -> base.copy(params = RpaParams(text = ""))
            RpaAction.PRESS_KEY -> base.copy(params = RpaParams(key = "back"))
            RpaAction.SWIPE -> base.copy(
                params = RpaParams(
                    direction = RpaDirection.UP,
                    distanceRatio = 0.5f,
                    durationMs = 300,
                ),
            )
            RpaAction.SCROLL -> base.copy(
                params = RpaParams(
                    direction = RpaDirection.UP,
                    distanceRatio = 0.5f,
                    durationMs = 300,
                ),
            )
            RpaAction.ASSERT_TEXT -> base.copy(
                expect = RpaExpect(text = "", match = RpaMatchMode.CONTAINS, timeoutMs = 5_000),
                timeoutMs = 5_000,
            )
            RpaAction.TAP,
            RpaAction.LONG_PRESS,
            RpaAction.COMMENT,
            -> base
            RpaAction.OPEN_LINK -> base.copy(params = RpaParams(url = "https://"))
            RpaAction.SET_VARIABLE -> base.copy(
                params = RpaParams(
                    assignments = listOf(RpaVarAssign(variableName = "var1")),
                ),
            )
            RpaAction.CONTROL_FLOW -> base.copy(
                params = RpaParams(flowKind = RpaFlowKind.STOP),
            )
            RpaAction.COMPOSITE -> base
            RpaAction.TAP_COLOR,
            RpaAction.GESTURE_MULTI,
            -> base
        }
    }
}

/** 三模式能力矩阵：决定属性面板哪些区域可用 / 必须双写。 */
object RpaModePolicy {

    fun a11yEnabled(mode: RpaMode): Boolean = mode != RpaMode.VISION

    fun visionEnabled(mode: RpaMode): Boolean = mode != RpaMode.A11Y

    fun bothRequired(mode: RpaMode): Boolean = mode == RpaMode.DUAL

    /** 该线路是否参与定位（模式切换时 UI 用它置灰面板）。 */
    fun lineEnabled(mode: RpaMode, line: RpaLine): Boolean =
        if (line == RpaLine.A11Y) a11yEnabled(mode) else visionEnabled(mode)
}

object RpaIds {

    /**
     * 进程内单调序号：一次插入多个步骤、复制脚本一次重排全部步骤时，
     * 单纯「毫秒 + 三位随机数」有可见的撞 id 概率（校验器会报 step_id_duplicate）。
     */
    private val sequence = java.util.concurrent.atomic.AtomicLong(0)

    fun newId(prefix: String): String {
        val stamp = System.currentTimeMillis().toString(36)
        val seq = sequence.incrementAndGet().toString(36)
        val salt = kotlin.random.Random.nextInt(0x1000).toString(36)
        return "${prefix}_${stamp}_${seq}_$salt"
    }
}

/**
 * 运行期硬边界：编辑器输入与解释器执行共用同一组上限，避免「UI 允许填、
 * 引擎静默截断」的两套语义。超限只报警告，执行时按此钳制。
 */
object RpaLimits {

    /** 单步超时上限（含 WAIT_FOR_TARGET / ASSERT_TEXT 轮询）。 */
    const val MAX_STEP_TIMEOUT_MS = 600_000L

    /** 单步等待上限。 */
    const val MAX_WAIT_MS = 600_000L

    /** 单步重试次数上限（0 表示不重试）。 */
    const val MAX_RETRIES = 10

    /** 步骤前后延迟上限。 */
    const val MAX_DELAY_MS = 60_000L

    /** 单次运行总预算，防止 WAIT / 重试把任务无限拖长。 */
    const val DEFAULT_RUN_TIMEOUT_MS = 1_800_000L
    const val MAX_RUN_TIMEOUT_MS = 3_600_000L

    const val MIN_SWIPE_DURATION_MS = 50
    const val MAX_SWIPE_DURATION_MS = 3_000

    /** 百分比坐标允许越界区间（图6「100.9%」、图11 ROI「103%」）。 */
    const val RATIO_MIN = -0.2f
    const val RATIO_MAX = 1.2f

    /** 跳转次数上限（PC 循环双保险之一，超限 jump_limit_exceeded）。 */
    const val MAX_JUMPS = 1000

    /** 重复次数上限（步骤 repeat 与整脚本 global.repeat 共用）。 */
    const val MAX_REPEAT = 999

    /** 复合动作递归展开深度上限。 */
    const val MAX_CUSTOM_DEPTH = 3

    /** 打开链接的 scheme 白名单。 */
    val URL_SCHEMES = setOf("http", "https", "mailto", "tel", "sms")

    fun stepTimeout(value: Long): Long =
        if (value <= 0) MIN_STEP_TIMEOUT_MS else value.coerceAtMost(MAX_STEP_TIMEOUT_MS)

    fun waitMs(value: Long): Long = value.coerceIn(0L, MAX_WAIT_MS)

    fun retries(value: Int): Int = value.coerceIn(0, MAX_RETRIES)

    fun delayMs(value: Long): Long = value.coerceIn(0L, MAX_DELAY_MS)

    /** 步骤 repeat：总执行次数（≥1）。 */
    fun repeat(value: Int): Int = value.coerceIn(1, MAX_REPEAT)

    /** 整脚本循环次数（0/1 = 跑一遍）。 */
    fun scriptRepeat(value: Int): Int = if (value <= 0) 1 else value.coerceAtMost(MAX_REPEAT)

    private const val MIN_STEP_TIMEOUT_MS = 100L
}

/**
 * 脚本静态校验（运行前必须全绿）：模式约束、动作必填参数、双模式双写、
 * 视觉模板/区域合法性、变量引用完整性。
 */
object RpaValidator {

    private val pressKeys = setOf("back", "home", "recents", "notifications", "enter")

    fun validate(script: RpaScript): List<RpaIssue> = buildList {
        if (script.id.isBlank()) {
            add(RpaIssue("script_id_missing"))
        }
        if (script.name.isBlank()) {
            add(RpaIssue("script_name_missing"))
        }
        if (script.steps.isEmpty()) {
            add(RpaIssue("script_steps_empty"))
        }
        val duplicateIds = script.steps.groupBy { it.id }.filterValues { it.size > 1 }.keys
        duplicateIds.forEach { id -> add(RpaIssue("step_id_duplicate", id)) }
        val duplicateCustomIds =
            script.customActions.groupBy { it.id }.filterValues { it.size > 1 }.keys
        duplicateCustomIds.forEach { id -> add(RpaIssue("custom_action_id_duplicate", id)) }

        // 变量声明：脚本级 variables + SET_VARIABLE（声明语义，消除误报）。
        val declared = script.declaredVariableKeys()
        (script.steps + script.customActions.flatMap { it.steps }).forEach { step ->
            if (step.enabled) addAll(validateStep(script, step))
            step.referencedVariables().filterNot { it in declared }.forEach { name ->
                add(
                    RpaIssue(
                        code = "variable_undefined",
                        stepId = step.id,
                        severity = RpaIssueSeverity.WARNING,
                        detail = name,
                    ),
                )
            }
        }

        // 复合动作内部也要过校验（跳转范围只在本动作内）。
        script.customActions.forEach { custom ->
            val ids = custom.steps.map { it.id }.toSet()
            custom.steps.forEach { step ->
                addAll(validateJump(script, step, ids, scopeDetail = custom.id))
            }
            custom.steps.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
                add(RpaIssue("step_id_duplicate", id, detail = custom.id))
            }
        }

        // 跳转图校验（顶层范围）+ 复合展开深度。
        val topLevelIds = script.steps.map { it.id }.toSet()
        script.steps.forEach { step -> addAll(validateJump(script, step, topLevelIds)) }
        addAll(validateCompositeGraph(script))
    }

    /** 跳转目标必须存在于同一作用域（顶层 / 同一复合动作内），禁止跨边界（§五）。 */
    private fun validateJump(
        script: RpaScript,
        step: RpaStep,
        scopeIds: Set<String>,
        scopeDetail: String = "",
    ): List<RpaIssue> = buildList {
        if (step.action != RpaAction.CONTROL_FLOW) return@buildList
        if (step.params.flowKind != RpaFlowKind.GOTO) return@buildList
        val targetId = step.params.gotoStepId
        if (targetId.isNullOrBlank()) {
            add(RpaIssue("param_goto_missing", step.id, detail = scopeDetail))
            return@buildList
        }
        if (targetId !in scopeIds) {
            add(RpaIssue("jump_target_missing", step.id, detail = targetId))
            return@buildList
        }
        // 无条件自跳转 = 死循环（条件自环留给用户表达「重试直到」）。
        if (targetId == step.id && step.runCondition == RpaStepCondition.None) {
            add(RpaIssue("jump_self_unconditional", step.id))
        }
    }

    /** 复合动作引用图：悬空引用 / 循环引用 / 展开深度超限。 */
    private fun validateCompositeGraph(script: RpaScript): List<RpaIssue> = buildList {
        val customById = script.customActions.associateBy { it.id }

        fun depthOf(id: String, seen: Set<String>): Int {
            if (id in seen) return RpaLimits.MAX_CUSTOM_DEPTH + 1
            val custom = customById[id] ?: return 0
            val nested = custom.steps.filter { it.action == RpaAction.COMPOSITE }
            return 1 + (nested.mapNotNull { it.params.customActionId }
                .maxOfOrNull { depthOf(it, seen + id) } ?: 0)
        }

        (script.steps + script.customActions.flatMap { it.steps }).forEach { step ->
            if (step.action != RpaAction.COMPOSITE) return@forEach
            val id = step.params.customActionId
            if (id.isNullOrBlank()) {
                add(RpaIssue("param_custom_action_missing", step.id))
                return@forEach
            }
            if (id !in customById) {
                add(RpaIssue("custom_action_unknown", step.id, detail = id))
                return@forEach
            }
            if (depthOf(id, emptySet()) > RpaLimits.MAX_CUSTOM_DEPTH) {
                add(RpaIssue("composite_depth_exceeded", step.id, detail = id))
            }
        }
    }

    fun validateStep(script: RpaScript, step: RpaStep): List<RpaIssue> = buildList {
        if (step.timeoutMs <= 0) {
            add(RpaIssue("step_timeout_invalid", step.id, RpaIssueSeverity.WARNING))
        } else if (step.timeoutMs > RpaLimits.MAX_STEP_TIMEOUT_MS) {
            add(RpaIssue("step_timeout_over_limit", step.id, RpaIssueSeverity.WARNING))
        }
        if (step.retries < 0) {
            add(RpaIssue("step_retries_invalid", step.id))
        } else if (step.retries > RpaLimits.MAX_RETRIES) {
            add(RpaIssue("step_retries_over_limit", step.id, RpaIssueSeverity.WARNING))
        }
        if (step.delayBeforeMs > RpaLimits.MAX_DELAY_MS || step.delayAfterMs > RpaLimits.MAX_DELAY_MS) {
            add(RpaIssue("step_delay_over_limit", step.id, RpaIssueSeverity.WARNING))
        }
        if (step.repeat < 1 || step.repeat > RpaLimits.MAX_REPEAT) {
            add(RpaIssue("step_repeat_invalid", step.id, RpaIssueSeverity.WARNING))
        }
        addAll(validateCondition(step))
        addAll(validateLineParams(script, step))
        val target = step.target
        when (step.action) {
            RpaAction.LAUNCH_APP -> if (step.params.packageName.isNullOrBlank()) {
                add(RpaIssue("param_package_missing", step.id))
            }
            RpaAction.OPEN_CHAT -> {
                if (step.params.imPackage.isNullOrBlank()) {
                    add(RpaIssue("param_im_package_missing", step.id))
                }
                if (step.params.contactName.isNullOrBlank()) {
                    add(RpaIssue("param_contact_missing", step.id))
                }
            }
            RpaAction.WAIT -> {
                val wait = step.params.waitMs ?: 0
                if (wait <= 0) {
                    add(RpaIssue("param_wait_missing", step.id))
                } else if (wait > RpaLimits.MAX_WAIT_MS) {
                    add(RpaIssue("param_wait_over_limit", step.id, RpaIssueSeverity.WARNING))
                }
            }
            RpaAction.INPUT_TEXT -> if (step.params.text.isNullOrEmpty()) {
                add(RpaIssue("param_text_missing", step.id))
            }
            RpaAction.PRESS_KEY -> if (step.params.key !in pressKeys) {
                add(RpaIssue("param_key_invalid", step.id))
            }
            RpaAction.SWIPE -> if (step.params.direction == null) {
                add(RpaIssue("param_direction_missing", step.id))
            }
            RpaAction.ASSERT_TEXT -> if (step.expect?.text.isNullOrBlank()) {
                add(RpaIssue("param_expect_missing", step.id))
            }
            RpaAction.TAP,
            RpaAction.LONG_PRESS,
            RpaAction.WAIT_FOR_TARGET,
            -> if (target == null) add(RpaIssue("target_missing", step.id))
            RpaAction.OPEN_LINK -> {
                val url = step.params.url
                if (url.isNullOrBlank()) {
                    add(RpaIssue("param_url_missing", step.id))
                } else {
                    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
                    if (scheme !in RpaLimits.URL_SCHEMES) {
                        add(RpaIssue("param_url_scheme_invalid", step.id, detail = scheme))
                    }
                }
            }
            RpaAction.SET_VARIABLE -> {
                if (step.params.assignments.isEmpty()) {
                    add(RpaIssue("param_assignments_missing", step.id))
                }
                step.params.assignments.forEach { assign ->
                    if (assign.variableName.isBlank() ||
                        !assign.variableName.all { it.isVariableNameChar() }
                    ) {
                        add(RpaIssue("param_variable_name_invalid", step.id, detail = assign.variableName))
                    }
                    if (assign.source == RpaVarSource.VARIABLE_REF && assign.value.isBlank()) {
                        add(RpaIssue("param_variable_ref_missing", step.id, detail = assign.variableName))
                    }
                    if (assign.source == RpaVarSource.INCREMENT &&
                        assign.value.isNotBlank() &&
                        assign.value.toDoubleOrNull() == null
                    ) {
                        add(
                            RpaIssue(
                                "param_variable_increment_invalid",
                                step.id,
                                RpaIssueSeverity.WARNING,
                                detail = assign.variableName,
                            ),
                        )
                    }
                }
            }
            RpaAction.CONTROL_FLOW -> {
                when (step.params.flowKind) {
                    null -> add(RpaIssue("param_flow_kind_missing", step.id))
                    RpaFlowKind.GOTO -> if (step.params.gotoStepId.isNullOrBlank()) {
                        add(RpaIssue("param_goto_missing", step.id))
                    }
                    RpaFlowKind.STOP -> Unit
                }
            }
            RpaAction.COMPOSITE -> if (step.params.customActionId.isNullOrBlank()) {
                add(RpaIssue("param_custom_action_missing", step.id))
            }
            RpaAction.TAP_COLOR,
            RpaAction.GESTURE_MULTI,
            -> add(
                RpaIssue(
                    "action_unsupported_p2",
                    step.id,
                    RpaIssueSeverity.WARNING,
                    step.action.name,
                ),
            )
            RpaAction.SCROLL,
            RpaAction.COMMENT,
            -> Unit
        }

        if (target != null) {
            addAll(validateTarget(script, step, target))
        }
    }

    /** 运行条件载荷校验（公式表达式只做静态解析，无副作用）。 */
    private fun validateCondition(step: RpaStep): List<RpaIssue> = buildList {
        when (val condition = step.runCondition) {
            RpaStepCondition.None -> Unit
            is RpaStepCondition.Variable -> {
                if (condition.lhs.isBlank()) {
                    add(RpaIssue("condition_variable_lhs_missing", step.id))
                }
                if (condition.rhs.isBlank()) {
                    add(RpaIssue("condition_variable_rhs_missing", step.id))
                }
            }
            is RpaStepCondition.ImageVisible -> if (condition.templateId.isBlank()) {
                add(RpaIssue("condition_template_missing", step.id))
            }
            is RpaStepCondition.TextVisible -> if (condition.text.isBlank()) {
                add(RpaIssue("condition_text_missing", step.id))
            }
            RpaStepCondition.PrevOk,
            RpaStepCondition.PrevFail,
            -> Unit
            is RpaStepCondition.Expr -> {
                if (condition.expression.isBlank()) {
                    add(RpaIssue("condition_expr_missing", step.id))
                } else {
                    val detail = RpaConditionDsl.validate(condition.expression)
                    if (detail != null) {
                        add(RpaIssue("condition_expr_invalid", step.id, detail = detail))
                    }
                }
            }
        }
    }

    /**
     * D 类分线通道参数校验（§五/§六）：空选择器、比例点越界、ROI 零面积、
     * 以及双模式双写（B10）——D 类动作恰好配置一侧时报缺侧引导补写。
     */
    private fun validateLineParams(script: RpaScript, step: RpaStep): List<RpaIssue> = buildList {
        val a11y = step.lineParams.a11y
        val vision = step.lineParams.vision
        // 空条件选择器一律拒绝（与 target 侧同口径，不看模式 / 动作）。
        if (a11y.readySelector?.isEmpty() == true) {
            add(RpaIssue("param_ready_selector_invalid", step.id))
        }
        if (a11y.inputSelector?.isEmpty() == true) {
            add(RpaIssue("param_input_selector_invalid", step.id))
        }
        if (a11y.anchorSelector?.isEmpty() == true) {
            add(RpaIssue("param_anchor_selector_invalid", step.id))
        }
        if (a11y.readScope?.isEmpty() == true) {
            add(RpaIssue("param_read_scope_invalid", step.id))
        }
        // 比例点（left=xRatio、top=yRatio）：越界报错（容忍区间同 RATIO_MIN..MAX）。
        if (vision.focusPoint?.let { !it.isRatioPoint() } == true) {
            add(RpaIssue("param_focus_point_invalid", step.id))
        }
        if (vision.startPoint?.let { !it.isRatioPoint() } == true) {
            add(RpaIssue("param_start_point_invalid", step.id))
        }
        if (vision.endPoint?.let { !it.isRatioPoint() } == true) {
            add(RpaIssue("param_end_point_invalid", step.id))
        }
        // ROI 四值可越界（图11「103%」）：只拒零面积。
        if (vision.ocrRegion?.let { !it.isValid() } == true) {
            add(RpaIssue("param_ocr_region_invalid", step.id))
        }
        // 双模式双写（B10）：恰好一侧配置时报缺侧；两侧全默认（旧脚本）与
        // 两侧齐备都不报；单模式只写本线，另一线允许缺失（§〇.2 模式兼容）。
        if (script.mode == RpaMode.DUAL && usesDualLineParams(step)) {
            val a11yConfigured = a11y.isConfigured()
            val visionConfigured = vision.isConfigured()
            if (a11yConfigured && !visionConfigured) {
                add(RpaIssue("mode_dual_missing_vision_line_params", step.id))
            }
            if (visionConfigured && !a11yConfigured) {
                add(RpaIssue("mode_dual_missing_a11y_line_params", step.id))
            }
        }
    }

    /** 双写约束只覆盖 D 类分线通道参数适用的动作（§一 动作矩阵）。 */
    private fun usesDualLineParams(step: RpaStep): Boolean = when (step.action) {
        RpaAction.LAUNCH_APP,
        RpaAction.OPEN_CHAT,
        RpaAction.INPUT_TEXT,
        RpaAction.SWIPE,
        RpaAction.SCROLL,
        RpaAction.ASSERT_TEXT,
        -> true
        // SET_VARIABLE 只有「界面读取」行才走读屏通道（readScope / ocrRegion）。
        RpaAction.SET_VARIABLE ->
            step.params.assignments.any { it.source == RpaVarSource.UI_READ }
        else -> false
    }

    private fun validateTarget(
        script: RpaScript,
        step: RpaStep,
        target: RpaTarget,
    ): List<RpaIssue> = buildList {
        val a11y = target.a11y
        val vision = target.vision
        when (script.mode) {
            RpaMode.A11Y -> {
                if (a11y == null || !a11y.isConfigured()) {
                    add(RpaIssue("mode_a11y_missing_a11y_target", step.id))
                }
            }
            RpaMode.VISION -> {
                if (vision == null || !vision.isConfigured()) {
                    add(RpaIssue("mode_vision_missing_vision_target", step.id))
                }
            }
            RpaMode.DUAL -> {
                if (a11y == null || !a11y.isConfigured()) {
                    add(RpaIssue("mode_dual_missing_a11y_target", step.id))
                }
                if (vision == null || !vision.isConfigured()) {
                    add(RpaIssue("mode_dual_missing_vision_target", step.id))
                }
            }
        }

        a11y?.let { spec ->
            if (spec.selectors.isEmpty() && spec.snapshot == null) {
                add(RpaIssue("a11y_selector_empty", step.id))
            }
            if (spec.selectors.any { it.isEmpty() }) {
                add(RpaIssue("a11y_selector_incomplete", step.id))
            }
            if (spec.selectors.isEmpty() && spec.snapshot != null) {
                add(
                    RpaIssue(
                        "a11y_snapshot_only",
                        step.id,
                        RpaIssueSeverity.WARNING,
                        "snapshot coordinates are used when selectors miss",
                    ),
                )
            }
        }

        vision?.let { spec ->
            addAll(validateVision(script, step, spec.primary))
            spec.fallback?.let { addAll(validateVision(script, step, it)) }
        }
    }

    private fun validateVision(
        script: RpaScript,
        step: RpaStep,
        locator: RpaVisionLocator,
    ): List<RpaIssue> = buildList {
        when (locator.kind) {
            RpaVisionKind.TEMPLATE -> if (locator.templateId.isNullOrBlank()) {
                add(RpaIssue("vision_template_missing", step.id))
            }
            RpaVisionKind.OCR_TEXT -> if (locator.ocrText.isNullOrBlank()) {
                add(RpaIssue("vision_ocr_text_missing", step.id))
            }
            RpaVisionKind.RATIO -> if (
                locator.ratioX !in RpaLimits.RATIO_MIN..RpaLimits.RATIO_MAX ||
                locator.ratioY !in RpaLimits.RATIO_MIN..RpaLimits.RATIO_MAX
            ) {
                add(RpaIssue("vision_ratio_invalid", step.id))
            }
        }
        // 相似度滑块口径 = 图11 的 50%–99%。
        if (locator.threshold !in 0.5..0.99) {
            add(RpaIssue("vision_threshold_invalid", step.id, RpaIssueSeverity.WARNING))
        }
        if (locator.region != null && !locator.region.isValid()) {
            add(RpaIssue("vision_region_invalid", step.id))
        }
    }

    fun hasErrors(issues: List<RpaIssue>): Boolean =
        issues.any { it.severity == RpaIssueSeverity.ERROR }
}

/**
 * 运行就绪门（与静态校验分离）：只看当前设备能力是否支撑脚本所选的模式，
 * 缺项一律报因，绝不静默换线。
 */
object RpaReadiness {

    fun check(
        script: RpaScript,
        caps: RpaCapabilities,
        // 前置探针（B1/B5）：默认「未接入」不误报，旧调用方零回归；
        // templateExists 必须最后（调用方用尾随 lambda 绑定模板探针）。
        appInstalled: (String) -> Boolean = { true },
        screenLocked: () -> Boolean = { false },
        templateExists: (scriptId: String, templateId: String) -> Boolean = { _, _ -> true },
    ): List<RpaIssue> = buildList {
        // B1 前置预检：目标 App 未安装 → 显式报因，禁止起跑（§六 6.1③）。
        if (script.appPackage.isNotBlank() && !appInstalled(script.appPackage)) {
            add(RpaIssue("app_missing", detail = script.appPackage))
        }
        // B5 前置预检：屏幕锁屏 → 显式报因，禁止起跑。
        if (screenLocked()) {
            add(RpaIssue("screen_locked"))
        }
        when (script.mode) {
            RpaMode.A11Y -> if (!caps.a11yReady) {
                add(RpaIssue("ready_a11y_missing"))
            }
            RpaMode.VISION -> {
                if (!caps.visionReady) add(RpaIssue("ready_vision_missing"))
                // 抽帧可以来自录屏，但点击 / 输入 / 滑动必须有 Shizuku 注入。
                if (!caps.shizukuReady) {
                    add(RpaIssue("ready_vision_injection_missing"))
                }
                if (!caps.ocrReady) add(RpaIssue("ready_ocr_missing"))
            }
            RpaMode.DUAL -> {
                if (!caps.a11yReady && !caps.visionReady) {
                    add(RpaIssue("ready_no_line"))
                } else {
                    if (!caps.a11yReady) {
                        add(
                            RpaIssue(
                                "ready_dual_a11y_missing",
                                severity = RpaIssueSeverity.WARNING,
                            ),
                        )
                    }
                    if (!caps.visionReady) {
                        add(
                            RpaIssue(
                                "ready_dual_vision_missing",
                                severity = RpaIssueSeverity.WARNING,
                            ),
                        )
                    }
                }
            }
        }
        // A11Y 模式根本不会走视觉线路：残留的 vision 定位不得阻止运行
        // （用户从 DUAL 切到 A11Y 后删除模板是合法操作）。
        if (RpaModePolicy.visionEnabled(script.mode)) {
            script.steps.filter { it.enabled }.forEach { step ->
                val vision = step.target?.vision
                listOfNotNull(vision?.primary, vision?.fallback).forEach { locator ->
                    if (locator.kind == RpaVisionKind.TEMPLATE) {
                        val templateId = locator.templateId
                        if (!templateId.isNullOrBlank() && !templateExists(script.id, templateId)) {
                            add(RpaIssue("ready_template_missing", step.id))
                        }
                    }
                }
                // 运行条件「出现图片」的模板同样要就绪（图7 条件自带模板）。
                val condition = step.runCondition
                if (condition is RpaStepCondition.ImageVisible &&
                    !templateExists(script.id, condition.templateId)
                ) {
                    add(RpaIssue("ready_template_missing", step.id, detail = condition.templateId))
                }
            }
        }
    }

    fun hasErrors(issues: List<RpaIssue>): Boolean =
        issues.any { it.severity == RpaIssueSeverity.ERROR }
}
