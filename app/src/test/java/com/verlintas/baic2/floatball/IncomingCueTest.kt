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

package com.verlintas.baic2.floatball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCueTest {

    @Test
    fun extractsLatestBubbleFromA11yChatDump() {
        val screen = listOf(
            "小夏",
            "14:32",
            "周末有个乐队来这边演出",
            "你看到了吗，要不要一起去",
            "我",
            "发送",
        ).joinToString("\n")

        val cue = IncomingCue.latestIncoming(screen)
        // 自底向上跳过「发送」「我」，取末尾连续两条消息行，顺序保持自上而下。
        assertEquals("周末有个乐队来这边演出 你看到了吗，要不要一起去", cue)
    }

    @Test
    fun skipsStickerPlaceholderAndFindsPriorText() {
        val screen = listOf(
            "昨天 21:08",
            "那家 livehouse 的票你还要不要",
            "要的话我帮你一起买",
            "[动画表情]",
            "按住说话",
        ).joinToString("\n")

        assertEquals(
            "那家 livehouse 的票你还要不要 要的话我帮你一起买",
            IncomingCue.latestIncoming(screen),
        )
    }

    @Test
    fun skipsTrailingChromeOnOcrStyleBody() {
        val screen = listOf(
            "星期五 18:22",
            "这个方案你们内部评审过了吗",
            "报价单我明天发你",
            "发送",
            "更多",
        ).joinToString("\n")

        assertEquals("这个方案你们内部评审过了吗 报价单我明天发你", IncomingCue.latestIncoming(screen))
    }

    @Test
    fun skipsSogouImeToolbarInsteadOfTakingItAsIncoming() {
        // 2026-10-27 真机：发完消息键盘未收起，Sogou 工具条「随写/中英/123」
        // 被 OCR 读进正文，末尾两行被当成对方来信（「中英 123」）。
        val screen = listOf(
            "【屏幕内容】",
            "凌晨2:13",
            "您之前提的滑点和时间戳对齐,我",
            "已经补完了,测数据跟基线的对比",
            "细节。",
            "自您之前提的滑点和时间戳对",
            "随写",
            "中英",
            "123",
        ).joinToString("\n")

        assertEquals(
            "细节。 自您之前提的滑点和时间戳对",
            IncomingCue.latestIncoming(screen),
        )
    }

    @Test
    fun skipsPinyinCandidateLines() {
        // 2026-10-27 真机消息 id=119：Sogou 拼音候选「kě,kè」被当成来信。
        val screen = listOf(
            "上午10:46",
            "很好,你干得很不错,加油",
            "()可",
            "(B发送",
            "kě,kè",
            "可丁可以 可打行啊〉",
            "随写",
            "中英",
            "123",
        ).joinToString("\n")

        assertEquals(
            "(B发送 可丁可以 可打行啊〉",
            IncomingCue.latestIncoming(screen),
        )
    }

    @Test
    fun keepsRealShortReplyAfterOurBubble() {
        // 我方气泡末行在上一行、对方新短句在最后一行：候选含两段，最后一段是真来信。
        val screen = listOf(
            "凌晨2:18",
            "省得后面来回对细节。",
            "很好",
        ).joinToString("\n")

        assertEquals("省得后面来回对细节。 很好", IncomingCue.latestIncoming(screen))
    }

    @Test
    fun returnsNullForArticleScreenWithoutChatMarkers() {
        val article = listOf(
            "英伟达发布新一代消费级芯片",
            "算力提升百分之四十，功耗下降三成",
            "首批合作厂商名单将于下月公布，国内厂商占一半席位",
        ).joinToString("\n")

        assertNull(IncomingCue.latestIncoming(article))
    }

    @Test
    fun returnsNullForBlankAndUnrelatedUi() {
        assertNull(IncomingCue.latestIncoming(""))
        assertNull(IncomingCue.latestIncoming("\n  \n\t"))
        val settings = listOf(
            "设置",
            "新消息通知",
            "隐私",
            "通用",
        ).joinToString("\n")
        assertNull(IncomingCue.latestIncoming(settings))
    }

    @Test
    fun returnsNullWhenChatHasNoTextBubble() {
        val screen = listOf(
            "10:15",
            "[图片]",
            "[语音]",
            "发送",
        ).joinToString("\n")
        assertNull(IncomingCue.latestIncoming(screen))
    }

    @Test
    fun recallQueryUsesIncomingAndAppendsGoal() {
        val screen = listOf(
            "昨天 14:30",
            "这周末有空一起吃饭吗",
            "发送",
        ).joinToString("\n")

        val query = IncomingCue.recallQuery(screen, goal = "约对方出来吃饭约会", maxChars = 600)
        assertTrue(query.startsWith("这周末有空一起吃饭吗"))
        assertTrue(query.endsWith("约对方出来吃饭约会"))
    }

    @Test
    fun recallQueryFallsBackToFullScreenOutsideChat() {
        val article = "某厂商发布新品，主打端侧大模型能力，续航提升两成"
        assertEquals(article, IncomingCue.recallQuery(article, goal = null, maxChars = 600))
    }

    @Test
    fun recallQueryRespectsCharBudget() {
        val screen = listOf(
            "14:32",
            "这是一条特别特别长的消息内容大概有二十二个字",
            "另外一条也挺长的消息内容大概也是二十来个字",
            "发送",
        ).joinToString("\n")
        val query = IncomingCue.recallQuery(screen, goal = null, maxChars = 30)
        assertEquals(30, query.length)
    }

    @Test
    fun speakerOfReturnsSoleCounterpartFromChatStructure() {
        val screen = listOf(
            "李总：我们这边基础版28万能做吗？",
            "我：李总，基础版给您28万，不含运维",
            "李总：行，那这两天把28万的合同发我",
            "李总：对了运维单独算",
        ).joinToString("\n")

        assertEquals("李总", IncomingCue.speakerOf(screen))
    }

    @Test
    fun speakerOfReturnsNullForNewsPageWithoutDialogue() {
        val news = listOf(
            "网页视图",
            "未连接到互联网",
            "打开主页",
            "news.baidu.com",
            "查看 15 个标签页",
        ).joinToString("\n")

        assertNull(IncomingCue.speakerOf(news))
    }

    @Test
    fun speakerOfReturnsNullWithoutSelfLine() {
        val screen = listOf(
            "李总：基础版多少钱",
            "李总：在吗",
        ).joinToString("\n")

        assertNull(IncomingCue.speakerOf(screen))
    }

    @Test
    fun speakerOfReturnsNullForGroupChatWithMultipleSpeakers() {
        val screen = listOf(
            "我：大家好",
            "李总：你好",
            "王总：你好",
        ).joinToString("\n")

        assertNull(IncomingCue.speakerOf(screen))
    }

    @Test
    fun speakerOfRequiresCounterpartToAppearTwice() {
        val screen = listOf(
            "李总：在吗",
            "我：在的",
        ).joinToString("\n")

        assertNull(IncomingCue.speakerOf(screen))
    }

    @Test
    fun speakerOfAcceptsHalfWidthColon() {
        val screen = listOf(
            "李总: 我们这边基础版28万能做吗",
            "我: 基础版给您28万，不含运维",
            "李总: 对了运维单独算",
        ).joinToString("\n")

        assertEquals("李总", IncomingCue.speakerOf(screen))
    }

    @Test
    fun speakerOfSkipsUrlBarLineWithPort() {
        val screen = listOf(
            "李总",
            "李总：我们这边基础版28万能做吗？",
            "我：李总，基础版给您28万，不含运维",
            "李总：行，那这两天把28万的合同发我",
            "未连接到互联网",
            "localhost:8765/li.html",
            "打开主页",
            "查看 16 个标签页",
        ).joinToString("\n")

        assertEquals("李总", IncomingCue.speakerOf(screen))
    }

    @Test
    fun speakerOfSkipsTimeDividerLines() {
        val screen = listOf(
            "14:32",
            "李总：在吗",
            "我：在的，您说",
            "李总：合同今天能发吗",
        ).joinToString("\n")

        assertEquals("李总", IncomingCue.speakerOf(screen))
    }
}
