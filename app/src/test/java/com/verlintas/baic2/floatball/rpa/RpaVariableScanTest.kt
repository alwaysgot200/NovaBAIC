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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa

import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaParams
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaScriptFactory
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaValidator
import com.verlintas.rpa.model.referencedVariables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变量扫描必须用纯字符串实现：Android 的 ICU 正则不接受 `\{`，早期
 * `Regex("""\$\{...}""")` 在设备上打开编辑器首帧就抛 PatternSyntaxException
 * （2026-10-27 模拟器实测，桌面 JVM 单测却全绿，故补此回归）。
 */
class RpaVariableScanTest {

    @Test
    fun `extracts variable names without platform regex`() {
        val step = RpaStep(
            id = "step_input",
            action = RpaAction.INPUT_TEXT,
            params = RpaParams(
                text = "你好 \${contactName}，订单号 \${order.no}，批次 \${batch-01}",
            ),
        )

        assertEquals(
            setOf("contactName", "order.no", "batch-01"),
            step.referencedVariables(),
        )
    }

    @Test
    fun `ignores malformed or empty placeholders`() {
        val step = RpaStep(
            id = "step_input",
            action = RpaAction.INPUT_TEXT,
            params = RpaParams(
                text = "\${} \${bad name} \${unclosed \${ok_1} \$ {x}",
            ),
        )

        assertEquals(setOf("ok_1"), step.referencedVariables())
    }

    @Test
    fun `validator reports undefined placeholder instead of crashing`() {
        val step = RpaStep(
            id = "step_input",
            action = RpaAction.INPUT_TEXT,
            params = RpaParams(text = "\${contactName}"),
        )
        val script = RpaScript(
            id = "script_vars",
            name = "变量脚本",
            mode = RpaMode.A11Y,
            steps = listOf(step),
        )

        val issues = RpaValidator.validate(script)

        assertTrue(issues.any { it.code == "variable_undefined" && it.detail == "contactName" })
    }

    @Test
    fun `starter script validates cleanly`() {
        val script = RpaScriptFactory.starter(name = "空白脚本", mode = RpaMode.DUAL)

        val issues = RpaValidator.validate(script)

        assertTrue(issues.none { it.code == "variable_undefined" })
    }
}
