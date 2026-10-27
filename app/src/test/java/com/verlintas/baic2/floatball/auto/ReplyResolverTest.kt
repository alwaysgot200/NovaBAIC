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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReplyResolver] v8 唯一回复出口：标记优先、零成本；抽取失败才调用一次
 * 代言者修复；allowCompose=false 时绝不额外调用；任何路径都不回落分析稿。
 */
class ReplyResolverTest {

    private class FakeComposer(private val result: SendableDraft?) : ReplyComposeSource {

        var calls = 0
        var lastIncoming: String? = null
        var rewriteCalls = 0
        var lastDraft: String? = null
        var lastConstraints: String? = null
        var rewriteResult: SendableDraft? = null

        override suspend fun compose(
            incoming: String?,
            analysis: String?,
            recentSent: List<String>,
            answerTargetChars: Int,
        ): SendableDraft? {
            calls++
            lastIncoming = incoming
            return result
        }

        override suspend fun rewrite(
            draft: String,
            incoming: String?,
            constraints: String,
            answerTargetChars: Int,
        ): SendableDraft? {
            rewriteCalls++
            lastDraft = draft
            lastConstraints = constraints
            return rewriteResult
        }
    }

    @Test
    fun taggedReply_shortCircuitsWithoutCompose() = runBlocking {
        val composer = FakeComposer(SendableDraft("不该用到", SendableDraftExtractor.SOURCE_PLAIN))
        val resolver = ReplyResolver(composer)

        val draft = resolver.resolve(
            full = "分析略。\n<reply>明天下午三点见。</reply>",
            answerTargetChars = 260,
        )!!

        assertEquals("明天下午三点见。", draft.text)
        assertEquals(SendableDraftExtractor.SOURCE_REPLY_TAG, draft.source)
        assertEquals(0, composer.calls)
    }

    @Test
    fun analysisOnly_composesOnce_andForcesComposedSource() = runBlocking {
        val composer = FakeComposer(
            SendableDraft("好的，我晚点回你。", SendableDraftExtractor.SOURCE_PLAIN),
        )
        val resolver = ReplyResolver(composer)

        val draft = resolver.resolve(
            full = "对方语气客气，建议你确认一下时间。",
            answerTargetChars = 260,
            incoming = "明天有空吗？",
            recentSent = listOf("在忙，稍后回。"),
        )!!

        assertEquals("好的，我晚点回你。", draft.text)
        // 无论修复调用内部给什么 source，出口统一标记为 composed。
        assertEquals(SendableDraftExtractor.SOURCE_COMPOSED, draft.source)
        assertEquals(1, composer.calls)
        assertEquals("明天有空吗？", composer.lastIncoming)
    }

    @Test
    fun composerReturnsNull_resolverReturnsNull() = runBlocking {
        val composer = FakeComposer(null)
        val resolver = ReplyResolver(composer)

        assertNull(
            resolver.resolve(full = "对方在等答复。", answerTargetChars = 260),
        )
        assertEquals(1, composer.calls)
    }

    @Test
    fun allowComposeFalse_neverCallsComposer() = runBlocking {
        val composer = FakeComposer(SendableDraft("好的。", SendableDraftExtractor.SOURCE_PLAIN))
        val resolver = ReplyResolver(composer)

        assertNull(
            resolver.resolve(
                full = "对方在等答复。",
                answerTargetChars = 260,
                allowCompose = false,
            ),
        )
        assertEquals(0, composer.calls)
    }

    @Test
    fun rewriteForGate_forwardsDraftAndConstraints() = runBlocking {
        val composer = FakeComposer(null).apply {
            rewriteResult = SendableDraft(
                "好的，我整理好就发您过目。",
                SendableDraftExtractor.SOURCE_COMPOSED,
            )
        }
        val resolver = ReplyResolver(composer)

        val draft = resolver.rewriteForGate(
            draft = "明天工作时间发您过目。",
            incoming = "很好",
            constraints = "不要出现时间承诺词",
            answerTargetChars = 260,
        )!!

        assertEquals("好的，我整理好就发您过目。", draft.text)
        assertEquals(1, composer.rewriteCalls)
        assertEquals("明天工作时间发您过目。", composer.lastDraft)
        assertEquals("不要出现时间承诺词", composer.lastConstraints)
    }

    @Test
    fun rewriteForGate_composerReturnsNull_passesThroughNull() = runBlocking {
        val composer = FakeComposer(null)
        val resolver = ReplyResolver(composer)

        assertNull(
            resolver.rewriteForGate("明天发您。", "很好", "不要承诺期限", 260),
        )
        assertEquals(1, composer.rewriteCalls)
    }

    @Test
    fun preview_neverCallsComposer() {
        val composer = FakeComposer(SendableDraft("好的。", SendableDraftExtractor.SOURCE_PLAIN))
        val resolver = ReplyResolver(composer)

        assertNull(resolver.preview("对方在等答复。", 260))
        val tagged = resolver.preview("<reply>收到。</reply>", 260)!!
        assertTrue(tagged.text == "收到。")
        assertEquals(0, composer.calls)
    }
}
