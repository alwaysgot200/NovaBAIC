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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 废话方向规划的提示词与解析纯函数（V-C 方案 §4.4）：宽松解析、限长去重、
 * 七行画像输入渲染。
 */
class TopicPlannerPromptsTest {

    // ── parse：宽松解析 ────────────────────────────────────────────────

    @Test
    fun parse_plainArray() {
        assertEquals(listOf("行业见闻", "工作观察"), TopicPlannerPrompts.parse("""["行业见闻","工作观察"]"""))
    }

    @Test
    fun parse_stripsCodeFence() {
        assertEquals(
            listOf("行业见闻"),
            TopicPlannerPrompts.parse("```json\n[\"行业见闻\"]\n```"),
        )
    }

    @Test
    fun parse_takesLastArrayInProse() {
        assertEquals(
            listOf("周末爬山"),
            TopicPlannerPrompts.parse("好的，方向如下：[\"周末爬山\"] 请查收。"),
        )
    }

    @Test
    fun parse_trimsDedupesAndCapsAtThree() {
        assertEquals(
            listOf("行业见闻", "工作观察", "健身日常"),
            TopicPlannerPrompts.parse("""["行业见闻"," 行业见闻 ","工作观察","健身日常"," fourth "]"""),
        )
    }

    @Test
    fun parse_dropsOverlongOrBlankDirections() {
        // 12 字放行（含 12），13 字丢弃；空白条目丢弃。
        val twelve = "一二三四五六七八九十十一"
        val thirteen = "一二三四五六七八九十十一二"
        assertEquals(
            listOf(twelve),
            TopicPlannerPrompts.parse("""["$twelve","$thirteen","   "]"""),
        )
    }

    @Test
    fun parse_malformedOrMissing_returnsEmpty() {
        assertEquals(emptyList<String>(), TopicPlannerPrompts.parse("不给数组"))
        assertEquals(emptyList<String>(), TopicPlannerPrompts.parse("[\"只有开括号"))
        assertEquals(emptyList<String>(), TopicPlannerPrompts.parse("[]"))
        assertEquals(emptyList<String>(), TopicPlannerPrompts.parse(""))
    }

    // ── userPrompt：七行画像输入 ───────────────────────────────────────

    @Test
    fun userPrompt_rendersAllSevenLines() {
        val prompt = TopicPlannerPrompts.userPrompt(
            stageLabel = "建立信任",
            identity = "领导",
            chatStyle = "严肃",
            closeness = 2,
            heatScore = 80,
            myFacets = listOf("专业", "可靠"),
            usedTopics = listOf("行业见闻", "项目进度"),
        )
        assertTrue(prompt.contains("目标阶段：建立信任"))
        assertTrue(prompt.contains("对方身份：领导"))
        assertTrue(prompt.contains("对方聊天风格：严肃"))
        assertTrue(prompt.contains("亲疏等级：亲近"))
        assertTrue(prompt.contains("反馈热度：80/100"))
        assertTrue(prompt.contains("我的侧面标签：专业、可靠"))
        assertTrue(prompt.contains("近期已聊话题：行业见闻、项目进度"))
    }

    @Test
    fun userPrompt_fallsBackForMissingProfile() {
        val prompt = TopicPlannerPrompts.userPrompt(
            stageLabel = "",
            identity = null,
            chatStyle = null,
            closeness = null,
            heatScore = null,
            myFacets = emptyList(),
            usedTopics = emptyList(),
        )
        assertTrue(prompt.contains("目标阶段：（未设定）"))
        assertTrue(prompt.contains("对方身份：（未知）"))
        assertTrue(prompt.contains("亲疏等级：（待定）"))
        assertTrue(prompt.contains("反馈热度：（无信号）"))
        assertTrue(prompt.contains("我的侧面标签：（未设置）"))
        assertTrue(prompt.contains("近期已聊话题：（无）"))
    }

    // ── systemPrompt 与常量契约 ────────────────────────────────────────

    @Test
    fun systemPrompt_statesOutputContract() {
        val prompt = TopicPlannerPrompts.systemPrompt()
        assertTrue(prompt.contains("JSON 数组"))
        assertTrue(prompt.contains("12"))
        assertTrue(prompt.contains("2~3"))
        assertTrue(prompt.contains("避开"))
    }

    @Test
    fun constants_matchSlowLaneBudget() {
        assertEquals(300, TopicPlannerPrompts.MAX_TOKENS)
        assertEquals(0.4, TopicPlannerPrompts.TEMPERATURE, 0.0)
    }

    // ── TopicPlanner 方向编解码（companion 纯函数） ─────────────────────

    @Test
    fun directions_roundtripAndDefensive() {
        assertEquals(
            """["行业见闻","工作观察"]""",
            TopicPlanner.encodeDirections(listOf("行业见闻", "工作观察")),
        )
        assertEquals(
            listOf("行业见闻", "工作观察"),
            TopicPlanner.decodeDirections("""["行业见闻","工作观察"]"""),
        )
        assertEquals(emptyList<String>(), TopicPlanner.decodeDirections(null))
        assertEquals(emptyList<String>(), TopicPlanner.decodeDirections("not-json"))
        assertEquals(emptyList<String>(), TopicPlanner.decodeDirections("{}"))
    }

    // ── F1：周方向节流判定（companion 纯函数） ──────────────────────────

    private val week = 7L * 24 * 60 * 60 * 1000

    @Test
    fun weeklyCycle_withinWindowSkipsLlm() {
        // 周内（第 0/3/6.9 天）→ 同周期，复用既有方向跳过 LLM。
        assertTrue(TopicPlanner.isWithinWeeklyCycle(0L, 0L))
        assertTrue(TopicPlanner.isWithinWeeklyCycle(0L, 3 * week / 7))
        assertTrue(TopicPlanner.isWithinWeeklyCycle(0L, week - 1))
        // 恰满一周（默认 4h 一班检点最多 42 班）→ 视为新周期，重新规划。
        assertEquals(false, TopicPlanner.isWithinWeeklyCycle(0L, week))
        assertEquals(false, TopicPlanner.isWithinWeeklyCycle(0L, week * 3))
    }

    @Test
    fun weeklyCycle_clockRollbackStaysWithinCycle() {
        // 时钟回拨产生负差也视为同周期（宁可少规划一次，不做负窗口放行）。
        assertTrue(TopicPlanner.isWithinWeeklyCycle(week, 0L))
        assertTrue(TopicPlanner.isWithinWeeklyCycle(0L, -week))
    }
}
