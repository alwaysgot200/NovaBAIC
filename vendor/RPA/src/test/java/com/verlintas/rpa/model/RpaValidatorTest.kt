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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态校验单测（v4 §六）：跳转悬空 / 自环、复合动作图、变量声明、
 * 公式条件静态检查、P2 占位告警、比例越界与 URL scheme 白名单。
 */
class RpaValidatorTest {

    @Test
    fun danglingJumpTargetIsReported() {
        val issues = validate(
            step("s1", RpaAction.CONTROL_FLOW, RpaParams(flowKind = RpaFlowKind.GOTO, gotoStepId = "ghost")),
        )
        assertHasCode(issues, "jump_target_missing")
    }

    @Test
    fun unconditionalSelfJumpIsReported() {
        val issues = validate(
            step("s1", RpaAction.CONTROL_FLOW, RpaParams(flowKind = RpaFlowKind.GOTO, gotoStepId = "s1")),
        )
        assertHasCode(issues, "jump_self_unconditional")
    }

    @Test
    fun conditionalSelfJumpIsAllowed() {
        val issues = validate(
            step(
                "s1",
                RpaAction.CONTROL_FLOW,
                RpaParams(flowKind = RpaFlowKind.GOTO, gotoStepId = "s1"),
                runCondition = RpaStepCondition.Variable(lhs = "\${n}", op = RpaCompareOp.LT, rhs = "3"),
            ),
        )
        assertNoCode(issues, "jump_self_unconditional")
    }

    @Test
    fun jumpIntoCompositeScopeIsRejected() {
        // 跳转只在本作用域内成立：顶层步骤跳进复合动作内部必须报悬空。
        val issues = validate(
            step("s1", RpaAction.CONTROL_FLOW, RpaParams(flowKind = RpaFlowKind.GOTO, gotoStepId = "c1")),
            customActions = listOf(
                RpaCustomAction(id = "ca1", steps = listOf(step("c1", RpaAction.COMMENT))),
            ),
        )
        assertHasCode(issues, "jump_target_missing")
    }

    @Test
    fun unknownCompositeReferenceIsReported() {
        val issues = validate(
            step("s1", RpaAction.COMPOSITE, RpaParams(customActionId = "ghost")),
        )
        assertHasCode(issues, "custom_action_unknown")
    }

    @Test
    fun compositeNestingOverDepthIsReported() {
        // ca1 → ca2 → ca3 → ca4：4 层展开超过 MAX_CUSTOM_DEPTH=3。
        val chain = listOf(
            RpaCustomAction(id = "ca1", steps = listOf(step("n1", RpaAction.COMPOSITE, RpaParams(customActionId = "ca2")))),
            RpaCustomAction(id = "ca2", steps = listOf(step("n2", RpaAction.COMPOSITE, RpaParams(customActionId = "ca3")))),
            RpaCustomAction(id = "ca3", steps = listOf(step("n3", RpaAction.COMPOSITE, RpaParams(customActionId = "ca4")))),
            RpaCustomAction(id = "ca4", steps = listOf(step("n4", RpaAction.COMMENT))),
        )
        val issues = validate(
            step("s1", RpaAction.COMPOSITE, RpaParams(customActionId = "ca1")),
            customActions = chain,
        )
        assertHasCode(issues, "composite_depth_exceeded")
    }

    @Test
    fun declaredVariableSilencesWarning() {
        val undeclared = validate(
            step("s1", RpaAction.INPUT_TEXT, RpaParams(text = "你好\${ghost}")),
        )
        assertTrue(
            undeclared.any {
                it.code == "variable_undefined" && it.severity == RpaIssueSeverity.WARNING
            },
        )
        val declared = validate(
            step(
                "s1",
                RpaAction.SET_VARIABLE,
                RpaParams(
                    assignments = listOf(RpaVarAssign(variableName = "ghost", value = "1")),
                ),
            ),
            step("s2", RpaAction.INPUT_TEXT, RpaParams(text = "你好\${ghost}")),
        )
        assertNoCode(declared, "variable_undefined")
    }

    @Test
    fun invalidExprConditionIsReported() {
        val issues = validate(
            step(
                "s1",
                RpaAction.COMMENT,
                runCondition = RpaStepCondition.Expr(expression = "var(\"x\") >"),
            ),
        )
        assertHasCode(issues, "condition_expr_invalid")
    }

    @Test
    fun p2PlaceholderActionsWarnExplicitly() {
        listOf(RpaAction.TAP_COLOR, RpaAction.GESTURE_MULTI).forEach { action ->
            val issues = validate(step("s1", action))
            assertTrue(
                "action=$action",
                issues.any {
                    it.code == "action_unsupported_p2" && it.severity == RpaIssueSeverity.WARNING
                },
            )
        }
    }

    @Test
    fun urlSchemeWhitelistIsEnforced() {
        val bad = validate(
            step("s1", RpaAction.OPEN_LINK, RpaParams(url = "ftp://example.com")),
        )
        assertHasCode(bad, "param_url_scheme_invalid")
        val good = validate(
            step("s1", RpaAction.OPEN_LINK, RpaParams(url = "https://example.com")),
        )
        assertNoCode(good, "param_url_scheme_invalid")
    }

    @Test
    fun ratioOutOfRangeIsReportedAndUnconfigured() {
        val issues = validate(
            step(
                "s1",
                RpaAction.TAP,
                target = RpaTarget(
                    vision = RpaVisionSpec(
                        primary = RpaVisionLocator(
                            kind = RpaVisionKind.RATIO,
                            ratioX = 2f,
                            ratioY = 0.5f,
                        ),
                    ),
                ),
            ),
            mode = RpaMode.VISION,
        )
        assertHasCode(issues, "vision_ratio_invalid")
        assertFalse(
            RpaVisionLocator(kind = RpaVisionKind.RATIO, ratioX = 2f).isConfigured(),
        )
        assertTrue(
            // 越界可容忍区间（图6 百分比坐标可越界）：-0.2..1.2 仍算已配置。
            RpaVisionLocator(kind = RpaVisionKind.RATIO, ratioX = -0.1f, ratioY = 1.1f).isConfigured(),
        )
    }

    @Test
    fun rectNormalizationClampsToUnitRange() {
        assertEquals(
            RpaRect(0f, 0.2f, 1f, 0.8f),
            RpaRect(-0.5f, 0.2f, 1.5f, 0.8f).normalized(),
        )
    }

    // ------------------------------ 分线通道参数（D 类） ------------------------------

    @Test
    fun emptyLineParamSelectorsAreReported() {
        val empty = RpaSelector()
        listOf(
            RpaAction.LAUNCH_APP to "param_ready_selector_invalid",
            RpaAction.SWIPE to "param_anchor_selector_invalid",
        ).forEach { (action, code) ->
            val issues = validate(
                step(
                    "s1",
                    action,
                    params = if (action == RpaAction.LAUNCH_APP) {
                        RpaParams(packageName = "com.example.app")
                    } else {
                        RpaParams(direction = RpaDirection.UP)
                    },
                    lineParams = when (code) {
                        "param_ready_selector_invalid" -> RpaLineParams(
                            a11y = RpaA11yLineParams(readySelector = empty),
                        )
                        else -> RpaLineParams(a11y = RpaA11yLineParams(anchorSelector = empty))
                    },
                ),
            )
            assertHasCode(issues, code)
        }
        val input = validate(
            step(
                "s1",
                RpaAction.INPUT_TEXT,
                params = RpaParams(text = "hi"),
                lineParams = RpaLineParams(a11y = RpaA11yLineParams(inputSelector = empty)),
            ),
        )
        assertHasCode(input, "param_input_selector_invalid")
        val assertText = validate(
            step(
                "s1",
                RpaAction.ASSERT_TEXT,
                expect = RpaExpect(text = "hi"),
                lineParams = RpaLineParams(a11y = RpaA11yLineParams(readScope = empty)),
            ),
        )
        assertHasCode(assertText, "param_read_scope_invalid")
    }

    @Test
    fun invalidLineParamPointsAreReported() {
        val focus = validate(
            step(
                "s1",
                RpaAction.INPUT_TEXT,
                params = RpaParams(text = "hi"),
                lineParams = RpaLineParams(
                    vision = RpaVisionLineParams(focusPoint = RpaRect(left = 2f, top = 0.5f)),
                ),
            ),
        )
        assertHasCode(focus, "param_focus_point_invalid")

        val swipe = validate(
            step(
                "s1",
                RpaAction.SWIPE,
                params = RpaParams(direction = RpaDirection.UP),
                lineParams = RpaLineParams(
                    vision = RpaVisionLineParams(
                        startPoint = RpaRect(left = -0.5f, top = 0.5f),
                        endPoint = RpaRect(left = 1.5f, top = 0.2f),
                    ),
                ),
            ),
        )
        assertHasCode(swipe, "param_start_point_invalid")
        assertHasCode(swipe, "param_end_point_invalid")

        val region = validate(
            step(
                "s1",
                RpaAction.ASSERT_TEXT,
                expect = RpaExpect(text = "hi"),
                lineParams = RpaLineParams(
                    vision = RpaVisionLineParams(
                        ocrRegion = RpaRect(0.5f, 0.5f, 0.5f, 0.9f),
                    ),
                ),
            ),
        )
        assertHasCode(region, "param_ocr_region_invalid")

        // ROI 四值可越界（图11「103%」）：越界但有面积的区域不报错。
        val validRegion = validate(
            step(
                "s1",
                RpaAction.ASSERT_TEXT,
                expect = RpaExpect(text = "hi"),
                lineParams = RpaLineParams(
                    vision = RpaVisionLineParams(ocrRegion = RpaRect(-0.1f, 0f, 1.1f, 1f)),
                ),
            ),
        )
        assertNoCode(validRegion, "param_ocr_region_invalid")
    }

    @Test
    fun dualModeRequiresBothLineParamsSides() {
        // 只写 a11y 侧 → 报缺 vision 侧（B10 双写约束）。
        val missingVision = validate(
            step(
                "s1",
                RpaAction.INPUT_TEXT,
                params = RpaParams(text = "hi"),
                lineParams = RpaLineParams(
                    a11y = RpaA11yLineParams(inputSelector = RpaSelector(editableOnly = true)),
                ),
            ),
            mode = RpaMode.DUAL,
        )
        assertHasCode(missingVision, "mode_dual_missing_vision_line_params")
        assertNoCode(missingVision, "mode_dual_missing_a11y_line_params")

        // 只写 vision 侧 → 报缺 a11y 侧。
        val missingA11y = validate(
            step(
                "s1",
                RpaAction.INPUT_TEXT,
                params = RpaParams(text = "hi"),
                lineParams = RpaLineParams(
                    vision = RpaVisionLineParams(inputChannel = RpaInputChannel.SHELL_INPUT),
                ),
            ),
            mode = RpaMode.DUAL,
        )
        assertHasCode(missingA11y, "mode_dual_missing_a11y_line_params")
        assertNoCode(missingA11y, "mode_dual_missing_vision_line_params")

        // 两侧都写 → 不报。
        val both = validate(
            step(
                "s1",
                RpaAction.LAUNCH_APP,
                params = RpaParams(packageName = "com.example.app"),
                lineParams = RpaLineParams(
                    a11y = RpaA11yLineParams(readySelector = RpaSelector(text = "首页")),
                    vision = RpaVisionLineParams(readyText = "首页"),
                ),
            ),
            mode = RpaMode.DUAL,
        )
        assertNoCode(both, "mode_dual_missing_a11y_line_params")
        assertNoCode(both, "mode_dual_missing_vision_line_params")
    }

    @Test
    fun singleModeAllowsMissingOtherLineParams() {
        // 单模式只写本线、另一线允许缺失（§〇.2 模式兼容）。
        val a11yOnly = validate(
            step(
                "s1",
                RpaAction.INPUT_TEXT,
                params = RpaParams(text = "hi"),
                lineParams = RpaLineParams(
                    a11y = RpaA11yLineParams(inputSelector = RpaSelector(editableOnly = true)),
                ),
            ),
            mode = RpaMode.A11Y,
        )
        assertNoCode(a11yOnly, "mode_dual_missing_vision_line_params")
        assertNoCode(a11yOnly, "mode_dual_missing_a11y_line_params")

        val visionOnly = validate(
            step(
                "s1",
                RpaAction.INPUT_TEXT,
                params = RpaParams(text = "hi"),
                lineParams = RpaLineParams(
                    vision = RpaVisionLineParams(inputChannel = RpaInputChannel.SHELL_INPUT),
                ),
            ),
            mode = RpaMode.VISION,
        )
        assertNoCode(visionOnly, "mode_dual_missing_a11y_line_params")
        assertNoCode(visionOnly, "mode_dual_missing_vision_line_params")
    }

    @Test
    fun defaultLineParamsPassWithoutDualWarnings() {
        // 旧脚本（lineParams 全默认）零回归：任何模式都不报分线缺侧。
        listOf(RpaMode.A11Y, RpaMode.VISION, RpaMode.DUAL).forEach { mode ->
            val issues = validate(
                step(
                    "s1",
                    RpaAction.INPUT_TEXT,
                    params = RpaParams(text = "hi"),
                ),
                mode = mode,
            )
            assertNoCode(issues, "mode_dual_missing_a11y_line_params")
            assertNoCode(issues, "mode_dual_missing_vision_line_params")
        }
    }

    // ------------------------------ 就绪预检（B1/B5） ------------------------------

    @Test
    fun readinessReportsAppMissingAndScreenLocked() {
        val script = RpaScript(
            id = "s",
            name = "就绪脚本",
            mode = RpaMode.A11Y,
            appPackage = "com.example.app",
            steps = listOf(step("s1", RpaAction.COMMENT)),
        )
        val caps = RpaCapabilities(a11yReady = true)

        val appMissing = RpaReadiness.check(script, caps, appInstalled = { false })
        assertHasCode(appMissing, "app_missing")
        assertTrue(RpaReadiness.hasErrors(appMissing))

        val locked = RpaReadiness.check(script, caps, screenLocked = { true })
        assertHasCode(locked, "screen_locked")
        assertTrue(RpaReadiness.hasErrors(locked))

        // 默认探针（未接入）不误报：旧调用方零回归。
        val ok = RpaReadiness.check(script, caps)
        assertNoCode(ok, "app_missing")
        assertNoCode(ok, "screen_locked")
        assertFalse(RpaReadiness.hasErrors(ok))
    }

    // ------------------------------ 夹具 ------------------------------

    private fun validate(
        vararg steps: RpaStep,
        customActions: List<RpaCustomAction> = emptyList(),
        mode: RpaMode = RpaMode.A11Y,
    ): List<RpaIssue> = RpaValidator.validate(
        RpaScript(
            id = "s",
            name = "校验脚本",
            mode = mode,
            steps = steps.toList(),
            customActions = customActions,
        ),
    )

    private fun step(
        id: String,
        action: RpaAction,
        params: RpaParams = RpaParams(),
        target: RpaTarget? = null,
        runCondition: RpaStepCondition = RpaStepCondition.None,
        expect: RpaExpect? = null,
        lineParams: RpaLineParams = RpaLineParams(),
    ): RpaStep = RpaStep(
        id = id,
        action = action,
        params = params,
        target = target,
        runCondition = runCondition,
        expect = expect,
        lineParams = lineParams,
    )

    private fun assertHasCode(issues: List<RpaIssue>, code: String) {
        assertTrue(
            "expect $code in ${issues.map { it.code }}",
            issues.any { it.code == code },
        )
    }

    private fun assertNoCode(issues: List<RpaIssue>, code: String) {
        assertFalse(
            "expect no $code in ${issues.map { it.code }}",
            issues.any { it.code == code },
        )
    }
}
