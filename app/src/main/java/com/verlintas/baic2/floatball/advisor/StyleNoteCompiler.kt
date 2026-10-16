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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor

import android.util.Log
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.strategy.CompiledCustomNote
import com.verlintas.baic2.floatball.advisor.strategy.StyleNoteRules
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * customNote 行为化编译器（P3 §八）：一次 AuxiliaryTasks 小调用（≤250 tok / 0.2）
 * 把用户手写的一句话解释成 `{"do":[],"dont":[]}`，经 [StyleNoteRules] 确定性裁剪后
 * 写回 compiledCustomJson（confirmed 仍为 0，等用户确认）。
 *
 * 任何失败（无配置/超时/JSON 不合规）只记 Log + [StyleNoteCompileState].markFailed，
 * 不抛异常、不阻塞慢车道其他作业，更不影响参谋主回合（增强失败退化口径）。
 */
@Singleton
class StyleNoteCompiler @Inject constructor(
    private val db: AdvisorDatabase,
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
    private val compileState: StyleNoteCompileState,
) {
    /**
     * 编译指定风格卡的 customNote。
     * @return true=已写回待确认草稿；false=跳过或失败（失败原因见日志与内存态）。
     */
    suspend fun compile(cardId: Long): Boolean {
        val dao = db.styleCardDao()
        val card = dao.findById(cardId) ?: return false
        val note = card.customNote?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
        if (config == null) {
            compileState.markFailed(cardId)
            return false
        }
        val raw = runCatching {
            auxiliaryTasks.complete(
                config = config,
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = "用户补充的说话要求：$note",
                maxTokens = MAX_TOKENS,
                temperature = TEMPERATURE,
            )
        }.onFailure { Log.d(TAG, "customNote compile failed: card=$cardId", it) }
            .getOrNull()
        val compiled = StyleNoteRules.parse(raw)
        if (compiled == null || raw == null) {
            compileState.markFailed(cardId)
            Log.d(TAG, "customNote compile rejected: card=$cardId raw=${raw?.take(120)}")
            return false
        }
        val json = strategyJson.encodeToString(CompiledCustomNote.serializer(), compiled)
        dao.setCompiledDraft(cardId, json, System.currentTimeMillis())
        compileState.clear(cardId)
        return true
    }

    private companion object {
        const val TAG = "StyleNoteCompiler"
        const val MAX_TOKENS = 250
        const val TEMPERATURE = 0.2

        val SYSTEM_PROMPT = """
            你把用户对「聊天说话方式」的一句口语补充，解释成可执行的行为规则。
            只输出一个 JSON 对象，格式严格为 {"do":[],"dont":[]}：
            - do=应该怎么做，dont=应该避免什么；
            - 每组至多 3 条，每条不超过 20 个汉字，具体、可照做；
            - 不要复述原句，不要解释，不要输出 JSON 以外的任何内容；
            - 无法从补充中得出的组留空数组。
        """.trimIndent()
    }
}
