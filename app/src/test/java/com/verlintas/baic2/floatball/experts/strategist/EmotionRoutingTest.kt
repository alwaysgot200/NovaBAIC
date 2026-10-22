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

import com.verlintas.baic2.floatball.advisor.emotion.EmotionJudge
import com.verlintas.baic2.floatball.advisor.emotion.EmotionLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 情绪→方针映射（方案 §4.6 映射表全行 + 执法口径 4 条）：确定档定行、派生态
 * 派生、强度下限、观察/平稳不干预、表外负面兜底——全部纯函数锁死。
 */
class EmotionRoutingTest {

    // ── §4.6 映射表全行 ──────────────────────────────────────────────────

    @Test
    fun rage_angerIntensity4MapsEmpathyAndBansHumorStoryExemplifying() {
        val route = EmotionRouting.route(verdict(labels = listOf(ls(EmotionLabels.ANGER, 3.0)), intensity = 4))
        assertTrue(route.active)
        assertEquals("rage", route.key)
        assertEquals(4, route.intensity)
        assertEquals(listOf(APPROACH_EMPATHY), route.approaches)
        assertTrue(APPROACH_HUMOR in route.bannedApproaches)
        assertTrue(APPROACH_STORY in route.bannedApproaches)
        assertTrue(StrategistRules.DIM_EXEMPLIFYING in route.bannedDims)
        assertTrue(route.bannedNote.contains("推进"))
    }

    @Test
    fun rage_belowIntensity4NotRage() {
        val route = EmotionRouting.route(verdict(labels = listOf(ls(EmotionLabels.ANGER, 3.0)), intensity = 3))
        // 表未列组合落保守兜底（口径 4），但绝不冒充盛怒行。
        assertFalse(route.key == "rage")
    }

    @Test
    fun unhappiness_2AndAboveMapsEmpathyBenefitListBansValueShow() {
        for (intensity in listOf(2, 3, 5)) {
            val route = EmotionRouting.route(
                verdict(labels = listOf(ls(EmotionLabels.DISSATISFACTION, 2.0)), intensity = intensity),
            )
            assertTrue(route.active)
            assertEquals("dissatisfaction", route.key)
            assertEquals(listOf(APPROACH_EMPATHY, APPROACH_BENEFIT_LIST), route.approaches)
            assertEquals(listOf(APPROACH_VALUE_SHOW), route.bannedApproaches)
        }
    }

    @Test
    fun unhappiness_intensity1Inactive() {
        val route = EmotionRouting.route(
            verdict(labels = listOf(ls(EmotionLabels.DISSATISFACTION, 0.8)), intensity = 1),
        )
        assertFalse(route.active)
    }

    @Test
    fun hesitation_anyIntensityMapsBenefitListStoryBansPushyClose() {
        for (intensity in listOf(1, 3, 5)) {
            val route = EmotionRouting.route(
                verdict(labels = listOf(ls(EmotionLabels.HESITATION, 1.5)), intensity = intensity),
            )
            assertTrue(route.active)
            assertEquals("hesitation", route.key)
            assertEquals(listOf(APPROACH_BENEFIT_LIST, APPROACH_STORY), route.approaches)
            assertTrue(route.bannedNote.contains("逼单"))
        }
    }

    @Test
    fun excitement_joyIntensity3MapsValueShowWithPushWindow() {
        val route = EmotionRouting.route(verdict(labels = listOf(ls(EmotionLabels.JOY, 3.0)), intensity = 3))
        assertTrue(route.active)
        assertEquals("excitement", route.key)
        assertEquals(listOf(APPROACH_VALUE_SHOW), route.approaches)
        assertTrue(route.guidance.contains("推进窗口"))
    }

    @Test
    fun excitement_belowIntensity3Inactive() {
        val route = EmotionRouting.route(verdict(labels = listOf(ls(EmotionLabels.JOY, 1.2)), intensity = 2))
        assertFalse(route.active)
    }

    @Test
    fun perfunctory_anyIntensityMapsCoolDownBansPushAndLongReply() {
        for (intensity in listOf(1, 2, 5)) {
            val route = EmotionRouting.route(
                verdict(labels = listOf(ls(EmotionLabels.PERFUNCTORY, 1.0)), intensity = intensity),
            )
            assertTrue(route.active)
            assertEquals("perfunctory", route.key)
            assertTrue(route.bannedNote.contains("推进"))
            assertTrue(route.bannedNote.contains("长篇"))
        }
    }

    @Test
    fun disappointmentAndGrievance_intensity3MapsEmpathyAndCompensation() {
        for (label in listOf(EmotionLabels.DISAPPOINTMENT, EmotionLabels.GRIEVANCE)) {
            val route = EmotionRouting.route(verdict(labels = listOf(ls(label, 2.0)), intensity = 3))
            assertTrue(route.active)
            assertEquals("disappointment", route.key)
            assertEquals(listOf(APPROACH_EMPATHY), route.approaches)
            assertTrue(route.bannedNote.contains("辩解"))
        }
    }

    @Test
    fun disappointment_belowIntensity3FallsBack() {
        val route = EmotionRouting.route(
            verdict(labels = listOf(ls(EmotionLabels.DISAPPOINTMENT, 1.5)), intensity = 2),
        )
        assertFalse(route.key == "disappointment")
    }

    @Test
    fun probing_anyIntensityMapsConservative() {
        val route = EmotionRouting.route(verdict(labels = listOf(ls(EmotionLabels.PROBING, 1.0)), intensity = 1))
        assertTrue(route.active)
        assertEquals("probing", route.key)
        assertTrue(route.bannedNote.contains("侥幸"))
    }

    @Test
    fun low_anyIntensityMapsEmpathyListeningBansPreaching() {
        val route = EmotionRouting.route(verdict(labels = listOf(ls(EmotionLabels.LOW, 1.5)), intensity = 4))
        assertTrue(route.active)
        assertEquals("low", route.key)
        assertEquals(listOf(APPROACH_EMPATHY), route.approaches)
        assertTrue(route.bannedNote.contains("说教"))
        assertTrue(route.bannedNote.contains("推进"))
    }

    // ── 执法口径：观察/平稳不干预（原则 5） ──────────────────────────────

    @Test
    fun calmStateNeverIntervenes() {
        val route = EmotionRouting.route(
            verdict(
                state = EmotionJudge.EmotionState.CALM,
                labels = listOf(ls(EmotionLabels.ANGER, 3.0)),
                intensity = 5,
            ),
        )
        assertFalse(route.active)
    }

    @Test
    fun watchStateNeverIntervenes() {
        val route = EmotionRouting.route(
            verdict(
                state = EmotionJudge.EmotionState.WATCH,
                labels = listOf(ls(EmotionLabels.ANGER, 3.0), ls(EmotionLabels.PERFUNCTORY, 1.0)),
                intensity = 5,
            ),
        )
        assertFalse(route.active)
    }

    @Test
    fun nullOrLabellessVerdictInactive() {
        assertFalse(EmotionRouting.route(null).active)
        assertFalse(EmotionRouting.route(verdict(labels = emptyList(), intensity = 3)).active)
        assertFalse(EmotionRouting.route(EmotionJudge.CALM_VERDICT).active)
    }

    // ── 执法口径：主导标签定行 + 表外负面兜底 ────────────────────────────

    @Test
    fun dominantLabelDecidesRowRegardlessOfOrder() {
        val route = EmotionRouting.route(
            verdict(
                labels = listOf(ls(EmotionLabels.DISSATISFACTION, 0.5), ls(EmotionLabels.JOY, 3.0)),
                intensity = 3,
            ),
        )
        assertEquals("excitement", route.key)
    }

    @Test
    fun unlistedNegativeLabelFallsToConservativeFallback() {
        for (label in listOf(EmotionLabels.ANGER, EmotionLabels.SADNESS, EmotionLabels.FEAR, EmotionLabels.DISGUST)) {
            val route = EmotionRouting.route(verdict(labels = listOf(ls(label, 1.5)), intensity = 3))
            assertTrue(route.active)
            assertEquals("negative_fallback", route.key)
            assertEquals(listOf(APPROACH_EMPATHY), route.approaches)
            assertTrue(route.bannedNote.contains("推进"))
        }
    }

    @Test
    fun redlineArmedOnlyOnRageRow() {
        // E-f 情绪红线只锁盛怒行：盛怒裁决映射出的行带 redline，其余行恒 false。
        val rage = EmotionRouting.route(verdict(labels = listOf(ls(EmotionLabels.ANGER, 2.0)), intensity = 4))
        assertEquals("rage", rage.key)
        assertTrue(rage.redline)
        val rows = listOf(
            EmotionRouting.UNHAPPY,
            EmotionRouting.HESITANT,
            EmotionRouting.EXCITED,
            EmotionRouting.PERFUNCTORY,
            EmotionRouting.DISAPPOINTED,
            EmotionRouting.PROBING,
            EmotionRouting.LOW,
            EmotionRouting.NEGATIVE_FALLBACK,
            EmotionRoute.INACTIVE,
        )
        for (row in rows) {
            assertFalse("row=${row.key}", row.redline)
        }
    }

    // ── 测试辅助 ─────────────────────────────────────────────────────────

    private fun verdict(
        state: EmotionJudge.EmotionState = EmotionJudge.EmotionState.CONFIRMED,
        labels: List<EmotionJudge.LabelScore> = emptyList(),
        intensity: Int = 3,
    ): EmotionJudge.EmotionVerdict = EmotionJudge.EmotionVerdict(
        state = state,
        labels = labels,
        intensity = intensity,
        confidence = EmotionJudge.EmotionConfidence.MEDIUM,
        evidence = emptyList(),
        reason = "test",
    )

    private fun ls(label: String, score: Double): EmotionJudge.LabelScore =
        EmotionJudge.LabelScore(label, score)
}
