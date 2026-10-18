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

import android.util.Log
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.advisor.EngagementRules
import com.verlintas.baic2.floatball.advisor.GoalRepository
import com.verlintas.baic2.floatball.advisor.SelfFacets
import com.verlintas.baic2.floatball.advisor.StyleVocab
import com.verlintas.baic2.floatball.advisor.StrategyBrief
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.TopicPlanEntity
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 废话方向规划器（V-C，方案 §4.4）：Tactician 慢车道在**周检点**（SCHEDULED +
 * 周报编译之后）为每个 engagement=proactive 联系人产出「下周期废话方向 2~3 条」，
 * 落 topic_plans（每联系人每周一条 REPLACE upsert）。方向是**基调**而非话题——
 * 输入=目标阶段 + 对方 identity/chatStyle + 我方 facet + heatScore + 已用话题
 * 去重指纹；TopicEngine 选题时优先消费 latest 方向（经 [TopicEngine.Materials.planDirections]），
 * 无方向自动退回四源，方向在去重窗口内视为已聊自动轮换。
 *
 * 纪律：AuxiliaryTasks 慢车道（≤[MAX_TOKENS] tok、temp 0.4）；任何失败静默降级
 * （无方向 = 退回四源，保温链永不因 Planner 断链）；只对 proactive 档生效，
 * reactive/mute 直接跳过不耗 token。
 */
@Singleton
class TopicPlanner @Inject constructor(
    private val db: AdvisorDatabase,
    private val goals: GoalRepository,
    private val strategyBrief: StrategyBrief,
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {

    /**
     * 为联系人生成本周期废话方向并落库；返回实际落库的方向（失败/跳过为空表）。
     * [nowMs] 为周检点时间；cycleStart 取当日零点（与周报口径对齐）。
     */
    suspend fun planWeekly(contactId: Long, nowMs: Long = System.currentTimeMillis()): List<String> {
        // 周期节流（F1）：周内已有方向直接复用，跳过 LLM——周检点默认 4h 一班，
        // 无节流则每个 proactive 联系人每天最多 6 次规划调用；口径与周报侧
        // refreshWeeklyIfStale 的 7 天节流对齐。
        val existing = runCatching { db.topicPlanDao().latest(contactId) }.getOrNull()
        if (existing != null && isWithinWeeklyCycle(existing.cycleStart, nowMs)) {
            return decodeDirections(existing.directionsJson)
        }
        val profile = runCatching { db.contactProfileDao().findById(contactId) }.getOrNull()
            ?: return emptyList()
        // 维持度门：只有 proactive 档参与方向规划（reactive/mute 不耗 token）。
        if (!EngagementRules.proactiveEligible(profile.engagement)) return emptyList()
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull() ?: return emptyList()

        val pack = runCatching { strategyBrief.pack(contactId) }.getOrNull()
        val stageCode = pack
            ?.let { p -> runCatching { goals.stage(contactId, p.code) }.getOrNull()?.stageCode }
        val stageLabel = pack?.stages
            ?.firstOrNull { it.code == stageCode }?.label
            ?: stageCode.orEmpty()
        val usedTopics = runCatching { goals.recentMissions(contactId, USED_MISSIONS) }
            .getOrDefault(emptyList())
            .mapNotNull { TopicEngine.decodeWarmChatTopic(it.mission) }
        val facets = SelfFacets.decode(profile.selfFacetJson)

        val raw = runCatching {
            auxiliaryTasks.complete(
                config = config,
                systemPrompt = TopicPlannerPrompts.systemPrompt(),
                userPrompt = TopicPlannerPrompts.userPrompt(
                    stageLabel = stageLabel,
                    identity = profile.identity,
                    chatStyle = profile.chatStyle,
                    closeness = profile.closeness,
                    heatScore = profile.heatScore,
                    myFacets = facets,
                    usedTopics = usedTopics,
                ),
                maxTokens = TopicPlannerPrompts.MAX_TOKENS,
                temperature = TopicPlannerPrompts.TEMPERATURE,
            )
        }.getOrNull() ?: return emptyList()

        val directions = TopicPlannerPrompts.parse(raw)
        if (directions.isEmpty()) return emptyList()
        val plan = TopicPlanEntity(
            contactId = contactId,
            cycleStart = startOfDayMs(nowMs),
            directionsJson = encodeDirections(directions),
            createdAt = nowMs,
        )
        return runCatching {
            db.topicPlanDao().upsert(plan)
            directions
        }.getOrElse {
            Log.d(TAG, "topic plan persist failed: $it")
            emptyList()
        }
    }

    /** 本机时区的当日零点（与 ProactiveCheckpoint 频控窗口同口径）。 */
    private fun startOfDayMs(nowMs: Long): Long =
        Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 方向 JSON 编码（纯函数，落库与测试共用）。 */
    companion object {

        private const val TAG = "TopicPlanner"

        /** 已聊话题回看条数（与去重窗口外冷却判定的输入一致，读近 60 条台账）。 */
        private const val USED_MISSIONS = 60

        /** 周方向节流窗口：与周报同周期（7 天）。 */
        const val WEEKLY_CYCLE_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * 周期节流判定（F1 纯函数）：[cycleStartMs] 距 [nowMs] 不足一周（含时钟
         * 回拨产生的负差）即视为同周期，调用方跳过 LLM 复用既有方向。
         */
        fun isWithinWeeklyCycle(cycleStartMs: Long, nowMs: Long): Boolean =
            nowMs - cycleStartMs < WEEKLY_CYCLE_MS

        /** 方向 JSON 数组解码（容忍畸形：非数组/解析失败一律空表，不抛）。 */
        fun decodeDirections(json: String?): List<String> {
            val text = json?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
            return runCatching {
                planJson.decodeFromString(ListSerializer(String.serializer()), text)
            }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun encodeDirections(directions: List<String>): String =
            planJson.encodeToString(ListSerializer(String.serializer()), directions)

        private val planJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

/** 方向规划的提示词与解析（纯函数，零 Android 依赖，JVM 可单测）。 */
internal object TopicPlannerPrompts {

    internal const val MAX_TOKENS = 300
    internal const val TEMPERATURE = 0.4

    /** 方向条数与单条长度上限（方向是基调不是话题，要短）。 */
    internal const val DIRECTIONS_MIN = 2
    internal const val DIRECTIONS_MAX = 3
    internal const val DIRECTION_MAX_CHARS = 12

    fun systemPrompt(): String = """
        你是聊天参谋的保温话题规划师。根据给定的联系人画像与近期已聊话题，规划下个周期
        的闲聊方向（基调，不是具体话题）。

        规则：
        1. 方向要贴合对方的身份与聊天风格，匹配当前关系阶段的亲疏深度：领导/严肃的人
           避开吃喝玩乐和搞笑，陌生关系避开太私人的方向；
        2. 避开「已聊话题」里列出的内容及其近义；
        3. 每条方向不超过 $DIRECTION_MAX_CHARS 个字，输出 ${DIRECTIONS_MIN}~$DIRECTIONS_MAX 条；
        4. 只输出一个 JSON 数组，不要解释、不要 Markdown，格式：["行业见闻","工作观察"]
    """.trimIndent()

    fun userPrompt(
        stageLabel: String?,
        identity: String?,
        chatStyle: String?,
        closeness: Int?,
        heatScore: Int?,
        myFacets: List<String>,
        usedTopics: List<String>,
    ): String = buildString {
        appendLine("目标阶段：${stageLabel?.takeIf { it.isNotBlank() } ?: "（未设定）"}")
        appendLine("对方身份：${identity?.takeIf { it.isNotBlank() } ?: "（未知）"}")
        appendLine("对方聊天风格：${chatStyle?.takeIf { it.isNotBlank() } ?: "（未知）"}")
        appendLine("亲疏等级：${closeness?.let { StyleVocab.closenessLabel(it) } ?: "（待定）"}")
        appendLine("反馈热度：${heatScore?.let { "$it/100" } ?: "（无信号）"}")
        appendLine("我的侧面标签：${myFacets.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "（未设置）"}")
        appendLine("近期已聊话题：${usedTopics.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "（无）"}")
    }

    /** 宽松解析：去围栏截首个 [ ]，逐条 trim/去重/限长，按上限截取；畸形返回空表。 */
    fun parse(raw: String): List<String> {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val list = runCatching {
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }.decodeFromString(ListSerializer(String.serializer()), text.substring(start, end + 1))
        }.getOrDefault(emptyList())
        return list.asSequence()
            .map { it.replace(Regex("\\s+"), "").trim() }
            .filter { it.isNotEmpty() && it.length <= DIRECTION_MAX_CHARS }
            .distinct()
            .take(DIRECTIONS_MAX)
            .toList()
    }
}
