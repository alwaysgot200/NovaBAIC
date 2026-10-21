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
 * [SendableDraftExtractor] 三级兜底链纯函数：主路径 reply 标记 / 编号建议 /
 * 引言首句句界截断，source 码随产物落台账（托管方案 §四.A.1）。
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
    fun replyTag_unclosed_fallsBackToNumbered() {
        val full = "先安抚再推进。\n1. 收到，我看看。\n2. 稍等，马上回。\n<reply>收到，我看看。"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("收到，我看看。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_NUMBERED, draft.source)
    }

    @Test
    fun noTag_numberedSuggestions_takeFirst() {
        val full = "两个思路：\n1. 先问进度，别催太紧。\n2. 直接提下周计划。"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("先问进度，别催太紧。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_NUMBERED, draft.source)
    }

    @Test
    fun noTag_plainPreamble_firstSentenceTruncated() {
        val full = "对方语气客气。可以直接答应时间。"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("对方语气客气。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_TRUNCATED, draft.source)
    }

    @Test
    fun noTag_noSentenceBreak_boundedByTargetChars() {
        val long = "无句界超长样例".repeat(12) // 84 字无句界
        val draft = SendableDraftExtractor.extract(long, 10)!!
        // 长度档 10 被下限 60 托底：截断到 60，硬切不腰斩句界。
        assertEquals(SendableDraftExtractor.SOURCE_TRUNCATED, draft.source)
        assertEquals(60, draft.text.length)
    }

    @Test
    fun tinyTarget_floorAtMinFallbackChars() {
        val withBreak = "第一句很短。第二句也很短。"
        val draft = SendableDraftExtractor.extract(withBreak, 5)!!
        // 下限 60 > 全文长度：截断不生效，仍取首句。
        assertEquals("第一句很短。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_TRUNCATED, draft.source)
    }

    @Test
    fun orphanCloseTag_doesNotPolluteFallback() {
        val full = "直接说结论：\n1. 好，就这么办。\n2. 再想想。</reply>"
        val draft = SendableDraftExtractor.extract(full, 260)!!
        assertEquals("好，就这么办。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_NUMBERED, draft.source)
    }
}
