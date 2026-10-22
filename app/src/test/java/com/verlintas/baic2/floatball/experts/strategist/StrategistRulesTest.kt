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

package com.verlintas.baic2.floatball.experts.strategist

import com.verlintas.baic2.floatball.advisor.data.PRIVACY_POLICY_ASSESS
import com.verlintas.baic2.floatball.advisor.data.PRIVACY_POLICY_HANDOFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 军师确定性校验（裁量层方案 §3.4 + §九 测试表）：`<tactics>` 解析降级、
 * 档位闸剥除、D4 claim 词表闸、D3 红线闸（泄露锁：free 档也恒拦）、
 * approach 归一——全部纯函数锁死，模型提案永远越不过代码执法。
 */
class StrategistRulesTest {

    // ── parse：围栏/畸形/空输入 → null（§3.5 降级），approach 归一 ──────────

    @Test
    fun parse_nullBlankAndMalformedReturnNull() {
        assertNull(StrategistRules.parse(null))
        assertNull(StrategistRules.parse(""))
        assertNull(StrategistRules.parse("我觉得应该撒谎"))
        assertNull(StrategistRules.parse("{\"approach\": }"))
    }

    @Test
    fun parse_fencedJsonExtractsPermit() {
        val raw = """
            ```json
            {"approach":"empathy","promising":{"use":true,"why":"推进","claim":"周五前发货","within":true}}
            ```
        """.trimIndent()
        val permit = StrategistRules.parse(raw)
        assertEquals(APPROACH_EMPATHY, permit?.approach)
        assertTrue(permit?.promising?.use == true)
    }

    @Test
    fun parse_unknownApproachNormalizesToMixed() {
        val permit = StrategistRules.parse("""{"approach":"aggressive"}""")
        assertEquals(APPROACH_MIXED, permit?.approach)
    }

    // ── 档位闸：never 维剥除（宪法优先于军令） ─────────────────────────────

    @Test
    fun enforce_neverDimIsStripped() {
        val permit = Permit(
            lying = TacticRuling(use = true, why = "圆场", claim = "我一直在出差"),
        )
        val ruled = StrategistRules.enforce(permit, TalkerStrategy(lying = TALKER_NEVER), PRIVACY_POLICY_HANDOFF)
        assertFalse(ruled.permit.lying.use)
        assertTrue(ruled.violations.isEmpty())
        assertFalse(ruled.needsHuman)
    }

    // ── 红线闸：死清单任何档位恒拦（泄露锁） ──────────────────────────────

    @Test
    fun enforce_redlineIsBlockedEvenOnFree() {
        val permit = Permit(
            lying = TacticRuling(use = true, claim = "已经付款了，截图稍后发你"),
        )
        val ruled = StrategistRules.enforce(permit, TalkerStrategy(lying = TALKER_FREE), PRIVACY_POLICY_HANDOFF)
        assertFalse(ruled.permit.lying.use)
        assertTrue(ruled.violations.single().startsWith("lying:redline:"))
    }

    @Test
    fun redlineHitCoversFiveDeathListFamilies() {
        // D3 五类各抽一锚点词验证单一来源词表可用（Gatekeeper 恒拦段共用）。
        assertEquals("已经转账", StrategistAssets.redlineHit("我已经转账给你了"))
        assertEquals("包赢", StrategistAssets.redlineHit("这场官司包赢"))
        assertEquals("不用去医院", StrategistAssets.redlineHit("小伤不用去医院"))
        assertEquals("官方客服", StrategistAssets.redlineHit("我是官方客服"))
        assertEquals("他的身份证", StrategistAssets.redlineHit("他的身份证号是"))
        assertNull(StrategistAssets.redlineHit("今天天气不错"))
    }

    // ── claim 词表闸：assess 档硬承诺 → 问主人；free 档不触发 ─────────────

    @Test
    fun enforce_assessPromiseWithMoneyTermNeedsHuman() {
        val permit = Permit(
            promising = TacticRuling(use = true, why = "推进成交", claim = "周五之前给你300元样品", within = true),
        )
        val ruled = StrategistRules.enforce(permit, TalkerStrategy(promising = TALKER_ASSESS), null)
        assertTrue(ruled.needsHuman)
        // 许可单保留（渲染层改为「需确认」话术），该维本回合不自动发送。
        assertTrue(ruled.permit.promising.use)
    }

    @Test
    fun enforce_withinFalseNeedsHumanRegardlessOfVocab() {
        val permit = Permit(
            promising = TacticRuling(use = true, why = "安抚", claim = "尽量安排", within = false),
        )
        val ruled = StrategistRules.enforce(permit, TalkerStrategy(promising = TALKER_ASSESS), null)
        assertTrue(ruled.needsHuman)
    }

    @Test
    fun enforce_freePromiseWithMoneyTermPasses() {
        val permit = Permit(
            promising = TacticRuling(use = true, claim = "周末内寄样品", within = true),
        )
        val ruled = StrategistRules.enforce(permit, TalkerStrategy(promising = TALKER_FREE), null)
        assertFalse(ruled.needsHuman)
        assertTrue(ruled.permit.promising.use)
    }

    @Test
    fun enforce_assessPromiseWithoutHardTermsPasses() {
        val permit = Permit(
            promising = TacticRuling(use = true, claim = "会认真考虑合作", within = true),
        )
        val ruled = StrategistRules.enforce(permit, TalkerStrategy(promising = TALKER_ASSESS), null)
        assertFalse(ruled.needsHuman)
    }

    // ── disclosure：F1 privacyPolicy 单点真源 ─────────────────────────────

    @Test
    fun enforce_disclosureStrippedUnlessPrivacyAssess() {
        val permit = Permit(
            disclosure = TacticRuling(use = true, domain = "p3", why = "促进信任", claim = "上周刚交付过同类项目"),
        )
        val handoff = StrategistRules.enforce(permit, TalkerStrategy(), PRIVACY_POLICY_HANDOFF)
        assertFalse(handoff.permit.disclosure.use)

        val assess = StrategistRules.enforce(permit, TalkerStrategy(), PRIVACY_POLICY_ASSESS)
        assertTrue(assess.permit.disclosure.use)
    }

    // ── E-e 情绪约束（方案 §4.6）：禁用维剥 use、禁用 approach 覆写 ────────

    @Test
    fun enforce_rageStripsExemplifyingEvenOnFree() {
        val permit = Permit(
            exemplifying = TacticRuling(use = true, why = "类比说服", claim = "就像别人家一样"),
        )
        val ruled = StrategistRules.enforce(
            permit,
            TalkerStrategy(exemplifying = TALKER_FREE),
            null,
            EmotionRouting.RAGE,
        )
        assertFalse(ruled.permit.exemplifying.use)
        // 情绪约束不是红线违规，不进 violations（红线台账语义保持纯净）。
        assertTrue(ruled.violations.isEmpty())
    }

    @Test
    fun enforce_rageOverridesBannedApproachToEmpathy() {
        val ruled = StrategistRules.enforce(
            Permit(approach = APPROACH_HUMOR),
            TalkerStrategy(),
            null,
            EmotionRouting.RAGE,
        )
        assertEquals(APPROACH_EMPATHY, ruled.permit.approach)
    }

    @Test
    fun enforce_bannedApproachWithoutSuggestionFallsToMixed() {
        val route = EmotionRoute(active = true, bannedApproaches = listOf(APPROACH_STORY))
        val ruled = StrategistRules.enforce(Permit(approach = APPROACH_STORY), TalkerStrategy(), null, route)
        assertEquals(APPROACH_MIXED, ruled.permit.approach)
    }

    @Test
    fun enforce_inactiveEmotionKeepsProposalUntouched() {
        val permit = Permit(
            approach = APPROACH_HUMOR,
            exemplifying = TacticRuling(use = true, why = "类比", claim = "就像…"),
        )
        val ruled = StrategistRules.enforce(
            permit,
            TalkerStrategy(exemplifying = TALKER_FREE),
            null,
            EmotionRoute.INACTIVE,
        )
        assertEquals(APPROACH_HUMOR, ruled.permit.approach)
        assertTrue(ruled.permit.exemplifying.use)
    }
}
