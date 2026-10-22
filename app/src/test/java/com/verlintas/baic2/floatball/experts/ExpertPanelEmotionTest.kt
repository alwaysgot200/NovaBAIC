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

import com.verlintas.baic2.floatball.advisor.emotion.EmotionJudge
import com.verlintas.baic2.floatball.advisor.emotion.EmotionLabels
import com.verlintas.baic2.floatball.director.IncomingEmotionRefine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合议团情绪面（E-d，方案 §4.5）：精判触发门 [ExpertPanel.refineGate]、
 * 复核采纳 [ExpertPanel.applyEmotionRefine]（LLM 只能复核不能污染）、
 * 合并附带情绪 [ExpertPanel.merge]（白名单短路不吞情绪——原则 3 的信号源
 * 恰恰是"好的"这类纯应答）。纯函数直测，不触网不落库。
 */
class ExpertPanelEmotionTest {

    private fun verdict(
        state: EmotionJudge.EmotionState = EmotionJudge.EmotionState.WATCH,
        intensity: Int = 2,
        confidence: EmotionJudge.EmotionConfidence = EmotionJudge.EmotionConfidence.MEDIUM,
        labels: List<EmotionJudge.LabelScore> =
            listOf(EmotionJudge.LabelScore(EmotionLabels.PERFUNCTORY, -1.5)),
        evidence: List<EmotionJudge.Evidence> = listOf(
            EmotionJudge.Evidence(
                kind = EmotionJudge.Evidence.KIND_BEHAVIOR,
                term = "short_confirm_streak",
                labels = listOf(EmotionLabels.PERFUNCTORY),
                polarity = -1,
                intensityDelta = 0,
                score = -1.0,
                note = "连续 3 条短确认",
            ),
        ),
        reason: String = "原判理由",
    ) = EmotionJudge.EmotionVerdict(state, labels, intensity, confidence, evidence, reason)

    // ── 精判触发门：置信非高 + 非平稳 + 高价值回合 ────────────────────────

    @Test
    fun refineGate_calm_never_refines() {
        assertFalse(ExpertPanel.refineGate(EmotionJudge.CALM_VERDICT, assessDim = true, takeoverRisk = true))
    }

    @Test
    fun refineGate_requires_high_value_turn() {
        val watch = verdict(intensity = 2)
        assertFalse(ExpertPanel.refineGate(watch, assessDim = false, takeoverRisk = false))
        assertTrue(ExpertPanel.refineGate(watch, assessDim = true, takeoverRisk = false))
        assertTrue(ExpertPanel.refineGate(watch, assessDim = false, takeoverRisk = true))
        assertTrue(ExpertPanel.refineGate(verdict(intensity = 4), assessDim = false, takeoverRisk = false))
    }

    @Test
    fun refineGate_high_confidence_never_refines() {
        val settled = verdict(confidence = EmotionJudge.EmotionConfidence.HIGH)
        assertFalse(ExpertPanel.refineGate(settled, assessDim = true, takeoverRisk = true))
    }

    // ── 复核采纳：合法整体采纳 / 畸形弃用回库判 ───────────────────────────

    @Test
    fun refine_valid_output_is_adopted_whole() {
        val base = verdict()
        val out = ExpertPanel.applyEmotionRefine(
            base,
            IncomingEmotionRefine(state = "watch", labels = listOf("perfunctory", "anger"), intensity = 3),
        )
        assertEquals(EmotionJudge.EmotionState.WATCH, out.state)
        assertEquals(EmotionJudge.EmotionConfidence.HIGH, out.confidence)
        assertEquals(3, out.intensity)
        assertEquals(listOf("perfunctory", "anger"), out.labels.map { it.label })
        // 库判原件得分保留；新标签按库判主导方向给符号（库判为负 → 负）。
        assertEquals(-1.5, out.labels.first { it.label == "perfunctory" }.score, 1e-9)
        assertEquals(-1.0, out.labels.first { it.label == "anger" }.score, 1e-9)
        // 证据链永不被 LLM 覆盖；reason 注明复核来源。
        assertSame(base.evidence, out.evidence)
        assertTrue(out.reason.contains("经 LLM 精判复核"))
        assertTrue(out.reason.contains("原判理由"))
    }

    @Test
    fun refine_malformed_state_falls_back_to_lexicon_verdict() {
        val base = verdict()
        val out = ExpertPanel.applyEmotionRefine(
            base,
            IncomingEmotionRefine(state = "rage", labels = listOf("anger"), intensity = 3),
        )
        assertSame(base, out)
    }

    @Test
    fun refine_non_calm_without_valid_labels_falls_back() {
        val base = verdict()
        // 非平稳档却给不出合法标签：弃用回库判。
        val bogus = ExpertPanel.applyEmotionRefine(
            base,
            IncomingEmotionRefine(state = "confirmed", labels = listOf("not_a_label"), intensity = 4),
        )
        assertSame(base, bogus)
        val empty = ExpertPanel.applyEmotionRefine(
            base,
            IncomingEmotionRefine(state = "confirmed", labels = emptyList(), intensity = 4),
        )
        assertSame(base, empty)
    }

    @Test
    fun refine_calm_with_no_labels_is_legal() {
        val base = verdict()
        val out = ExpertPanel.applyEmotionRefine(
            base,
            IncomingEmotionRefine(state = "calm", labels = emptyList(), intensity = 1),
        )
        assertEquals(EmotionJudge.EmotionState.CALM, out.state)
        assertTrue(out.labels.isEmpty())
        assertEquals(EmotionJudge.EmotionConfidence.HIGH, out.confidence)
    }

    @Test
    fun refine_labels_filtered_and_intensity_clamped() {
        val base = verdict(intensity = 2)
        val out = ExpertPanel.applyEmotionRefine(
            base,
            IncomingEmotionRefine(state = "watch", labels = listOf(" anger ", "bogus"), intensity = 9),
        )
        assertEquals(listOf("anger"), out.labels.map { it.label })
        // 强度越界 → 沿用库判，LLM 不能虚报。
        assertEquals(2, out.intensity)

        val zero = ExpertPanel.applyEmotionRefine(
            base,
            IncomingEmotionRefine(state = "watch", labels = listOf("anger"), intensity = 0),
        )
        assertEquals(2, zero.intensity)
    }

    // ── 合并附带情绪：白名单短路不吞情绪 ──────────────────────────────────

    @Test
    fun merge_attaches_emotion_verdict() {
        val emotion = verdict()
        val review = ExpertPanel.merge("你到底想怎么样", strongHandoff = null, llm = null, emotion = emotion)
        assertSame(emotion, review.emotion)
    }

    @Test
    fun whitelist_skip_still_carries_emotion() {
        // 「好的」连发正是原则 3 情形 B 的信号源：skip 只短路 handoff/隐私/达成，
        // 情绪裁决必须随行。
        val emotion = verdict(
            state = EmotionJudge.EmotionState.CONFIRMED,
            intensity = 2,
            labels = listOf(EmotionJudge.LabelScore(EmotionLabels.PERFUNCTORY, -3.0)),
        )
        val review = ExpertPanel.merge("好的", strongHandoff = null, llm = null, emotion = emotion)
        assertTrue(review.handoff.skipLlm)
        assertSame(emotion, review.emotion)
    }

    @Test
    fun merge_defaults_to_calm_verdict() {
        val review = ExpertPanel.merge("你到底想怎么样", strongHandoff = null, llm = null)
        assertSame(EmotionJudge.CALM_VERDICT, review.emotion)
    }
}
