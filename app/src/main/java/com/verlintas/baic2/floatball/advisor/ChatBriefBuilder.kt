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

package com.verlintas.baic2.floatball.advisor

import com.verlintas.baic2.floatball.advisor.data.MY_PERSONA_CONTACT_ID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 绑定联系人参谋回合的提示词组装（方案 §6.2）：业务目标 + 语言风格 +
 * 双方结构化人设（字段目录驱动，空字段跳过）+ 以对方最新发言检索的话术
 * Top-3。输出为 [com.verlintas.baic2.core.engine.renderSystemPrompt] 的
 * custom 段落，与上游 memoryContext 分轨、与 ADVISOR_STYLE 叠加。
 *
 * 取数纪律（§6.5）：目标/风格/人设只取 AdvisorDatabase 按 contactId 精确
 * 读取，绝不来自全局记忆，防止串档；未确认（confirmed=false）的萃取草稿
 * 不进提示词。
 */
@Singleton
class ChatBriefBuilder @Inject constructor(
    private val contacts: ContactRepository,
    private val schemas: PersonaSchemas,
    private val playbooks: PlaybookRetriever,
) {
    /**
     * 返回拼装好的 custom 段落；档案/目标/人设/话术全空时返回 null，
     * 调用方退回到无 brief 的基线提示词。
     *
     * @param contactId 当前回合绑定的联系人档案 id。
     * @param latestScreenText 当前屏正文（增量优先），用于话术检索 query。
     */
    suspend fun buildSection(contactId: Long, latestScreenText: String): String? {
        if (contactId == MY_PERSONA_CONTACT_ID) return null
        val profile = contacts.profile(contactId) ?: return null
        val schema = schemas.get()

        val goal = profile.goal?.trim().orEmpty()
        val style = profile.style?.trim().orEmpty()
        val myLines = renderPersona(
            schema.myFields,
            contacts.myPersona().filter { it.confirmed },
        )
        val theirLines = renderPersona(
            schema.theirFields,
            contacts.fields(contactId).filter { it.confirmed },
        )

        val query = buildString {
            append(latestScreenText.trim())
            if (goal.isNotEmpty()) append(' ').append(goal)
        }.take(RETRIEVE_QUERY_CHARS)
        val playbookLines = runCatching {
            playbooks.retrieve(query = query, categories = null, limit = PLAYBOOK_TOP_K)
        }.getOrDefault(emptyList()).mapIndexed { index, item ->
            "${index + 1}. 【${item.category}·${item.scenario}】${item.content}"
        }

        val sections = buildList {
            if (goal.isNotEmpty()) add(SECTION_GOAL to goal)
            if (style.isNotEmpty()) add(SECTION_STYLE to style)
            if (myLines.isNotEmpty()) add(SECTION_MY to myLines.joinToString("\n"))
            if (theirLines.isNotEmpty()) add(SECTION_THEIR to theirLines.joinToString("\n"))
            if (playbookLines.isNotEmpty()) {
                add(SECTION_PLAYBOOKS to playbookLines.joinToString("\n"))
            }
        }
        if (sections.isEmpty()) return null

        return buildString {
            sections.forEachIndexed { index, (title, body) ->
                if (index > 0) append("\n\n")
                append(title).append('\n').append(body)
            }
            append("\n\n").append(OUTPUT_REQUIREMENTS)
        }
    }

    /** 按字段目录顺序渲染「标签: 值」，空值/未知字段自动跳过。 */
    private fun renderPersona(
        defs: List<PersonaFieldDef>,
        values: List<com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity>,
    ): List<String> {
        val byKey = values.associateBy { it.field }
        return defs.mapNotNull { def ->
            val entity = byKey[def.key] ?: return@mapNotNull null
            PersonaValues.renderLine(def.label, def.type, entity.value)
        }
    }

    private companion object {
        const val PLAYBOOK_TOP_K = 3
        const val RETRIEVE_QUERY_CHARS = 600

        const val SECTION_GOAL = "【本次聊天的业务目标】"
        const val SECTION_STYLE = "【我的语言风格】"
        const val SECTION_MY = "【我的人设】"
        const val SECTION_THEIR = "【对方的人设】"
        const val SECTION_PLAYBOOKS = "【可参考的话术】（结合当前场景选用，不要照搬）"

        const val OUTPUT_REQUIREMENTS =
            "【输出要求】\n" +
                "以「我」的身份给出 2~3 条建议回复（标注各自意图与风险），" +
                "并给出一句话策略提示。不要复述屏幕内容。"
    }
}
