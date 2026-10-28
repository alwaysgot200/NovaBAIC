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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaIssueSeverity
import com.verlintas.rpa.model.RpaLimits
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaReadiness
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaValidator
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpaValidatorTest {

    private fun script(
        mode: RpaMode = RpaMode.A11Y,
        steps: List<RpaStep>,
        appPackage: String = "",
    ) = RpaScript(
        id = "script_test",
        name = "validator",
        mode = mode,
        appPackage = appPackage,
        steps = steps,
    )

    private fun codes(issues: List<com.verlintas.rpa.model.RpaIssue>): List<String> =
        issues.map { it.code }

    private fun a11yTarget(): RpaTarget = RpaTarget(
        a11y = RpaA11ySpec(
            selectors = listOf(RpaSelector(text = "send")),
        ),
    )

    private fun visionTarget(templateId: String? = "tpl_1"): RpaTarget = RpaTarget(
        vision = RpaVisionSpec(
            primary = RpaVisionLocator(
                kind = RpaVisionKind.TEMPLATE,
                templateId = templateId,
            ),
        ),
    )

    @Test
    fun `negative retries is an error and over limit is a warning`() {
        val negative = RpaValidator.validate(
            script(steps = listOf(RpaStep(id = "s1", action = RpaAction.COMMENT, retries = -1))),
        )
        assertTrue(codes(negative).contains("step_retries_invalid"))
        assertEquals(RpaIssueSeverity.ERROR, negative.first { it.code == "step_retries_invalid" }.severity)

        val over = RpaValidator.validate(
            script(
                steps = listOf(
                    RpaStep(
                        id = "s2",
                        action = RpaAction.COMMENT,
                        retries = RpaLimits.MAX_RETRIES + 1,
                    ),
                ),
            ),
        )
        val issue = over.first { it.code == "step_retries_over_limit" }
        assertEquals(RpaIssueSeverity.WARNING, issue.severity)
        assertFalse(RpaValidator.hasErrors(over))
    }

    @Test
    fun `timeout delay and wait over limits only warn`() {
        val issues = RpaValidator.validate(
            script(
                steps = listOf(
                    RpaStep(
                        id = "s1",
                        action = RpaAction.WAIT,
                        params = com.verlintas.rpa.model.RpaParams(
                            waitMs = RpaLimits.MAX_WAIT_MS + 1,
                        ),
                        timeoutMs = RpaLimits.MAX_STEP_TIMEOUT_MS + 1,
                        delayBeforeMs = RpaLimits.MAX_DELAY_MS + 1,
                    ),
                ),
            ),
        )

        assertTrue(codes(issues).contains("step_timeout_over_limit"))
        assertTrue(codes(issues).contains("step_delay_over_limit"))
        assertTrue(codes(issues).contains("param_wait_over_limit"))
        assertFalse(RpaValidator.hasErrors(issues))
    }

    @Test
    fun `dual mode requires both target lines`() {
        val issues = RpaValidator.validate(
            script(
                mode = RpaMode.DUAL,
                steps = listOf(RpaStep(id = "s1", action = RpaAction.TAP, target = a11yTarget())),
            ),
        )

        assertTrue(codes(issues).contains("mode_dual_missing_vision_target"))
        assertTrue(RpaValidator.hasErrors(issues))
    }

    @Test
    fun `a11y mode ignores stale vision target`() {
        val issues = RpaValidator.validate(
            script(
                mode = RpaMode.A11Y,
                steps = listOf(
                    RpaStep(
                        id = "s1",
                        action = RpaAction.TAP,
                        target = a11yTarget().copy(vision = visionTarget().vision),
                    ),
                ),
            ),
        )

        assertFalse(codes(issues).contains("mode_a11y_missing_a11y_target"))
        assertTrue(RpaValidator.hasErrors(issues).not())
    }

    @Test
    fun `disabled steps do not fail target validation`() {
        val issues = RpaValidator.validate(
            script(
                steps = listOf(
                    RpaStep(id = "s1", action = RpaAction.TAP, enabled = false),
                ),
            ),
        )

        assertFalse(codes(issues).contains("target_missing"))
        assertFalse(RpaValidator.hasErrors(issues))
    }
}

class RpaReadinessTest {

    private val readyCaps = RpaCapabilities(
        a11yReady = true,
        shizukuReady = true,
        visionReady = true,
        ocrReady = true,
        projectionReady = true,
        screenshotReady = true,
    )

    private fun script(mode: RpaMode, templateId: String? = "tpl_1"): RpaScript = RpaScript(
        id = "script_ready",
        name = "readiness",
        mode = mode,
        steps = listOf(
            RpaStep(
                id = "s1",
                action = RpaAction.TAP,
                target = RpaTarget(
                    a11y = RpaA11ySpec(selectors = listOf(RpaSelector(text = "send"))),
                    vision = RpaVisionSpec(
                        primary = RpaVisionLocator(
                            kind = RpaVisionKind.TEMPLATE,
                            templateId = templateId,
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a11y mode does not require vision templates`() {
        val issues = RpaReadiness.check(script(RpaMode.A11Y), readyCaps) { _, _ -> false }

        assertFalse(issues.any { it.code == "ready_template_missing" })
        assertFalse(RpaReadiness.hasErrors(issues))
    }

    @Test
    fun `vision mode reports missing template`() {
        val issues = RpaReadiness.check(script(RpaMode.VISION), readyCaps) { _, _ -> false }

        assertTrue(issues.any { it.code == "ready_template_missing" })
        assertTrue(RpaReadiness.hasErrors(issues))
    }

    @Test
    fun `vision mode requires a11y-free prerequisites`() {
        val issues = RpaReadiness.check(
            script(RpaMode.VISION),
            readyCaps.copy(visionReady = false, shizukuReady = false, ocrReady = false),
        ) { _, _ -> true }

        val codes = issues.map { it.code }
        assertTrue(codes.contains("ready_vision_missing"))
        assertTrue(codes.contains("ready_vision_injection_missing"))
        assertTrue(codes.contains("ready_ocr_missing"))
        assertTrue(RpaReadiness.hasErrors(issues))
    }

    @Test
    fun `a11y mode fails when a11y is unavailable`() {
        val issues = RpaReadiness.check(
            script(RpaMode.A11Y),
            readyCaps.copy(a11yReady = false),
        ) { _, _ -> true }

        assertTrue(issues.any { it.code == "ready_a11y_missing" })
        assertTrue(RpaReadiness.hasErrors(issues))
    }

    @Test
    fun `dual mode degrades to warning when only one line is ready`() {
        val issues = RpaReadiness.check(
            script(RpaMode.DUAL),
            readyCaps.copy(visionReady = false),
        ) { _, _ -> true }

        assertTrue(issues.any { it.code == "ready_dual_vision_missing" })
        assertFalse(RpaReadiness.hasErrors(issues))
    }
}
