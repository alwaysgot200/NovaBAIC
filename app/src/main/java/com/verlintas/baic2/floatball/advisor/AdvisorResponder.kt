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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor

import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRequest
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.core.model.StreamEvent
import com.verlintas.baic2.core.network.provider.ProviderFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 老参谋的「精简直连」回答通道（与全功能 [com.verlintas.baic2.core.engine.AgentLoop]
 * 二选一，由 AdvisorPrefs.memoryMode 决定）。
 *
 * 区别与依据：AgentLoop 的 CHAT 模式每回合固定附带 7 个记忆工具的 JSON
 * schema 与大段记忆协议系统提示词，且模型一旦调用工具会发起第二轮请求
 * （整条上下文重发，input tokens 近翻倍）。老参谋在回合外已主动注入核心
 * 记忆/唤醒笔记/联系人事实/到期承诺/成交要件，记忆写回另有滚动摘要与
 * 画像萃取承担，因此自动看屏回合不需要工具。本通道：
 * - tools 恒为空：不发工具 schema，不存在工具二次往返；
 * - 系统提示词只保留参谋人设与已注入的记忆/联系人段落；
 * - 单轮流式，保留 [StreamEvent.Usage] 供用量统计。
 *
 * 业务能力不被削弱：App 侧记忆注入在两条通道完全一致；需要模型回合内
 * 自主检索/写入记忆的场景（手动参谋与追问）由「智能/增强」模式继续走
 * AgentLoop。
 */
@Singleton
class AdvisorResponder @Inject constructor(
    private val providerFactory: ProviderFactory,
) {
    /** 直连通道的流式事件（与 AgentEvent 同构的最小子集）。 */
    sealed interface Chunk {
        data class Delta(val text: String) : Chunk
        data class Usage(val inputTokens: Long, val outputTokens: Long) : Chunk
        data class Failure(val reason: String) : Chunk
        data object Done : Chunk
    }

    /**
     * 发起一次无工具参谋回合。
     *
     * @param maxTokens 回答硬上限（长度档），null 表示沿用模型默认。
     */
    fun stream(
        config: ProviderConfig,
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): Flow<Chunk> = flow {
        val provider = providerFactory.create(config.provider)
        val request = ChatRequest(
            config = config.copy(maxTokens = maxTokens),
            systemPrompt = systemPrompt,
            messages = messages,
            tools = emptyList(),
        )
        provider.stream(request).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> emit(Chunk.Delta(event.text))
                is StreamEvent.Usage -> emit(
                    Chunk.Usage(event.promptTokens ?: 0L, event.completionTokens ?: 0L),
                )
                is StreamEvent.Failed -> emit(Chunk.Failure(event.error.message ?: "provider_error"))
                else -> Unit
            }
        }
        emit(Chunk.Done)
    }
}
