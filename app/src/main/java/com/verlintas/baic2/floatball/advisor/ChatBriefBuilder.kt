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

import com.verlintas.baic2.floatball.IncomingCue
import com.verlintas.baic2.floatball.advisor.data.MY_PERSONA_CONTACT_ID
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 绑定联系人参谋回合的提示词组装（方案 §6.2/§8.1）：
 * 说话方式（风格卡，独立段）+ 业务目标 + 双方结构化人设（字段目录驱动，
 * 空字段跳过；enum 选项的 impl 行为暗示行尾追加）+ 以对方最新发言检索的话术 Top-3。
 *
 * 取数纪律（§6.5）：目标/风格/人设只取 AdvisorDatabase 按 contactId 精确
 * 读取，绝不来自全局记忆，防止串档；未确认（confirmed=false）的萃取草稿不进提示词。
 *
 * v4 §8.2：除渲染段落外同时返回 [Brief.curatedTexts]——进入人设段的全部
 * 已确认值的**显示文本**（code 已转 label），供调用方对通用召回与交往脉络
 * 做指纹去重。风格段是指令性文本，不参与事实指纹。
 */
@Singleton
class ChatBriefBuilder @Inject constructor(
    private val contacts: ContactRepository,
    private val schemas: PersonaSchemas,
    private val playbooks: PlaybookRetriever,
    private val styleCards: StyleCardRepository,
    private val styleAssets: StyleAssets,
) {
    /**
     * @param section 业务目标 + 双方人设 + 话术段（尾部带输出要求）；全无内容时为 null。
     * @param styleSection 【说话方式】段（风格卡三级回退）；无卡且无旧 style 时为 null。
     * @param curatedTexts 已确认目标/画像显示值（去重指纹原料，无标签前缀）。
     */
    data class Brief(
        val section: String?,
        val styleSection: String?,
        val curatedTexts: List<String>,
    )

    /**
     * @param contactId 当前回合绑定的联系人档案 id。
     * @param latestScreenText 当前屏正文（增量优先），来信提取失败时作为兜底 query。
     * @param goalOverride 联系人业务目标（调用方已从 ContactRef 取到时传入，
     *   保证话术 query 的目标关键词与召回收窄一致；null 时读档案 goal）。
     */
    suspend fun build(
        contactId: Long,
        latestScreenText: String,
        goalOverride: String? = null,
    ): Brief? {
        if (contactId == MY_PERSONA_CONTACT_ID) return null
        val profile = contacts.profile(contactId) ?: return null
        val schema = schemas.get()

        val goal = (goalOverride ?: profile.goal)?.trim().orEmpty()
        val myValues = contacts.myPersona().filter { it.confirmed }
        val theirValues = contacts.fields(contactId).filter { it.confirmed }
        val myLines = renderPersona(schema.myFields, myValues)
        val theirLines = renderPersona(schema.theirFields, theirValues)

        // 说话方式：联系人卡 → 全局卡 → v3 style 自由文本兜底；P1 stageCode=null（不过滤意图）。
        val styleSection = StyleResolver.render(styleCards.cardFor(contactId), styleAssets.playbook())
            ?: StyleResolver.renderLegacy(profile.style)

        // v3 §6.2：话术 query = 对方最新发言 + 目标关键词；来信提取失败
        // （文章等非聊天界面）回退整屏，维持原行为，质量不回退。
        val query = IncomingCue.recallQuery(
            screenBody = latestScreenText.trim(),
            goal = goal,
            maxChars = RETRIEVE_QUERY_CHARS,
        )
        val playbookLines = runCatching {
            playbooks.retrieve(query = query, categories = null, limit = PLAYBOOK_TOP_K)
        }.getOrDefault(emptyList()).mapIndexed { index, item ->
            "${index + 1}. 【${item.category}·${item.scenario}】${item.content}"
        }

        val sections = buildList {
            if (goal.isNotEmpty()) add(SECTION_GOAL to goal)
            if (myLines.isNotEmpty()) add(SECTION_MY to myLines.joinToString("\n"))
            if (theirLines.isNotEmpty()) add(SECTION_THEIR to theirLines.joinToString("\n"))
            if (playbookLines.isNotEmpty()) {
                add(SECTION_PLAYBOOKS to playbookLines.joinToString("\n"))
            }
        }
        if (sections.isEmpty() && styleSection == null) return null

        val section = if (sections.isEmpty()) null else buildString {
            sections.forEachIndexed { index, (title, body) ->
                if (index > 0) append("\n\n")
                append(title).append('\n').append(body)
            }
            append("\n\n").append(OUTPUT_REQUIREMENTS)
        }
        return Brief(
            section = section,
            styleSection = styleSection,
            curatedTexts = collectCurated(goal, myValues, theirValues),
        )
    }

    /**
     * 按字段目录顺序渲染「标签: 值」，空值/未知字段自动跳过。
     * 存储 code/`~x` 一律转显示文本；enum 选项带 impl 或字段带 implTpl 时
     * 行尾追加行为暗示短句（方案 §6.1）。
     */
    private fun renderPersona(
        defs: List<PersonaFieldDef>,
        values: List<PersonaFieldEntity>,
    ): List<String> {
        val byKey = values.associateBy { it.field }
        return defs.mapNotNull { def ->
            val entity = byKey[def.key] ?: return@mapNotNull null
            val displayed = PersonaValueCodes.displayValue(def, entity.value).trim()
            if (displayed.isEmpty()) return@mapNotNull null
            val annotation = annotationFor(def, entity.value, displayed)
            val suffix = annotation?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            "${def.label}: $displayed$suffix"
        }
    }

    private fun annotationFor(def: PersonaFieldDef, stored: String, displayed: String): String? {
        if (def.type == PersonaFieldType.ENUM) {
            val raw = stored.trim()
            val impl = def.optionByCode(raw)?.impl?.takeIf { it.isNotBlank() }
                ?: def.optionByLabel(raw)?.impl?.takeIf { it.isNotBlank() }
            if (impl != null) return impl
        }
        if ((def.type == PersonaFieldType.TEXT || def.type == PersonaFieldType.TEXTAREA) &&
            !def.implTpl.isNullOrBlank()
        ) {
            return def.implTpl.replace("{v}", displayed)
        }
        return null
    }

    /** 汇总进入人设段的显示值（tags 展开、code 转 label），供跨段指纹去重。 */
    private fun collectCurated(
        goal: String,
        myValues: List<PersonaFieldEntity>,
        theirValues: List<PersonaFieldEntity>,
    ): List<String> {
        val defs = schemas.get().fields.associateBy { it.key }
        val out = LinkedHashSet<String>()
        goal.takeIf { it.isNotEmpty() }?.let(out::add)
        (myValues + theirValues).forEach { entity ->
            val def = defs[entity.field]
            val pieces = if (def?.type == PersonaFieldType.TAGS) {
                PersonaValues.decodeTags(entity.value).map { PersonaValueCodes.displaySingle(def, it) }
            } else {
                listOf(PersonaValueCodes.displaySingle(def, entity.value))
            }
            pieces.asSequence()
                .map { it.trim() }
                .filter { it.length >= MIN_CURATED_CHARS }
                .forEach(out::add)
        }
        return out.toList()
    }

    private companion object {
        const val PLAYBOOK_TOP_K = 3
        const val RETRIEVE_QUERY_CHARS = 600

        /** 短于此长度的画像值不做指纹原料（泛化单字/双字易误判）。 */
        const val MIN_CURATED_CHARS = 3

        const val SECTION_GOAL = "【本次聊天的业务目标】"
        const val SECTION_MY = "【我的人设】"
        const val SECTION_THEIR = "【对方的人设】"
        const val SECTION_PLAYBOOKS = "【可参考的话术】（结合当前场景选用，不要照搬）"

        const val OUTPUT_REQUIREMENTS =
            "【输出要求】\n" +
                "以「我」的身份给出 2~3 条建议回复（标注各自意图与风险），" +
                "并给出一句话策略提示。不要复述屏幕内容。"
    }
}
