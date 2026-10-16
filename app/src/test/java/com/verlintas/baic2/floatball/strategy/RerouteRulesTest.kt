/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
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
import com.verlintas.baic2.floatball.advisor.data.StageStateEntity
import com.verlintas.baic2.floatball.advisor.strategy.PrivateStageDef
import com.verlintas.baic2.floatball.advisor.strategy.RerouteRules
import com.verlintas.baic2.floatball.advisor.strategy.RoadmapPayload
import com.verlintas.baic2.floatball.advisor.strategy.RoadmapStage
import com.verlintas.baic2.floatball.advisor.strategy.StageGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RerouteRulesTest {

    private val pack = ScenarioPack(
        code = "sales_b2b",
        label = "B2B 销售",
        stages = listOf(
            ScenarioStage("a", "初触"),
            ScenarioStage("b", "跟进"),
            ScenarioStage("c", "成交"),
        ),
        retreatSignals = emptyList(),
    )

    private fun state(code: String, version: Int? = 3, locked: Boolean = true) =
        StageStateEntity(
            contactId = 1L,
            scenarioCode = "sales_b2b",
            stageCode = code,
            enteredAt = 1_000L,
            advanceHits = """{"s1":2}""",
            roadmapVersion = version,
            locked = locked,
        )

    @Test
    fun targetCode_mappingHit_missToFirst_invalidToFirst() {
        val graph = StageGraph.resolve(pack, null)
        // 有映射且合法 → 映射站。
        assertEquals("c", RerouteRules.targetCode("a", graph, mapOf("a" to "c")))
        // 无当前站映射 → 新链首站（绝不乱猜）。
        assertEquals("a", RerouteRules.targetCode("x", graph, mapOf("a" to "c")))
        assertEquals("a", RerouteRules.targetCode("b", graph, emptyMap()))
        // 映射指向新图不存在的站（已被 sanitize 清洗）→ 首站兜底。
        assertEquals("a", RerouteRules.targetCode("a", graph, mapOf("a" to "ghost")))
        // 当前站未知也安全落首站。
        assertEquals("a", RerouteRules.targetCode(null, graph, mapOf("a" to "c")))
    }

    @Test
    fun migrateState_pinsVersion_unlocks_clearsHits_syncsPathTag() {
        // 新路线含一个带 pathTag 的私有支线，映射把当前站指过去。
        val roadmap = RoadmapPayload(
            stages = listOf(RoadmapStage("a", "g"), RoadmapStage("b", "g")),
            privateStages = listOf(PrivateStageDef(code = "p9", label = "招标支线", pathTag = "tender")),
            stageMapping = mapOf("b" to "p9"),
        )
        val graph = StageGraph.resolve(pack, StageGraph.sanitize(pack, roadmap))
        val now = 9_000L
        val moved = RerouteRules.migrateState(
            state = state("b", version = 2, locked = true),
            graph = graph,
            mapping = roadmap.stageMapping,
            newVersion = 4,
            now = now,
            reason = "重规划路线",
        )!!
        assertEquals("p9", moved.stageCode)
        assertEquals(4, moved.roadmapVersion)
        assertFalse("reroute 是显式意图，必须解锁", moved.locked)
        assertEquals("{}", moved.advanceHits)
        assertEquals(now, moved.enteredAt)
        assertEquals("tender", moved.pathTag)
        assertEquals("重规划路线", moved.evidence)
    }

    @Test
    fun migrateState_noMappingFallsToFirst() {
        val graph = StageGraph.resolve(pack, null)
        val moved = RerouteRules.migrateState(
            state = state("c", version = 1),
            graph = graph,
            mapping = emptyMap(),
            newVersion = 2,
            now = 5L,
            reason = "重规划路线",
        )!!
        assertEquals("a", moved.stageCode)
        assertEquals(2, moved.roadmapVersion)
    }

    @Test
    fun isPending_onlyNewUnconfirmedDraftCounts() {
        // 钉版 3，最新 4 未确认 → 待确认。
        assertTrue(RerouteRules.isPending(pinnedVersion = 3, latestVersion = 4, latestConfirmed = false))
        // 最新已确认（采用完成）→ 不再待确认。
        assertFalse(RerouteRules.isPending(pinnedVersion = 4, latestVersion = 4, latestConfirmed = true))
        // v5 旧数据无版本指针 → 永不判定待确认。
        assertFalse(RerouteRules.isPending(pinnedVersion = null, latestVersion = 4, latestConfirmed = false))
        // 最新比钉版还旧（异常数据）→ 不待确认。
        assertFalse(RerouteRules.isPending(pinnedVersion = 5, latestVersion = 4, latestConfirmed = false))
    }
}
