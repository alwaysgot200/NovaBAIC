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
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SendSequence A 期两步新增（托管方案 §四.A.3/§四.A.4）：聚焦步先于注入且
 * best-effort、发送后回读未命中按 SEND_TAP_FAILED 处理并清残留。全 fake 注入，
 * 0 延迟（PostSendVerifier 延迟参数可注入）。
 */
class SendSequenceFocusVerifyTest {

    private companion object {
        const val IM_PKG = "com.tencent.mm"
        val SEND_TARGET = SendTarget(640, 1200)
    }

    /** fake 注入器：记录调用顺序，注入按队列出结果。 */
    private class FakeInjector(
        typeResults: List<Result<Unit>> = listOf(Result.success(Unit)),
    ) : InputInjector {
        val events = mutableListOf<String>()
        private val typeQueue = typeResults.toMutableList()
        override val channel = InjectorChannel.A11y
        override val available = true
        override val unavailableReason = "fake_unavailable"
        override suspend fun tap(x: Int, y: Int): Result<Unit> {
            events += "tap($x,$y)"
            return Result.success(Unit)
        }

        override suspend fun type(text: String): Result<Unit> {
            events += if (text.isEmpty()) "clear" else "type"
            return next(typeQueue)
        }

        override suspend fun paste(): Result<Unit> = Result.success(Unit)
        override suspend fun clearInput(): Result<Unit> {
            events += "clear"
            return Result.success(Unit)
        }

        override suspend fun foregroundPackage(): String? = IM_PKG

        private fun <T> next(queue: MutableList<T>): T = queue.removeAt(0)
    }

    private val locator = SendLocator { SEND_TARGET }

    private suspend fun run(
        injector: FakeInjector,
        draft: String = "好啊，那就这么定。",
        evidence: SendEvidenceFinder? = null,
        focusStep: (suspend () -> Boolean)? = null,
    ): String? = SendSequence.run(
        injector = injector,
        locator = locator,
        foregroundPackage = IM_PKG,
        imPackage = IM_PKG,
        draft = draft,
        evidence = evidence,
        focusStep = focusStep,
    )

    @Test
    fun focusStep_runsBeforeInject_andIsBestEffort() = runBlocking {
        val injector = FakeInjector()
        val order = injector.events
        val result = run(
            injector,
            focusStep = { order += "focus"; true },
        )
        assertNull(result)
        assertEquals(listOf("focus", "type", "tap(640,1200)"), order)
    }

    @Test
    fun focusStep_throws_ignoredAndInjectionProceeds() = runBlocking {
        val injector = FakeInjector()
        val result = run(
            injector,
            focusStep = { throw IllegalStateException("focus boom") },
        )
        assertNull(result)
        assertEquals(listOf("type", "tap(640,1200)"), injector.events)
    }

    @Test
    fun evidenceHit_sendsNormally() = runBlocking {
        val injector = FakeInjector()
        val result = run(
            injector,
            evidence = SendEvidenceFinder { true },
        )
        assertNull(result)
        assertTrue("成功路径不清残留", !injector.events.contains("clear"))
    }

    @Test
    fun evidenceMiss_treatedAsTapFailed_clearsResidue() = runBlocking {
        val injector = FakeInjector()
        val result = run(
            injector,
            evidence = SendEvidenceFinder { false },
        )
        assertEquals(SendSequence.REASON_SEND_TAP_FAILED, result)
        assertEquals("clear", injector.events.last())
    }

    @Test
    fun evidenceHitOnLaterQuery_afterRetry_sendsNormally() = runBlocking {
        // PostSendVerifier 的重查时序已单测；这里只证状态机对最终 true 放行。
        val injector = FakeInjector()
        val result = run(
            injector,
            evidence = SendEvidenceFinder { true },
        )
        assertNull(result)
    }

    @Test
    fun noEvidence_legacyBehaviorUnchanged() = runBlocking {
        val injector = FakeInjector()
        assertNull(run(injector, evidence = null))
        assertEquals(listOf("type", "tap(640,1200)"), injector.events)
    }
}
