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

package com.verlintas.rpa.engine

import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaCompareOp
import com.verlintas.rpa.model.RpaCustomAction
import com.verlintas.rpa.model.RpaCustomParam
import com.verlintas.rpa.model.RpaDirection
import com.verlintas.rpa.model.RpaExpect
import com.verlintas.rpa.model.RpaFailurePolicy
import com.verlintas.rpa.model.RpaFlowKind
import com.verlintas.rpa.model.RpaGlobalSetting
import com.verlintas.rpa.model.RpaHit
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaParams
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaRunResult
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaStepCondition
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaVarAssign
import com.verlintas.rpa.model.RpaVarSource
import com.verlintas.rpa.model.RpaVarType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎行为单测（v4 §六）：handler 注册表完备性、P2 占位显式报错、
 * 跳转（相对锚定 / 上限 / 悬空）、条件跳过与求值异常降级、
 * 变量四来源、repeat 与 retries 交互、整脚本循环、复合动作深度、
 * 全局默认等待回退、stopOnFailure 处置。
 */
class RpaRunnerBehaviorTest {

    // ------------------------------ handler 注册表 ------------------------------

    @Test
    fun p2PlaceholderActionsFailExplicitly() {
        listOf(
            RpaAction.TAP_COLOR to "unsupported_action:TAP_COLOR",
            RpaAction.GESTURE_MULTI to "unsupported_action:GESTURE_MULTI",
        ).forEach { (action, expected) ->
            val events = mutableListOf<RpaRunEvent>()
            val result = run(scriptOf(listOf(step("p", action))), FakeDevice(), events)
            assertFalse("action=$action", result.ok)
            assertEquals("action=$action", expected, result.detail)
            val finished = events.filterIsInstance<RpaRunEvent.StepFinished>().single()
            assertEquals(expected, finished.detail)
        }
    }

    @Test
    fun everyNonP2ActionHasAHandler() {
        val p2 = setOf(RpaAction.TAP_COLOR, RpaAction.GESTURE_MULTI)
        RpaAction.values().filterNot { it in p2 }.forEach { action ->
            val custom = if (action == RpaAction.COMPOSITE) {
                listOf(RpaCustomAction(id = "ca", steps = listOf(step("c", RpaAction.COMMENT))))
            } else {
                emptyList()
            }
            val events = mutableListOf<RpaRunEvent>()
            val result = run(scriptOf(listOf(minimalStep(action)), customActions = custom), FakeDevice(), events)
            val details = events.filterIsInstance<RpaRunEvent.StepFinished>().map { it.detail } +
                result.detail
            assertTrue(
                "action=$action details=$details",
                details.none { it.startsWith("unsupported_action") },
            )
        }
    }

    @Test
    fun tapHoldMsIsPassedThrough() {
        val device = FakeDevice()
        run(
            scriptOf(
                listOf(
                    step(
                        "t",
                        RpaAction.TAP,
                        target = hitTarget(),
                        params = RpaParams(holdMs = 250),
                    ),
                ),
            ),
            device,
        )
        assertEquals(listOf(250), device.holds)
    }

    // ------------------------------ 跳转（裁定⑤） ------------------------------

    @Test
    fun gotoJumpsToAnchorAndSkipsInBetween() {
        val events = mutableListOf<RpaRunEvent>()
        val device = FakeDevice()
        val result = run(
            scriptOf(
                listOf(
                    step("a", RpaAction.COMMENT),
                    step("b", RpaAction.CONTROL_FLOW, RpaParams(flowKind = RpaFlowKind.GOTO, gotoStepId = "d")),
                    step("c", RpaAction.TAP, target = hitTarget()),
                    step("d", RpaAction.COMMENT),
                ),
            ),
            device,
            events,
        )
        assertTrue(result.ok)
        assertTrue(device.taps.isEmpty())
        assertEquals(
            listOf(0, 1, 3),
            events.filterIsInstance<RpaRunEvent.StepStarted>().map { it.index },
        )
        assertTrue(events.any { it is RpaRunEvent.Log && it.message.startsWith("jump step=b -> d") })
    }

    @Test
    fun stopControlFlowEndsScriptSuccessfully() {
        val device = FakeDevice()
        val result = run(
            scriptOf(
                listOf(
                    step("a", RpaAction.CONTROL_FLOW, RpaParams(flowKind = RpaFlowKind.STOP)),
                    step("b", RpaAction.TAP, target = hitTarget()),
                ),
            ),
            device,
        )
        assertTrue(result.ok)
        assertEquals("script_stopped", result.detail)
        assertTrue(device.taps.isEmpty())
    }

    @Test
    fun unconditionalSelfJumpHitsJumpLimit() {
        val result = run(
            scriptOf(
                listOf(
                    step("a", RpaAction.CONTROL_FLOW, RpaParams(flowKind = RpaFlowKind.GOTO, gotoStepId = "a")),
                ),
            ),
            FakeDevice(),
        )
        assertFalse(result.ok)
        assertEquals("jump_limit_exceeded", result.detail)
        assertEquals(0, result.failedStepIndex)
    }

    @Test
    fun runtimeJumpTargetMissingIsReported() {
        val result = run(
            scriptOf(
                listOf(
                    step("a", RpaAction.CONTROL_FLOW, RpaParams(flowKind = RpaFlowKind.GOTO, gotoStepId = "ghost")),
                ),
            ),
            FakeDevice(),
        )
        assertFalse(result.ok)
        assertTrue(result.detail, result.detail.startsWith("jump_target_missing"))
    }

    // ------------------------------ 条件（§六-3） ------------------------------

    @Test
    fun conditionNotMetSkipsStepWithoutFailure() {
        val events = mutableListOf<RpaRunEvent>()
        val device = FakeDevice()
        val result = run(
            scriptOf(
                steps = listOf(
                    step(
                        "a",
                        RpaAction.INPUT_TEXT,
                        RpaParams(text = "ran"),
                        runCondition = RpaStepCondition.Variable(
                            lhs = "\${counter}",
                            op = RpaCompareOp.EQ,
                            rhs = "9",
                        ),
                    ),
                    step("b", RpaAction.COMMENT),
                ),
                variables = mapOf("counter" to "5"),
            ),
            device,
            events,
        )
        assertTrue(result.ok)
        assertTrue(device.inputs.isEmpty())
        assertTrue(
            events.any {
                it is RpaRunEvent.Log && it.message.contains("step_skipped step=a")
            },
        )
    }

    @Test
    fun satisfiedConditionRunsStep() {
        val device = FakeDevice()
        val result = run(
            scriptOf(
                steps = listOf(
                    step(
                        "a",
                        RpaAction.INPUT_TEXT,
                        RpaParams(text = "ran"),
                        runCondition = RpaStepCondition.Variable(
                            lhs = "\${counter}",
                            op = RpaCompareOp.EQ,
                            rhs = "5",
                        ),
                    ),
                ),
                variables = mapOf("counter" to "5"),
            ),
            device,
        )
        assertTrue(result.ok)
        assertEquals(listOf("ran"), device.inputs)
    }

    @Test
    fun conditionEvalErrorDegradesToSkip() {
        val events = mutableListOf<RpaRunEvent>()
        val device = FakeDevice()
        val result = run(
            scriptOf(
                listOf(
                    step(
                        "a",
                        RpaAction.INPUT_TEXT,
                        RpaParams(text = "ran"),
                        runCondition = RpaStepCondition.Expr(expression = "var(\"x\") >"),
                    ),
                    step("b", RpaAction.COMMENT),
                ),
            ),
            device,
            events,
        )
        // 求值异常按「不满足」降级：跳过 + 记原因，脚本继续，不算失败。
        assertTrue(result.ok)
        assertTrue(device.inputs.isEmpty())
        assertTrue(events.any { it is RpaRunEvent.Log && it.message.contains("condition_error") })
    }

    // ------------------------------ 变量（图8） ------------------------------

    @Test
    fun setVariableResolvesAllFourSources() {
        val device = FakeDevice()
        val result = run(
            scriptOf(
                steps = listOf(
                    step(
                        "s1",
                        RpaAction.SET_VARIABLE,
                        RpaParams(
                            assignments = listOf(
                                RpaVarAssign(
                                    variableName = "greet",
                                    type = RpaVarType.STRING,
                                    source = RpaVarSource.LITERAL,
                                    value = "你好\${name}",
                                ),
                                RpaVarAssign(
                                    variableName = "counter",
                                    type = RpaVarType.NUMBER,
                                    source = RpaVarSource.INCREMENT,
                                    value = "3",
                                ),
                                RpaVarAssign(
                                    variableName = "mirror",
                                    type = RpaVarType.STRING,
                                    source = RpaVarSource.VARIABLE_REF,
                                    value = "greet",
                                ),
                            ),
                        ),
                    ),
                    step(
                        "s2",
                        RpaAction.SET_VARIABLE,
                        RpaParams(
                            assignments = listOf(
                                RpaVarAssign(
                                    variableName = "seen",
                                    type = RpaVarType.STRING,
                                    source = RpaVarSource.UI_READ,
                                ),
                            ),
                        ),
                    ),
                    gatedTap("g1", "greet", "你好张三"),
                    gatedTap("g2", "counter", "8"),
                    gatedTap("g3", "mirror", "你好张三"),
                    gatedTap("g4", "seen", "你好"),
                ),
                variables = mapOf("name" to "张三", "counter" to "5"),
            ),
            device,
        )
        assertTrue(result.detail, result.ok)
        assertEquals(4, device.taps.size)
    }

    // ------------------------------ repeat / 重试 / 循环 ------------------------------

    @Test
    fun stepRepeatRunsActionNTimes() {
        val events = mutableListOf<RpaRunEvent>()
        val device = FakeDevice()
        val result = run(
            scriptOf(listOf(step("a", RpaAction.TAP, target = hitTarget(), repeat = 3))),
            device,
            events,
        )
        assertTrue(result.ok)
        assertEquals(3, device.taps.size)
        assertTrue(events.any { it is RpaRunEvent.Log && it.message.contains("repeat step=a") })
    }

    @Test
    fun retriesRepeatWithinOneIteration() {
        val device = FakeDevice(failFirstTaps = 1)
        val result = run(
            scriptOf(
                listOf(
                    step("a", RpaAction.TAP, target = hitTarget(), repeat = 2, retries = 1),
                ),
            ),
            device,
        )
        // 迭代1：失败1次 + 重试1次成功；迭代2：一次成功 = 3 次点击。
        assertTrue(result.detail, result.ok)
        assertEquals(3, device.taps.size)
    }

    @Test
    fun exhaustedRetriesFailTheStep() {
        val device = FakeDevice(failFirstTaps = Int.MAX_VALUE)
        val result = run(
            scriptOf(listOf(step("a", RpaAction.TAP, target = hitTarget(), retries = 2))),
            device,
        )
        assertFalse(result.ok)
        assertEquals(3, device.taps.size)
    }

    @Test
    fun scriptRepeatLoopsWholeScript() {
        val events = mutableListOf<RpaRunEvent>()
        val result = run(
            scriptOf(
                listOf(step("a", RpaAction.COMMENT)),
                global = RpaGlobalSetting(repeat = 2),
            ),
            FakeDevice(),
            events,
        )
        assertTrue(result.ok)
        assertEquals(2, events.count { it is RpaRunEvent.StepStarted })
        assertTrue(events.any { it is RpaRunEvent.Log && it.message.contains("script_repeat") })
    }

    // ------------------------------ 复合动作（§五） ------------------------------

    @Test
    fun compositeRunsCustomStepsWithArgs() {
        val device = FakeDevice()
        val result = run(
            scriptOf(
                steps = listOf(
                    step(
                        "top",
                        RpaAction.COMPOSITE,
                        RpaParams(customActionId = "ca", customArgValues = mapOf("who" to "张三")),
                    ),
                ),
                customActions = listOf(
                    RpaCustomAction(
                        id = "ca",
                        name = "打招呼",
                        params = listOf(RpaCustomParam(name = "who", default = "朋友")),
                        steps = listOf(step("c1", RpaAction.INPUT_TEXT, RpaParams(text = "\${who}"))),
                    ),
                ),
            ),
            device,
        )
        assertTrue(result.detail, result.ok)
        assertEquals(listOf("张三"), device.inputs)
    }

    @Test
    fun compositeNestingBeyondDepthFails() {
        val chain = listOf(
            RpaCustomAction(id = "ca1", steps = listOf(step("n1", RpaAction.COMPOSITE, RpaParams(customActionId = "ca2")))),
            RpaCustomAction(id = "ca2", steps = listOf(step("n2", RpaAction.COMPOSITE, RpaParams(customActionId = "ca3")))),
            RpaCustomAction(id = "ca3", steps = listOf(step("n3", RpaAction.COMPOSITE, RpaParams(customActionId = "ca4")))),
            RpaCustomAction(id = "ca4", steps = listOf(step("n4", RpaAction.COMMENT))),
        )
        val events = mutableListOf<RpaRunEvent>()
        val result = run(
            scriptOf(
                steps = listOf(step("top", RpaAction.COMPOSITE, RpaParams(customActionId = "ca1"))),
                customActions = chain,
            ),
            FakeDevice(),
            events,
        )
        assertFalse(result.ok)
        val hitDepthLimit = result.detail.contains("composite_depth_exceeded") ||
            events.filterIsInstance<RpaRunEvent.StepFinished>()
                .any { it.detail.contains("composite_depth_exceeded") }
        assertTrue(hitDepthLimit)
    }

    // ------------------------------ 全局设置（图12） ------------------------------

    @Test
    fun globalDefaultWaitFillsInWhenStepHasNone() {
        val start = System.currentTimeMillis()
        val result = run(
            scriptOf(
                steps = listOf(
                    step("a", RpaAction.COMMENT),
                    step("b", RpaAction.COMMENT),
                ),
                global = RpaGlobalSetting(defaultWaitSec = 0.2),
            ),
            FakeDevice(),
        )
        val elapsed = System.currentTimeMillis() - start
        assertTrue(result.ok)
        assertTrue("elapsed=${elapsed}ms", elapsed >= 350)
    }

    @Test
    fun stopOnFailureHonorsGlobalSwitchAndStepPolicy() {
        // A：默认（全局即停 + 步骤 STOP）→ 后续步骤不执行。
        val strict = FakeDevice(failFirstTaps = Int.MAX_VALUE)
        val strictResult = run(
            scriptOf(
                listOf(
                    step("f", RpaAction.TAP, target = hitTarget()),
                    step("after", RpaAction.INPUT_TEXT, RpaParams(text = "after")),
                ),
            ),
            strict,
        )
        assertFalse(strictResult.ok)
        assertTrue(strict.inputs.isEmpty())

        // B：步骤级 CONTINUE 显式豁免 → 继续跑。
        val continued = FakeDevice(failFirstTaps = Int.MAX_VALUE)
        val continuedResult = run(
            scriptOf(
                listOf(
                    step("f", RpaAction.TAP, target = hitTarget(), onFailure = RpaFailurePolicy.CONTINUE),
                    step("after", RpaAction.INPUT_TEXT, RpaParams(text = "after")),
                ),
            ),
            continued,
        )
        assertTrue(continuedResult.ok)
        assertEquals(listOf("after"), continued.inputs)

        // C：全局 stopOnFailure=false → 即使步骤 STOP 也继续。
        val relaxed = FakeDevice(failFirstTaps = Int.MAX_VALUE)
        val relaxedResult = run(
            scriptOf(
                steps = listOf(
                    step("f", RpaAction.TAP, target = hitTarget()),
                    step("after", RpaAction.INPUT_TEXT, RpaParams(text = "after")),
                ),
                global = RpaGlobalSetting(stopOnFailure = false),
            ),
            relaxed,
        )
        assertTrue(relaxedResult.ok)
        assertEquals(listOf("after"), relaxed.inputs)
    }

    // ------------------------------ 打开链接（新动作） ------------------------------

    @Test
    fun openLinkPassesUrlAndRejectsBadSchemeAtRuntime() {
        val device = FakeDevice()
        val events = mutableListOf<RpaRunEvent>()
        val result = run(
            scriptOf(
                listOf(
                    step("a", RpaAction.OPEN_LINK, RpaParams(url = "https://example.com")),
                    step(
                        "b",
                        RpaAction.OPEN_LINK,
                        RpaParams(url = "ftp://example.com"),
                        onFailure = RpaFailurePolicy.CONTINUE,
                    ),
                ),
            ),
            device,
            events,
        )
        assertEquals(listOf("https://example.com"), device.links)
        val finished = events.filterIsInstance<RpaRunEvent.StepFinished>()
        assertTrue(finished[0].ok)
        assertFalse(finished[1].ok)
    }

    // ------------------------------ 夹具 ------------------------------

    private fun run(
        script: RpaScript,
        device: RpaDevice,
        events: MutableList<RpaRunEvent> = mutableListOf(),
    ): RpaRunResult = runBlocking {
        RpaRunner(device = device).run(script) { event -> events += event }
    }

    private fun scriptOf(
        steps: List<RpaStep>,
        customActions: List<RpaCustomAction> = emptyList(),
        variables: Map<String, String> = emptyMap(),
        global: RpaGlobalSetting = RpaGlobalSetting(),
    ) = RpaScript(
        id = "script",
        name = "引擎单测",
        mode = RpaMode.A11Y,
        variables = variables,
        steps = steps,
        customActions = customActions,
        global = global,
    )

    private fun step(
        id: String,
        action: RpaAction,
        params: RpaParams = RpaParams(),
        target: RpaTarget? = null,
        expect: RpaExpect? = null,
        runCondition: RpaStepCondition = RpaStepCondition.None,
        repeat: Int = 1,
        retries: Int = 0,
        onFailure: RpaFailurePolicy = RpaFailurePolicy.STOP,
    ) = RpaStep(
        id = id,
        action = action,
        params = params,
        target = target,
        expect = expect,
        runCondition = runCondition,
        repeat = repeat,
        retries = retries,
        onFailure = onFailure,
    )

    private fun gatedTap(id: String, variable: String, expected: String): RpaStep = step(
        id,
        RpaAction.TAP,
        target = hitTarget(),
        runCondition = RpaStepCondition.Variable(
            lhs = "\${$variable}",
            op = RpaCompareOp.EQ,
            rhs = expected,
        ),
    )

    private fun hitTarget() = RpaTarget(
        a11y = RpaA11ySpec(selectors = listOf(RpaSelector(text = "按钮"))),
    )

    /** 每个动作的最小可用参数：只验证 handler 存在（不报 unsupported_action）。 */
    private fun minimalStep(action: RpaAction): RpaStep = when (action) {
        RpaAction.LAUNCH_APP ->
            step("m", action, RpaParams(packageName = "com.example.app"))
        RpaAction.OPEN_CHAT ->
            step("m", action, RpaParams(imPackage = "com.tencent.mm", contactName = "张三"))
        RpaAction.WAIT ->
            step("m", action, RpaParams(waitMs = 1))
        RpaAction.WAIT_FOR_TARGET,
        RpaAction.TAP,
        RpaAction.LONG_PRESS,
        -> step("m", action, target = hitTarget())
        RpaAction.INPUT_TEXT ->
            step("m", action, RpaParams(text = "hi"))
        RpaAction.PRESS_KEY ->
            step("m", action, RpaParams(key = "back"))
        RpaAction.SWIPE ->
            step(
                "m",
                action,
                RpaParams(direction = RpaDirection.UP, distanceRatio = 0.5f, durationMs = 200),
            )
        RpaAction.ASSERT_TEXT ->
            step("m", action, expect = RpaExpect(text = "你好"))
        RpaAction.OPEN_LINK ->
            step("m", action, RpaParams(url = "https://example.com"))
        RpaAction.SET_VARIABLE ->
            step(
                "m",
                action,
                RpaParams(
                    assignments = listOf(RpaVarAssign(variableName = "v", value = "1")),
                ),
            )
        RpaAction.CONTROL_FLOW ->
            step("m", action, RpaParams(flowKind = RpaFlowKind.STOP))
        RpaAction.COMPOSITE ->
            step("m", action, RpaParams(customActionId = "ca"))
        else -> step("m", action)
    }

    private class FakeDevice(
        private val hit: RpaHit? = RpaHit(10, 20, 1.0, "fake", RpaLine.A11Y),
        private val texts: List<String> = listOf("你好"),
        /** 前 N 次点击故意失败（0 = 全成功；Int.MAX_VALUE = 全失败）。 */
        private val failFirstTaps: Int = 0,
    ) : RpaDevice {

        override val capabilities = RpaCapabilities(
            a11yReady = true,
            shizukuReady = true,
            visionReady = true,
            ocrReady = true,
            projectionReady = true,
            screenshotReady = true,
        )

        val taps = mutableListOf<Pair<Int, Int>>()
        val holds = mutableListOf<Int>()
        val inputs = mutableListOf<String>()
        val links = mutableListOf<String>()

        private var tapFailBudget = failFirstTaps

        override suspend fun launchApp(
            packageName: String,
            line: RpaLine,
            restart: Boolean,
            closeOnly: Boolean,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun openChat(
            imPackage: String,
            contactName: String,
            line: RpaLine,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun tap(x: Int, y: Int, line: RpaLine, holdMs: Int): Result<Unit> {
            taps += x to y
            holds += holdMs
            return if (tapFailBudget > 0) {
                tapFailBudget--
                Result.failure(IllegalStateException("tap_failed"))
            } else {
                Result.success(Unit)
            }
        }

        override suspend fun longPress(x: Int, y: Int, line: RpaLine): Result<Unit> =
            Result.success(Unit)

        override suspend fun swipe(
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            durationMs: Int,
            line: RpaLine,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun inputText(
            text: String,
            line: RpaLine,
            clearFirst: Boolean,
        ): Result<Unit> {
            inputs += text
            return Result.success(Unit)
        }

        override suspend fun pressKey(key: String, line: RpaLine): Result<Unit> =
            Result.success(Unit)

        override suspend fun openLink(url: String, line: RpaLine): Result<Unit> {
            links += url
            return Result.success(Unit)
        }

        override suspend fun locate(
            scriptId: String,
            target: RpaTarget,
            line: RpaLine,
        ): RpaHit? = hit

        override suspend fun textLines(line: RpaLine): List<String> = texts

        override suspend fun foregroundPackage(line: RpaLine): String? = "com.example.app"

        override fun screenSize(): Pair<Int, Int> = 1080 to 1920
    }
}
