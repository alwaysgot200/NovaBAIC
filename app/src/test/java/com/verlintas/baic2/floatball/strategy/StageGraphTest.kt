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
import com.verlintas.baic2.floatball.advisor.strategy.EDGE_KIND_BRANCH
import com.verlintas.baic2.floatball.advisor.strategy.EDGE_KIND_RETREAT
import com.verlintas.baic2.floatball.advisor.strategy.EDGE_KIND_SKIP
import com.verlintas.baic2.floatball.advisor.strategy.EdgeDef
import com.verlintas.baic2.floatball.advisor.strategy.PrivateStageDef
import com.verlintas.baic2.floatball.advisor.strategy.RoadmapPayload
import com.verlintas.baic2.floatball.advisor.strategy.RoadmapStage
import com.verlintas.baic2.floatball.advisor.strategy.StageGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageGraphTest {

    private val now = 2_000_000L

    private val pack = ScenarioPack(
        code = "sales_b2b",
        label = "B2B 销售",
        stages = listOf(
            ScenarioStage("a", "初触", advanceSignals = listOf("s1", "s2", "s3")),
            ScenarioStage("b", "跟进", advanceSignals = listOf("s4", "s5")),
            ScenarioStage("c", "成交", advanceSignals = listOf("s6")),
        ),
        retreatSignals = listOf("明确拒绝"),
    )

    private fun state(code: String, hits: Set<String> = emptySet(), locked: Boolean = false) =
        StageStateEntity(
            contactId = 1L,
            scenarioCode = "sales_b2b",
            stageCode = code,
            enteredAt = now,
            advanceHits = "{" + hits.joinToString(",") { "\"$it\":1" } + "}",
            locked = locked,
        )

    // ── 线性缺省等价 P2 ────────────────────────────────────────────────────

    @Test
    fun nullRoadmap_buildsImplicitLinearEdges() {
        val graph = StageGraph.resolve(pack, null)
        val edges = graph.outgoingFrom("a")
        assertEquals(1, edges.size)
        assertEquals("b", edges.single().to)
        assertEquals(3, graph.backbone.size)
        assertTrue(graph.privates.isEmpty())
    }

    @Test
    fun linearThreshold_advanceEquivalentToP2() {
        val graph = StageGraph.resolve(pack, null)
        // 甲站阈值 max(1,3-1)=2：1 个信号不动，2 个前进到 b。
        assertNull(StageGraph.autoMove(state("a", setOf("s1")), graph, setOf("s1"), emptySet(), emptyList()))
        val move = StageGraph.autoMove(state("a", setOf("s1", "s2")), graph, setOf("s1", "s2"), emptySet(), emptyList())
        assertEquals("b", move!!.edge.to)
        val applied = StageGraph.applyEdge(state("a"), move.edge, graph, now + 1, move.reason)!!
        assertEquals("b", applied.stageCode)
        assertEquals("{}", applied.advanceHits)
    }

    @Test
    fun terminalStage_noAutoMove() {
        val graph = StageGraph.resolve(pack, null)
        assertNull(StageGraph.autoMove(state("c", setOf("s6")), graph, setOf("s6"), emptySet(), emptyList()))
    }

    // ── locked ─────────────────────────────────────────────────────────────

    @Test
    fun lockedStage_blocksAllAutoMoves() {
        val graph = StageGraph.resolve(pack, roadmapWithRetreat())
        val s = state("b", setOf("s4", "s5"), locked = true)
        assertNull(StageGraph.autoMove(s, graph, setOf("s4", "s5"), emptySet(), listOf("明确拒绝")))
        assertTrue(StageGraph.offers(s, graph, setOf("有预算")).isEmpty())
    }

    // ── retreat ────────────────────────────────────────────────────────────

    @Test
    fun retreatEdge_movesBackWhenHit_noEdgeStaysNull() {
        // b 有 retreat 边回 a：命中包级退缩词 → 回站。
        val graph = StageGraph.resolve(pack, roadmapWithRetreat())
        val move = StageGraph.autoMove(state("b"), graph, emptySet(), emptySet(), listOf("明确拒绝"))
        assertEquals("a", move!!.edge.to)
        assertEquals(EDGE_KIND_RETREAT, move.edge.kind)

        // c 没有 retreat 边：即使有退缩命中也不自动移动（调用方走降温+原地留痕）。
        assertNull(StageGraph.autoMove(state("c"), graph, emptySet(), emptySet(), listOf("明确拒绝")))
    }

    // ── skip / branch ──────────────────────────────────────────────────────

    @Test
    fun skipEdge_autoMovesAndBranchNeedsConfirm() {
        val roadmap = RoadmapPayload(
            stages = listOf(RoadmapStage("a", "g"), RoadmapStage("b", "g"), RoadmapStage("c", "g")),
            edges = listOf(
                EdgeDef(from = "a", to = "c", kind = EDGE_KIND_SKIP, condition = "直接拍板"),
                EdgeDef(from = "a", to = "b", kind = EDGE_KIND_BRANCH, condition = "客户要求走招标"),
            ),
        )
        val graph = StageGraph.resolve(pack, StageGraph.sanitize(pack, roadmap))
        // branch 被 sanitize 强制 needConfirm。
        assertTrue(graph.outgoingFrom("a").first { it.kind == EDGE_KIND_BRANCH }.needConfirm)

        val skip = StageGraph.autoMove(state("a"), graph, emptySet(), setOf("客户直接拍板了"), emptyList())
        assertEquals("c", skip!!.edge.to)
        assertEquals(EDGE_KIND_SKIP, skip.edge.kind)

        val offers = StageGraph.offers(state("a"), graph, setOf("客户要求走招标流程"))
        assertEquals(1, offers.size)
        assertEquals("b", offers.single().edge.to)
        // offers 不进 autoMove。
        assertNull(StageGraph.autoMove(state("a"), graph, emptySet(), setOf("客户要求走招标流程"), emptyList()))
    }

    // ── 私有支线 + 清洗 ──────────────────────────────────────────────────────

    @Test
    fun sanitize_dropsIllegalEdgesAndDuplicatePrivateStages() {
        val raw = RoadmapPayload(
            stages = listOf(RoadmapStage("a", "g"), RoadmapStage("zzz", "非法站")),
            privateStages = listOf(
                PrivateStageDef(code = "p1", label = "支线一"),
                PrivateStageDef(code = "a", label = "与骨架重名"),
                PrivateStageDef(code = "p2", label = ""),
            ),
            edges = listOf(
                EdgeDef(from = "a", to = "p1"), // 合法 advance
                EdgeDef(from = "a", to = "ghost", kind = EDGE_KIND_SKIP), // 端点不存在
                EdgeDef(from = "zzz", to = "a"), // 起点随非法 stage 丢弃
            ),
        )
        val clean = StageGraph.sanitize(pack, raw)
        assertEquals(listOf("a"), clean.stages.map { it.code })
        assertEquals(listOf("p1"), clean.privateStages.map { it.code })
        assertEquals(1, clean.edges.size)
        assertEquals("p1", clean.edges.single().to)

        val graph = StageGraph.resolve(pack, clean)
        assertNotNull(graph.stage("p1"))
        // a 有两条 advance 出边：显式 a→p1 优先于隐式 a→b。
        val move = StageGraph.autoMove(state("a", setOf("s1", "s2")), graph, setOf("s1", "s2"), emptySet(), emptyList())
        assertEquals("p1", move!!.edge.to)
        val applied = StageGraph.applyEdge(state("a"), move.edge, graph, now + 9, move.reason)!!
        assertEquals("p1", applied.stageCode)
    }

    private fun roadmapWithRetreat(): RoadmapPayload = RoadmapPayload(
        stages = listOf(RoadmapStage("a", "g"), RoadmapStage("b", "g"), RoadmapStage("c", "g")),
        edges = listOf(EdgeDef(from = "b", to = "a", kind = EDGE_KIND_RETREAT)),
    )
}
