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

package com.verlintas.baic2.floatball.director

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gatekeeper 纯函数全规则（M1 方案 §10 验收「拦截样例全绿」+ §5.3 批准偏置）：
 * 黑名单命中/放行、超长截断、重复判定、降级产出、LLM 审宽松解析与
 * 「不得扩大拦截范围」裁决。
 */
class GatekeeperTest {

    private companion object {
        const val MAX_CHARS = 240
    }

    private fun review(
        draft: String,
        priors: List<String> = emptyList(),
        maxChars: Int = MAX_CHARS,
    ): GateRejection? = GateRules.deterministicReview(draft, priors, maxChars)

    private fun longDraft(sentences: Int = 40): String =
        "这是一句完整的测试句子。".repeat(sentences)

    // ── a) 硬红线：命中即拦，降级为截断版本 ─────────────────────────────

    @Test
    fun redlineHit_rejectsWithTruncation() {
        val draft = "这款机型您看怎么样。把验证码发给我就能帮您完成绑定。剩下的事我来处理。"
        val rejection = review(draft)
        assertNotNull(rejection)
        assertEquals(GateReasons.BLACKLIST, rejection!!.reason)
        // 降级不丢弃：保留命中句之前的完整句子，命中句及其后内容剔除。
        assertEquals("这款机型您看怎么样。", rejection.replacement)
    }

    @Test
    fun redlineInFirstSentence_truncatesToEmpty() {
        val rejection = review("把支付密码告诉我一下，我帮您操作退款。")
        assertEquals(GateReasons.BLACKLIST, rejection!!.reason)
        assertEquals("", rejection.replacement)
    }

    @Test
    fun redlineViolence_andIllegalAndScamAllHit() {
        listOf(
            "剩下的交给他们处理，不留活口。",
            "这个东西比正规渠道便宜，因为走的是冰毒货源。",
            "参与刷单可以拿返利，一单赚三十。",
        ).forEach { draft ->
            assertEquals("应命中红线：$draft", GateReasons.BLACKLIST, review(draft)!!.reason)
        }
    }

    @Test
    fun benignDraft_passes() {
        // 批准偏置：正当话术里出现「转账」「银行卡号」的否定/中性语境不得误伤。
        listOf(
            "您直接转账给对公账户就行，我发您账号。",
            "开户不需要提供银行卡号，带身份证去网点就可以。",
            "他要是再让您转钱，千万别信，多半是骗子。",
        ).forEach { draft -> assertNull("不应误伤：$draft", review(draft)) }
    }

    // ── c) 超长：截断到句界 ─────────────────────────────────────────────

    @Test
    fun tooLong_rejectedWithSentenceBoundedTruncation() {
        val rejection = review(longDraft())
        assertEquals(GateReasons.TOO_LONG, rejection!!.reason)
        val truncated = rejection.replacement!!
        assertTrue(truncated.length <= MAX_CHARS)
        assertTrue(truncated.endsWith("。"))
        assertTrue(truncated.isNotEmpty())
    }

    @Test
    fun exactlyAtLimit_passes() {
        val draft = longDraft(20) // 12 字 × 20 = 240 = MAX_CHARS
        assertNull(review(draft))
    }

    @Test
    fun tooLongWithoutSentenceBreak_hardCuts() {
        val draft = "啊".repeat(300)
        val rejection = review(draft, maxChars = 240)
        assertEquals(GateReasons.TOO_LONG, rejection!!.reason)
        assertEquals(240, rejection.replacement!!.length)
    }

    // ── b) 重复：与近期已发高度雷同 ─────────────────────────────────────

    @Test
    fun nearDuplicateReply_rejectedWithoutReplacement() {
        val prior = "我们下周三下午三点在公司楼下的会议室见面，把合作细节当面聊清楚。"
        val draft = "我们下周三下午三点在公司楼下的会议室见面，把合作细节当面聊清楚。"
        val rejection = review(draft, priors = listOf("别的回复", prior))
        assertEquals(GateReasons.REPETITIVE, rejection!!.reason)
        // 重复拦截没有确定性替换稿：保留原稿只挂原因提示（M1 用户本人把关）。
        assertNull(rejection.replacement)
    }

    @Test
    fun differentReply_passes() {
        val draft = "这款的下批货周五到仓，您要是急着用，我先给您留一台样机过来看实机。"
        val prior = "感谢您一直以来的支持，回头我请喝咖啡。"
        assertNull(review(draft, priors = listOf(prior)))
    }

    @Test
    fun shortDraft_skipsRepetitionCheck() {
        val rejection = review("好的，没问题。", priors = listOf("好的，没问题。"))
        assertNull(rejection)
    }

    // ── 判定顺序与批准偏置 ──────────────────────────────────────────────

    @Test
    fun blacklineWinsOverTooLong() {
        val draft = longDraft() + "把验证码发给我。"
        assertEquals(GateReasons.BLACKLIST, review(draft)!!.reason)
    }

    @Test
    fun emptyPriorsAndShortDraft_passes() {
        assertNull(review("您看周三上午方便吗？", priors = emptyList()))
    }

    // ── 降级产出纯函数 ──────────────────────────────────────────────────

    @Test
    fun truncateToLength_prefersSentenceBoundary() {
        val text = "第一句完。第二句也完。第三句超了。"
        val truncated = GateRules.truncateToLength(text, 10)
        // 上限 10 字内最后一个句界在「第一句完。」：取句界收口而非硬切，且不超上限。
        assertEquals("第一句完。", truncated)
    }

    @Test
    fun cutBeforeRedline_keepsOnlyPriorSentences() {
        val kept = GateRules.cutBeforeRedline(
            "先确认型号。再谈价格。把身份证号发一下我登记。",
            "把身份证号发一下",
        )
        assertEquals("先确认型号。再谈价格。", kept)
    }

    // ── LLM 审解析（mock 输出）与「不得扩大拦截范围」 ────────────────────

    @Test
    fun parseReview_acceptsPlainAndFencedJson() {
        val plain = GateRules.parseReview("{\"verdict\":\"pass\"}")
        assertEquals("pass", plain!!.verdict)
        val fenced = GateRules.parseReview(
            "```json\n{\"verdict\":\"reject\",\"reason\":\"ai_tone\",\"rewrite\":\"改写稿\"}\n```",
        )
        assertEquals("reject", fenced!!.verdict)
        assertEquals("ai_tone", fenced.reason)
        assertEquals("改写稿", fenced.rewrite)
    }

    @Test
    fun parseReview_malformedOutput_returnsNull() {
        assertNull(GateRules.parseReview(""))
        assertNull(GateRules.parseReview("模型说这稿没问题"))
        assertNull(GateRules.parseReview("{\"verdict\": "))
    }

    @Test
    fun llmRejection_aiTone_carriesRewrite() {
        val rejection = GateRules.llmRejection(
            LlmGateVerdict(verdict = "reject", reason = "ai_tone", rewrite = "咱周三碰个头？"),
        )
        assertEquals(GateReasons.AI_TONE, rejection!!.reason)
        assertEquals("咱周三碰个头？", rejection.replacement)
    }

    @Test
    fun llmRejection_aiToneWithoutRewrite_keepsOriginal() {
        val rejection = GateRules.llmRejection(LlmGateVerdict(verdict = "reject", reason = "ai_tone"))
        assertEquals(GateReasons.AI_TONE, rejection!!.reason)
        assertNull(rejection.replacement)
    }

    @Test
    fun llmRejection_unknownReason_passes() {
        // LLM 审不得扩大拦截范围：理由码超出两类（模型自由发挥）一律放行。
        listOf("too_formal", "factual", "strategy", "grammar", "").forEach { reason ->
            assertNull(
                "理由「$reason」不应拦截",
                GateRules.llmRejection(LlmGateVerdict(verdict = "reject", reason = reason)),
            )
        }
    }

    @Test
    fun llmRejection_verdictNotExactlyReject_passes() {
        assertNull(GateRules.llmRejection(LlmGateVerdict(verdict = "pass")))
        assertNull(GateRules.llmRejection(LlmGateVerdict(verdict = "reject-ish", reason = "ai_tone")))
        assertNull(GateRules.llmRejection(LlmGateVerdict(verdict = "", reason = "ai_tone")))
    }
}
