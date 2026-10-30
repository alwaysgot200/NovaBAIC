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

package com.verlintas.baic2.floatball.rpa

import com.verlintas.rpa.engine.RpaDevice
import com.verlintas.rpa.engine.RpaRunner
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaHit
import com.verlintas.rpa.model.RpaIssueSeverity
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaRunEvent
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpaWechatHelloFixtureTest {

    @Test
    fun fixtureIsSevenStepDualWithBothLines() {
        val script = RpaWechatHelloFixture.build(launchDelayMs = 0)
        assertEquals(7, script.steps.size)
        assertTrue(script.steps.all { it.enabled })

        val defaultScript = RpaWechatHelloFixture.build()
        assertEquals(5_000L, defaultScript.steps.first().delayAfterMs)
        assertEquals(0L, script.steps.first().delayAfterMs)

        val errors = RpaValidator.validate(script)
            .filter { it.severity == RpaIssueSeverity.ERROR }
        assertTrue(errors.toString(), errors.isEmpty())

        listOf(1, 3, 4, 6).forEach { index ->
            val target = script.steps[index].target
            assertNotNull(target?.a11y)
            assertNotNull(target?.vision)
            assertTrue(target!!.a11y!!.isConfigured())
            assertTrue(target.vision!!.isConfigured())
        }
    }

    @Test
    fun fakeDeviceRunsAllSevenSteps() = runBlocking {
        val script = RpaWechatHelloFixture.build(launchDelayMs = 0)
        val events = mutableListOf<RpaRunEvent>()
        val result = RpaRunner(FakeRpaDevice())
            .run(script) { event -> events += event }

        assertTrue(result.detail, result.ok)
        assertEquals(7, events.count { it is RpaRunEvent.StepStarted })
        assertEquals(7, events.count { it is RpaRunEvent.StepFinished })
        assertTrue(
            events.filterIsInstance<RpaRunEvent.StepFinished>().all { it.ok },
        )
        val finished = events.last() as RpaRunEvent.ScriptFinished
        assertTrue(finished.ok)
    }

    private class FakeRpaDevice : RpaDevice {

        override val capabilities = RpaCapabilities(
            a11yReady = true,
            shizukuReady = true,
            visionReady = true,
            ocrReady = true,
            projectionReady = true,
            screenshotReady = true,
        )

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

        override suspend fun tap(x: Int, y: Int, line: RpaLine, holdMs: Int): Result<Unit> =
            Result.success(Unit)

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
        ): Result<Unit> = Result.success(Unit)

        override suspend fun pressKey(key: String, line: RpaLine): Result<Unit> =
            Result.success(Unit)

        override suspend fun openLink(url: String, line: RpaLine): Result<Unit> =
            Result.success(Unit)

        override suspend fun locate(
            scriptId: String,
            target: RpaTarget,
            line: RpaLine,
        ): RpaHit = RpaHit(x = 540, y = 960, detail = "fake", line = line)

        override suspend fun textLines(line: RpaLine): List<String> = listOf("你好")

        override suspend fun foregroundPackage(line: RpaLine): String =
            RpaWechatHelloFixture.DEFAULT_IM_PACKAGE

        override fun screenSize(): Pair<Int, Int> = 1080 to 1920
    }
}
