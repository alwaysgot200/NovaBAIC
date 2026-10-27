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

    // ── 运行期降级链（2026-10-27 真机修复）：auto 档 A 失败且确定未发出时回落 B ──

    @Test
    fun plan_auto_bothReady_walksAThenB() {
        assertEquals(
            listOf(InjectorChannel.A11y, InjectorChannel.Shizuku),
            InjectorSelector.plan(InjectorSelector.CONFIG_AUTO, a11yReady = true, shizukuReady = true),
        )
    }

    @Test
    fun plan_auto_singleReady_yieldsSingleCandidate() {
        assertEquals(
            listOf(InjectorChannel.Shizuku),
            InjectorSelector.plan(InjectorSelector.CONFIG_AUTO, a11yReady = false, shizukuReady = true),
        )
        // 双通道都不可用：空计划（调用方直接降 C 起草档，不虚构候选）。
        assertEquals(
            emptyList<InjectorChannel>(),
            InjectorSelector.plan(InjectorSelector.CONFIG_AUTO, a11yReady = false, shizukuReady = false),
        )
    }

    @Test
    fun plan_forcedChannelDown_isEmpty_neverSwitches() {
        assertEquals(
            emptyList<InjectorChannel>(),
            InjectorSelector.plan(InjectorSelector.CONFIG_A11Y, a11yReady = false, shizukuReady = true),
        )
        assertEquals(
            emptyList<InjectorChannel>(),
            InjectorSelector.plan(InjectorSelector.CONFIG_SHIZUKU, a11yReady = true, shizukuReady = false),
        )
    }

    @Test
    fun plan_invalidConfig_behavesAsAuto() {
        assertEquals(
            listOf(InjectorChannel.A11y, InjectorChannel.Shizuku),
            InjectorSelector.plan("garbage", a11yReady = true, shizukuReady = true),
        )
    }

    // ── 换道安全闸：只有「确定未发出」的失败才允许换通道重跑 ──

    @Test
    fun safeToRetryElsewhere_definiteFailuresAllowed_includingDetailedForm() {
        listOf(
            SendSequence.REASON_A11Y_DISCONNECTED,
            SendSequence.REASON_WRONG_FOREGROUND,
            SendSequence.REASON_INPUT_FAILED,
            SendSequence.REASON_SEND_NOT_FOUND,
            SendSequence.REASON_SEND_TAP_FAILED,
        ).forEach { assertTrue(it, SendSequence.safeToRetryElsewhere(it)) }
        // 台账带明细形态（`原因码:明细`）同样按前缀命中。
        assertTrue(
            SendSequence.safeToRetryElsewhere(
                "${SendSequence.REASON_INPUT_FAILED}:IllegalStateException_rejected",
            ),
        )
        assertTrue(
            SendSequence.safeToRetryElsewhere("${SendSequence.REASON_SEND_TAP_FAILED}:proof_not_sent"),
        )
    }

    @Test
    fun safeToRetryElsewhere_uncertainOrUnknown_blocked() {
        // 证据不足 = 可能已发出，宁可降级起草也绝不赌第二枪（防重发）。
        assertFalse(SendSequence.safeToRetryElsewhere(SendSequence.REASON_SEND_NOT_VERIFIED))
        assertFalse(SendSequence.safeToRetryElsewhere(null))
        assertFalse(SendSequence.safeToRetryElsewhere("dispatch_error"))
        assertFalse(SendSequence.safeToRetryElsewhere("im_nav_failed:not_found"))
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

    /** fake 三态证据源：按队列出结果，并记录每次取证的点击位置。 */
    private class FakeProofFinder(private val results: ArrayDeque<SendProof>) : SendProofFinder {
        val calls = mutableListOf<Pair<List<String>, SendTarget>>()

        override suspend fun proof(clips: List<String>, tapped: SendTarget): SendProof {
            calls += clips to tapped
            return results.removeFirstOrNull() ?: SendProof.UNKNOWN
        }
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

    // ── OCR 输入栏锚点（模式 B 注入前聚焦，先切键盘再点输入框） ──

    @Test
    fun pickInputAnchor_voiceBar_needsKeyboardToggle() {
        val lines = listOf(
            OcrLine("按住 说话", 300, 2280, 780, 2328),
            OcrLine("在么？？？", 200, 1500, 480, 1550),
        )
        val anchor = OcrSendLocator.pickInputAnchor(lines, 2460)
        assertTrue(anchor.voice)
        assertEquals(2304, anchor.centerY)
    }

    @Test
    fun pickInputAnchor_textMode_usesSendRow() {
        val lines = listOf(
            OcrLine("发送", 950, 2280, 1030, 2330),
            OcrLine("按住 说话", 300, 300, 780, 350),
        )
        val anchor = OcrSendLocator.pickInputAnchor(lines, 2460)
        assertFalse(anchor.voice)
        assertEquals(2305, anchor.centerY)
    }

    @Test
    fun pickInputAnchor_emptyInput_fallsBackToRatio() {
        val anchor = OcrSendLocator.pickInputAnchor(emptyList(), 2460)
        assertFalse(anchor.voice)
        // 0.936 × 2460 = 2302.56 → 2302（真机输入栏实测中线 y≈2304）
        assertEquals(2302, anchor.centerY)
    }

    // ── OCR 发送后回读（跨行拼接 + 空白/标点/大小写归一化） ──

    @Test
    fun frameContains_joinsSplitOcrLines() {
        val lines = listOf(
            OcrLine("吴总，自动炒股 v", 100, 800, 900, 850),
            OcrLine("1 我这两天在做质量", 100, 860, 900, 910),
        )
        assertTrue(OcrSendLocator.frameContains(lines, "吴总，自动炒股v1"))
        assertTrue(OcrSendLocator.frameContains(lines, "自动炒股 v1 我这两"))
    }

    @Test
    fun frameContains_ignoresPunctuationAndCase() {
        val lines = listOf(OcrLine("V1 版 已 发 布", 100, 800, 900, 850))
        assertTrue(OcrSendLocator.frameContains(lines, "v1版已发布"))
    }

    @Test
    fun frameContains_missOrBlank_false() {
        val lines = listOf(OcrLine("吴总，自动炒股 v1", 100, 800, 900, 850))
        assertFalse(OcrSendLocator.frameContains(lines, "架构评审"))
        assertFalse(OcrSendLocator.frameContains(lines, "   "))
        assertFalse(OcrSendLocator.frameContains(emptyList(), "吴总"))
    }

    // ── 三态发送证据（2026-10-27 auto_sent 假成功修复） ──

    @Test
    fun proofNotSent_relocatesAndRetries() = runBlocking {
        val injector = FakeInjector(
            tapResults = listOf(Result.success(Unit), Result.success(Unit)),
        )
        val locator = FakeLocator(ArrayDeque(listOf(SendTarget(650, 1200), SendTarget(660, 1210))))
        val proof = FakeProofFinder(ArrayDeque(listOf(SendProof.NOT_SENT, SendProof.SENT)))
        val reason = SendSequence.run(
            injector, locator, IM_PKG, IM_PKG, DRAFT, proofFinder = proof,
        )
        assertNull(reason)
        assertEquals(listOf(650 to 1200, 660 to 1210), injector.taps)
        assertEquals(2, proof.calls.size)
        assertEquals(SendTarget(660, 1210), proof.calls.last().second)
    }

    @Test
    fun proofUnknown_degradesWithoutRetap() = runBlocking {
        val injector = FakeInjector()
        val locator = FakeLocator(ArrayDeque(listOf(SendTarget(650, 1200))))
        val proof = FakeProofFinder(ArrayDeque(listOf(SendProof.UNKNOWN)))
        val reason = SendSequence.run(
            injector, locator, IM_PKG, IM_PKG, DRAFT, proofFinder = proof,
        )
        assertEquals(SendSequence.REASON_SEND_NOT_VERIFIED, reason)
        assertEquals(1, injector.taps.size)
        assertEquals(1, injector.cleared)
    }

    @Test
    fun proofStillNotSent_givesUpAfterSecondTap() = runBlocking {
        val injector = FakeInjector(
            tapResults = listOf(Result.success(Unit), Result.success(Unit)),
        )
        val locator = FakeLocator(ArrayDeque(listOf(SendTarget(650, 1200), SendTarget(660, 1210))))
        val proof = FakeProofFinder(ArrayDeque(listOf(SendProof.NOT_SENT, SendProof.NOT_SENT)))
        val reason = SendSequence.run(
            injector, locator, IM_PKG, IM_PKG, DRAFT, proofFinder = proof,
        )
        // 强证据「按钮还在」第二次仍判未发出：原因码带 `proof_not_sent` 明细。
        assertTrue(reason!!.startsWith(SendSequence.REASON_SEND_TAP_FAILED))
        assertEquals(2, injector.taps.size)
        assertEquals(1, injector.cleared)
    }

    // ── 像素级绿色发送键定位（品牌绿 #07C160；我方气泡 #95EC69 必须排除） ──

    /** 在 ARGB 帧上填充若干矩形：[left, top, right, bottom, color]。 */
    private fun framePixels(width: Int, height: Int, blocks: List<IntArray>): IntArray {
        val pixels = IntArray(width * height)
        for (block in blocks) {
            val left = block[0]
            val top = block[1]
            val right = block[2]
            val bottom = block[3]
            val color = block[4]
            for (y in top until bottom) {
                val row = y * width
                for (x in left until right) pixels[row + x] = color
            }
        }
        return pixels
    }

    @Test
    fun findGreenSend_locatesWechatButton() {
        val pixels = framePixels(
            1080,
            2460,
            listOf(
                intArrayOf(917, 1021, 1051, 1106, 0xFF07C160.toInt()),
                // 我方绿色气泡（#95EC69）虽在右半，但颜色不符，必须忽略。
                intArrayOf(300, 800, 800, 950, 0xFF95EC69.toInt()),
            ),
        )
        val target = OcrSendLocator.findGreenSend(pixels, 1080, 2460)
        assertEquals(985, target?.centerX)
        assertEquals(1064, target?.centerY)
        assertEquals(134, target?.width)
        assertEquals(84, target?.height)
    }

    @Test
    fun findGreenSend_ignoresImeGreenKeyAtBottom() {
        val pixels = framePixels(
            1080,
            2460,
            listOf(intArrayOf(917, 2250, 1051, 2350, 0xFF07C160.toInt())),
        )
        // 键盘展开：底部同色键属 Sogou「发送/回车」，禁止第二段扫描（2026-10-27）。
        assertNull(OcrSendLocator.findGreenSend(pixels, 1080, 2460, imeOpen = true))
    }

    @Test
    fun findGreenSend_findsBottomButtonWhenImeClosed() {
        // 键盘收起：微信绿色发送键贴底部输入栏（~0.93H），旧单段扫描恒 null
        // → send_button_not_found（真机 12:16/12:17）。第二段扫描必须命中。
        val pixels = framePixels(
            1080,
            2460,
            listOf(intArrayOf(917, 2250, 1051, 2350, 0xFF07C160.toInt())),
        )
        val target = OcrSendLocator.findGreenSend(pixels, 1080, 2460, imeOpen = false)
        assertEquals(985, target?.centerX)
        assertEquals(2300, target?.centerY)
    }

    @Test
    fun findGreenSend_emptyFrame_null() {
        assertNull(OcrSendLocator.findGreenSend(IntArray(0), 0, 0))
    }

    // ── OCR 发送候选：带帧高时优先键盘上方（IME 键盘发送键在下方） ──

    @Test
    fun pickSend_frameHeight_prefersAboveIme() {
        val lines = listOf(
            OcrLine("发送", 920, 1020, 1050, 1105),
            OcrLine("发送", 920, 2270, 1050, 2330),
        )
        val above = OcrSendLocator.pickSend(lines, 2460)
        assertEquals(1062, above?.centerY)
        // 无帧高（既有口径）：仍取最靠下者。
        assertEquals(2300, OcrSendLocator.pickSend(lines)?.centerY)
    }

    // ── 聚焦锚点：键盘打开时绝不点底部比例位（防 Sogou「随写」误触） ──

    @Test
    fun pickFocusAnchor_keyboardOpenSendRow_avoidsIme() {
        val lines = listOf(
            OcrLine("发送", 917, 1021, 1051, 1106),
            OcrLine("符", 60, 2250, 120, 2310),
        )
        val anchor = OcrSendLocator.pickFocusAnchor(lines, 2460)
        assertFalse(anchor.voice)
        assertEquals(1063, anchor.centerY)
    }

    @Test
    fun pickFocusAnchor_keyboardOpenEmpty_usesKeyboardOpenRow() {
        val lines = listOf(OcrLine("随写", 20, 2210, 200, 2270))
        val anchor = OcrSendLocator.pickFocusAnchor(lines, 2460)
        assertFalse(anchor.voice)
        // 0.438 × 2460 = 1077（键盘打开时微信输入栏上移后的中线）
        assertEquals(1077, anchor.centerY)
    }

    @Test
    fun pickFocusAnchor_imeWordInChatBody_ignored() {
        val lines = listOf(OcrLine("随写", 20, 300, 200, 360))
        assertEquals(2302, OcrSendLocator.pickFocusAnchor(lines, 2460).centerY)
    }

    @Test
    fun pickFocusAnchor_voiceBar_bottomWins() {
        val lines = listOf(OcrLine("按住 说话", 300, 2280, 780, 2328))
        val anchor = OcrSendLocator.pickFocusAnchor(lines, 2460)
        assertTrue(anchor.voice)
        assertEquals(2304, anchor.centerY)
    }

    // ── 自回声判定（我方气泡回流不回复） ──

    @Test
    fun replyEcho_selfBubbleDetected() {
        val sent = listOf("吴总，自动炒股 v1 的量化模块已经跑通，今天想跟您同步下进度")
        val incoming = "吴总 自动炒股 v1 的量化模块已经跑通 今天想跟您同步下进度"
        assertTrue(ReplyEchoGuard.isSelfEcho(incoming, sent))
    }

    @Test
    fun replyEcho_ocrDropsLatinAndPunctuation() {
        val sent = listOf("吴总，v1 版本已经发布。")
        assertTrue(ReplyEchoGuard.isSelfEcho("吴总 v 版本已经发布", sent))
    }

    @Test
    fun replyEcho_realIncomingNotEcho() {
        val sent = listOf("吴总，自动炒股 v1 已经发布")
        assertFalse(ReplyEchoGuard.isSelfEcho("这才几天就发版了？质量能保证吗", sent))
    }

    @Test
    fun replyEcho_shortIncomingIgnored() {
        val sent = listOf("好的收到我马上处理这个问题")
        assertFalse(ReplyEchoGuard.isSelfEcho("好的收到", sent))
    }

    @Test
    fun replyEcho_ocrSplitAndImeNoise_detectedByCoverage() {
        // 2026-10-27 真机：OCR 把整条气泡拆行，并把输入法「随写」条一起读入，
        // 首字多出「自」、标点/断行错位——前缀与整段包含都漏判，覆盖率兜住。
        val sent = listOf(
            "明白了，那我不拿技术细节烦您。我按“能不能稳住不亏”来做验收：" +
                "把滑点、时间戳对齐这些影响结果的点先补齐，再拉一段回测数据对比给您看，" +
                "用数字说话。您看这样汇报行不行？",
        )
        val incoming = "自明白了那我不拿技术细节烦您我按能不能稳住不亏来做验收把滑点时问戳" +
            "对齐这些影响结果的点先补齐再拉一段回测数据对比给您看用数字说话您看这样汇报行不行"
        assertTrue(ReplyEchoGuard.isSelfEcho(incoming, sent))
    }

    @Test
    fun replyEcho_leaderRealReply_notEcho() {
        val sent = listOf(
            "明白了，那我不拿技术细节烦您。我按“能不能稳住不亏”来做验收：" +
                "把滑点、时间戳对齐这些影响结果的点先补齐，再拉一段回测数据对比给您看。",
        )
        val incoming = "你问我？？我哪里知道这么专业，我不懂技术，你直要给我看一下结果"
        assertFalse(ReplyEchoGuard.isSelfEcho(incoming, sent))
    }

    @Test
    fun replyEcho_shortAgreementQuotingOurWords_notEcho() {
        // 对方顺着我们的话回「行，就按这个口径来」：短句即便与已发回复高度
        // 重合，也不靠覆盖率误吞（宁可多回一轮，不可漏掉真来信）。
        val sent = listOf("好，就按这个口径来。我这两天先把滑点和时间戳对齐补齐。")
        assertFalse(ReplyEchoGuard.isSelfEcho("行，就按这个口径来", sent))
    }

    @Test
    fun replyEcho_ourOldLinePlusRealShortReply_notEcho() {
        // 2026-10-27 真机事故：IncomingCue 末尾两行拼成候选——我方旧气泡末行
        // 「省得后面来回对细节。」+ 领导真实答复「很好」。整段覆盖率会误吞
        // 真来信；按段切分后只判最新一段（「很好」太短不判回声）。
        val sent = listOf(
            "滑点和时间戳对齐已经补完，测试数据和基线的对比口径也统一了。" +
                "另外想请教您一句：这轮结果出来后，团队里要不要安排一次质量复盘会，" +
                "把口径和验收标准定下来，省得后面来回对细节。",
        )
        assertFalse(
            ReplyEchoGuard.isSelfEcho("省得后面来回对细节。 很好", sent),
        )
    }

    @Test
    fun replyEcho_ourBubbleTailAfterMergedLine_detected() {
        // 同一候选里最新一段仍是我方气泡（对方没有新短句）时必须拦住。
        // 数据取自 2026-10-27 真机 msg52（02:13:55 实发）与 msg53 的 OCR 回流：
        // 「自您之前提的滑点和时间戳对」= 我方气泡末行 + 首字噪声「自」。
        val sent = listOf(
            "您之前提的滑点和时间戳对齐，我已经补完了，测数据跟基线的对比口径也统一了，" +
                "数字对照这两天整理出来给您看。另外想请教您一句：这轮结果出来后，" +
                "团队里要不要安排一次质量复盘会，把口径和验收标准定下来，省得后面来回对细节。",
        )
        assertTrue(
            ReplyEchoGuard.isSelfEcho("细节。 自您之前提的滑点和时间戳对", sent),
        )
    }

    @Test
    fun replyEcho_imeToolbarOnly_notEcho() {
        // 输入法工具条只剩非中文词时归一化为空：不判回声、不阻断真实回合。
        val sent = listOf("好，就按这个口径来。我这两天先把滑点和时间戳对齐补齐。")
        assertFalse(ReplyEchoGuard.isSelfEcho("中英 123", sent))
    }
}
