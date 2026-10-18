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

import com.verlintas.baic2.floatball.advisor.StyleVocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保温选题的风格词归一与三重过滤裁决（V-C 方案 §4.2）：identity 定禁区、
 * chatStyle/facet 定降权、closeness 定深度、heatScore 调权——全纯函数。
 */
class TopicStyleRulesTest {

    // ── 风格词归一（素材 tag 自由文本 → 风格词） ──────────────────────────

    @Test
    fun matchOf_exactAliasAndContains() {
        assertEquals(listOf(TopicStyleTags.FOOD), TopicStyleTags.matchOf("吃喝"))
        assertEquals(listOf(TopicStyleTags.FOOD), TopicStyleTags.matchOf("美食"))
        assertEquals(listOf(TopicStyleTags.FOOD), TopicStyleTags.matchOf("food"))
        assertEquals(listOf(TopicStyleTags.JOKE), TopicStyleTags.matchOf("搞笑段子"))
        assertEquals(listOf(TopicStyleTags.FUN), TopicStyleTags.matchOf("宠物"))
        assertTrue(TopicStyleTags.matchOf("天文学").isEmpty())
        assertTrue(TopicStyleTags.matchOf(null).isEmpty())
        assertTrue(TopicStyleTags.matchOf("  ").isEmpty())
    }

    @Test
    fun styleTagsOf_mergesDistinct() {
        assertEquals(
            listOf(TopicStyleTags.FOOD, TopicStyleTags.JOKE),
            TopicStyleTags.styleTagsOf(listOf("美食", "搞笑", "吃喝")),
        )
    }

    @Test
    fun isWorkFacet_keywordMatch() {
        assertTrue(TopicStyleTags.isWorkFacet(listOf("办事周到")))
        assertTrue(TopicStyleTags.isWorkFacet(listOf("专业", "靠谱")))
        assertFalse(TopicStyleTags.isWorkFacet(listOf("钓鱼", "烘焙")))
        assertFalse(TopicStyleTags.isWorkFacet(emptyList()))
    }

    // ── filterFor：identity 禁区 ───────────────────────────────────────

    @Test
    fun leader_identity_forbidsJokeFoodFun() {
        val filter = TopicStyleRules.filterFor(
            identity = StyleVocab.IDENTITY_LEADER,
            chatStyle = null,
            closeness = null,
            facetTags = emptyList(),
            heatScore = null,
        )
        assertEquals(setOf(TopicStyleTags.JOKE, TopicStyleTags.FOOD, TopicStyleTags.FUN), filter.forbiddenTags)
        assertTrue(filter.penaltyTags.isEmpty())
        assertEquals(3, filter.maxDepth)
    }

    // ── filterFor：chatStyle / facet 降权 ──────────────────────────────

    @Test
    fun seriousStyle_penalizesFunFood() {
        val filter = TopicStyleRules.filterFor(
            identity = null,
            chatStyle = "严肃",
            closeness = null,
            facetTags = emptyList(),
            heatScore = null,
        )
        assertTrue(filter.forbiddenTags.isEmpty())
        assertEquals(setOf(TopicStyleTags.FUN, TopicStyleTags.FOOD), filter.penaltyTags)
    }

    @Test
    fun workFacet_penalizesFunFood_andStacksWithStyle() {
        val filter = TopicStyleRules.filterFor(
            identity = null,
            chatStyle = "严肃",
            closeness = null,
            facetTags = listOf("办事周到"),
            heatScore = null,
        )
        assertEquals(setOf(TopicStyleTags.FUN, TopicStyleTags.FOOD), filter.penaltyTags)
    }

    // ── filterFor：closeness 深度门 ────────────────────────────────────

    @Test
    fun closenessBoundsMaxDepth() {
        assertEquals(
            1,
            TopicStyleRules.filterFor(null, null, 0, emptyList(), null).maxDepth,
        )
        assertEquals(
            3,
            TopicStyleRules.filterFor(null, null, 2, emptyList(), null).maxDepth,
        )
        assertEquals(
            3,
            TopicStyleRules.filterFor(null, null, 3, emptyList(), null).maxDepth,
        )
        // 未萃取不设限（交阶段门控兜底，防止开天窗）。
        assertEquals(
            3,
            TopicStyleRules.filterFor(null, null, null, emptyList(), null).maxDepth,
        )
    }

    // ── filterFor：heatScore 调权 ──────────────────────────────────────

    @Test
    fun coldHeat_shrinksDepthAndAddsPenalty() {
        val filter = TopicStyleRules.filterFor(
            identity = null,
            chatStyle = null,
            closeness = 2,
            facetTags = emptyList(),
            heatScore = 20,
        )
        assertEquals(1, filter.maxDepth)
        assertEquals(setOf(TopicStyleTags.JOKE, TopicStyleTags.FUN), filter.penaltyTags)
    }

    @Test
    fun hotHeat_deepensButCapsAtThree() {
        assertEquals(
            3,
            TopicStyleRules.filterFor(null, null, 1, emptyList(), 80).maxDepth, // 2+1 cap 3
        )
        assertEquals(
            3,
            TopicStyleRules.filterFor(null, null, 3, emptyList(), 80).maxDepth,
        )
    }

    @Test
    fun midHeat_noAdjustment() {
        val filter = TopicStyleRules.filterFor(null, null, 1, emptyList(), 50)
        assertEquals(2, filter.maxDepth)
        assertTrue(filter.penaltyTags.isEmpty())
    }

    // ── TopicFilter 种子亲疏门 ─────────────────────────────────────────

    @Test
    fun seedAllowed_gatesByCloseness() {
        val filter = TopicFilter(closeness = 1)
        assertTrue(filter.seedAllowed(0))
        assertTrue(filter.seedAllowed(1))
        assertFalse(filter.seedAllowed(2))
        // 未萃取（null）不设限。
        assertTrue(TopicFilter(closeness = null).seedAllowed(3))
    }
}
