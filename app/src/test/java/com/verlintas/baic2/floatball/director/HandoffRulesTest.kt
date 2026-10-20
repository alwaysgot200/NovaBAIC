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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接管型（H）判定规则全分支（方案 2026-10-20 §2.2/§2.3）：快筛白名单、强模式
 * 直判（OR 一票否决）、LLM 主判定合并、uncertain 档、例句库完备性。
 */
class HandoffRulesTest {

    // ── 快筛白名单 ────────────────────────────────────────────────────────

    @Test
    fun whitelist_pureAckHits() {
        assertTrue(HandoffRules.whitelistHit("好的"))
        assertTrue(HandoffRules.whitelistHit("哈哈哈😂"))
        assertTrue(HandoffRules.whitelistHit("ok!"))
        assertTrue(HandoffRules.whitelistHit("嗯嗯~"))
        assertTrue(HandoffRules.whitelistHit("👍"))
        assertTrue(HandoffRules.whitelistHit(""))
    }

    @Test
    fun whitelist_substantiveTextDoesNotHit() {
        assertFalse(HandoffRules.whitelistHit("好的，明天说"))
        assertFalse(HandoffRules.whitelistHit("几点回家？"))
        assertFalse(HandoffRules.whitelistHit("照片发我一下"))
    }

    // ── 强模式直判 ────────────────────────────────────────────────────────

    @Test
    fun strong_scheduleHits() {
        assertEquals(HandoffCategory.SCHEDULE, HandoffRules.strongHit("你今天几点回家？")?.first)
        assertEquals(HandoffCategory.SCHEDULE, HandoffRules.strongHit("周末能不能来一趟")?.first)
        assertEquals(HandoffCategory.SCHEDULE, HandoffRules.strongHit("在哪儿等我？")?.first)
    }

    @Test
    fun strong_entityHits() {
        assertEquals(HandoffCategory.ENTITY, HandoffRules.strongHit("把上次的照片发给我")?.first)
        assertEquals(HandoffCategory.ENTITY, HandoffRules.strongHit("发个定位给我")?.first)
        assertEquals(HandoffCategory.ENTITY, HandoffRules.strongHit("把资料发给我一下")?.first)
    }

    @Test
    fun strong_privacyHits() {
        assertEquals(HandoffCategory.PRIVACY, HandoffRules.strongHit("你家住哪呀")?.first)
        assertEquals(HandoffCategory.PRIVACY, HandoffRules.strongHit("你工资多少啊")?.first)
    }

    @Test
    fun strong_decisionHits() {
        assertEquals(HandoffCategory.DECISION, HandoffRules.strongHit("这两个方案选哪个？")?.first)
        assertEquals(HandoffCategory.DECISION, HandoffRules.strongHit("这批货要吗？")?.first)
        assertEquals(HandoffCategory.DECISION, HandoffRules.strongHit("你觉得怎么办")?.first)
    }

    @Test
    fun strong_ordinaryChatMisses() {
        assertNull(HandoffRules.strongHit("今天天气不错"))
        assertNull(HandoffRules.strongHit("我刚看完那部电影"))
        assertNull(HandoffRules.strongHit("哈哈，真的假的"))
    }

    // ── 合并判定（LLM 主判定 + 词表否决/备胎） ────────────────────────────

    @Test
    fun judge_llmTrue_handoff() {
        val j = HandoffRules.judge("你说怎么办", llmHandoff = true)
        assertTrue(j.handoff)
        assertFalse(j.uncertain)
        assertEquals(HandoffCategory.DECISION, j.category)
    }

    @Test
    fun judge_llmTrueWithStrong_usesStrongCategory() {
        val j = HandoffRules.judge("你今天几点回家？", llmHandoff = true)
        assertTrue(j.handoff)
        assertEquals(HandoffCategory.SCHEDULE, j.category)
    }

    @Test
    fun judge_strongOverridesLlmFalse() {
        // 宁严勿松：强模式一票否决 LLM 的 normal。
        val j = HandoffRules.judge("把照片发给我", llmHandoff = false)
        assertTrue(j.handoff)
        assertFalse(j.uncertain)
        assertEquals(HandoffCategory.ENTITY, j.category)
    }

    @Test
    fun judge_llmFalseNoStrong_normal() {
        val j = HandoffRules.judge("今天吃了啥", llmHandoff = false)
        assertFalse(j.handoff)
        assertFalse(j.uncertain)
    }

    @Test
    fun judge_llmUnavailable_uncertain() {
        val j = HandoffRules.judge("今天吃了啥", llmHandoff = null)
        assertFalse(j.handoff)
        assertTrue(j.uncertain)
    }

    @Test
    fun judge_whitelistHits_skipsLlm() {
        val j = HandoffRules.judge("好的", llmHandoff = null)
        assertFalse(j.handoff)
        assertTrue(j.skipLlm)
        assertFalse(j.uncertain)
    }

    // ── 例句库完备性（用户要求枚举库要够大） ──────────────────────────────

    @Test
    fun examples_coverAllCategoriesWithDepth() {
        for (category in HandoffCategory.values()) {
            val samples = HandoffRules.EXAMPLES[category].orEmpty()
            assertTrue("${category.name} 例句不足 8 条", samples.size >= 8)
            assertTrue(samples.all { it.isNotBlank() })
        }
        assertTrue(HandoffRules.EXAMPLES_SECTION.contains(HandoffCategory.ENTITY.label))
    }
}
