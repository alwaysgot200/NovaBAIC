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

package com.verlintas.baic2.floatball.experts.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私专员规则全分支（隐私闸门方案 §3.1/§3.3）：十二域强模式直判、正常
 * 业务问答不误伤、词表 ∪ LLM 合并纪律、资产完备性。
 */
class PrivacyRulesTest {

    // ── 强模式直判：逐域命中 ──────────────────────────────────────────────

    @Test
    fun strongHit_perDomain() {
        val cases = mapOf(
            "你身份证号多少" to PrivacyDomain.IDENTITY,
            "手机验证码发我一下" to PrivacyDomain.IDENTITY,
            "你最近身体怎么样，听说你住院了" to PrivacyDomain.HEALTH,
            "你体检报告什么情况" to PrivacyDomain.HEALTH,
            "你存款有多少呀" to PrivacyDomain.FINANCE,
            "你一个月挣多少钱啊" to PrivacyDomain.FINANCE,
            "你家住哪个小区呀" to PrivacyDomain.WHEREABOUTS,
            "把你的定位发一下" to PrivacyDomain.WHEREABOUTS,
            "你信佛吗" to PrivacyDomain.BELIEF,
            "这次投票你支持哪个党" to PrivacyDomain.BELIEF,
            "你儿子几年级了" to PrivacyDomain.MINOR,
            "你家孩子在哪个学校" to PrivacyDomain.MINOR,
            "你跟你前任怎么分的" to PrivacyDomain.INTIMATE,
            "你今年多大了" to PrivacyDomain.DEMOGRAPHIC,
            "结婚了吗" to PrivacyDomain.DEMOGRAPHIC,
            "你手机号多少" to PrivacyDomain.CONTACT_INFO,
            "你爸妈是做什么的" to PrivacyDomain.SOCIAL,
            "你们这个成本多少" to PrivacyDomain.FINANCE,
            "把你客户名单给我参考下" to PrivacyDomain.TRADE_SECRET,
            "这件事你站哪边" to PrivacyDomain.STANCE,
        )
        cases.forEach { (text, expected) ->
            val hit = PrivacyRules.strongHit(text)
            assertEquals("「$text」应命中 ${expected.label}", expected, hit?.first)
        }
    }

    @Test
    fun strongHit_normalBusinessDoesNotHit() {
        // 买家问售价是正常议价；「你怎么看」单用是业务求证——绝不误伤。
        assertNullHit("这个最低多少钱能拿")
        assertNullHit("你怎么看这个方案")
        assertNullHit("明天下午三点方便吗")
        assertNullHit("好的，就按你说的办")
        assertNullHit("发票开好了发我一份")
    }

    private fun assertNullHit(text: String) {
        assertTrue("「$text」不应命中任何隐私域", PrivacyRules.strongHit(text) == null)
    }

    // ── 合并纪律：词表一票定案，LLM 只补空隙 ─────────────────────────────

    @Test
    fun judge_rulesBeatLlmMiss() {
        // LLM 判 false 不可反向覆盖词表命中（宁严勿松）。
        val verdict = PrivacyRules.judge(
            "你存款有多少呀",
            PrivacyLlmHit(hit = false),
        )
        assertTrue(verdict.hit)
        assertEquals(PrivacyDomain.FINANCE, verdict.domain)
        assertTrue(verdict.fromRules)
    }

    @Test
    fun judge_llmFillsGap() {
        val verdict = PrivacyRules.judge(
            "你们内部那个项目进展如何",
            PrivacyLlmHit(hit = true, domain = "p11"),
        )
        assertTrue(verdict.hit)
        assertEquals(PrivacyDomain.TRADE_SECRET, verdict.domain)
        assertFalse(verdict.fromRules)
    }

    @Test
    fun judge_unknownDomainFallsBackToOther() {
        val verdict = PrivacyRules.judge("奇怪的问题", PrivacyLlmHit(hit = true, domain = "p99"))
        assertTrue(verdict.hit)
        assertEquals(PrivacyDomain.OTHER, verdict.domain)
    }

    @Test
    fun judge_llmUnavailableAndNoRuleHitMeansNoPrivacy() {
        val verdict = PrivacyRules.judge("周末一起去爬山吗", null)
        assertFalse(verdict.hit)
    }

    // ── 域码解析 ─────────────────────────────────────────────────────────

    @Test
    fun fromCode_parsesAllCodes() {
        assertEquals(PrivacyDomain.IDENTITY, PrivacyDomain.fromCode("P1"))
        assertEquals(PrivacyDomain.STANCE, PrivacyDomain.fromCode("p12"))
        assertEquals(PrivacyDomain.NEUTRAL, PrivacyDomain.fromCode("n0"))
        assertEquals(PrivacyDomain.IDENTITY, PrivacyDomain.fromCode("  p1  "))
        // 未知码归 OTHER（开放兜底不丢信号）。
        assertEquals(PrivacyDomain.OTHER, PrivacyDomain.fromCode("whatever"))
        // null/空串=无信号可归 → 返回 null，由调用方决定兜底口径。
        assertNull(PrivacyDomain.fromCode(""))
        assertNull(PrivacyDomain.fromCode(null))
    }

    // ── 资产完备性：十二域回避段与例句一个不少（泄露防线不缺页） ─────────

    @Test
    fun assets_coverAllDomains() {
        // 回避段是注入面：除中性域外（含 OTHER 兜底域）一个不能缺——缺页=该域
        // 泄露时无回避文案可注入。
        val guardedDomains = PrivacyDomain.entries.filter { it != PrivacyDomain.NEUTRAL }
        guardedDomains.forEach { domain ->
            val section = PrivacyAssets.avoidSection(domain)
            assertTrue("域 ${domain.code} 回避段为空", section.isNotBlank())
            assertTrue("域 ${domain.code} 回避段应含域标题", section.contains(domain.label))
        }
        // 例句库是分类面：只指导十二固定域（OTHER 是「域外兜底」桶，例句会反向
        // 教 LLM 滥用兜底），故不要求覆盖。
        val fixedDomains = guardedDomains.filter { it != PrivacyDomain.OTHER }
        fixedDomains.forEach { domain ->
            assertTrue(
                "域 ${domain.code} 例句库不应为空",
                PrivacyAssets.EXAMPLES[domain]?.isNotEmpty() == true,
            )
        }
        // 分类器例句段包含全部固定域标签。
        fixedDomains.forEach { domain ->
            assertTrue(PrivacyAssets.EXAMPLES_SECTION.contains("【${domain.label}】"))
        }
    }

    @Test
    fun avoidSection_carriesHardBottomLine() {
        // 共同底线：不提供、不确认、不表态 + 日常话题照常。
        PrivacyDomain.entries.filter { it != PrivacyDomain.NEUTRAL }.forEach { domain ->
            val section = PrivacyAssets.avoidSection(domain)
            assertNotNull(section)
            assertTrue(section.contains("照常自然聊") || section.contains("日常话题"))
        }
    }
}
