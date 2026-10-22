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

package com.verlintas.baic2.floatball.advisor.data

import com.verlintas.baic2.floatball.advisor.emotion.EmotionJudge
import com.verlintas.baic2.floatball.advisor.emotion.EmotionLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 情绪台账落库映射（E-d，方案 §4.7）：判定结论 → emotion_events 行、
 * 标签得分/证据链 JSON 往返。台账键 `emotion_*` 家族供回放检索。
 */
class EmotionEventMappingTest {

    private fun verdict(): EmotionJudge.EmotionVerdict = EmotionJudge.EmotionVerdict(
        state = EmotionJudge.EmotionState.WATCH,
        labels = listOf(
            EmotionJudge.LabelScore(EmotionLabels.PERFUNCTORY, -2.5),
            EmotionJudge.LabelScore(EmotionLabels.DISSATISFACTION, -1.0),
        ),
        intensity = 2,
        confidence = EmotionJudge.EmotionConfidence.MEDIUM,
        evidence = listOf(
            EmotionJudge.Evidence(
                kind = EmotionJudge.Evidence.KIND_BEHAVIOR,
                term = "short_confirm_streak",
                labels = listOf(EmotionLabels.PERFUNCTORY),
                polarity = -1,
                intensityDelta = 0,
                score = -1.0,
                note = "连续 3 条短确认且零主动追问",
            ),
            EmotionJudge.Evidence(
                kind = EmotionJudge.Evidence.KIND_WORD,
                term = "呵呵",
                labels = listOf(EmotionLabels.DISSATISFACTION),
                polarity = -1,
                intensityDelta = 1,
                score = -1.5,
                note = "",
            ),
        ),
        reason = "连续短确认累积命中原则 3",
    )

    @Test
    fun toEventEntity_maps_codes_ids_and_reason() {
        val row = verdict().toEventEntity(
            contactId = 7L,
            conversationId = 42L,
            turnMessageId = 99L,
            reason = EMOTION_REASON_REFINE,
            createdAt = 1_000L,
        )
        assertEquals(7L, row.contactId)
        assertEquals(42L, row.conversationId)
        assertEquals(99L, row.turnMessageId)
        assertEquals(EMOTION_STATE_WATCH, row.state)
        assertEquals(EMOTION_CONFIDENCE_MEDIUM, row.confidence)
        assertEquals(2, row.intensity)
        assertEquals(EMOTION_REASON_REFINE, row.reason)
        assertEquals(1_000L, row.createdAt)
    }

    @Test
    fun label_scores_json_roundtrip() {
        val labels = listOf(
            EmotionJudge.LabelScore(EmotionLabels.PERFUNCTORY, -2.5),
            EmotionJudge.LabelScore(EmotionLabels.JOY, 1.25),
        )
        assertEquals(labels, decodeEmotionLabelScores(encodeEmotionLabelScores(labels)))
    }

    @Test
    fun evidence_json_roundtrip() {
        val evidence = verdict().evidence
        assertEquals(evidence, decodeEmotionEvidence(encodeEmotionEvidence(evidence)))
    }

    @Test
    fun malformed_json_decodes_to_empty_tables() {
        assertTrue(decodeEmotionLabelScores("{oops").isEmpty())
        assertTrue(decodeEmotionEvidence("not-json").isEmpty())
    }

    @Test
    fun calm_verdict_lands_as_calm_high_with_empty_tables() {
        val row = EmotionJudge.CALM_VERDICT.toEventEntity(
            contactId = 1L,
            conversationId = null,
            turnMessageId = null,
            reason = EMOTION_REASON_JUDGE,
            createdAt = 5_000L,
        )
        assertEquals(EMOTION_STATE_CALM, row.state)
        assertEquals(EMOTION_CONFIDENCE_HIGH, row.confidence)
        assertEquals(1, row.intensity)
        assertTrue(decodeEmotionLabelScores(row.labelsJson).isEmpty())
        assertTrue(decodeEmotionEvidence(row.evidenceJson).isEmpty())
    }
}
