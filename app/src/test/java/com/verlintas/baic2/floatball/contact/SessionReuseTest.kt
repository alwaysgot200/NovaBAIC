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

package com.verlintas.baic2.floatball.contact

import com.verlintas.baic2.floatball.advisor.data.SCENE_CHAT
import com.verlintas.baic2.floatball.advisor.data.SCENE_NON_CHAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话复用强化（V-B，方案 §3）纯规则单测：池隔离 / 相似度阈值 / 断崖路由。
 */
class SessionReuseTest {

    // ---- B2 池隔离 ----

    @Test
    fun nonChatTurnOnlyMatchesNonChatPool() {
        assertTrue(SessionReuse.samePool(SCENE_NON_CHAT, SCENE_NON_CHAT))
        assertEquals(false, SessionReuse.samePool(SCENE_NON_CHAT, SCENE_CHAT))
    }

    @Test
    fun chatTurnNeverMatchesNonChatPool() {
        assertTrue(SessionReuse.samePool(SCENE_CHAT, SCENE_CHAT))
        assertEquals(false, SessionReuse.samePool(SCENE_CHAT, SCENE_NON_CHAT))
    }

    @Test
    fun filterPoolKeepsOnlySameSceneCandidates() {
        val candidates = listOf(
            SessionReuse.Candidate(1, SCENE_NON_CHAT),
            SessionReuse.Candidate(2, SCENE_CHAT),
            SessionReuse.Candidate(3, SCENE_NON_CHAT),
            SessionReuse.Candidate(4, SCENE_CHAT),
        )
        assertEquals(
            listOf(1L, 3L),
            SessionReuse.filterPool(candidates, SCENE_NON_CHAT),
        )
        assertEquals(
            listOf(2L, 4L),
            SessionReuse.filterPool(candidates, SCENE_CHAT),
        )
    }

    // ---- B1 复用优先：取相似度最高者续用 ----

    @Test
    fun routePicksHighestSimilarityForReuse() {
        val scored = listOf(
            SessionReuse.Scored(11, 0.32),
            SessionReuse.Scored(22, 0.78),
            SessionReuse.Scored(33, 0.51),
        )
        val route = SessionReuse.route(scored, cliffThreshold = 0.25)
        assertEquals(SessionReuse.Route.Reuse(22, 0.78), route)
    }

    @Test
    fun routeReusesAtExactThreshold() {
        // 达到阈值（含等于）即续用：断崖只在「低于阈值」时触发（宁可续用）。
        val route = SessionReuse.route(
            listOf(SessionReuse.Scored(7, 0.25)),
            cliffThreshold = 0.25,
        )
        assertEquals(SessionReuse.Route.Reuse(7, 0.25), route)
    }

    // ---- B3 断崖：低于阈值即新开 ----

    @Test
    fun routeCliffsBelowThreshold() {
        val route = SessionReuse.route(
            listOf(SessionReuse.Scored(11, 0.10), SessionReuse.Scored(22, 0.24)),
            cliffThreshold = 0.25,
        )
        assertEquals(SessionReuse.Route.Cliff(bestId = 22, bestSimilarity = 0.24), route)
    }

    @Test
    fun routeWithNoComparableCandidatesReturnsNoComparable() {
        assertEquals(SessionReuse.Route.NoComparable, SessionReuse.route(emptyList(), 0.25))
    }

    @Test
    fun routeWithZeroThresholdNeverCliffs() {
        // 阈值调到 0 = 永不新开（最保守档）：任何相似度都续用最高者。
        val route = SessionReuse.route(
            listOf(SessionReuse.Scored(11, 0.0), SessionReuse.Scored(22, 0.05)),
            cliffThreshold = 0.0,
        )
        assertTrue(route is SessionReuse.Route.Reuse)
        assertEquals(22L, (route as SessionReuse.Route.Reuse).conversationId)
    }
}
