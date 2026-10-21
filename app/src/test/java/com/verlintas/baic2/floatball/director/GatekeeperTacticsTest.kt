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

import com.verlintas.baic2.floatball.experts.strategist.Permit
import com.verlintas.baic2.floatball.experts.strategist.RuledPermit
import com.verlintas.baic2.floatball.experts.strategist.StrategistAssets
import com.verlintas.baic2.floatball.experts.strategist.TacticRuling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gatekeeper 越许可检测（T1-c，裁量层方案 §五）：军师许可单 × 稿件词表的
 * 确定性对账。红线泄露锁（D3 五类词表抽验）与三行表优先级在此锁死。
 */
class GatekeeperTacticsTest {

    /**
     * D3 红线死清单泄露锁样本：五类各抽一条（金钱事实欺骗/法律承诺/医疗
     * 人身/冒充机构/第三方隐私）。词表在 [StrategistAssets.REDLINE_WORDS]，
     * 本表锁死样本必须恒命中——词条被误删时此处先红。
     */
    private val redlineLeakLockSamples = listOf(
        "别担心，我已经付款了，截图稍后补给你", // 金钱交易事实性欺骗
        "合同你直接签，出了事我担保你", // 法律后果承诺
        "小毛病，停药两天没事的，不用去医院", // 医疗人身安全谎言
        "相信我，我是警察，这事我能摆平", // 冒充真实机构/职务
        "他的身份证号我发你，你直接去查", // 泄露第三方隐私
    )

    /** 无敏感维许可的基线单（全部 use=false，needsHuman=false）。 */
    private fun ruled(
        promisingUse: Boolean = false,
        promisingWithin: Boolean = true,
        disclosureUse: Boolean = false,
        needsHuman: Boolean = false,
    ) = RuledPermit(
        permit = Permit(
            promising = TacticRuling(use = promisingUse, within = promisingWithin),
            disclosure = TacticRuling(use = disclosureUse),
        ),
        needsHuman = needsHuman,
    )

    @Test
    fun `tactics 为空但红线命中仍恒拦（降级不放松红线）`() {
        val draft = "放心，我已经付款了，截图回头发你。"
        val rejection = GateRules.tacticReview(draft, tactics = null)
        assertNotNull(rejection)
        assertEquals(GateReasons.TACTIC_REDLINE, rejection?.reason)
        assertNull(rejection?.replacement)
    }

    @Test
    fun `tactics 为空且无红线时放行`() {
        assertNull(GateRules.tacticReview("今天天气不错，出来走走？", tactics = null))
    }

    @Test
    fun `D3 红线死清单五类抽验全部恒拦（泄露锁）`() {
        redlineLeakLockSamples.forEach { sample ->
            val rejection = GateRules.tacticReview(sample, tactics = null)
            assertEquals("sample=$sample", GateReasons.TACTIC_REDLINE, rejection?.reason)
            assertNotNull("词表未命中 sample=$sample", StrategistAssets.redlineHit(sample))
        }
    }

    @Test
    fun `promising 未批而稿件含承诺词则拦`() {
        val rejection = GateRules.tacticReview(
            "好的，报价三百元，明天之前给你。",
            tactics = ruled(promisingUse = false),
        )
        assertEquals(GateReasons.TACTIC_PROMISING, rejection?.reason)
        assertNull(rejection?.replacement)
    }

    @Test
    fun `promising 已批且 within 时不拦承诺词`() {
        assertNull(
            GateRules.tacticReview(
                "好的，报价三百元，明天之前给你。",
                tactics = ruled(promisingUse = true),
            ),
        )
    }

    @Test
    fun `needsHuman 超授权时即使 use=true 也拦（转问主人不许自动发送）`() {
        val rejection = GateRules.tacticReview(
            "我保证本周之内搞定。",
            tactics = ruled(promisingUse = true, promisingWithin = false, needsHuman = true),
        )
        assertEquals(GateReasons.TACTIC_PROMISING, rejection?.reason)
    }

    @Test
    fun `disclosure 未批而稿件自曝隐私则拦`() {
        val rejection = GateRules.tacticReview(
            "我住在城东小区，欢迎来玩。",
            tactics = ruled(disclosureUse = false),
        )
        assertEquals(GateReasons.TACTIC_DISCLOSURE, rejection?.reason)
    }

    @Test
    fun `disclosure 已批时不拦自曝语`() {
        assertNull(
            GateRules.tacticReview(
                "我住在城东小区，欢迎来玩。",
                tactics = ruled(disclosureUse = true),
            ),
        )
    }

    @Test
    fun `红线行优先于承诺与自曝行`() {
        val rejection = GateRules.tacticReview(
            "我是警察，能查你的身份证号，报价五百元。",
            tactics = ruled(promisingUse = true, disclosureUse = true),
        )
        assertEquals(GateReasons.TACTIC_REDLINE, rejection?.reason)
    }

    @Test
    fun `deterministicReview 透传 tactics 且与 auto 兼容`() {
        // auto=false + tactics 命中 → 越许可拦截生效。
        val viaTactics = GateRules.deterministicReview(
            draft = "我住在城东小区，欢迎来玩。",
            recentReplies = emptyList(),
            maxChars = 200,
            auto = false,
            tactics = ruled(disclosureUse = false),
        )
        assertEquals(GateReasons.TACTIC_DISCLOSURE, viaTactics?.reason)
        // tactics=null + auto=false → 行为与旧签名一致（放行）。
        assertNull(
            GateRules.deterministicReview(
                draft = "我住在城东小区，欢迎来玩。",
                recentReplies = emptyList(),
                maxChars = 200,
            ),
        )
    }
}
