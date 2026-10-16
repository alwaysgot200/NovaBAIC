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

package com.verlintas.baic2.floatball.strategy

import com.verlintas.baic2.floatball.advisor.ScenarioPack
import com.verlintas.baic2.floatball.advisor.ScenarioStage
import com.verlintas.baic2.floatball.advisor.strategy.SignalRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalRulesTest {

    private val pack = ScenarioPack(
        code = "dating",
        label = "恋爱",
        retreatSignals = listOf("算了", "已读不回超过24小时"),
        stages = listOf(
            ScenarioStage(
                code = "probe",
                label = "试探邀约",
                advanceSignals = listOf("主动约面试时间/问到岗时间", "连续两次回复不少于2句"),
            ),
        ),
    )

    @Test
    fun retreatHits_plainSignalMatched() {
        assertEquals(listOf("算了"), SignalRules.retreatHits(pack, "那算了吧"))
    }

    @Test
    fun retreatHits_durationSignalWithDigits_skipped() {
        // P2 无消息时间戳能力：含数字的时长信号不参与词表匹配。
        assertTrue(SignalRules.retreatHits(pack, "已读不回超过24小时了").isEmpty())
    }

    @Test
    fun advanceCandidates_incomingHitsCoreKey() {
        val stage = pack.stages.single()
        val hits = SignalRules.advanceCandidates(stage, incomingText = "我主动约面试时间吧", replyText = "")
        assertEquals(listOf("主动约面试时间/问到岗时间"), hits)
    }

    @Test
    fun advanceCandidates_digitQuantifierSplitAndReplyHit() {
        val stage = pack.stages.single()
        // 数字量词被切段后，中文核心「连续两次回复不少于」仍可命中；reply 通道也生效。
        val hits = SignalRules.advanceCandidates(stage, incomingText = "", replyText = "我连续两次回复不少于三句")
        assertTrue(hits.contains("连续两次回复不少于2句"))
    }

    @Test
    fun silenceSignal_noTimestamp_meansNoSignal() {
        // 旧会话从未记录来信时间：无此信号，不臆测。
        assertEquals(null, SignalRules.silenceSignal(null, NOW))
        assertEquals(null, SignalRules.silenceSignal(0L, NOW))
        assertEquals(null, SignalRules.hoursSinceIncoming(null, NOW))
    }

    @Test
    fun silenceSignal_under24h_meansNoSignal() {
        val at = NOW - 23L * 60L * 60L * 1000L - 59L * 60L * 1000L
        assertEquals(null, SignalRules.silenceSignal(at, NOW))
        assertEquals(23, SignalRules.hoursSinceIncoming(at, NOW))
    }

    @Test
    fun silenceSignal_over24h_emitsNeutralSignal() {
        val at = NOW - 26L * 60L * 60L * 1000L
        assertEquals(SignalRules.SILENCE_24H, SignalRules.silenceSignal(at, NOW))
        assertEquals(26, SignalRules.hoursSinceIncoming(at, NOW))
    }

    @Test
    fun silenceSignal_clockSkew_meansNoSignal() {
        // 来信时间晚于当前时间（时钟回拨）：不当作任何信号。
        assertEquals(null, SignalRules.silenceSignal(NOW + 1000L, NOW))
        assertEquals(null, SignalRules.hoursSinceIncoming(NOW + 1000L, NOW))
    }

    private companion object {
        const val NOW = 1_000_000_000_000L
    }
}
