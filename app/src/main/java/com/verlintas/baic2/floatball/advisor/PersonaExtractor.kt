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
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_OTHER
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
 *   间隔由 AdvisorPrefs 传入（默认每 5 个「屏幕+回复」配对至多一次）；
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
     *
     * @param everyTurns 距上次萃取至少新增多少个完整回合才再跑（设置页可调）。
     */
    suspend fun maybeExtract(
        contactId: Long,
        conversationId: Long,
        turns: List<Turn>,
        everyTurns: Int = DEFAULT_EVERY_TURNS,
    ): Int {
        val profile = contacts.profile(contactId) ?: return 0
        if (profile.contactType != CONTACT_TYPE_PERSON) return 0

        val since = contacts.binding(conversationId)?.lastExtractAt ?: 0L
        val fresh = turns.filter { it.at > since }
        if (fresh.size < everyTurns.coerceAtLeast(1)) return 0

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
        for (draft in parsed.fields.take(MAX_DRAFTS)) {
            val stored = contacts.applyDraft(
                contactId = contactId,
                field = draft.field,
                value = draft.value,
                evidence = draft.evidence,
                action = draft.actionEnum,
            )
            if (stored != null) landed++
        }
        // 联系方式一问双产出（2026-10-23）：电话/邮件/地点/证件落 AI 草稿
        // （confirmed=0），联系方式 Tab 人工确认后才生效并双写记忆。
        for (info in parsed.infos.take(MAX_INFO_DRAFTS)) {
            val stored = contacts.applyInfoDraft(
                contactId = contactId,
                kind = info.kind,
                label = info.label,
                value = info.value,
                evidence = info.evidence,
            )
            if (stored != null) landed++
        }
        // 一问双产出（V-C，方案 §4.1）：style 三标签与我方 facet 增量先落
        // 待确认通道（proposeStylePending 内部保证绝不覆盖手动值），核阅后转正。
        if (parsed.style.facet.isNotEmpty() || parsed.style.identity != null ||
            parsed.style.chatStyle != null || parsed.style.closeness != null
        ) {
            runCatching { contacts.proposeStylePending(contactId, parsed.style) }
                .onFailure { android.util.Log.d(TAG, "style pending failed", it) }
        }
        // 无论模型是否提取到东西都盖时间戳，避免空窗期每回合重复调用。
        contacts.setLastExtractAt(conversationId, System.currentTimeMillis())
        return landed
    }

    private fun systemPrompt(fields: List<PersonaFieldDef>): String {
        val catalog = fields.joinToString("\n") { def ->
            val options = def.options?.takeIf { it.isNotEmpty() }?.let {
                val customRule = if (def.allowCustom) "；确有依据但不在可选值内时才填你的原词" else "；只能从可选值中原样选取"
                "，可选值：${it.joinToString("/") { option -> option.label }}$customRule"
            } ?: ""
            "- ${def.key}（${def.label}，${def.type}$options）"
        }
        return """
            你是聊天记录画像分析器。从给定的「屏幕内容」（用户与对方的聊天实录）和「参谋回复」中，
            提取关于【对方】本人的稳定事实，用于逐步建立客户/联系人画像。

            规则：
            1. 只提取有明确对话依据、跨回合仍成立的事实（身份、职业、偏好、关系、预算、需求、禁忌等）；
               一次性寒暄、情绪、时间不确定的内容不要提取；严禁臆测。
            2. fields 只提取【对方】的信息；selfFacet 才提取「我」（用户本人）在这段关系里
               呈现的侧面标签（如 办事周到/国企背景/随和耐烦），每条不超过 6 字、最多 3 条。
            3. fields 的 field 必须且只能取自以下字段目录；目录外的键一律丢弃；
               带可选值的字段，value 必须原样使用可选值中文，不要自造同义词：
            $catalog
            4. action：新值或覆盖旧草稿用 set；tags 类字段追加标签用 add、撤销标签用 remove。
            5. value 简洁客观，单个不超过 80 字；tags 类多个标签用顿号分隔；
               evidence 填对话中支持该事实的原文短句（不超过 60 字）。
            6. fields 最多输出 $MAX_DRAFTS 条。
            7. style 是对方风格三标签（可整体缺省，拿不准就不要填）：
               identity 只能取 领导/客户/同事/朋友/家人/暧昧对象/其他；一人多身份
               时可给多个（顿号分隔，如「领导、家人」，最多 3 个）；
               chatStyle 只能取 严肃/随和/幽默/热情/冷淡 之一；
               closeness 取 0~3 的整数（0 陌生/1 熟识/2 亲近/3 亲密）。
            8. contactInfos 提取对方留下的联系方式（可整体缺省，最多 3 条）：
               kind 只取 phone/email/address/id_doc/im_account/other 之一
               （电话/邮箱/地址/证件/IM 账号/其他）；value 填原值，电话、邮箱、
               证件号必须完整准确、拿不准就不要输出；label 填不超过 10 字的说明
               （如「单位座机」「老家住址」）；evidence 填原文短句。
            9. 没有值得提取的内容就输出空的 {"fields":[],"style":{},"selfFacet":[],"contactInfos":[]}。
            10. 只输出一个 JSON 对象，不要任何解释或 Markdown 代码块。格式：
            {"fields":[{"field":"字段键","value":"值","action":"set","evidence":"原文短句"}],
             "style":{"identity":"领导","chatStyle":"严肃","closeness":1},
             "selfFacet":["办事周到"],
             "contactInfos":[{"kind":"phone","value":"13800138000","label":"他留的手机号","evidence":"原文短句"}]}
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

    /** 一次萃取的产物：fields 走 applyDraft 草稿，style+selfFacet 走待确认通道。 */
    private data class Parsed(
        val fields: List<DraftJson>,
        val style: SelfFacets.StylePending,
        val infos: List<InfoJson> = emptyList(),
    )

    /** 联系方式萃取协议行（2026-10-23）：kind/value 必填，label/evidence 可缺省。 */
    @Serializable
    private data class InfoJson(
        val kind: String = "",
        val value: String = "",
        val label: String = "",
        val evidence: String? = null,
    )

    /** 模型 style 段输出协议（字段全部缺省容忍；宽松 JSON 由外层 Json 承担）。 */
    @Serializable
    private data class StyleJson(
        val identity: String? = null,
        val chatStyle: String? = null,
        /** 数字或中文词皆可（0~3 / 陌生~亲密），原样取 content 交 [StyleVocab.closenessOf]。 */
        val closeness: kotlinx.serialization.json.JsonPrimitive? = null,
    )

    @Serializable
    private data class OutputJson(
        val fields: List<DraftJson> = emptyList(),
        val style: StyleJson = StyleJson(),
        val selfFacet: List<String> = emptyList(),
        val contactInfos: List<InfoJson> = emptyList(),
    )

    /**
     * 宽松解析（V-C 一问双产出）：优先按对象协议 `{"fields":[…],"style":{…},
     * "selfFacet":[…]}` 截首个 {…}；裸数组 `[…]`（旧协议/模型不守规矩）回退为
     * fields-only，style 全空。style 三值经 [StyleVocab] 词表归一，词表外丢弃；
     * selfFacet 每条 ≤6 字、最多 3 条、去重保序。
     */
    private fun parse(raw: String, fields: List<PersonaFieldDef>): Parsed {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        val objStart = text.indexOf('{')
        val objEnd = text.lastIndexOf('}')
        val arrStart = text.indexOf('[')
        val arrEnd = text.lastIndexOf(']')
        val output: OutputJson = when {
            objStart >= 0 && objEnd > objStart && (arrStart < 0 || objStart < arrStart) ->
                runCatching { json.decodeFromString(OutputJson.serializer(), text.substring(objStart, objEnd + 1)) }
                    .getOrNull()
            arrStart >= 0 && arrEnd > arrStart ->
                runCatching {
                    OutputJson(
                        fields = json.decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(DraftJson.serializer()),
                            text.substring(arrStart, arrEnd + 1),
                        ),
                    )
                }.getOrNull()
            else -> null
        } ?: return Parsed(emptyList(), SelfFacets.StylePending())
        val normalized = normalizeFields(output.fields, fields)
        val infos = normalizeInfos(output.contactInfos)
        val style = SelfFacets.StylePending(
            facet = output.selfFacet.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= FACET_MAX_CHARS }
                .distinct()
                .take(MAX_FACETS)
                .toList(),
            // 身份多选（2026-10-23 §2.3）：归一收集全部词表命中（≤3），编码多值
            // 进待确认通道，核阅确认时**并入**已有多选、不覆盖手动值。
            identity = StyleVocab.encodeIdentities(StyleVocab.normalizeIdentities(output.style.identity)),
            chatStyle = StyleVocab.normalizeChatStyle(output.style.chatStyle),
            closeness = StyleVocab.closenessOf(output.style.closeness?.content),
        )
        return Parsed(normalized, style, infos)
    }

    /**
     * 联系方式归一（2026-10-23）：kind 词表归一（词表外回退 other）、严格值校验，
     * 不合法直接丢弃；同 (kind, value) 去重保序。
     */
    private fun normalizeInfos(list: List<InfoJson>): List<InfoJson> =
        list.mapNotNull { info ->
            val value = info.value.trim()
            if (value.isEmpty()) return@mapNotNull null
            val kind = ContactInfoRules.normalizeKind(info.kind) ?: CONTACT_INFO_OTHER
            if (!ContactInfoRules.validExtracted(kind, value)) return@mapNotNull null
            InfoJson(
                kind = kind,
                value = value,
                label = info.label.trim().take(INFO_LABEL_MAX_CHARS),
                evidence = info.evidence?.trim()?.take(EVIDENCE_MAX_CHARS),
            )
        }.distinctBy { it.kind to it.value }

    private fun normalizeFields(list: List<DraftJson>, fields: List<PersonaFieldDef>): List<DraftJson> {
        val allowed = fields.associateBy { it.key }
        return list.mapNotNull { draft ->
            val def = allowed[draft.field] ?: return@mapNotNull null
            val rawValue = draft.value.trim()
            val action = draft.action?.lowercase()?.takeIf { it in ACTIONS } ?: return@mapNotNull null
            if (rawValue.isEmpty() || rawValue.length > VALUE_MAX_CHARS) return@mapNotNull null
            // tags 类才允许 add/remove；其余只接受 set。
            if (action != "set" && def.type != PersonaFieldType.TAGS) return@mapNotNull null
            // 落库前码表归一：label→code、自造值 ~原文（不允许自定义的枚举值直接丢弃）。
            val normalized = normalizeDraftValue(def, rawValue) ?: return@mapNotNull null
            DraftJson(
                field = draft.field,
                value = normalized,
                action = action,
                evidence = draft.evidence?.trim()?.take(EVIDENCE_MAX_CHARS),
                actionEnum = when (action) {
                    "add" -> ContactRepository.DraftAction.ADD
                    "remove" -> ContactRepository.DraftAction.REMOVE
                    else -> ContactRepository.DraftAction.SET
                },
            )
        }
    }

    /**
     * 萃取值落库归一（仍保持 ContactRepository.encodeValue 约定的形态：
     * tags 顿号字符串、enum 单值）；标签逐元素归一后为空则整条丢弃。
     */
    private fun normalizeDraftValue(def: PersonaFieldDef, raw: String): String? {
        if (def.type != PersonaFieldType.TAGS) return PersonaValueCodes.normalizeExtracted(def, raw)
        val parts = raw.split(",", "，", "、")
            .mapNotNull { PersonaValueCodes.normalizeExtracted(def, it) }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("、")
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
        private const val TAG = "PersonaExtractor"

        /** 默认萃取节流（调用方未显式传参时；正常链路由 AdvisorPrefs 决定）。 */
        const val DEFAULT_EVERY_TURNS = 5
        // 萃取素材屏数 6→4：萃取只看最近新增量（节流已是每 3/5/10 屏），
        // 4 个新屏足以捕捉画像变化，输入 token 再减三分之一。
        const val MAX_FED_TURNS = 4
        const val MAX_DRAFTS = 5

        /** 联系方式单次萃取上限与 label 长度（2026-10-23）。 */
        const val MAX_INFO_DRAFTS = 3
        const val INFO_LABEL_MAX_CHARS = 10
        const val SCREEN_CHARS = 600
        const val REPLY_CHARS = 300
        const val VALUE_MAX_CHARS = 80
        const val EVIDENCE_MAX_CHARS = 60

        /** 我方 facet 待确认标签的单条长度与条数上限（V-C：短标签、少量增量）。 */
        const val FACET_MAX_CHARS = 6
        const val MAX_FACETS = 3
        val ACTIONS = setOf("set", "add", "remove")

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
