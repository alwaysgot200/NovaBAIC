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

import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** HandoffGate 分类输出协议（输出永远不可信：畸形/超时一律按 null 处理）。 */
@Serializable
internal data class HandoffLlmVerdict(
    /** handoff / normal，其余归一为 null（uncertain）。 */
    val kind: String = "normal",
    /** kind=handoff 时给类别码；解析失败不影响接管结论。 */
    val category: String = "",
)

/**
 * 接管判定门（方案 2026-10-20 §2.2，v2 主判定器）：自动域内每条来信必跑的
 * 轻 LLM 分类。中文表达灵活、词表穷举不完（用户拍板），泛化交给 few-shot
 * 例句学习（[HandoffRules.EXAMPLES_SECTION]）。
 *
 * 成本口径：≤120 tok、8s 硬超时，相对回合本体增量 <10%；PromptDedup 已挡
 * 同屏重复来信。**失败降级**：超时/无配置/畸形输出返回 null（调用方按
 * [HandoffRules.judge] 走词表备胎 + uncertain 回避约束），绝不打断回答链。
 */
@Singleton
class HandoffGate @Inject constructor(
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {

    /**
     * 轻分类：[text] 为对方来信原文（IncomingCue 抽取的来信段）。
     * @return true=handoff / false=normal / null=不可判定（降级备胎）。
     */
    suspend fun classify(text: String): Boolean? = withContext(Dispatchers.Default) {
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            ?: return@withContext null
        val raw = withTimeoutOrNull(CLASSIFY_TIMEOUT_MS) {
            runCatching {
                auxiliaryTasks.complete(
                    config = config,
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = text.take(TEXT_CLIP_CHARS),
                    maxTokens = CLASSIFY_MAX_TOKENS,
                    temperature = CLASSIFY_TEMPERATURE,
                )
            }.getOrNull()
        }
        raw?.let { parse(it)?.kind?.trim()?.lowercase()?.let { kind -> kind == "handoff" } }
    }

    private fun parse(raw: String): HandoffLlmVerdict? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            strategyJson.decodeFromString(HandoffLlmVerdict.serializer(), text.substring(start, end + 1))
        }.getOrNull()
    }

    private companion object {
        /** 慢车道口径（同 Gatekeeper 量级：短超时、低温度、小输出预算）。 */
        const val CLASSIFY_MAX_TOKENS = 120
        const val CLASSIFY_TEMPERATURE = 0.1
        const val CLASSIFY_TIMEOUT_MS = 8_000L

        /** 来信截断（分类只需最后一句实质内容，超长句式前段基本是铺垫）。 */
        const val TEXT_CLIP_CHARS = 300

        /**
         * 分类器系统提示词：判据写死「答案只有用户本人持有」+ 四类定义 +
         * few-shot 例句库。批准偏置的镜像——**宁严勿松**（漏判=无人值守编造
         * 事实，误判=主人多看一眼通知）；拿不准输出 normal，由生成端回避
         * 约束兜底。禁止对纯闲聊/寒暄/表白情绪类来信判 handoff。
         */
        val SYSTEM_PROMPT = buildString {
            append("你是聊天托管系统的分流器。判断对方最新一条消息是否属于「必须由用户本人接管回答」")
            append("——即正确答案只有用户本人知道/持有/有权决定，AI 代答等于编造或越权。\n")
            append("四类接管：\n")
            append("1. 索取物品：要照片/文件/定位/地址/电话等实物或信息资产；\n")
            append("2. 日程出席：问几点回家/什么时候到/来不来/约时间地点——需要用户真实日程；\n")
            append("3. 私人事实：问住址/收入/年龄/婚恋等个人事实——编造即事故；\n")
            append("4. 决策请求：选哪个/要不要答应/行不行——决策权在用户本人。\n")
            append("例句参考（覆盖不限于）：\n")
            append(HandoffRules.EXAMPLES_SECTION).append('\n')
            append("非接管（正常闲聊/寒暄/情绪交流/对方自述，AI 可以自然接话）：一律 normal。\n")
            append("拿不准 → normal。只输出一个 JSON 对象，不要解释：\n")
            append("{\"kind\":\"handoff\",\"category\":\"索取物品|日程出席|私人事实|决策请求\"}\n")
            append("{\"kind\":\"normal\"}")
        }
    }
}
