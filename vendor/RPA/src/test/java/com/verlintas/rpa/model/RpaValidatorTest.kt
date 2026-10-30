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
    ): RpaStep = RpaStep(
        id = id,
        action = action,
        params = params,
        target = target,
        runCondition = runCondition,
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
