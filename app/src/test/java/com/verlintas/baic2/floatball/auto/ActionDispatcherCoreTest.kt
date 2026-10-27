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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.TextNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3-a 自动发送核心（方案 §8.2/§8.3）：三步状态机的校验与一次重试
 * （fake [AccessibilityBridge]，全程节点树定位、零坐标硬编码）、发送前指纹、
 * 总闸+白名单前置闸、内存对账表。Android 边界（Room 落库/通知降级）不在此测。
 */
class ActionDispatcherCoreTest {

    private companion object {
        const val IM_PKG = "com.tencent.mm"
        const val DRAFT = "周末愉快呀，最近忙什么呢？"
        val SEND_NODE = TextNode("发送", 600, 1180, 680, 1220)
    }

    /** 可编程 fake：注入/点击按队列出结果，findText 只回真实命中的节点池。 */
    private class FakeBridge(
        connectedValue: Boolean = true,
        private val foreground: String? = IM_PKG,
        injectResults: List<Result<Unit>> = listOf(Result.success(Unit)),
        sendNodes: List<TextNode> = listOf(SEND_NODE),
        tapResults: List<Result<Unit>> = listOf(Result.success(Unit)),
    ) : AccessibilityBridge {
        val injected = mutableListOf<String>()
        val taps = mutableListOf<Pair<Int, Int>>()
        private val injectQueue = injectResults.toMutableList()
        private val tapQueue = tapResults.toMutableList()
        private var nodePool = sendNodes

        override val connected: StateFlow<Boolean> = MutableStateFlow(connectedValue)

        override suspend fun tap(x: Int, y: Int): Result<Unit> {
            taps += x to y
            return next(tapQueue)
        }

        override suspend fun longPress(x: Int, y: Int): Result<Unit> = Result.success(Unit)

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<Unit> =
            Result.success(Unit)

        override suspend fun typeText(text: String): Result<Unit> {
            injected += text
            return next(injectQueue)
        }

        override suspend fun pressKey(key: String): Result<Unit> = Result.success(Unit)

        override fun findText(query: String): List<TextNode> =
            if (nodePool.any { it.text.lowercase().contains(query.trim().lowercase()) }) {
                nodePool
            } else {
                emptyList()
            }

        override fun screenText(maxNodes: Int): String = ""
        override fun foregroundPackage(): String? = foreground
        override fun windowTitle(): String? = null
        override fun screenSize(): Pair<Int, Int>? = 720 to 1280
        override fun editableFocused(): Boolean = true
        override suspend fun captureScreen(): Result<ByteArray> =
            Result.failure(IllegalStateException("unused in dispatch"))

        // removeFirst() 在 JDK 21 才进 List 接口，单测 JVM(JDK 17) 无此方法。
        private fun <T> next(queue: MutableList<T>): T = queue.removeAt(0)
    }

    private suspend fun run(
        bridge: FakeBridge,
        imPackage: String = IM_PKG,
        draft: String = DRAFT,
    ): String? = SendSequence.run(bridge, imPackage, draft)

    // ── 三步状态机：成功路径 ──

    @Test
    fun allStepsSucceed_returnsNull_andTapsNodeCenter() = runBlocking {
        val bridge = FakeBridge()
        assertNull(run(bridge))
        assertEquals(listOf(DRAFT), bridge.injected)
        // 只点击 findText 返回的节点中心，绝无盲点坐标。
        assertEquals(listOf(SEND_NODE.centerX to SEND_NODE.centerY), bridge.taps)
    }

    @Test
    fun injectRetryOnce_succeeds() = runBlocking {
        val bridge = FakeBridge(injectResults = listOf(fail(), Result.success(Unit)))
        assertNull(run(bridge))
        assertEquals(2, bridge.injected.size)
    }

    @Test
    fun tapRetryViaRelocation_succeeds() = runBlocking {
        val bridge = FakeBridge(tapResults = listOf(fail(), Result.success(Unit)))
        assertNull(run(bridge))
        assertEquals(2, bridge.taps.size)
    }

    // ── 每步失败 → 放弃发送（交调用方降级起草档） ──

    @Test
    fun disconnected_shortCircuitsBeforeAnyTouch() = runBlocking {
        val bridge = FakeBridge(connectedValue = false)
        assertEquals(SendSequence.REASON_A11Y_DISCONNECTED, run(bridge))
        assertTrue(bridge.injected.isEmpty() && bridge.taps.isEmpty())
    }

    @Test
    fun wrongForeground_neverInjects() = runBlocking {
        val bridge = FakeBridge(foreground = "com.other.app")
        assertEquals(SendSequence.REASON_WRONG_FOREGROUND, run(bridge))
        assertTrue(bridge.injected.isEmpty())
    }

    @Test
    fun injectFailsTwice_givesUp() = runBlocking {
        val bridge = FakeBridge(injectResults = listOf(fail(), fail()))
        // 2026-10-27 起失败原因带异常明细（`input_inject_failed:类型_消息`），只断前缀。
        assertTrue(run(bridge)!!.startsWith(SendSequence.REASON_INPUT_FAILED))
        assertEquals(2, bridge.injected.size)
        assertTrue("注入失败绝不点击发送", bridge.taps.isEmpty())
    }

    @Test
    fun sendButtonNotFound_clearsAndRetriesOnceThenGivesUp() = runBlocking {
        val bridge = FakeBridge(
            sendNodes = emptyList(),
            injectResults = List(4) { Result.success(Unit) },
        )
        assertEquals(SendSequence.REASON_SEND_NOT_FOUND, run(bridge))
        // 2026-10-27 恢复一轮：注入 → 定位失败 → 清空 → 重新注入 → 仍失败 → 最终清空。
        assertEquals(listOf(DRAFT, "", DRAFT, ""), bridge.injected)
        assertTrue("找不到按钮绝不盲点", bridge.taps.isEmpty())
    }

    @Test
    fun tapFailsBothRounds_clearsResidueAndGivesUp() = runBlocking {
        val bridge = FakeBridge(tapResults = listOf(fail(), fail()))
        assertEquals(SendSequence.REASON_SEND_TAP_FAILED, run(bridge))
        assertEquals(2, bridge.taps.size)
        // best effort 清残留：最后一次注入是空串，降级后人肉档不面对半成品。
        assertEquals("", bridge.injected.last())
    }

    private fun fail(): Result<Unit> = Result.failure(IllegalStateException("rejected"))

    // ── 前置闸：总闸 + 白名单 ──

    @Test
    fun gate_masterSwitchOff_rejectsDispatch() {
        assertEquals(
            DispatchGate.REASON_AUTO_DISABLED,
            DispatchGate.vetoReason(autoEnabled = false, whitelist = setOf(7L), contactId = 7L),
        )
    }

    @Test
    fun gate_contactNotWhitelisted_rejectsDispatch() {
        assertEquals(
            DispatchGate.REASON_NOT_WHITELISTED,
            DispatchGate.vetoReason(autoEnabled = true, whitelist = setOf(7L), contactId = 9L),
        )
    }

    @Test
    fun gate_enabledAndWhitelisted_passes() {
        assertNull(DispatchGate.vetoReason(autoEnabled = true, whitelist = setOf(7L), contactId = 7L))
    }

    // ── 发送前文本指纹 ──

    @Test
    fun fingerprint_stableAndDistinct() {
        assertEquals(SendFingerprint.of(DRAFT), SendFingerprint.of(DRAFT))
        assertNotEquals(SendFingerprint.of(DRAFT), SendFingerprint.of("另一条草稿"))
        assertEquals(16, SendFingerprint.of(DRAFT).length)
    }

    // ── 内存对账表 ──

    @Test
    fun ledger_recordsPerContact_boundedAndNewestFirst() {
        val ledger = AutoSendLedger(cap = 3)
        repeat(5) { round ->
            ledger.record(contactId = 1L, fingerprint = "fp$round", reason = "auto_sent:fp$round", at = round.toLong())
        }
        ledger.record(2L, "fp-other", "auto_sent:fp-other", 9L)
        val recent = ledger.recent(1L)
        assertEquals(3, recent.size)
        assertEquals("fp4", recent.first().fingerprint)
        assertEquals("fp2", recent.last().fingerprint)
        assertEquals(1, ledger.recent(2L).size)
        assertTrue(ledger.recent(3L).isEmpty())
    }
}
