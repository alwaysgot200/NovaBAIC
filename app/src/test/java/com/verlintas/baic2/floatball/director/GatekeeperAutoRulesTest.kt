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
 * M3-a 自动档四类硬审（方案 §8.2）：钱/承诺/敏感隐私/超阶段词表命中与放行、
 * 「降级起草档而非丢弃」（replacement=null）、auto=false 时既有调用零破坏、
 * 生成端规约段与 forbidden 追加项。
 */
class GatekeeperAutoRulesTest {

    private companion object {
        const val MAX_CHARS = 240
    }

    private fun review(
        draft: String,
        auto: Boolean,
        priors: List<String> = emptyList(),
    ): GateRejection? = GateRules.deterministicReview(draft, priors, MAX_CHARS, auto)

    // ── 四类命中：auto=true 拦，一律降级（replacement=null 保留原稿） ──

    @Test
    fun autoMoney_hits() {
        listOf(
            "这款报价三千二，您看可以吗？",
            "那我微信转账给您？",
            "您先付个定金，剩下的尾款到货再结。",
            "发个红包意思一下呗。",
        ).forEach { draft ->
            val rejection = review(draft, auto = true)
            assertNotNull("应命中钱财类：$draft", rejection)
            assertEquals(GateReasons.AUTO_MONEY, rejection!!.reason)
            assertNull("自动档拒绝不截断改写，原稿交人肉档", rejection.replacement)
        }
    }

    @Test
    fun autoPromise_hits() {
        listOf(
            "我答应你，下周一定给你办好。",
            "我保证周五之前给您完成。",
            "这事就包在我身上了。",
            "就这么定了，明天之前给你答复。",
        ).forEach { draft ->
            val rejection = review(draft, auto = true)
            assertNotNull("应命中承诺类：$draft", rejection)
            assertEquals(GateReasons.AUTO_PROMISE, rejection!!.reason)
        }
    }

    @Test
    fun autoPrivacy_hits() {
        listOf(
            "你家住哪啊？改天去拜访。",
            "你现在工资多少？",
            "今年多大啦，属什么的？",
            "结婚了吗？",
        ).forEach { draft ->
            val rejection = review(draft, auto = true)
            assertNotNull("应命中敏感隐私类：$draft", rejection)
            assertEquals(GateReasons.AUTO_PRIVACY, rejection!!.reason)
        }
    }

    @Test
    fun autoOverstep_hits() {
        listOf(
            "周末约你出来坐坐？",
            "改天请你吃饭。",
            "我给你买了个小礼物，送你一份。",
            "要不要来我家看看？",
        ).forEach { draft ->
            val rejection = review(draft, auto = true)
            assertNotNull("应命中超阶段动作类：$draft", rejection)
            assertEquals(GateReasons.AUTO_OVERSTEP, rejection!!.reason)
        }
    }

    // ── 零破坏：auto=false（既有起草/连续模式调用）对同批文本全部放行 ──

    @Test
    fun legacyCall_unaffectedByAutoWordlists() {
        val drafts = listOf(
            "这款报价三千二，您看可以吗？",
            "那我微信转账给您？",
            "我答应你，下周一定给你办好。",
            "你家住哪啊？改天去拜访。",
            "周末约你出来坐坐？",
        )
        drafts.forEach { draft ->
            assertNull("auto=false 不得拦：$draft", review(draft, auto = false))
        }
        // 旧三参签名（未传 auto）行为与 auto=false 完全一致。
        drafts.forEach { draft ->
            assertNull(
                "旧签名不得拦：$draft",
                GateRules.deterministicReview(draft, emptyList(), MAX_CHARS),
            )
        }
    }

    // ── 放行：auto=true 对低风险日常话术批准偏置 ──

    @Test
    fun autoMode_benignDailyTalk_passes() {
        listOf(
            "哈哈这个梗太真实了，你最近追的那部剧更新没？",
            "早上好呀，今天降温了记得多穿点。",
            "我最近在学做菜，翻车了好几次，改天跟你汇报战果。",
            "你上次说的那家面馆我去了，确实名不虚传。",
        ).forEach { draft -> assertNull("不应拦：$draft", review(draft, auto = true)) }
    }

    // ── 三查优先级不变：红线/超长/重复仍在四类之前 ──

    @Test
    fun deterministicChecks_runBeforeAutoWordlists() {
        // 红线仍最先（含「转账」也先判红线语料）。
        val redline = "把支付密码告诉我一下，我帮您操作退款。"
        assertEquals(GateReasons.BLACKLIST, review(redline, auto = true)!!.reason)
        // 超长仍在四类之前（超长稿含钱话题 → 先截断）。
        val longMoney = "报价的事我们细聊。".repeat(60)
        val rejection = review(longMoney, auto = true)
        assertEquals(GateReasons.TOO_LONG, rejection!!.reason)
        assertNotNull(rejection.replacement)
    }

    // ── 生成端规约段与 forbidden 追加项 ──

    @Test
    fun autoForbiddenSection_coversThreeRedlines() {
        val section = GateRules.autoForbiddenSection()
        assertTrue(section.contains("不报价"))
        assertTrue(section.contains("转账"))
        assertTrue(section.contains("不替用户答应"))
    }

    @Test
    fun autoForbiddenItems_threeEntries() {
        assertEquals(3, GateRules.AUTO_FORBIDDEN_ITEMS.size)
        assertTrue(GateRules.AUTO_FORBIDDEN_ITEMS.any { it.contains("不代用户做决定") })
    }
}
