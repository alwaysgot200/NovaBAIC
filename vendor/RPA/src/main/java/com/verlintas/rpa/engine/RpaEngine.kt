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
import com.verlintas.rpa.model.RpaDirection
import com.verlintas.rpa.model.RpaFailurePolicy
import com.verlintas.rpa.model.RpaHit
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaReadiness
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaRunResult
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaTextMatch
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

    suspend fun launchApp(packageName: String, line: RpaLine): Result<Unit>

    suspend fun openChat(imPackage: String, contactName: String, line: RpaLine): Result<Unit>

    suspend fun tap(x: Int, y: Int, line: RpaLine): Result<Unit>

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

    /** 按线路定位；返回 null 表示未命中（不抛异常，方便轮询）。 */
    suspend fun locate(scriptId: String, target: RpaTarget, line: RpaLine): RpaHit?

    /** 当前屏幕的文本行（a11y 节点文本 / OCR 行），断言用。 */
    suspend fun textLines(line: RpaLine): List<String>

    suspend fun foregroundPackage(line: RpaLine): String?

    fun screenSize(): Pair<Int, Int>?
}

/**
 * RPA 解释器：按 JSON 文档的 `steps` 顺序执行；每一步先过线路就绪校验，
 * 再定位 / 执行；双模式下定位失败会显式切换线路并写入 [RpaRunEvent.LineFallback]。
 */
class RpaRunner(
    private val device: RpaDevice,
    private val templateExists: (scriptId: String, templateId: String) -> Boolean = { _, _ -> true },
) {

    suspend fun run(
        script: RpaScript,
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
        val session = Session(script, caps, onEvent)
        return session.run()
    }

    private inner class Session(
        private val script: RpaScript,
        private val caps: RpaCapabilities,
        private val emit: suspend (RpaRunEvent) -> Unit,
    ) {

        private var line: RpaLine? = initialLine()

        suspend fun run(): RpaRunResult {
            emit(RpaRunEvent.ScriptStarted(script, line))
            script.steps.forEachIndexed { index, step ->
                if (!step.enabled) return@forEachIndexed
                if (step.delayBeforeMs > 0) delay(step.delayBeforeMs)
                emit(RpaRunEvent.StepStarted(index, step, line))
                val outcome = executeWithRetry(index, step)
                emit(
                    RpaRunEvent.StepFinished(
                        index = index,
                        step = step,
                        ok = outcome.ok,
                        detail = outcome.detail,
                    ),
                )
                if (!outcome.ok && step.onFailure == RpaFailurePolicy.STOP) {
                    emit(RpaRunEvent.ScriptFinished(false, index, outcome.detail))
                    return RpaRunResult(false, index, outcome.detail)
                }
                // 执行后等待只在动作最终成功后生效：失败 / 重试 / CONTINUE 都不等。
                if (outcome.ok && step.delayAfterMs > 0) delay(step.delayAfterMs)
                if (!outcome.ok) {
                    emit(RpaRunEvent.Log("continue_after_failure step=${step.id}"))
                }
            }
            emit(RpaRunEvent.ScriptFinished(true))
            return RpaRunResult(true)
        }

        private suspend fun executeWithRetry(index: Int, step: RpaStep): StepOutcome {
            val attempts = step.retries.coerceAtLeast(0) + 1
            var last = StepOutcome(false, "not_executed")
            for (attempt in 1..attempts) {
                last = executeOnce(index, step)
                if (last.ok) return last
                if (attempt < attempts) {
                    emit(
                        RpaRunEvent.Log(
                            "retry step=${step.id} attempt=${attempt + 1} reason=${last.detail}",
                        ),
                    )
                    delay(RETRY_DELAY_MS)
                }
            }
            return last
        }

        private suspend fun executeOnce(index: Int, step: RpaStep): StepOutcome {
            guardForeground(step)?.let { return StepOutcome(false, it) }
            return when (step.action) {
                RpaAction.LAUNCH_APP -> {
                    val pkg = resolve(step.params.packageName)
                    if (pkg.isNullOrBlank()) {
                        StepOutcome(false, "param_package_missing")
                    } else {
                        val active = line ?: return StepOutcome(false, "no_active_line")
                        device.launchApp(pkg, active).outcome("launch_app $pkg line=$active")
                    }
                }
                RpaAction.OPEN_CHAT -> openChat(index, step)
                RpaAction.WAIT -> {
                    val wait = step.params.waitMs ?: 0
                    if (wait <= 0) {
                        StepOutcome(false, "param_wait_missing")
                    } else {
                        delay(wait)
                        StepOutcome(true, "waited ${wait}ms")
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
                device.tap(hit.x, hit.y, active)
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
            if (script.mode != RpaMode.DUAL) return StepOutcome(false, detail)
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

        private suspend fun gesture(
            index: Int,
            step: RpaStep,
            direction: RpaDirection,
        ): StepOutcome {
            val size = device.screenSize() ?: return StepOutcome(false, "screen_size_unknown")
            val (w, h) = size
            if (w <= 0 || h <= 0) return StepOutcome(false, "screen_size_unknown")
            val ratio = (step.params.distanceRatio ?: 0.5f).coerceIn(0.05f, 0.9f)
            val duration = (step.params.durationMs ?: 300).coerceIn(50, 3_000)
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
            val target = step.target?.resolve(script.variables)
                ?: return StepOutcome(false, "target_missing")
            val primary = line ?: return StepOutcome(false, "no_active_line")
            val timeout = step.timeoutMs.coerceAtLeast(100)
            val deadline = System.currentTimeMillis() + timeout
            var hit: RpaHit? = null
            while (hit == null && System.currentTimeMillis() < deadline) {
                hit = device.locate(script.id, target, primary)
                if (hit == null) delay(POLL_INTERVAL_MS)
            }
            if (hit != null) {
                return StepOutcome(true, "target_ready (${hit.x},${hit.y}) line=$primary")
            }
            if (script.mode == RpaMode.DUAL) {
                val alt = primary.other()
                if (lineReady(alt)) {
                    emit(RpaRunEvent.LineFallback(index, step, primary, alt, "wait_target_miss"))
                    val second = device.locate(script.id, target, alt)
                    if (second != null) {
                        line = alt
                        return StepOutcome(
                            true,
                            "target_ready fallback (${second.x},${second.y}) line=$alt",
                        )
                    }
                }
            }
            return StepOutcome(false, "target_timeout")
        }

        private suspend fun assertText(index: Int, step: RpaStep): StepOutcome {
            val expect = step.expect ?: return StepOutcome(false, "param_expect_missing")
            val primary = line ?: return StepOutcome(false, "no_active_line")
            val timeout = expect.timeoutMs.coerceAtLeast(100)
            val deadline = System.currentTimeMillis() + timeout
            var found = false
            while (!found && System.currentTimeMillis() < deadline) {
                found = device.textLines(primary).any { text ->
                    RpaTextMatch.matches(text, expect.text, expect.match, ignoreWhitespace = true)
                }
                if (!found) delay(POLL_INTERVAL_MS)
            }
            if (found) return StepOutcome(true, "assert_ok line=$primary")
            if (script.mode == RpaMode.DUAL) {
                val alt = primary.other()
                if (lineReady(alt)) {
                    emit(RpaRunEvent.LineFallback(index, step, primary, alt, "assert_miss"))
                    val second = device.textLines(alt).any { text ->
                        RpaTextMatch.matches(
                            text,
                            expect.text,
                            expect.match,
                            ignoreWhitespace = true,
                        )
                    }
                    if (second) {
                        line = alt
                        return StepOutcome(true, "assert_ok fallback line=$alt")
                    }
                }
            }
            return StepOutcome(false, "assert_text_missing: ${expect.text}")
        }

        // ---------------------------- 线路 ----------------------------

        private fun initialLine(): RpaLine? = when (script.mode) {
            RpaMode.A11Y -> RpaLine.A11Y.takeIf { caps.a11yReady }
            RpaMode.VISION -> RpaLine.VISION.takeIf { caps.visionReady }
            RpaMode.DUAL -> when {
                caps.a11yReady -> RpaLine.A11Y
                caps.visionReady -> RpaLine.VISION
                else -> null
            }
        }

        private fun lineReady(target: RpaLine): Boolean =
            if (target == RpaLine.A11Y) caps.a11yReady else caps.visionReady

        /**
         * 双模式下定位失败后的显式换线：先记录 [RpaRunEvent.LineFallback]，
         * 命中后把本次运行粘在新线路上；两条线都 miss 才算失败。
         */
        private suspend fun locateWithFallback(index: Int, step: RpaStep): RpaHit? {
            val target = step.target?.resolve(script.variables) ?: return null
            val primary = line ?: return null
            val first = device.locate(script.id, target, primary)
            if (first != null) return first
            if (script.mode != RpaMode.DUAL) return null
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
            if (script.mode != RpaMode.DUAL) return StepOutcome(false, detail)
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
         */
        private suspend fun guardForeground(step: RpaStep): String? {
            if (script.appPackage.isBlank()) return null
            if (step.action == RpaAction.LAUNCH_APP ||
                step.action == RpaAction.OPEN_CHAT ||
                step.action == RpaAction.WAIT ||
                step.action == RpaAction.COMMENT
            ) {
                return null
            }
            val active = line ?: return "no_active_line"
            val fg = device.foregroundPackage(active) ?: return null
            return if (fg == script.appPackage) {
                null
            } else {
                "foreground_mismatch: expected=${script.appPackage} actual=$fg"
            }
        }

        private fun resolve(value: String?): String? =
            resolveVariables(value, script.variables)?.trim()
    }

    private data class StepOutcome(val ok: Boolean, val detail: String = "")

    private fun Result<Unit>.outcome(successDetail: String): StepOutcome =
        if (isSuccess) {
            StepOutcome(true, successDetail)
        } else {
            StepOutcome(false, exceptionOrNull()?.message ?: "failed")
        }

    private companion object {
        const val POLL_INTERVAL_MS = 300L
        const val RETRY_DELAY_MS = 400L
    }
}
