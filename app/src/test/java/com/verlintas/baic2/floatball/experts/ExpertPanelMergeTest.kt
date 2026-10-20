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

package com.verlintas.baic2.floatball.experts

import com.verlintas.baic2.floatball.director.HandoffCategory
import com.verlintas.baic2.floatball.director.IncomingLlmVerdict
import com.verlintas.baic2.floatball.experts.privacy.PrivacyDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合议团纯合并逻辑（ExpertPanel.merge，方案 2026-10-20 隐私闸门 §3.3 + E1
 * 行为等价迁接）：词表先行、LLM 只补空隙、白名单短路、goalHit 短路顺序与
 * E1 judgeEscalation 逐字一致。LLM 结论以 [IncomingLlmVerdict] 直接注入，
 * 不触网（reviewIncoming 的网络路径由 HandoffGate 自身降级保证）。
 */
class ExpertPanelMergeTest {

    // ── 白名单快筛：短路一切专家 ──────────────────────────────────────────

    @Test
    fun whitelist_shortCircuitsAllExperts() {
        val review = ExpertPanel.merge("嗯嗯~", strongHandoff = null, llm = null)
        assertFalse(review.handoff.handoff)
        assertTrue(review.handoff.skipLlm)
        assertFalse(review.handoff.uncertain)
        assertFalse(review.privacy.hit)
        assertFalse(review.goalHit)

        // 即使 LLM 误判也必须被白名单拦下（纯应答永远不触发任何处置）。
        val noisy = ExpertPanel.merge(
            "好的",
            strongHandoff = null,
            llm = IncomingLlmVerdict(kind = "handoff", category = "决策请求", privacy = true, privacyDomain = "p1"),
        )
        assertTrue(noisy.handoff.skipLlm)
        assertFalse(noisy.handoff.handoff)
        assertFalse(noisy.privacy.hit)
    }

    // ── 强模式直判：省 LLM 且 LLM 故障不拖垮 ─────────────────────────────

    @Test
    fun strongHandoff_directVerdictWithoutLlm() {
        // llm=null 模拟「强命中即短路分类器」：接管结论已定，不得 uncertain。
        val review = ExpertPanel.merge("你今天几点回家？", strongHandoff = HandoffCategory.SCHEDULE to "几点回家", llm = null)
        assertTrue(review.handoff.handoff)
        assertFalse(review.handoff.uncertain)
        assertEquals(HandoffCategory.SCHEDULE, review.handoff.category)
        assertFalse(review.privacy.hit)
        assertFalse(review.goalHit)
    }

    @Test
    fun strongHandoff_privacyRulesStillEvaluated() {
        // 双域强命中（E1 H 类私人事实 + F1 词表）：LLM 缺席隐私词表仍一票定案。
        val review = ExpertPanel.merge("你家住哪呀", strongHandoff = HandoffCategory.PRIVACY to "你家住哪", llm = null)
        assertTrue(review.handoff.handoff)
        assertEquals(HandoffCategory.PRIVACY, review.handoff.category)
        assertTrue(review.privacy.hit)
        assertTrue(review.privacy.fromRules)
        assertEquals(PrivacyDomain.WHEREABOUTS, review.privacy.domain)
    }

    // ── LLM 失败降级：uncertain 而非 false（E1 等价关键三态） ────────────

    @Test
    fun llmUnavailable_noStrong_uncertain() {
        val review = ExpertPanel.merge("今天吃了啥", strongHandoff = null, llm = null)
        assertFalse(review.handoff.handoff)
        assertTrue(review.handoff.uncertain)
        assertFalse(review.privacy.hit)
        // E1 短路：uncertain 时不评达成快通道。
        assertFalse(review.goalHit)
    }

    // ── LLM 补空隙：两域结论一次消费 ─────────────────────────────────────

    @Test
    fun llmFillsPrivacyGap() {
        val review = ExpertPanel.merge(
            "你手头最近宽裕吗",
            strongHandoff = null,
            llm = IncomingLlmVerdict(kind = "normal", privacy = true, privacyDomain = "p3"),
        )
        assertFalse(review.handoff.handoff)
        assertFalse(review.handoff.uncertain)
        assertTrue(review.privacy.hit)
        assertFalse(review.privacy.fromRules)
        assertEquals(PrivacyDomain.FINANCE, review.privacy.domain)
        assertFalse(review.goalHit)
    }

    @Test
    fun llmUnknownDomainFallsBackToOther() {
        val review = ExpertPanel.merge(
            "顺便问一句那个事怎么样了",
            strongHandoff = null,
            llm = IncomingLlmVerdict(kind = "normal", privacy = true, privacyDomain = "px"),
        )
        assertTrue(review.privacy.hit)
        assertEquals(PrivacyDomain.OTHER, review.privacy.domain)
    }

    @Test
    fun llmJudgesHandoff_fillsGap() {
        val review = ExpertPanel.merge(
            "这周找时间聚一聚呗",
            strongHandoff = null,
            llm = IncomingLlmVerdict(kind = "handoff", category = "日程出席"),
        )
        assertTrue(review.handoff.handoff)
        assertFalse(review.handoff.uncertain)
        assertFalse(review.privacy.hit)
        assertFalse(review.goalHit)
    }

    // ── goalHit 短路顺序：只在非接管且非 uncertain 时评估 ─────────────────

    @Test
    fun goalHit_firesWhenClear() {
        val review = ExpertPanel.merge(
            "就这么定了，一言为定",
            strongHandoff = null,
            llm = IncomingLlmVerdict(kind = "normal"),
        )
        assertFalse(review.handoff.handoff)
        assertFalse(review.handoff.uncertain)
        assertTrue(review.goalHit)
    }

    @Test
    fun goalHit_suppressedWhenUncertain() {
        // LLM 故障（llm=null）：即使词表敲定语在场，uncertain 优先不评达成。
        val review = ExpertPanel.merge("就这么定了，一言为定", strongHandoff = null, llm = null)
        assertTrue(review.handoff.uncertain)
        assertFalse(review.goalHit)
    }

    @Test
    fun goalHit_suppressedWhenHandoff() {
        // 接管优先于达成通报：同句共现时只走 E1 铃声通报，不走 G 快通道。
        val review = ExpertPanel.merge(
            "这批货要吗？就这么定了",
            strongHandoff = HandoffCategory.DECISION to "要吗？",
            llm = IncomingLlmVerdict(kind = "normal"),
        )
        assertTrue(review.handoff.handoff)
        assertEquals(HandoffCategory.DECISION, review.handoff.category)
        assertFalse(review.goalHit)
    }
}
