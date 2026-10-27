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

        val declared = script.variables.keys
        script.steps.forEach { step ->
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
    }

    fun validateStep(script: RpaScript, step: RpaStep): List<RpaIssue> = buildList {
        if (step.timeoutMs <= 0) {
            add(RpaIssue("step_timeout_invalid", step.id, RpaIssueSeverity.WARNING))
        }
        if (step.retries < 0) {
            add(RpaIssue("step_retries_invalid", step.id))
        }
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
            RpaAction.WAIT -> if ((step.params.waitMs ?: 0) <= 0) {
                add(RpaIssue("param_wait_missing", step.id))
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
            RpaAction.SCROLL,
            RpaAction.COMMENT,
            -> Unit
        }

        if (target != null) {
            addAll(validateTarget(script, step, target))
        }
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
            RpaVisionKind.RATIO -> if (locator.ratioX !in 0f..1f || locator.ratioY !in 0f..1f) {
                add(RpaIssue("vision_ratio_invalid", step.id))
            }
        }
        if (locator.threshold !in 0.1..0.99) {
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
        templateExists: (scriptId: String, templateId: String) -> Boolean = { _, _ -> true },
    ): List<RpaIssue> = buildList {
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
        script.steps.filter { it.enabled }.forEach { step ->
            val vision = step.target?.vision ?: return@forEach
            listOfNotNull(vision.primary, vision.fallback).forEach { locator ->
                if (locator.kind == RpaVisionKind.TEMPLATE) {
                    val templateId = locator.templateId
                    if (!templateId.isNullOrBlank() && !templateExists(script.id, templateId)) {
                        add(RpaIssue("ready_template_missing", step.id))
                    }
                }
            }
        }
    }

    fun hasErrors(issues: List<RpaIssue>): Boolean =
        issues.any { it.severity == RpaIssueSeverity.ERROR }
}
