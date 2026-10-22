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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代言者两段渲染（T1-c，裁量层方案 §四）：手段库段/作战指令段/降级禁令段
 * 的拼装协议在此锁死——审批话术三选一、缺省维不渲染、mixed 省略重点行。
 */
class TacticPromptBuilderTest {

    private val neverStrategy = TalkerStrategy(
        lying = TALKER_NEVER,
        promising = TALKER_NEVER,
        exemplifying = TALKER_NEVER,
    )

    private val freeStrategy = TalkerStrategy(
        lying = TALKER_FREE,
        promising = TALKER_FREE,
        exemplifying = TALKER_FREE,
    )

    // ---- 手段库段 ----

    @Test
    fun `手段库段恒非空且不含阶段意图行（stageIntent 为空时）`() {
        val section = StrategistPrompt.toolkitSection("")
        assertNotNull(section)
        assertTrue(section!!.contains(StrategistAssets.TOOLKIT_SECTION))
        assertFalse(section.contains("本阶段你要推进"))
    }

    @Test
    fun `手段库段携带阶段意图`() {
        val section = StrategistPrompt.toolkitSection("约到线下见面")
        assertTrue(section!!.contains("本阶段你要推进：约到线下见面"))
    }

    // ---- 作战指令段 ----

    @Test
    fun `mixed 且无敏感维许可时整段省略`() {
        val ruled = StrategistRules.enforce(Permit(), neverStrategy, PRIVACY_POLICY_HANDOFF)
        assertNull(StrategistPrompt.commandSection(ruled))
    }

    @Test
    fun `非 mixed approach 渲染本回合重点行`() {
        val ruled = StrategistRules.enforce(
            Permit(approach = APPROACH_EMPATHY),
            neverStrategy,
            PRIVACY_POLICY_HANDOFF,
        )
        val section = StrategistPrompt.commandSection(ruled)
        assertNotNull(section)
        assertTrue(section!!.contains("本回合重点"))
        assertTrue(section.contains("共情回应"))
    }

    @Test
    fun `promising 批准时渲染可自然提及与 claim`() {
        val ruled = StrategistRules.enforce(
            Permit(promising = TacticRuling(use = true, claim = "三天内发货", why = "库存已确认")),
            freeStrategy,
            PRIVACY_POLICY_HANDOFF,
        )
        val section = StrategistPrompt.commandSection(ruled)
        assertNotNull(section)
        assertTrue(section!!.contains("可自然提及「三天内发货」"))
        assertTrue(section.contains("理由：库存已确认"))
    }

    @Test
    fun `promising 超授权渲染问主人话术`() {
        val ruled = StrategistRules.enforce(
            Permit(promising = TacticRuling(use = true, within = false, claim = "先垫付一千元")),
            freeStrategy,
            PRIVACY_POLICY_HANDOFF,
        )
        val section = StrategistPrompt.commandSection(ruled)
        assertNotNull(section)
        // D4 词表（元）命中 → assess/free 同样 needsHuman？free 档词表闸不触发，
        // within=false 才 needsHuman；此处锁 within=false 的问主人口径。
        assertTrue(section!!.contains("需要向用户确认"))
        assertFalse(section.contains("可自然提及"))
    }

    @Test
    fun `未批但带理由的维渲染禁令句`() {
        val ruled = StrategistRules.enforce(
            Permit(lying = TacticRuling(use = false, why = "对方在核实经历")),
            neverStrategy,
            PRIVACY_POLICY_HANDOFF,
        )
        val section = StrategistPrompt.commandSection(ruled)
        assertNotNull(section)
        assertTrue(section!!.contains("本回合不要动用"))
    }

    @Test
    fun `缺省维对象不渲染（避免空指令噪声）`() {
        val ruled = StrategistRules.enforce(
            Permit(disclosure = TacticRuling(use = false)),
            neverStrategy,
            PRIVACY_POLICY_HANDOFF,
        )
        assertNull(StrategistPrompt.commandSection(ruled))
    }

    // ---- 降级禁令段 ----

    @Test
    fun `全 never 且非 assess 时渲染三条禁令`() {
        val section = StrategistPrompt.fallbackSection(neverStrategy, PRIVACY_POLICY_HANDOFF)
        assertNotNull(section)
        assertTrue(section!!.contains("本回合不要虚构事实"))
        assertTrue(section.contains("不要替用户做任何承诺"))
        assertTrue(section.contains("不要透露用户本人的隐私信息"))
    }

    @Test
    fun `全 free 且 assess 时整段省略`() {
        assertNull(
            StrategistPrompt.fallbackSection(freeStrategy, PRIVACY_POLICY_ASSESS),
        )
    }

    @Test
    fun `free 档单项省略对应禁令`() {
        val partial = TalkerStrategy(
            lying = TALKER_FREE,
            promising = TALKER_NEVER,
            exemplifying = TALKER_FREE,
        )
        val section = StrategistPrompt.fallbackSection(partial, PRIVACY_POLICY_ASSESS)
        assertNotNull(section)
        assertFalse(section!!.contains("不实陈述"))
        assertTrue(section.contains("不要替用户做任何承诺"))
        assertFalse(section.contains("隐私信息"))
    }

    // ---- 军师系统提示词协议 ----

    @Test
    fun `systemPrompt 含四维与红线禁令与 JSON 协议`() {
        val prompt = StrategistPrompt.systemPrompt()
        listOf("disclosure", "lying", "promising", "exemplifying", "approach").forEach { key ->
            assertTrue("missing $key", prompt.contains(key))
        }
        assertTrue(prompt.contains("红线"))
        assertTrue(prompt.contains("{\"approach\""))
        assertEquals(
            "军师提示词不得把 D3 死清单全量外泄（防词表逆向规避）",
            -1,
            prompt.indexOf(StrategistAssets.REDLINE_WORDS.first()),
        )
    }

    // ---- 情绪段（E-e，方案 §4.6）----

    @Test
    fun `情绪段不干预时恒省略`() {
        assertNull(StrategistPrompt.emotionSection(EmotionRoute.INACTIVE))
    }

    @Test
    fun `情绪段渲染情绪行重点与禁用`() {
        val section = StrategistPrompt.emotionSection(EmotionRouting.RAGE.copy(intensity = 4))
        assertNotNull(section)
        assertTrue(section!!.contains("【对方当前情绪】盛怒（强度 4）"))
        assertTrue(section.contains("重点用共情回应"))
        assertTrue(section.contains("禁用："))
        assertTrue(section.contains("幽默化解"))
        assertTrue(section.contains("推进"))
    }

    @Test
    fun `userPrompt 携带情绪段且不干预时无该段`() {
        val active = StrategistPrompt.userPrompt(
            stageIntent = "",
            incomingText = "你怎么能这样",
            recentDigest = "",
            personaDigest = "",
            strategy = neverStrategy,
            privacyPolicy = PRIVACY_POLICY_HANDOFF,
            privacyDomain = null,
            emotion = EmotionRouting.RAGE.copy(intensity = 4),
        )
        assertTrue(active.contains("【对方当前情绪】"))

        val inactive = StrategistPrompt.userPrompt(
            stageIntent = "",
            incomingText = "在吗",
            recentDigest = "",
            personaDigest = "",
            strategy = neverStrategy,
            privacyPolicy = PRIVACY_POLICY_HANDOFF,
            privacyDomain = null,
        )
        assertFalse(inactive.contains("【对方当前情绪】"))
    }
}
