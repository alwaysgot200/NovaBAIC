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

package com.verlintas.baic2.floatball.brief

import com.verlintas.baic2.floatball.advisor.data.GOAL_ACTIVE
import com.verlintas.baic2.floatball.advisor.data.GoalEntity
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L2
import com.verlintas.baic2.floatball.advisor.data.MISSION_ADVANCED
import com.verlintas.baic2.floatball.advisor.data.MISSION_HELD
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_ADVANCE
import com.verlintas.baic2.floatball.advisor.data.STAGE_EDGE_INIT
import com.verlintas.baic2.floatball.advisor.data.StageHistoryEntity
import com.verlintas.baic2.floatball.advisor.data.TalkMissionEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 简报纯函数全分支（方案 §10 M0 验收：提示词含关键事实、日期格式正确、
 * 超长截断生效；模型输出宽松解析；空窗口数据不触发 LLM 的判定分支）。
 */
class BriefCompilerPromptTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun ms(date: String, hour: Int = 12): Long =
        LocalDate.parse(date).atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private fun facts(
        history: List<StageHistoryEntity> = emptyList(),
        missions: List<TalkMissionEntity> = emptyList(),
        goals: List<GoalEntity> = emptyList(),
    ) = BriefFacts(
        contactName = "张三",
        rangeStart = ms("2026-10-04"),
        rangeEnd = ms("2026-10-18"),
        history = history,
        missions = missions,
        goals = goals,
        currentStageCode = "warm_up",
        currentStageEnteredAt = ms("2026-10-05", 9),
    )

    private fun history(at: String = "2026-10-10", reason: String? = "对方主动询问价格") =
        StageHistoryEntity(
            id = 1,
            contactId = 1,
            scenarioCode = "dating",
            fromCode = "first_touch",
            toCode = "warm_up",
            edge = STAGE_EDGE_ADVANCE,
            reason = reason,
            at = ms(at),
        )

    private fun mission(
        text: String = "再确认一次周末见面的安排",
        result: String = MISSION_ADVANCED,
    ) = TalkMissionEntity(
        id = 2,
        conversationId = 1,
        contactId = 1,
        mission = text,
        result = result,
        createdAt = ms("2026-10-12"),
    )

    private fun goal() = GoalEntity(
        id = 3,
        contactId = 1,
        horizon = HORIZON_L2,
        title = "两周内约出来见面",
        status = GOAL_ACTIVE,
        progressNote = "对方答应周末吃饭",
    )

    // ── 提示词拼装 ────────────────────────────────────────────────────────

    @Test
    fun userPrompt_containsKeyFactsAndDates() {
        val prompt = BriefPrompts.userPrompt(
            facts(history = listOf(history()), missions = listOf(mission()), goals = listOf(goal())),
            zone,
        )
        assertTrue(prompt.contains("联系人：张三"))
        assertTrue(prompt.contains("统计区间：10-04 ~ 10-18"))
        assertTrue(prompt.contains("当前阶段：warm_up"))
        assertTrue(prompt.contains("（自 10-05 起）"))
        assertTrue(prompt.contains("两周内约出来见面"))
        assertTrue(prompt.contains("进展：对方答应周末吃饭"))
        assertTrue(prompt.contains("10-10 推进：first_touch → warm_up"))
        assertTrue(prompt.contains("对方主动询问价格"))
        assertTrue(prompt.contains("10-12 再确认一次周末见面的安排"))
        assertTrue(prompt.contains("结局：推进"))
    }

    @Test
    fun userPrompt_clipsOverlongLines() {
        val longMission = "很".repeat(300) + "TAIL_MARKER"
        val prompt = BriefPrompts.userPrompt(facts(missions = listOf(mission(longMission))), zone)
        assertFalse(prompt.contains("TAIL_MARKER"))
        assertTrue(prompt.contains("…"))
        assertTrue(prompt.contains("很".repeat(BriefPrompts.LINE_MAX)))
    }

    @Test
    fun userPrompt_respectsTotalBudget() {
        val facts = facts(
            history = (0..19).map { history(at = "2026-10-1${it % 10}") },
            missions = (0..19).map { mission(text = "话题${"细节".repeat(45)}$it") },
            goals = (1..6).map { n ->
                goal().copy(id = n.toLong(), title = "目标$n${"说明".repeat(60)}")
            },
        )
        val prompt = BriefPrompts.userPrompt(facts, zone)
        assertTrue("budget=${prompt.length}", prompt.length <= BriefPrompts.PROMPT_BUDGET + 50)
    }

    @Test
    fun userPrompt_omitsEmptySections() {
        val prompt = BriefPrompts.userPrompt(facts(missions = listOf(mission())), zone)
        assertFalse(prompt.contains("阶段变迁"))
        assertFalse(prompt.contains("有效目标"))
        assertTrue(prompt.contains("任务令台账"))
    }

    // ── 空数据判定（GoalTab 不调 LLM 的依据） ─────────────────────────────

    @Test
    fun hasWindowData_emptyMeansNoLlm() {
        assertFalse(BriefPrompts.hasWindowData(facts()))
    }

    @Test
    fun hasWindowData_goalsAloneDoNotTriggerLlm() {
        assertFalse(BriefPrompts.hasWindowData(facts(goals = listOf(goal()))))
    }

    @Test
    fun hasWindowData_historyOrMissionTriggersLlm() {
        assertTrue(BriefPrompts.hasWindowData(facts(history = listOf(history()))))
        assertTrue(BriefPrompts.hasWindowData(facts(missions = listOf(mission()))))
    }

    // ── 模型输出解析 ──────────────────────────────────────────────────────

    @Test
    fun parse_acceptsCleanJson() {
        val content = BriefPrompts.parse(
            "{\"stagePosition\":\"邀约后的等待期\",\"recentEvents\":[\"对方答应周末见面\"]," +
                "\"goalProgress\":[\"L2 有进展\"],\"risks\":[],\"nextMoves\":[\"周五再确认\"]}",
        )
        assertNotNull(content)
        assertEquals("邀约后的等待期", content!!.stagePosition)
        assertEquals(listOf("对方答应周末见面"), content.recentEvents)
        assertEquals(listOf("周五再确认"), content.nextMoves)
        assertTrue(content.risks.isEmpty())
    }

    @Test
    fun parse_acceptsNoisyFencedJson() {
        val content = BriefPrompts.parse(
            "好的，这是简报：\n```json\n{\"stagePosition\":\"x\",\"recentEvents\":[\"y\"]}\n```\n希望有帮助",
        )
        assertNotNull(content)
        assertEquals("x", content!!.stagePosition)
        assertEquals(listOf("y"), content.recentEvents)
    }

    @Test
    fun parse_missingFieldsFallBackToDefaults() {
        val content = BriefPrompts.parse("{\"stagePosition\":\"只有阶段\"}")
        assertNotNull(content)
        assertEquals("只有阶段", content!!.stagePosition)
        assertTrue(content.recentEvents.isEmpty())
        assertTrue(content.goalProgress.isEmpty())
        assertTrue(content.risks.isEmpty())
        assertTrue(content.nextMoves.isEmpty())
    }

    @Test
    fun parse_malformedReturnsNull() {
        assertNull(BriefPrompts.parse(""))
        assertNull(BriefPrompts.parse("今天状态不好，不想输出 JSON"))
        assertNull(BriefPrompts.parse("{broken"))
    }

    // ── 协议与标签 ────────────────────────────────────────────────────────

    @Test
    fun systemPrompt_containsOutputProtocolAndAntiHallucination() {
        val system = BriefPrompts.systemPrompt()
        listOf("stagePosition", "recentEvents", "goalProgress", "risks", "nextMoves", "禁止编造")
            .forEach { key -> assertTrue("missing $key", system.contains(key)) }
    }

    @Test
    fun labels_mapKnownCodes() {
        assertEquals("推进", BriefPrompts.missionResultLabel(MISSION_ADVANCED))
        assertEquals("维持", BriefPrompts.missionResultLabel(MISSION_HELD))
        assertEquals("起步", BriefPrompts.edgeLabel(STAGE_EDGE_INIT))
    }
}
