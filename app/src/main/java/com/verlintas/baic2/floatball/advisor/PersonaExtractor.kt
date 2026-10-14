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

import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.advisor.data.CONTACT_TYPE_PERSON
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 对方画像的 AI 萃取器（方案 §4.2）。
 *
 * 纪律：
 * - 只对 person 档案萃取（群聊不跑）；
 * - 节流由 [maybeExtract] 按 session_bindings.lastExtractAt + 回合配对数执行，
 *   默认每 5 个「屏幕+回复」配对至多一次；
 * - 模型只产出**草稿**（confirmed=0）：confirmed/user 值在
 *   [ContactRepository.applyDraft] 内拒绝覆盖；
 * - 单次最多落 [MAX_DRAFTS] 条；字段目录只取 persona_schema 中
 *   owner=their 且 extract=true 的定义，新增字段只改 JSON 资产。
 */
@Singleton
class PersonaExtractor @Inject constructor(
    private val contacts: ContactRepository,
    private val schemas: PersonaSchemas,
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {
    /** 一个已完成配对：[at] 为屏幕消息时间，用于「距上次萃取」节流。 */
    data class Turn(val screen: String, val reply: String, val at: Long)

    /**
     * 节流满足时跑一次萃取并写草稿；返回实际新落库的草稿数。
     * 未到节流点 / 非 person / 无配置返回 0；调用方无需额外兜底。
     */
    suspend fun maybeExtract(contactId: Long, conversationId: Long, turns: List<Turn>): Int {
        val profile = contacts.profile(contactId) ?: return 0
        if (profile.contactType != CONTACT_TYPE_PERSON) return 0

        val since = contacts.binding(conversationId)?.lastExtractAt ?: 0L
        val fresh = turns.filter { it.at > since }
        if (fresh.size < EXTRACT_EVERY_TURNS) return 0

        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull() ?: return 0
        val extractable = schemas.get().theirFields.filter { it.extract }
        if (extractable.isEmpty()) return 0

        val raw = runCatching {
            auxiliaryTasks.complete(
                config = config,
                systemPrompt = systemPrompt(extractable),
                userPrompt = buildMaterial(fresh.takeLast(MAX_FED_TURNS)),
                maxTokens = 512,
                temperature = 0.2,
            )
        }.getOrNull() ?: return 0

        val parsed = parse(raw, extractable)
        var landed = 0
        for (draft in parsed.take(MAX_DRAFTS)) {
            val stored = contacts.applyDraft(
                contactId = contactId,
                field = draft.field,
                value = draft.value,
                evidence = draft.evidence,
                action = draft.actionEnum,
            )
            if (stored != null) landed++
        }
        // 无论模型是否提取到东西都盖时间戳，避免空窗期每回合重复调用。
        contacts.setLastExtractAt(conversationId, System.currentTimeMillis())
        return landed
    }

    private fun systemPrompt(fields: List<PersonaFieldDef>): String {
        val catalog = fields.joinToString("\n") { def ->
            val options = def.options?.takeIf { it.isNotEmpty() }?.let { "，可选值：${it.joinToString("/")}" } ?: ""
            "- ${def.key}（${def.label}，${def.type}$options）"
        }
        return """
            你是聊天记录画像分析器。从给定的「屏幕内容」（用户与对方的聊天实录）和「参谋回复」中，
            提取关于【对方】本人的稳定事实，用于逐步建立客户/联系人画像。

            规则：
            1. 只提取有明确对话依据、跨回合仍成立的事实（身份、职业、偏好、关系、预算、需求、禁忌等）；
               一次性寒暄、情绪、时间不确定的内容不要提取；严禁臆测。
            2. 只提取【对方】的信息，不要提取「我」（用户本人）的信息。
            3. field 必须且只能取自以下字段目录；目录外的键一律丢弃：
            $catalog
            4. action：新值或覆盖旧草稿用 set；tags 类字段追加标签用 add、撤销标签用 remove。
            5. value 简洁客观，单个不超过 80 字；tags 类多个标签用顿号分隔；
               evidence 填对话中支持该事实的原文短句（不超过 60 字）。
            6. 最多输出 $MAX_DRAFTS 条；没有值得提取的内容就输出 []。
            7. 只输出 JSON 数组，不要任何解释或 Markdown 代码块。格式：
            [{"field":"字段键","value":"值","action":"set","evidence":"原文短句"}]
        """.trimIndent()
    }

    private fun buildMaterial(turns: List<Turn>): String =
        buildString {
            turns.forEachIndexed { index, turn ->
                append("--- 回合").append(index + 1).append(" ---\n")
                append("屏幕内容：\n").append(turn.screen.take(SCREEN_CHARS)).append("\n\n")
                append("参谋回复：\n").append(turn.reply.take(REPLY_CHARS)).append("\n\n")
            }
        }

    private fun parse(raw: String, fields: List<PersonaFieldDef>): List<DraftJson> {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val list = runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DraftJson.serializer()), text.substring(start, end + 1))
        }.getOrDefault(emptyList())
        val allowed = fields.associateBy { it.key }
        return list.filter { draft ->
            val def = allowed[draft.field] ?: return@filter false
            val value = draft.value.trim()
            val action = draft.action?.lowercase()?.takeIf { it in ACTIONS }
            value.isNotEmpty() && value.length <= VALUE_MAX_CHARS && action != null &&
                // tags 类才允许 add/remove；其余只接受 set。
                (action == "set" || def.type == PersonaFieldType.TAGS)
        }.map {
            DraftJson(
                field = it.field,
                value = it.value.trim(),
                action = it.action!!.lowercase(),
                evidence = it.evidence?.trim()?.take(EVIDENCE_MAX_CHARS),
                actionEnum = when (it.action!!.lowercase()) {
                    "add" -> ContactRepository.DraftAction.ADD
                    "remove" -> ContactRepository.DraftAction.REMOVE
                    else -> ContactRepository.DraftAction.SET
                },
            )
        }
    }

    @Serializable
    private data class DraftJson(
        val field: String = "",
        val value: String = "",
        val action: String? = null,
        val evidence: String? = null,
        @kotlinx.serialization.Transient val actionEnum: ContactRepository.DraftAction =
            ContactRepository.DraftAction.SET,
    )

    private companion object {
        const val EXTRACT_EVERY_TURNS = 5
        const val MAX_FED_TURNS = 6
        const val MAX_DRAFTS = 5
        const val SCREEN_CHARS = 600
        const val REPLY_CHARS = 300
        const val VALUE_MAX_CHARS = 80
        const val EVIDENCE_MAX_CHARS = 60
        val ACTIONS = setOf("set", "add", "remove")

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
