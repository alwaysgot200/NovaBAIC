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

import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaCompareOp
import com.verlintas.rpa.model.RpaConditionContext
import com.verlintas.rpa.model.RpaConditionDsl
import com.verlintas.rpa.model.RpaConditionException
import com.verlintas.rpa.model.RpaDirection
import com.verlintas.rpa.model.RpaExpect
import com.verlintas.rpa.model.RpaFailurePolicy
import com.verlintas.rpa.model.RpaFlowKind
import com.verlintas.rpa.model.RpaHit
import com.verlintas.rpa.model.RpaLimits
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaModePolicy
import com.verlintas.rpa.model.RpaReadiness
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaRunResult
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaStepCondition
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaTextMatch
import com.verlintas.rpa.model.RpaVarSource
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.model.resolve
import com.verlintas.rpa.model.resolveVariables
import kotlinx.coroutines.delay

/**
 * RPA 执行端口（app 侧实现，vendor 只依赖该接口，保持纯 Kotlin 可测试）。
 *
 * 线路语义由 [RpaRunner] 决定：A11Y 线只能走无障碍节点 / 手势 / SET_TEXT；
 * VISION 线只能走 Shizuku 注入 + 抽帧感知。实现方不得在同一方法里偷偷换线。
 */
interface RpaDevice {

    val capabilities: RpaCapabilities

    /**
     * 拉起（或关闭）App。`restart` = 重新打开（重启，图5）；`closeOnly` = 仅关闭应用（图5）。
     */
    suspend fun launchApp(
        packageName: String,
        line: RpaLine,
        restart: Boolean = false,
        closeOnly: Boolean = false,
    ): Result<Unit>

    suspend fun openChat(imPackage: String, contactName: String, line: RpaLine): Result<Unit>

    /** 点击；`holdMs` > 0 时按住该毫秒数再抬起（图6 按下时间）。 */
    suspend fun tap(x: Int, y: Int, line: RpaLine, holdMs: Int = 0): Result<Unit>

    suspend fun longPress(x: Int, y: Int, line: RpaLine): Result<Unit>

    suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        line: RpaLine,
    ): Result<Unit>

    suspend fun inputText(text: String, line: RpaLine, clearFirst: Boolean): Result<Unit>

    suspend fun pressKey(key: String, line: RpaLine): Result<Unit>

    /** 打开链接（OPEN_LINK；scheme 白名单由校验器把关）。 */
    suspend fun openLink(url: String, line: RpaLine): Result<Unit>

    /** 按线路定位；返回 null 表示未命中（不抛异常，方便轮询）。 */
    suspend fun locate(scriptId: String, target: RpaTarget, line: RpaLine): RpaHit?

    /** 当前屏幕的文本行（a11y 节点文本 / OCR 行），断言与「界面读取」用。 */
    suspend fun textLines(line: RpaLine): List<String>

    suspend fun foregroundPackage(line: RpaLine): String?

    fun screenSize(): Pair<Int, Int>?
}

/**
 * RPA 解释器（v4 §六）：
 *
 * - **handler 注册表（裁定⑧）**：按 [RpaAction] 显式分发，未注册（P2 占位）动作
 *   显式报 `unsupported_action`，禁止静默；新增动作 = 加一个 handler 分支，循环零改动。
 * - **程序计数器循环**：`while (pc in steps.indices)`；`CONTROL_FLOW` 改写 `pc`
 *   （stepId → 下标在进入序列时一次性解析成跳转表），受 `MAX_JUMPS` + 运行预算双保险。
 * - **条件求值**：不满足跳过该步并记日志（不算失败）；求值异常一律按「不满足」降级。
 * - **repeat**：总执行次数，每次执行都先过运行条件；**复合动作**递归展开（深度 ≤3）。
 * - **全局默认等待**：`delayAfterMs == 0` 时回退 `global.defaultWaitSec`；
 *   **stopOnFailure** 遇失败即停（步骤级 `onFailure=CONTINUE` 为显式豁免）。
 */
class RpaRunner(
    private val device: RpaDevice,
    private val templateExists: (scriptId: String, templateId: String) -> Boolean = { _, _ -> true },
    /** 单次运行总预算：防止 WAIT / 重试把任务无限拖长。 */
    private val totalTimeoutMs: Long = RpaLimits.DEFAULT_RUN_TIMEOUT_MS,
    /** 随机数注入点（公式条件 random()），便于单测确定性。 */
    private val randomValue: () -> Double = { kotlin.random.Random.nextDouble() },
) {

    suspend fun run(
        script: RpaScript,
        // 运行模式门（用户定案）：非空即锁线运行——初始线路必须是它，运行中禁止
        // 换线 fallback，失败显式报因（§4.5 严禁静默换线）；null = 原自动选线行为。
        forceLine: RpaLine? = null,
        onEvent: suspend (RpaRunEvent) -> Unit = {},
    ): RpaRunResult {
        val caps = device.capabilities
        val readiness = RpaReadiness.check(script, caps, templateExists)
        if (RpaReadiness.hasErrors(readiness)) {
            onEvent(RpaRunEvent.ScriptStarted(script, null))
            readiness.filter { it.severity == com.verlintas.rpa.model.RpaIssueSeverity.ERROR }
                .forEach { issue ->
                    onEvent(
                        RpaRunEvent.Log(
                            "not_ready code=${issue.code} step=${issue.stepId ?: "-"}",
                        ),
                    )
                }
            onEvent(RpaRunEvent.ScriptFinished(false, null, "readiness_failed"))
            return RpaRunResult(false, null, "readiness_failed")
        }
        val session = Session(script, caps, forceLine, onEvent)
        return session.run()
    }

    private inner class Session(
        private val script: RpaScript,
        private val caps: RpaCapabilities,
        private val forcedLine: RpaLine?,
        private val emit: suspend (RpaRunEvent) -> Unit,
    ) {

        private var line: RpaLine? = initialLine()

        private val deadline: Long = System.currentTimeMillis() +
            totalTimeoutMs.coerceIn(1L, RpaLimits.MAX_RUN_TIMEOUT_MS)

        /** 运行时变量表（脚本级 variables 为初始值，SET_VARIABLE 写这里，§四.4）。 */
        private val runtimeVars: MutableMap<String, String> = script.variables.toMutableMap()

        /** 上一步执行结果（PrevOk / PrevFail 条件与公式 prevOk() 共用）。 */
        private var prevOk: Boolean? = null

        /** 已发生的跳转次数（MAX_JUMPS 双保险之一）。 */
        private var jumps = 0

        suspend fun run(): RpaRunResult {
            emit(RpaRunEvent.ScriptStarted(script, line))
            val loops = RpaLimits.scriptRepeat(script.global.repeat)
            for (loop in 1..loops) {
                if (loop > 1) {
                    emit(RpaRunEvent.Log("script_repeat loop=$loop/$loops"))
                }
                val outcome = runSequence(script.steps, depth = 0)
                if (outcome.detail == "run_timeout") {
                    emit(RpaRunEvent.Log("run_timeout budget_ms=$totalTimeoutMs"))
                    emit(RpaRunEvent.ScriptFinished(false, outcome.failedIndex, "run_timeout"))
                    return RpaRunResult(false, outcome.failedIndex, "run_timeout")
                }
                if (!outcome.ok) {
                    emit(
                        RpaRunEvent.ScriptFinished(
                            false,
                            outcome.failedIndex,
                            outcome.detail,
                        ),
                    )
                    return RpaRunResult(false, outcome.failedIndex, outcome.detail)
                }
                if (outcome.stopped) {
                    emit(RpaRunEvent.Log("script_stopped by CONTROL_FLOW"))
                    emit(RpaRunEvent.ScriptFinished(true, null, "script_stopped"))
                    return RpaRunResult(true, null, "script_stopped")
                }
                if (remainingMs() <= 0) {
                    emit(RpaRunEvent.Log("run_timeout budget_ms=$totalTimeoutMs"))
                    emit(RpaRunEvent.ScriptFinished(false, null, "run_timeout"))
                    return RpaRunResult(false, null, "run_timeout")
                }
            }
            emit(RpaRunEvent.ScriptFinished(true))
            return RpaRunResult(true)
        }

        /**
         * 按 PC 循环执行一段步骤（顶层或复合动作内部）。
         * 跳转表按 [steps] 作用域解析——跳转不得跨复合动作边界（校验器已拦截）。
         */
        private suspend fun runSequence(steps: List<RpaStep>, depth: Int): SeqOutcome {
            val indexById = steps.withIndex().associate { (index, step) -> step.id to index }
            var pc = 0
            while (pc in steps.indices) {
                if (remainingMs() <= 0) {
                    return SeqOutcome(false, failedIndex = pc, detail = "run_timeout")
                }
                val step = steps[pc]
                if (!step.enabled) {
                    pc++
                    continue
                }
                // 运行条件（repeat 的后续迭代同样先过条件，§六-4）。
                if (!conditionPassed(step)) {
                    emit(RpaRunEvent.Log("step_skipped step=${step.id} reason=condition_not_met"))
                    pc++
                    continue
                }
                if (!pause(RpaLimits.delayMs(step.delayBeforeMs))) {
                    return SeqOutcome(false, failedIndex = pc, detail = "run_timeout")
                }
                emit(RpaRunEvent.StepStarted(pc, step, line))
                val repeats = RpaLimits.repeat(step.repeat)
                var outcome = StepOutcome(false, "not_executed")
                for (iteration in 1..repeats) {
                    outcome = executeWithRetry(pc, step, depth)
                    if (!outcome.ok) break
                    if (iteration < repeats) {
                        if (!conditionPassed(step)) {
                            emit(
                                RpaRunEvent.Log(
                                    "repeat_stopped step=${step.id} iteration=$iteration " +
                                        "reason=condition_not_met",
                                ),
                            )
                            break
                        }
                        emit(
                            RpaRunEvent.Log(
                                "repeat step=${step.id} iteration=${iteration + 1}/$repeats",
                            ),
                        )
                    }
                }
                emit(
                    RpaRunEvent.StepFinished(
                        index = pc,
                        step = step,
                        ok = outcome.ok,
                        detail = outcome.detail,
                    ),
                )
                prevOk = outcome.ok
                // 总预算耗尽优先于步骤失败策略。
                if (!outcome.ok && outcome.detail == "run_timeout") {
                    return SeqOutcome(false, failedIndex = pc, detail = "run_timeout")
                }
                if (!outcome.ok && stopOnFailure(step)) {
                    return SeqOutcome(false, failedIndex = pc, detail = outcome.detail)
                }
                if (!outcome.ok) {
                    emit(RpaRunEvent.Log("continue_after_failure step=${step.id}"))
                }
                // CONTROL_FLOW：STOP 收口 / GOTO 改写 PC。
                if (outcome.ok && outcome.stop) {
                    return SeqOutcome(ok = true, stopped = true)
                }
                val jump = outcome.jumpTo
                if (jump != null) {
                    jumps++
                    if (jumps > RpaLimits.MAX_JUMPS) {
                        return SeqOutcome(false, failedIndex = pc, detail = "jump_limit_exceeded")
                    }
                    val target = indexById[jump]
                    if (target == null) {
                        return SeqOutcome(
                            false,
                            failedIndex = pc,
                            detail = "jump_target_missing:$jump",
                        )
                    }
                    emit(RpaRunEvent.Log("jump step=${step.id} -> $jump (pc=$target)"))
                    pc = target
                } else {
                    pc++
                }
                // 执行后等待只在最终成功后生效；delayAfterMs==0 回退全局默认等待。
                if (outcome.ok) {
                    val wait = when {
                        step.delayAfterMs > 0 -> RpaLimits.delayMs(step.delayAfterMs)
                        else -> script.global.defaultWaitSec
                            ?.let { (it * 1000).toLong().coerceIn(0, RpaLimits.MAX_WAIT_MS) }
                            ?: 0L
                    }
                    if (wait > 0 && !pause(wait)) {
                        return SeqOutcome(false, failedIndex = pc, detail = "run_timeout")
                    }
                }
            }
            return SeqOutcome(ok = true)
        }

        // ---------------------------- 条件 ----------------------------

        /** 求值异常一律按「不满足」处理并记原因日志（§六-3，绝不崩引擎、绝不静默）。 */
        private suspend fun conditionPassed(step: RpaStep): Boolean {
            val condition = step.runCondition
            if (condition == RpaStepCondition.None) return true
            return try {
                when (condition) {
                    is RpaStepCondition.Variable -> compareVariables(condition)
                    is RpaStepCondition.ImageVisible -> {
                        val active = line ?: return falseWithLog(step, "no_active_line")
                        val target = RpaTarget(
                            vision = RpaVisionSpec(
                                primary = RpaVisionLocator(
                                    kind = RpaVisionKind.TEMPLATE,
                                    templateId = condition.templateId,
                                ),
                            ),
                        )
                        device.locate(script.id, target, active) != null
                    }
                    is RpaStepCondition.TextVisible -> {
                        val active = line ?: return falseWithLog(step, "no_active_line")
                        device.textLines(active).any { text ->
                            RpaTextMatch.matches(
                                text,
                                condition.text,
                                condition.match,
                                ignoreWhitespace = true,
                            )
                        }
                    }
                    RpaStepCondition.PrevOk -> prevOk == true
                    RpaStepCondition.PrevFail -> prevOk == false
                    is RpaStepCondition.Expr -> RpaConditionDsl.evaluate(
                        condition.expression,
                        conditionContext,
                    )
                    RpaStepCondition.None -> true
                }
            } catch (failure: RpaConditionException) {
                falseWithLog(step, "condition_error ${failure.message}")
            } catch (failure: Throwable) {
                falseWithLog(step, "condition_error ${failure.message ?: failure::class.simpleName}")
            }
        }

        private suspend fun falseWithLog(step: RpaStep, detail: String): Boolean {
            emit(RpaRunEvent.Log("condition_not_met step=${step.id} detail=$detail"))
            return false
        }

        private fun compareVariables(condition: RpaStepCondition.Variable): Boolean {
            val lhs = resolve(condition.lhs) ?: ""
            val rhs = resolve(condition.rhs) ?: ""
            val ln = lhs.trim().toDoubleOrNull()
            val rn = rhs.trim().toDoubleOrNull()
            return if (ln != null && rn != null) {
                when (condition.op) {
                    RpaCompareOp.EQ -> ln == rn
                    RpaCompareOp.NE -> ln != rn
                    RpaCompareOp.GT -> ln > rn
                    RpaCompareOp.GE -> ln >= rn
                    RpaCompareOp.LT -> ln < rn
                    RpaCompareOp.LE -> ln <= rn
                }
            } else {
                when (condition.op) {
                    RpaCompareOp.EQ -> lhs == rhs
                    RpaCompareOp.NE -> lhs != rhs
                    // 排序比较作用于非数值字符串：按「条件求值异常」口径降级为不满足。
                    else -> throw RpaConditionException("compare_needs_number ${condition.op}")
                }
            }
        }

        private val conditionContext = object : RpaConditionContext {
            override fun variable(name: String): String? = runtimeVars[name]

            override suspend fun hasImage(templateId: String): Boolean {
                val active = line ?: return false
                val target = RpaTarget(
                    vision = RpaVisionSpec(
                        primary = RpaVisionLocator(
                            kind = RpaVisionKind.TEMPLATE,
                            templateId = templateId,
                        ),
                    ),
                )
                return device.locate(script.id, target, active) != null
            }

            override suspend fun hasText(text: String): Boolean {
                val active = line ?: return false
                return device.textLines(active).any {
                    RpaTextMatch.matches(it, text, RpaMatchMode.CONTAINS, ignoreWhitespace = true)
                }
            }

            override fun prevOk(): Boolean = this@Session.prevOk == true

            override fun random(): Double = randomValue()

            override fun now(): Long = System.currentTimeMillis()
        }

        // ---------------------------- 执行 ----------------------------

        private suspend fun executeWithRetry(index: Int, step: RpaStep, depth: Int): StepOutcome {
            val attempts = RpaLimits.retries(step.retries) + 1
            var last = StepOutcome(false, "not_executed")
            for (attempt in 1..attempts) {
                last = executeOnce(index, step, depth)
                if (last.ok) return last
                if (remainingMs() <= 0) return StepOutcome(false, "run_timeout")
                if (attempt < attempts) {
                    emit(
                        RpaRunEvent.Log(
                            "retry step=${step.id} attempt=${attempt + 1} reason=${last.detail}",
                        ),
                    )
                    if (!pause(RETRY_DELAY_MS)) return StepOutcome(false, "run_timeout")
                }
            }
            return last
        }

        /** handler 注册表（裁定⑧）：when 显式分发；P2 占位动作显式报 unsupported_action。 */
        private suspend fun executeOnce(index: Int, step: RpaStep, depth: Int): StepOutcome {
            if (step.action in UNSUPPORTED_ACTIONS) {
                return StepOutcome(false, "unsupported_action:${step.action.name}")
            }
            guardForeground(step)?.let { return StepOutcome(false, it) }
            return when (step.action) {
                RpaAction.LAUNCH_APP -> {
                    val pkg = resolve(step.params.packageName)
                    if (pkg.isNullOrBlank()) {
                        StepOutcome(false, "param_package_missing")
                    } else {
                        val active = line ?: return StepOutcome(false, "no_active_line")
                        device.launchApp(
                            pkg,
                            active,
                            restart = step.params.restart,
                            closeOnly = step.params.closeOnly,
                        ).outcome("launch_app $pkg line=$active")
                    }
                }
                RpaAction.OPEN_CHAT -> openChat(index, step)
                RpaAction.WAIT -> {
                    val wait = RpaLimits.waitMs(step.params.waitMs ?: 0)
                    when {
                        wait <= 0 -> StepOutcome(false, "param_wait_missing")
                        !pause(wait) -> StepOutcome(false, "run_timeout")
                        else -> StepOutcome(true, "waited ${wait}ms")
                    }
                }
                RpaAction.WAIT_FOR_TARGET -> waitForTarget(index, step)
                RpaAction.TAP -> tap(index, step, long = false)
                RpaAction.LONG_PRESS -> tap(index, step, long = true)
                RpaAction.INPUT_TEXT -> inputText(index, step)
                RpaAction.PRESS_KEY -> pressKey(index, step)
                RpaAction.SWIPE -> gesture(index, step, step.params.direction ?: RpaDirection.UP)
                RpaAction.SCROLL -> gesture(index, step, step.params.direction ?: RpaDirection.UP)
                RpaAction.ASSERT_TEXT -> assertText(index, step)
                RpaAction.COMMENT -> StepOutcome(true, step.note)
                RpaAction.OPEN_LINK -> openLink(index, step)
                RpaAction.SET_VARIABLE -> setVariable(index, step)
                RpaAction.CONTROL_FLOW -> controlFlow(index, step)
                RpaAction.COMPOSITE -> composite(step, depth)
                RpaAction.TAP_COLOR,
                RpaAction.GESTURE_MULTI,
                -> StepOutcome(false, "unsupported_action:${step.action.name}")
            }
        }

        // ---------------------------- 动作实现 ----------------------------

        private suspend fun openChat(index: Int, step: RpaStep): StepOutcome {
            val im = resolve(step.params.imPackage)
            val contact = resolve(step.params.contactName)
            if (im.isNullOrBlank() || contact.isNullOrBlank()) {
                return StepOutcome(false, "param_open_chat_missing")
            }
            return onLine(index, step, "open_chat_failed") { active ->
                device.openChat(im, contact, active)
            }
        }

        private suspend fun tap(index: Int, step: RpaStep, long: Boolean): StepOutcome {
            val hit = locateWithFallback(index, step)
                ?: return StepOutcome(false, "target_not_found")
            val active = line ?: return StepOutcome(false, "no_active_line")
            val result = if (long) {
                device.longPress(hit.x, hit.y, active)
            } else {
                device.tap(hit.x, hit.y, active, holdMs = step.params.holdMs ?: 0)
            }
            return result.outcome(
                "${if (long) "long_press" else "tap"} (${hit.x},${hit.y}) " +
                    "line=$active ${hit.detail}",
            )
        }

        private suspend fun inputText(index: Int, step: RpaStep): StepOutcome {
            val text = resolve(step.params.text)
            if (text.isNullOrEmpty()) return StepOutcome(false, "param_text_missing")
            val primary = line ?: return StepOutcome(false, "no_active_line")
            val first = device.inputText(text, primary, step.params.clearFirst)
            if (first.isSuccess) return first.outcome("input_text line=$primary")
            val detail = first.exceptionOrNull()?.message ?: "input_failed"
            if (!fallbackAllowed()) return StepOutcome(false, detail)
            val alt = primary.other()
            if (!lineReady(alt)) return StepOutcome(false, detail)
            emit(RpaRunEvent.LineFallback(index, step, primary, alt, detail))
            val second = device.inputText(text, alt, clearFirst = true)
            if (second.isSuccess) {
                line = alt
                return second.outcome("input_text fallback line=$alt")
            }
            return StepOutcome(false, second.exceptionOrNull()?.message ?: "input_failed")
        }

        private suspend fun pressKey(index: Int, step: RpaStep): StepOutcome {
            val key = step.params.key
            if (key.isNullOrBlank()) return StepOutcome(false, "param_key_invalid")
            return onLine(index, step, "press_key_failed") { active ->
                device.pressKey(key, active)
            }
        }

        private suspend fun openLink(index: Int, step: RpaStep): StepOutcome {
            val url = resolve(step.params.url)
            if (url.isNullOrBlank()) return StepOutcome(false, "param_url_missing")
            val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
            if (scheme !in RpaLimits.URL_SCHEMES) {
                return StepOutcome(false, "param_url_scheme_invalid:$scheme")
            }
            return onLine(index, step, "open_link_failed") { active ->
                device.openLink(url, active)
            }
        }

        /** 设置变量（图8）：逐行写运行时变量表；UI_READ = 读屏（裁定⑥「识别屏幕」）。 */
        private suspend fun setVariable(index: Int, step: RpaStep): StepOutcome {
            if (step.params.assignments.isEmpty()) {
                return StepOutcome(false, "param_assignments_missing")
            }
            step.params.assignments.forEach { assign ->
                val name = assign.variableName
                if (name.isBlank()) return StepOutcome(false, "param_variable_name_invalid")
                val value = when (assign.source) {
                    RpaVarSource.LITERAL -> resolve(assign.value) ?: ""
                    RpaVarSource.UI_READ -> {
                        val active = line ?: return StepOutcome(false, "no_active_line")
                        device.textLines(active).joinToString("\n")
                    }
                    RpaVarSource.VARIABLE_REF -> runtimeVars[assign.value]
                        ?: return StepOutcome(false, "variable_undefined:${assign.value}")
                    RpaVarSource.INCREMENT -> {
                        val current = runtimeVars[name]?.trim()?.toDoubleOrNull() ?: 0.0
                        val delta = assign.value.trim().takeIf { it.isNotEmpty() }
                            ?.toDoubleOrNull() ?: 1.0
                        formatNumber(current + delta)
                    }
                }
                runtimeVars[name] = value
            }
            emit(
                RpaRunEvent.Log(
                    "set_variable step=${step.id} names=" +
                        step.params.assignments.joinToString(",") { it.variableName },
                ),
            )
            return StepOutcome(true, "set_variable ${step.params.assignments.size} row(s)")
        }

        /** 控制运行（图9）：GOTO 改写 PC（stepId 锚定）/ STOP 收口整段脚本。 */
        private suspend fun controlFlow(index: Int, step: RpaStep): StepOutcome {
            return when (step.params.flowKind) {
                RpaFlowKind.STOP -> StepOutcome(true, "flow_stop", stop = true)
                RpaFlowKind.GOTO -> {
                    val target = step.params.gotoStepId
                    if (target.isNullOrBlank()) {
                        StepOutcome(false, "param_goto_missing")
                    } else {
                        StepOutcome(true, "flow_goto $target", jumpTo = target)
                    }
                }
                null -> StepOutcome(false, "param_flow_kind_missing")
            }
        }

        /** 复合动作（§五）：参数代入 + 递归展开，深度 ≤3；跳转不跨边界。 */
        private suspend fun composite(step: RpaStep, depth: Int): StepOutcome {
            val customId = step.params.customActionId
                ?: return StepOutcome(false, "param_custom_action_missing")
            val custom = script.customActions.find { it.id == customId }
                ?: return StepOutcome(false, "custom_action_unknown:$customId")
            if (depth + 1 > RpaLimits.MAX_CUSTOM_DEPTH) {
                return StepOutcome(false, "composite_depth_exceeded")
            }
            // 参数代入：${param} 走运行时表；退出时恢复原值（内部 SET_VARIABLE 的写入保留）。
            val saved = mutableMapOf<String, String?>()
            custom.params.forEach { param ->
                saved[param.name] = runtimeVars[param.name]
                runtimeVars[param.name] =
                    step.params.customArgValues[param.name] ?: resolve(param.default) ?: ""
            }
            try {
                val outcome = runSequence(custom.steps, depth = depth + 1)
                return when {
                    outcome.detail == "run_timeout" -> StepOutcome(false, "run_timeout")
                    !outcome.ok -> StepOutcome(false, outcome.detail)
                    outcome.stopped -> StepOutcome(true, "flow_stop", stop = true)
                    else -> StepOutcome(true, "composite $customId ok")
                }
            } finally {
                saved.forEach { (name, value) ->
                    if (value == null) runtimeVars.remove(name) else runtimeVars[name] = value
                }
            }
        }

        private suspend fun gesture(
            index: Int,
            step: RpaStep,
            direction: RpaDirection,
        ): StepOutcome {
            val size = device.screenSize() ?: return StepOutcome(false, "screen_size_unknown")
            val (w, h) = size
            if (w <= 0 || h <= 0) return StepOutcome(false, "screen_size_unknown")
            val ratio = (step.params.distanceRatio ?: 0.5f).coerceIn(0.05f, 0.9f)
            val duration = (step.params.durationMs ?: 300).coerceIn(
                RpaLimits.MIN_SWIPE_DURATION_MS,
                RpaLimits.MAX_SWIPE_DURATION_MS,
            )
            val cx = w / 2
            val cy = h / 2
            val dx = (w * ratio / 2f).toInt()
            val dy = (h * ratio / 2f).toInt()
            val (x1, y1, x2, y2) = when (direction) {
                RpaDirection.LEFT -> listOf(cx + dx, cy, cx - dx, cy)
                RpaDirection.RIGHT -> listOf(cx - dx, cy, cx + dx, cy)
                RpaDirection.UP -> listOf(cx, cy + dy, cx, cy - dy)
                RpaDirection.DOWN -> listOf(cx, cy - dy, cx, cy + dy)
            }
            return onLine(index, step, "swipe_failed") { active ->
                device.swipe(x1, y1, x2, y2, duration, active)
            }
        }

        private suspend fun waitForTarget(index: Int, step: RpaStep): StepOutcome {
            val target = step.target?.resolve(runtimeVars)
                ?: return StepOutcome(false, "target_missing")
            val primary = line ?: return StepOutcome(false, "no_active_line")
            val timeout = RpaLimits.stepTimeout(step.timeoutMs)
            val now = System.currentTimeMillis()
            val stepDeadline = now + timeout
            val alt = primary.other().takeIf { fallbackAllowed() && lineReady(it) }
            // 双模式先给主线路留出时间，再预留兜底轮询窗口；总预算始终优先。
            val reserve = if (alt != null) DUAL_FALLBACK_WINDOW_MS else 0L
            val primaryDeadline = minOf(
                stepDeadline,
                (deadline - reserve).coerceAtLeast(now + MIN_PRIMARY_WINDOW_MS),
            )
            var hit = pollLocate(target, primary, primaryDeadline)
            if (hit != null) {
                return StepOutcome(true, "target_ready (${hit.x},${hit.y}) line=$primary")
            }
            if (alt != null) {
                emit(RpaRunEvent.LineFallback(index, step, primary, alt, "wait_target_miss"))
                val altDeadline = minOf(stepDeadline + DUAL_FALLBACK_WINDOW_MS, deadline)
                hit = pollLocate(target, alt, altDeadline)
                if (hit != null) {
                    line = alt
                    return StepOutcome(
                        true,
                        "target_ready fallback (${hit.x},${hit.y}) line=$alt",
                    )
                }
            }
            return StepOutcome(false, "target_timeout")
        }

        private suspend fun pollLocate(
            target: RpaTarget,
            line: RpaLine,
            until: Long,
        ): RpaHit? {
            var hit: RpaHit? = null
            while (hit == null && System.currentTimeMillis() < until) {
                hit = device.locate(script.id, target, line)
                if (hit == null) {
                    val remaining = until - System.currentTimeMillis()
                    if (remaining > 0) delay(minOf(POLL_INTERVAL_MS, remaining))
                }
            }
            return hit
        }

        private suspend fun assertText(index: Int, step: RpaStep): StepOutcome {
            val expect = step.expect ?: return StepOutcome(false, "param_expect_missing")
            val primary = line ?: return StepOutcome(false, "no_active_line")
            val timeout = RpaLimits.stepTimeout(expect.timeoutMs)
            val now = System.currentTimeMillis()
            val stepDeadline = now + timeout
            val alt = primary.other().takeIf { fallbackAllowed() && lineReady(it) }
            val reserve = if (alt != null) DUAL_FALLBACK_WINDOW_MS else 0L
            val primaryDeadline = minOf(
                stepDeadline,
                (deadline - reserve).coerceAtLeast(now + MIN_PRIMARY_WINDOW_MS),
            )
            if (pollText(expect, primary, primaryDeadline)) {
                return StepOutcome(true, "assert_ok line=$primary")
            }
            if (alt != null) {
                emit(RpaRunEvent.LineFallback(index, step, primary, alt, "assert_miss"))
                val altDeadline = minOf(stepDeadline + DUAL_FALLBACK_WINDOW_MS, deadline)
                if (pollText(expect, alt, altDeadline)) {
                    line = alt
                    return StepOutcome(true, "assert_ok fallback line=$alt")
                }
            }
            return StepOutcome(false, "assert_text_missing: ${expect.text}")
        }

        private suspend fun pollText(expect: RpaExpect, line: RpaLine, until: Long): Boolean {
            while (System.currentTimeMillis() < until) {
                val found = device.textLines(line).any { text ->
                    RpaTextMatch.matches(text, expect.text, expect.match, ignoreWhitespace = true)
                }
                if (found) return true
                val remaining = until - System.currentTimeMillis()
                if (remaining > 0) delay(minOf(POLL_INTERVAL_MS, remaining))
            }
            return false
        }

        // ---------------------------- 线路 ----------------------------

        private fun initialLine(): RpaLine? {
            // 运行模式门锁线（用户定案）：forceLine 必须脚本模式允许且本线就绪，
            // 不满足按「无可用线路」显式失败，绝不偷换另一条线（§4.5）。
            if (forcedLine != null) {
                return forcedLine.takeIf {
                    RpaModePolicy.lineEnabled(script.mode, it) && lineReady(it)
                }
            }
            return when (script.mode) {
                RpaMode.A11Y -> RpaLine.A11Y.takeIf { caps.a11yReady }
                RpaMode.VISION -> RpaLine.VISION.takeIf { caps.visionReady }
                RpaMode.DUAL -> when {
                    caps.a11yReady -> RpaLine.A11Y
                    caps.visionReady -> RpaLine.VISION
                    else -> null
                }
            }
        }

        /** 换线 fallback 只对 DUAL 自动选线开放；锁线运行（forcedLine）永不换线。 */
        private fun fallbackAllowed(): Boolean = forcedLine == null && script.mode == RpaMode.DUAL

        private fun lineReady(target: RpaLine): Boolean =
            if (target == RpaLine.A11Y) caps.a11yReady else caps.visionReady

        /**
         * 双模式下定位失败后的显式换线：先记录 [RpaRunEvent.LineFallback]，
         * 命中后把本次运行粘在新线路上；两条线都 miss 才算失败。
         */
        private suspend fun locateWithFallback(index: Int, step: RpaStep): RpaHit? {
            val target = step.target?.resolve(runtimeVars) ?: return null
            val primary = line ?: return null
            val first = device.locate(script.id, target, primary)
            if (first != null) return first
            if (!fallbackAllowed()) return null
            val alt = primary.other()
            if (!lineReady(alt)) return null
            emit(RpaRunEvent.LineFallback(index, step, primary, alt, "locate_miss"))
            val second = device.locate(script.id, target, alt) ?: return null
            line = alt
            return second
        }

        private suspend fun onLine(
            index: Int,
            step: RpaStep,
            failureDetail: String,
            block: suspend (RpaLine) -> Result<Unit>,
        ): StepOutcome {
            val primary = line ?: return StepOutcome(false, "no_active_line")
            val first = block(primary)
            if (first.isSuccess) return StepOutcome(true, "line=$primary")
            val detail = first.exceptionOrNull()?.message ?: failureDetail
            if (!fallbackAllowed()) return StepOutcome(false, detail)
            val alt = primary.other()
            if (!lineReady(alt)) return StepOutcome(false, detail)
            emit(RpaRunEvent.LineFallback(index, step, primary, alt, detail))
            val second = block(alt)
            if (second.isSuccess) {
                line = alt
                return StepOutcome(true, "fallback line=$alt")
            }
            return StepOutcome(false, second.exceptionOrNull()?.message ?: failureDetail)
        }

        /**
         * 防呆：脚本声明了目标 App 时，点击 / 输入 / 断言前前台必须在目标 App，
         * 防止自动化误落进自家 App 的输入框（2026-10-21 真机事故教训）。
         * 变量 / 跳转 / 复合 / 打开链接不触碰目标 App 的 UI，不在拦截范围。
         */
        private suspend fun guardForeground(step: RpaStep): String? {
            if (script.appPackage.isBlank()) return null
            when (step.action) {
                RpaAction.LAUNCH_APP,
                RpaAction.OPEN_CHAT,
                RpaAction.WAIT,
                RpaAction.COMMENT,
                RpaAction.OPEN_LINK,
                RpaAction.SET_VARIABLE,
                RpaAction.CONTROL_FLOW,
                RpaAction.COMPOSITE,
                -> return null
                else -> Unit
            }
            val active = line ?: return "no_active_line"
            // 探针失败必须 fail-closed：宁可本步失败，也不能在未知前台里点击 / 输入。
            val fg = device.foregroundPackage(active)
                ?: return "foreground_unknown: expected=${script.appPackage} line=$active"
            return if (fg == script.appPackage) {
                null
            } else {
                "foreground_mismatch: expected=${script.appPackage} actual=$fg"
            }
        }

        /** 失败处置：步骤级 CONTINUE 显式豁免；否则由全局 stopOnFailure 总闸决定。 */
        private fun stopOnFailure(step: RpaStep): Boolean =
            step.onFailure != RpaFailurePolicy.CONTINUE && script.global.stopOnFailure

        private fun resolve(value: String?): String? =
            resolveVariables(value, runtimeVars)?.trim()

        // ---------------------------- 预算 ----------------------------

        /** 在总预算内等待；预算耗尽返回 false（由调用方收口为 run_timeout）。 */
        private suspend fun pause(ms: Long): Boolean {
            if (ms <= 0) return remainingMs() > 0
            val remaining = remainingMs()
            if (remaining <= 0) return false
            delay(ms.coerceAtMost(remaining))
            return remainingMs() > 0
        }

        private fun remainingMs(): Long = deadline - System.currentTimeMillis()

        private fun formatNumber(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }

    /** 一次步骤执行的结论；jumpTo / stop 由 CONTROL_FLOW 改写 PC 或收口脚本。 */
    private data class StepOutcome(
        val ok: Boolean,
        val detail: String = "",
        val jumpTo: String? = null,
        val stop: Boolean = false,
    )

    /** 一段序列（顶层或复合内部）的结论。 */
    private data class SeqOutcome(
        val ok: Boolean,
        val stopped: Boolean = false,
        val failedIndex: Int? = null,
        val detail: String = "",
    )

    private fun Result<Unit>.outcome(successDetail: String): StepOutcome =
        if (isSuccess) {
            StepOutcome(true, successDetail)
        } else {
            StepOutcome(false, exceptionOrNull()?.message ?: "failed")
        }

    companion object {

        /**
         * P2 占位动作（裁定③）：目录可见、UI 置灰，但运行显式报 `unsupported_action`。
         * 单测兜底：`UNSUPPORTED_ACTIONS` 之外的枚举值都必须有 handler（when 编译期穷尽）。
         */
        val UNSUPPORTED_ACTIONS: Set<RpaAction> = setOf(
            RpaAction.TAP_COLOR,
            RpaAction.GESTURE_MULTI,
        )

        private const val POLL_INTERVAL_MS = 300L
        private const val RETRY_DELAY_MS = 400L

        /** 双模式兜底线路的轮询窗口（不是「只试一次」）。 */
        private const val DUAL_FALLBACK_WINDOW_MS = 2_000L

        /** 主线路至少保留的探测时间，避免总预算把主线路压成 0。 */
        private const val MIN_PRIMARY_WINDOW_MS = 200L
    }
}
