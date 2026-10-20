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

import com.verlintas.baic2.device.api.OcrLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 §17.2 三模执行面纯逻辑：通道选择降级链（配置 > A > B > C）、解耦形态的
 * 三步状态机（fake 注入器 + fake 定位器，坐标驱动）、Shizuku 注入的纯函数
 * （ASCII 判定/shell 转义/dumpsys 前台解析）、OCR 定位选择。Android 边界
 * （shell exec/剪贴板/ML Kit）不在此测，R1-7 真机验收覆盖。
 */
class InjectorSelectorTest {

    private companion object {
        const val IM_PKG = "com.tencent.mm"
        const val DRAFT = "周末愉快呀"
    }

    // ── 通道选择降级链：配置 > A > B > C ──

    @Test
    fun auto_a11yReady_picksA() {
        assertEquals(
            InjectorChannel.A11y,
            InjectorSelector.select(InjectorSelector.CONFIG_AUTO, a11yReady = true, shizukuReady = false),
        )
    }

    @Test
    fun auto_a11yDown_fallsToShizuku() {
        assertEquals(
            InjectorChannel.Shizuku,
            InjectorSelector.select(InjectorSelector.CONFIG_AUTO, a11yReady = false, shizukuReady = true),
        )
    }

    @Test
    fun auto_bothDown_fallsToDraft() {
        assertEquals(
            InjectorChannel.DraftFallback,
            InjectorSelector.select(InjectorSelector.CONFIG_AUTO, a11yReady = false, shizukuReady = false),
        )
    }

    @Test
    fun auto_bothReady_a11yWins() {
        assertEquals(
            InjectorChannel.A11y,
            InjectorSelector.select(InjectorSelector.CONFIG_AUTO, a11yReady = true, shizukuReady = true),
        )
    }

    @Test
    fun forcedChannel_ready_picked() {
        assertEquals(
            InjectorChannel.A11y,
            InjectorSelector.select(InjectorSelector.CONFIG_A11Y, a11yReady = true, shizukuReady = false),
        )
        assertEquals(
            InjectorChannel.Shizuku,
            InjectorSelector.select(InjectorSelector.CONFIG_SHIZUKU, a11yReady = true, shizukuReady = true),
        )
    }

    @Test
    fun forcedChannel_down_neverSilentlySwitches() {
        // 用户指定即强制：a11y 不可用即使 shizuku 在线也不借道 B。
        assertEquals(
            InjectorChannel.DraftFallback,
            InjectorSelector.select(InjectorSelector.CONFIG_A11Y, a11yReady = false, shizukuReady = true),
        )
        assertEquals(
            InjectorChannel.DraftFallback,
            InjectorSelector.select(InjectorSelector.CONFIG_SHIZUKU, a11yReady = true, shizukuReady = false),
        )
    }

    @Test
    fun invalidConfig_behavesAsAuto() {
        assertEquals(
            InjectorChannel.Shizuku,
            InjectorSelector.select("garbage", a11yReady = false, shizukuReady = true),
        )
    }

    // ── 解耦形态三步状态机：坐标驱动 + 通道不可用短路 ──

    /** fake 注入器：记录注入/点击/清空，结果按队列出。 */
    private class FakeInjector(
        available: Boolean = true,
        injectResults: List<Result<Unit>> = listOf(Result.success(Unit)),
        tapResults: List<Result<Unit>> = listOf(Result.success(Unit)),
    ) : InputInjector {
        override val channel = InjectorChannel.Shizuku
        override val available = available
        override val unavailableReason = "fake_not_ready"
        val typed = mutableListOf<String>()
        val taps = mutableListOf<Pair<Int, Int>>()
        var cleared = 0
        private val injectQueue = injectResults.toMutableList()
        private val tapQueue = tapResults.toMutableList()

        override suspend fun tap(x: Int, y: Int): Result<Unit> {
            taps += x to y
            return tapQueue.removeAt(0)
        }

        override suspend fun type(text: String): Result<Unit> {
            typed += text
            return injectQueue.removeAt(0)
        }

        override suspend fun paste(): Result<Unit> = Result.success(Unit)

        override suspend fun clearInput(): Result<Unit> {
            cleared++
            return Result.success(Unit)
        }

        override suspend fun foregroundPackage(): String? = IM_PKG
    }

    private class FakeLocator(private val targets: ArrayDeque<SendTarget?>) : SendLocator {
        override suspend fun locate(): SendTarget? = targets.removeFirstOrNull()
    }

    @Test
    fun decoupledRun_tapsLocatorCoordinates() = runBlocking {
        val injector = FakeInjector()
        val locator = FakeLocator(ArrayDeque(listOf(SendTarget(650, 1200))))
        val reason = SendSequence.run(injector, locator, IM_PKG, IM_PKG, DRAFT)
        assertNull(reason)
        assertEquals(listOf(DRAFT), injector.typed)
        assertEquals(listOf(650 to 1200), injector.taps)
    }

    @Test
    fun injectorUnavailable_shortCircuitsWithOwnReason() = runBlocking {
        val injector = FakeInjector(available = false)
        val reason = SendSequence.run(injector, FakeLocator(ArrayDeque()), IM_PKG, IM_PKG, DRAFT)
        assertEquals("fake_not_ready", reason)
        assertTrue(injector.typed.isEmpty() && injector.taps.isEmpty())
    }

    @Test
    fun foregroundUnknown_rejectsInjection() = runBlocking {
        val injector = FakeInjector()
        val reason = SendSequence.run(injector, FakeLocator(ArrayDeque()), null, IM_PKG, DRAFT)
        assertEquals(SendSequence.REASON_WRONG_FOREGROUND, reason)
        assertTrue(injector.typed.isEmpty())
    }

    @Test
    fun tapFailsTwice_clearsInputBeforeGiveUp() = runBlocking {
        val injector = FakeInjector(
            tapResults = listOf(Result.failure(IllegalStateException("x")), Result.failure(IllegalStateException("x"))),
        )
        val reason = SendSequence.run(injector, FakeLocator(ArrayDeque(listOf(SendTarget(1, 2), SendTarget(1, 2)))), IM_PKG, IM_PKG, DRAFT)
        assertEquals(SendSequence.REASON_SEND_TAP_FAILED, reason)
        assertEquals(1, injector.cleared)
    }

    // ── Shizuku 注入纯函数 ──

    @Test
    fun viaInputText_asciiNoSpaceOnly() {
        assertTrue(ShizukuInjector.viaInputText("ok"))
        assertFalse(ShizukuInjector.viaInputText("hello world"))
        assertFalse(ShizukuInjector.viaInputText(DRAFT))
        assertFalse(ShizukuInjector.viaInputText(""))
    }

    @Test
    fun shellQuote_escapesSingleQuotes() {
        assertEquals("'ok'", ShizukuInjector.shellQuote("ok"))
        assertEquals("'it'\\''s'", ShizukuInjector.shellQuote("it's"))
    }

    @Test
    fun parseResumedPackage_readsDumpsysLine() {
        val line = "    mResumedActivity: ActivityRecord{6c55ff6 u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI t1234}"
        assertEquals("com.tencent.mm", ShizukuInjector.parseResumedPackage(line))
        assertNull(ShizukuInjector.parseResumedPackage(""))
    }

    // ── OCR 定位选择（与 A11y 定位器同一启发式口径） ──

    @Test
    fun pickSend_takesBottomRightCandidate() {
        val lines = listOf(
            OcrLine("发送", 600, 900, 660, 940),
            OcrLine("发送", 600, 1180, 680, 1220),
        )
        assertEquals(SendTarget(640, 1200), OcrSendLocator.pickSend(lines))
    }

    @Test
    fun pickSend_filtersOversizedContainers() {
        val lines = listOf(OcrLine("点击发送按钮继续会话", 40, 0, 700, 500))
        assertNull(OcrSendLocator.pickSend(lines))
        assertNull(OcrSendLocator.pickSend(emptyList()))
    }
}
