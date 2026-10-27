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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SendableDraftExtractor] v8 纯函数：只接受 `<reply>` 标记 / 编号建议两种
 * 明确可发送形态，其余返回 null 交「代言者修复调用」或降级；source 码随产物
 * 落台账（托管方案 §四.A.1 / v8 §二十四）。
 */
class SendableDraftExtractorTest {

    @Test
    fun nullAndEmpty_returnNull() {
        assertNull(SendableDraftExtractor.extract(null, 260))
        assertNull(SendableDraftExtractor.extract("", 260))
        assertNull(SendableDraftExtractor.extract("   \n  ", 260))
    }

    @Test
    fun replyTag_complete_takesInner() {
        val full = "对方语气挺松快，适合顺势确认。\n" +
            "1. 好啊，那就这么定。\n2. 行，等你消息。\n" +
            "<reply>好啊，那就这么定。</reply>"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("好啊，那就这么定。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_REPLY_TAG, draft.source)
    }

    @Test
    fun replyTag_multiline_innerTrimmed() {
        val full = "前文分析\n<reply>\n行，下午两点电话联系。\n</reply>"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("行，下午两点电话联系。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_REPLY_TAG, draft.source)
    }

    @Test
    fun replyTag_emptyInner_fallsBackToNumbered() {
        val full = "1. 先问进度\n2. 再提方案\n<reply>   </reply>"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("先问进度", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_NUMBERED, draft.source)
    }

    @Test
    fun replyTag_unclosed_takesTail() {
        val full = "先安抚再推进。\n1. 收到，我看看。\n2. 稍等，马上回。\n<reply>收到，我看看。"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("收到，我看看。", draft.text)
        // v8：缺闭标记时开标记之后的剩余文本优先于编号建议。
        assertEquals(SendableDraftExtractor.SOURCE_REPLY_UNCLOSED, draft.source)
    }

    @Test
    fun noTag_numberedSuggestions_takeFirst() {
        val full = "两个思路：\n1. 先问进度，别催太紧。\n2. 直接提下周计划。"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("先问进度，别催太紧。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_NUMBERED, draft.source)
    }

    @Test
    fun noTag_plainPreamble_returnsNull() {
        // v8：分析稿首句不再当回复，交代言者修复调用/降级。
        val full = "对方语气客气。可以直接答应时间。"
        assertNull(SendableDraftExtractor.extract(full, 260))
    }

    @Test
    fun noTag_noSentenceBreak_returnsNull() {
        val long = "无句界超长样例".repeat(12) // 84 字无句界
        assertNull(SendableDraftExtractor.extract(long, 10))
    }

    @Test
    fun tinyTarget_plainText_returnsNull() {
        val withBreak = "第一句很短。第二句也很短。"
        assertNull(SendableDraftExtractor.extract(withBreak, 5))
    }

    @Test
    fun orphanCloseTag_doesNotPolluteFallback() {
        val full = "直接说结论：\n1. 好，就这么办。\n2. 再想想。</reply>"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("好，就这么办。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_NUMBERED, draft.source)
    }

    @Test
    fun replyTag_caseInsensitive() {
        val draft = SendableDraftExtractor.extract(
            "分析略。\n<REPLY>收到，我马上看。</Reply>",
            260,
        )!!
        assertEquals("收到，我马上看。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_REPLY_TAG, draft.source)
    }

    @Test
    fun replyTag_fullWidthAndSpaced() {
        val draft = SendableDraftExtractor.extract(
            "分析略。\n＜ reply ＞收到，我马上看。＜ ／ reply ＞",
            260,
        )!!
        assertEquals("收到，我马上看。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_REPLY_TAG, draft.source)
    }

    @Test
    fun replyTag_xmlEscaped() {
        val draft = SendableDraftExtractor.extract(
            "分析略。\n&lt;reply&gt;收到，我马上看。&lt;/reply&gt;",
            260,
        )!!
        assertEquals("收到，我马上看。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_REPLY_TAG, draft.source)
    }

    @Test
    fun replyTag_codeFenceInsideBlockStripped() {
        val draft = SendableDraftExtractor.extract(
            "分析略。\n<reply>\n```\n好的，明天见。\n```\n</reply>",
            260,
        )!!
        assertEquals("好的，明天见。", draft.text)
    }

    @Test
    fun replyTag_multipleBlocks_takesLast() {
        val full = "<reply>第一版。</reply>\n再想想。\n<reply>第二版，明天下午见。</reply>"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("第二版，明天下午见。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_REPLY_TAG, draft.source)
    }

    @Test
    fun replyTag_analysisInside_rejected() {
        // 标记不是免死金牌：块内是分析/建议照样拒绝。
        val full = "<reply>建议你这样回复：好的，明天见。</reply>"
        assertNull(SendableDraftExtractor.extract(full, 260))
    }

    @Test
    fun replyTag_placeholder_rejected() {
        assertNull(SendableDraftExtractor.extract("<reply>回复正文</reply>", 260))
    }

    @Test
    fun extractPlain_shortReply_accepted() {
        val draft = SendableDraftExtractor.extractPlain("好的，明天下午三点见。", 260)!!
        assertEquals("好的，明天下午三点见。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_PLAIN, draft.source)
    }

    @Test
    fun extractPlain_analysisOrList_rejected() {
        assertNull(SendableDraftExtractor.extractPlain("建议你这样回复：好的。", 260))
        assertNull(SendableDraftExtractor.extractPlain("1. 先问进度\n2. 再提方案", 260))
        assertNull(SendableDraftExtractor.extractPlain("嗯".repeat(500), 260))
        assertNull(
            SendableDraftExtractor.extractPlain(
                "第一行\n第二行\n第三行\n第四行",
                260,
            ),
        )
    }
}
