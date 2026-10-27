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
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 运行状态单例：进度、线路回退与结果收口（服务只负责喂事件）。 */
class RpaRunControllerTest {

    private fun script(): RpaScript = RpaScript(
        id = "script-1",
        name = "测试脚本",
        steps = listOf(
            RpaStep(id = "s1", action = RpaAction.COMMENT),
            RpaStep(id = "s2", action = RpaAction.TAP, label = "点搜索"),
        ),
    )

    @Test
    fun `start exposes running with no step yet`() {
        val controller = RpaRunController()
        controller.start(script(), startedAt = 123L)

        val state = controller.state.value as RpaRunController.State.Running
        assertEquals("script-1", state.scriptId)
        assertEquals("测试脚本", state.scriptName)
        assertEquals(-1, state.stepIndex)
        assertEquals(2, state.stepCount)
        assertEquals(1, state.displayIndex)
        assertEquals(123L, state.startedAt)
        assertTrue(controller.events.value.isEmpty())
    }

    @Test
    fun `step started updates progress label and line`() {
        val controller = RpaRunController()
        val target = script()
        controller.start(target)
        controller.record(
            RpaRunEvent.StepStarted(
                index = 1,
                step = target.steps[1],
                line = RpaLine.A11Y,
            ),
        )

        val state = controller.state.value as RpaRunController.State.Running
        assertEquals(1, state.stepIndex)
        assertEquals(2, state.displayIndex)
        assertEquals("点搜索", state.stepLabel)
        assertEquals(RpaAction.TAP, state.stepAction)
        assertEquals(RpaLine.A11Y, state.line)
        assertEquals(1, controller.events.value.size)
    }

    @Test
    fun `line fallback switches running line`() {
        val controller = RpaRunController()
        val target = script()
        controller.start(target)
        controller.record(RpaRunEvent.StepStarted(1, target.steps[1], RpaLine.A11Y))
        controller.record(
            RpaRunEvent.LineFallback(
                index = 1,
                step = target.steps[1],
                from = RpaLine.A11Y,
                to = RpaLine.VISION,
                reason = "not_found",
            ),
        )

        val state = controller.state.value as RpaRunController.State.Running
        assertEquals(RpaLine.VISION, state.line)
        assertEquals(2, controller.events.value.size)
    }

    @Test
    fun `finish only collapses running and keeps first detail`() {
        val controller = RpaRunController()
        val target = script()
        controller.start(target)
        controller.finish(false, "step_failed")
        controller.finish(true, "late_success")

        val state = controller.state.value as RpaRunController.State.Finished
        assertFalse(state.ok)
        assertEquals("step_failed", state.detail)
        assertEquals("script-1", state.scriptId)
    }

    @Test
    fun `finish before start is ignored`() {
        val controller = RpaRunController()
        controller.finish(true, "nothing")

        assertEquals(RpaRunController.State.Idle, controller.state.value)
    }

    @Test
    fun `dismiss finished returns idle and reset clears events`() {
        val controller = RpaRunController()
        val target = script()
        controller.start(target)
        controller.record(RpaRunEvent.Log("hello"))
        controller.finish(true, "")
        controller.dismissFinished()

        assertEquals(RpaRunController.State.Idle, controller.state.value)
        assertEquals(1, controller.events.value.size)

        controller.reset()
        assertEquals(RpaRunController.State.Idle, controller.state.value)
        assertTrue(controller.events.value.isEmpty())
    }

    @Test
    fun `start clears previous events`() {
        val controller = RpaRunController()
        val target = script()
        controller.start(target)
        controller.record(RpaRunEvent.Log("old"))
        controller.start(target)

        assertTrue(controller.events.value.isEmpty())
    }

    @Test
    fun `events are capped at the limit`() {
        val controller = RpaRunController()
        controller.start(script())
        repeat(RpaRunController.EVENT_LIMIT + 12) { index ->
            controller.record(RpaRunEvent.Log("event-$index"))
        }

        val events = controller.events.value
        assertEquals(RpaRunController.EVENT_LIMIT, events.size)
        assertEquals("event-12", (events.first() as RpaRunEvent.Log).message)
    }
}
