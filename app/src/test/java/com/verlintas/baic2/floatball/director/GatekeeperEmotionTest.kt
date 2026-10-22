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

import com.verlintas.baic2.floatball.experts.strategist.EmotionRoute
import com.verlintas.baic2.floatball.experts.strategist.EmotionRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 审查官情绪红线（E-f，情感分析师方案 §4.5 行 11）：盛怒禁玩笑/推进 →
 * 拦截降级重写为安抚稿（不丢稿）。范围纪律在此锁死——**红线只锁盛怒行**，
 * 其余情绪行与 INACTIVE 带玩笑稿一律放行（误报保守）。
 */
class GatekeeperEmotionTest {

    private val rage = EmotionRouting.RAGE.copy(intensity = 4)

    private val jokeDraft = "这事是我没办好，开个玩笑别当真哈，下次注意。"
    private val pushDraft = "方案就是这个，考虑得怎么样？今天就定下来吧。"
    private val cleanDraft = "这件事是我没处理好，我马上给你补发一份，你看行吗。"

    @Test
    fun `盛怒稿带玩笑必拦并降级为安抚稿（不丢稿）`() {
        val rejection = GateRules.emotionReview(jokeDraft, rage)
        assertNotNull(rejection)
        assertEquals(GateReasons.EMOTION_VETO, rejection?.reason)
        assertEquals(GateRules.EMOTION_SOOTHING_DRAFT, rejection?.replacement)
    }

    @Test
    fun `盛怒稿带推进必拦并降级为安抚稿`() {
        val rejection = GateRules.emotionReview(pushDraft, rage)
        assertNotNull(rejection)
        assertEquals(GateReasons.EMOTION_VETO, rejection?.reason)
        assertEquals(GateRules.EMOTION_SOOTHING_DRAFT, rejection?.replacement)
    }

    @Test
    fun `盛怒稿干净安抚稿放行`() {
        assertNull(GateRules.emotionReview(cleanDraft, rage))
    }

    @Test
    fun `非盛怒行带玩笑稿放行（范围纪律）`() {
        assertNull(GateRules.emotionReview(jokeDraft, EmotionRouting.UNHAPPY))
        assertNull(GateRules.emotionReview(jokeDraft, EmotionRouting.LOW))
        assertNull(GateRules.emotionReview(jokeDraft, EmotionRouting.PERFUNCTORY))
        assertNull(GateRules.emotionReview(jokeDraft, EmotionRouting.NEGATIVE_FALLBACK))
    }

    @Test
    fun `非盛怒行带推进稿放行（范围纪律）`() {
        assertNull(GateRules.emotionReview(pushDraft, EmotionRouting.HESITANT))
    }

    @Test
    fun `INACTIVE 与关闸路线恒放行`() {
        assertNull(GateRules.emotionReview(jokeDraft, EmotionRoute.INACTIVE))
        assertNull(GateRules.emotionReview(jokeDraft, EmotionRoute(active = true, key = "rage")))
    }

    @Test
    fun `deterministicReview 透传情绪：盛怒玩笑稿命中 emotion_veto`() {
        val rejection = GateRules.deterministicReview(
            jokeDraft,
            emptyList(),
            MAX_CHARS,
            auto = false,
            emotion = rage,
        )
        assertEquals(GateReasons.EMOTION_VETO, rejection?.reason)
        assertNull(GateRules.deterministicReview(jokeDraft, emptyList(), MAX_CHARS, auto = false))
    }

    @Test
    fun `顺序：硬红线先于情绪红线，情绪红线先于 auto 四类`() {
        // 硬红线（枪支）+ 玩笑 → BLACKLIST 恒优先。
        val blacklist = GateRules.deterministicReview(
            "开个玩笑，枪支弹药我这都有，哈哈。",
            emptyList(),
            MAX_CHARS,
            auto = true,
            emotion = rage,
        )
        assertEquals(GateReasons.BLACKLIST, blacklist?.reason)
        // 玩笑 + 钱话题（auto 词表）+ 盛怒 → 情绪红线先命中。
        val emotion = GateRules.deterministicReview(
            "今天就转账吧，开个玩笑别当真。",
            emptyList(),
            MAX_CHARS,
            auto = true,
            emotion = rage,
        )
        assertEquals(GateReasons.EMOTION_VETO, emotion?.reason)
        // 无盛怒时同稿落 auto 钱话题（既有行为零破坏）。
        val auto = GateRules.deterministicReview(
            "今天就转账吧，开个玩笑别当真。",
            emptyList(),
            MAX_CHARS,
            auto = true,
        )
        assertEquals(GateReasons.AUTO_MONEY, auto?.reason)
    }

    private companion object {
        const val MAX_CHARS = 1_000
    }
}
