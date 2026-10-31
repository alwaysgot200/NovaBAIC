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

import com.verlintas.rpa.engine.RpaDevice
import com.verlintas.rpa.engine.RpaRunner
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaFailurePolicy
import com.verlintas.rpa.model.RpaHit
import com.verlintas.rpa.model.RpaInputChannel
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaParams
import com.verlintas.rpa.model.RpaRect
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpaRunnerTest {

    private val bothLines = RpaCapabilities(
        a11yReady = true,
        shizukuReady = true,
        visionReady = true,
        ocrReady = true,
        projectionReady = true,
        screenshotReady = true,
    )

    private fun a11yTarget() = RpaTarget(
        a11y = RpaA11ySpec(selectors = listOf(RpaSelector(text = "send"))),
    )

    private fun dualTarget() = RpaTarget(
        a11y = RpaA11ySpec(selectors = listOf(RpaSelector(text = "send"))),
        vision = RpaVisionSpec(
            primary = RpaVisionLocator(
                kind = RpaVisionKind.TEMPLATE,
                templateId = "tpl_send",
            ),
        ),
    )

    private fun script(
        steps: List<RpaStep>,
        mode: RpaMode = RpaMode.A11Y,
        appPackage: String = "",
    ) = RpaScript(
        id = "script_runner",
        name = "runner",
        mode = mode,
        appPackage = appPackage,
        steps = steps,
    )

    private fun tapStep(
        id: String = "s1",
        target: RpaTarget = a11yTarget(),
        retries: Int = 0,
        onFailure: RpaFailurePolicy = RpaFailurePolicy.STOP,
        // 定位按步轮询的「等待目标」预算；恒 miss / 首枪 miss 的用例压到一轮询
        // 间隔（300ms）之内，每次尝试恰好一枪，既保语义又不拖慢套件。
        timeoutMs: Long = 5_000,
    ) = RpaStep(
        id = id,
        action = RpaAction.TAP,
        target = target,
        retries = retries,
        onFailure = onFailure,
        timeoutMs = timeoutMs,
    )

    @Test
    fun `foreground probe failure is fail closed`() = runBlocking {
        val device = FakeDevice(foreground = null, locate = { _, _ -> RpaHit(10, 20) })
        val events = mutableListOf<RpaRunEvent>()
        val result = RpaRunner(device).run(
            script(
                steps = listOf(tapStep()),
                appPackage = "com.example.app",
            ),
        ) { events += it }

        assertFalse(result.ok)
        assertTrue(result.detail.startsWith("foreground_unknown"))
        assertTrue(device.taps.isEmpty())
        assertEquals(0, result.failedStepIndex)
    }

    @Test
    fun `total budget ends a long wait with run timeout`() = runBlocking {
        val device = FakeDevice()
        val events = mutableListOf<RpaRunEvent>()
        val result = RpaRunner(device, totalTimeoutMs = 100).run(
            script(
                steps = listOf(
                    RpaStep(
                        id = "wait",
                        action = RpaAction.WAIT,
                        params = RpaParams(waitMs = 5_000),
                    ),
                ),
            ),
        ) { events += it }

        assertFalse(result.ok)
        assertEquals("run_timeout", result.detail)
        val finished = events.last() as RpaRunEvent.ScriptFinished
        assertEquals("run_timeout", finished.detail)
        assertTrue(events.any { it is RpaRunEvent.Log && it.message.startsWith("run_timeout") })
    }

    @Test
    fun `dual mode records fallback and keeps the new line`() = runBlocking {
        val device = FakeDevice(
            locate = { _, line -> if (line == RpaLine.VISION) RpaHit(30, 40) else null },
        )
        val events = mutableListOf<RpaRunEvent>()
        val result = RpaRunner(device).run(
            script(
                mode = RpaMode.DUAL,
                // 恒 miss 侧压缩等待预算，防轮询拖慢（语义不受影响）。
                steps = listOf(tapStep(target = dualTarget(), timeoutMs = 200)),
            ),
        ) { events += it }

        assertTrue(result.detail, result.ok)
        val fallback = events.filterIsInstance<RpaRunEvent.LineFallback>().single()
        assertEquals(RpaLine.A11Y, fallback.from)
        assertEquals(RpaLine.VISION, fallback.to)
        assertEquals(listOf(RpaLine.VISION), device.taps)
    }

    @Test
    fun `stop policy halts following steps while continue keeps going`() = runBlocking {
        val stopDevice = FakeDevice(locate = { _, _ -> null })
        val stopEvents = mutableListOf<RpaRunEvent>()
        val stopped = RpaRunner(stopDevice).run(
            script(
                steps = listOf(
                    // 恒 miss：压缩等待预算防轮询拖慢。
                    tapStep(id = "s1", onFailure = RpaFailurePolicy.STOP, timeoutMs = 200),
                    tapStep(id = "s2", timeoutMs = 200),
                ),
            ),
        ) { stopEvents += it }

        assertFalse(stopped.ok)
        assertEquals(0, stopped.failedStepIndex)
        assertEquals(1, stopEvents.count { it is RpaRunEvent.StepStarted })

        val continueDevice = FakeDevice(locate = { _, _ -> null })
        val continueEvents = mutableListOf<RpaRunEvent>()
        val continued = RpaRunner(continueDevice).run(
            script(
                steps = listOf(
                    tapStep(id = "s1", onFailure = RpaFailurePolicy.CONTINUE, timeoutMs = 200),
                    tapStep(id = "s2", timeoutMs = 200),
                ),
            ),
        ) { continueEvents += it }

        assertFalse(continued.ok)
        assertEquals(1, continued.failedStepIndex)
        assertEquals(2, continueEvents.count { it is RpaRunEvent.StepStarted })
    }

    @Test
    fun `retry succeeds on the second attempt`() = runBlocking {
        var calls = 0
        val device = FakeDevice(
            locate = { _, _ ->
                calls += 1
                if (calls >= 2) RpaHit(5, 6) else null
            },
        )
        val events = mutableListOf<RpaRunEvent>()
        val result = RpaRunner(device).run(
            // timeoutMs 压到一轮询间隔内 = 每次尝试恰好一次定位，
            // 保住「首试 miss、重试命中」的重试语义（定位已按步轮询）。
            script(steps = listOf(tapStep(retries = 1, timeoutMs = 200))),
        ) { events += it }

        assertTrue(result.detail, result.ok)
        assertTrue(events.any { it is RpaRunEvent.Log && it.message.startsWith("retry") })
        assertEquals(listOf(RpaLine.A11Y), device.taps)
    }

    private class FakeDevice(
        override val capabilities: RpaCapabilities = RpaCapabilities(
            a11yReady = true,
            shizukuReady = true,
            visionReady = true,
            ocrReady = true,
            projectionReady = true,
            screenshotReady = true,
        ),
        private val foreground: String? = "com.example.app",
        private val locate: (RpaTarget, RpaLine) -> RpaHit? = { _, _ -> null },
        private val textLines: List<String> = emptyList(),
    ) : RpaDevice {

        val taps = mutableListOf<RpaLine>()

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
            taps += line
            return Result.success(Unit)
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
            inputSelector: RpaSelector?,
            appendMode: Boolean,
            inputChannel: RpaInputChannel,
            focusPoint: RpaRect?,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun pressKey(key: String, line: RpaLine): Result<Unit> =
            Result.success(Unit)

        override suspend fun openLink(url: String, line: RpaLine): Result<Unit> =
            Result.success(Unit)

        override suspend fun locate(scriptId: String, target: RpaTarget, line: RpaLine): RpaHit? =
            locate(target, line)

        override suspend fun textLines(
            line: RpaLine,
            readScope: RpaSelector?,
            ocrRegion: RpaRect?,
        ): List<String> = textLines

        override suspend fun foregroundPackage(line: RpaLine): String? = foreground

        override fun screenSize(): Pair<Int, Int> = 1080 to 1920
    }
}
