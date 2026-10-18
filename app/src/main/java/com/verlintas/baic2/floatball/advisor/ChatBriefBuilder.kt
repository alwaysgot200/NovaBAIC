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
import com.verlintas.baic2.floatball.advisor.data.CONTACT_TYPE_GROUP
import com.verlintas.baic2.floatball.advisor.data.MY_PERSONA_CONTACT_ID
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import com.verlintas.baic2.floatball.advisor.strategy.BasisKeywords
import com.verlintas.baic2.floatball.advisor.strategy.PersonaCandidate
import com.verlintas.baic2.floatball.advisor.strategy.PersonaSelector
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
     * @param basis P3 §九：本回合实际引用依据的 ≤3 个短关键词（气泡「依据」小字用）。
     */
    data class Brief(
        val section: String?,
        val styleSection: String?,
        val curatedTexts: List<String>,
        val basis: List<String> = emptyList(),
    )

    /**
     * @param contactId 当前回合绑定的联系人档案 id。
     * @param latestScreenText 当前屏正文（增量优先），来信提取失败时作为兜底 query。
     * @param goalOverride 联系人业务目标（调用方已从 ContactRef 取到时传入，
     *   保证话术 query 的目标关键词与召回收窄一致；null 时读档案 goal）。
     * @param stageCode 当前场景站码（P2）：风格卡意图按站过滤；null=不过滤（P1 行为）。
     * @param packStageCodes 当前包全部站码（P2 跨包门控口径，见 [StyleResolver.render]）。
     * @param suppressGoal true=策略体系已有生效 L2，抑制旧【本次聊天的业务目标】行
     *   （L2 标题已由策略段呈现，避免重复）；其余人设/话术段不受影响。
     * @param relevanceSignals P3 当前站推进/退缩信号原文：人设裁剪时弱关联字段优先。
     * @param pathKind P3 当前场景路径（pursue/maintain/defend）：意图按 pathKind 门控；
     *   null=无场景，不做 pathKind 过滤。
     */
    suspend fun build(
        contactId: Long,
        latestScreenText: String,
        goalOverride: String? = null,
        stageCode: String? = null,
        packStageCodes: Set<String>? = null,
        suppressGoal: Boolean = false,
        relevanceSignals: Set<String> = emptySet(),
        pathKind: String? = null,
    ): Brief? {
        if (contactId == MY_PERSONA_CONTACT_ID) return null
        val profile = contacts.profile(contactId) ?: return null
        val schema = schemas.get()

        val goal = (goalOverride ?: profile.goal)?.trim().orEmpty()
        val myValues = contacts.myPersona().filter { it.confirmed }
        val theirValues = contacts.fields(contactId).filter { it.confirmed }
        // F3：我方段按当前关系叠加 selfFacet（「领导面前的我」），全局 my_* 字段
        // 为底、关系侧面标签并列成行；facet 为空时保持纯全局段（与既有行为一致）。
        val facet = SelfFacets.decode(profile.selfFacetJson)
        val myRender = renderPersona(
            schema.fieldsFor("my", profile.contactType),
            myValues,
            relevanceSignals,
        )
        val theirRender = renderPersona(
            schema.fieldsFor("their", profile.contactType),
            theirValues,
            relevanceSignals,
        )
        val myLines = mySectionLines(myRender.first, facet)
        val theirLines = theirRender.first

        // 说话方式：联系人卡 → 全局卡 → v3 style 自由文本兜底；P2 传真实站码做意图过滤。
        val styleSection = StyleResolver.render(
            card = styleCards.cardFor(contactId),
            playbook = styleAssets.playbook(),
            stageCode = stageCode,
            packStageCodes = packStageCodes,
            pathKind = pathKind,
        ) ?: StyleResolver.renderLegacy(profile.style)

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
            if (goal.isNotEmpty() && !suppressGoal) add(SECTION_GOAL to goal)
            if (myLines.isNotEmpty()) add(SECTION_MY to myLines.joinToString("\n"))
            if (theirLines.isNotEmpty()) {
                // P3：群=单一对方实体，人设段标题切换为群口径，避免模型按私聊语境作答。
                val theirTitle = if (profile.contactType == CONTACT_TYPE_GROUP) {
                    SECTION_GROUP
                } else {
                    SECTION_THEIR
                }
                add(theirTitle to theirLines.joinToString("\n"))
            }
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
        val curated = collectCurated(goal, myRender.second, theirRender.second)
            // F3：facet 行已进提示词，同样作为去重指纹原料（短于指纹下限的除外）。
            .let { list -> (list + facet.filter { it.trim().length >= MIN_CURATED_CHARS }).distinct() }
        return Brief(
            section = section,
            styleSection = styleSection,
            curatedTexts = curated,
            basis = BasisKeywords.from(curated),
        )
    }

    /**
     * P3 §十一：按字段目录顺序构造候选 → [PersonaSelector] 裁剪（禁忌/红线永不裁、
     * 信号弱关联优先、salience/updatedAt 排序、每侧 ≤ [PersonaSelector.DEFAULT_LIMIT]）。
     * 存储 code/`~x` 一律转显示文本；超 staleAfterDays 的行追加时效措辞；
     * enum 选项带 impl 或字段带 implTpl 时行尾追加行为暗示短句（方案 §6.1）。
     *
     * @return (渲染行, 选中的字段实体——供指纹去重只覆盖实际进提示词的值)。
     */
    private fun renderPersona(
        defs: List<PersonaFieldDef>,
        values: List<PersonaFieldEntity>,
        signals: Set<String>,
    ): Pair<List<String>, List<PersonaFieldEntity>> {
        val byKey = values.associateBy { it.field }
        val candidates = defs.mapNotNull { def ->
            val entity = byKey[def.key] ?: return@mapNotNull null
            val displayed = PersonaValueCodes.displayValue(def, entity.value).trim()
            if (displayed.isEmpty()) return@mapNotNull null
            PersonaCandidate(def, entity, displayed)
        }
        val selected = PersonaSelector.select(candidates, signals)
        val lines = selected.map { item ->
            val c = item.candidate
            val annotation = annotationFor(c.def, c.entity.value, c.displayed)
            val noteParts = buildList {
                annotation?.takeIf { it.isNotBlank() }?.let(::add)
                if (item.stale) add(PersonaSelector.STALE_NOTE)
            }
            val suffix = noteParts.takeIf { it.isNotEmpty() }?.joinToString("；")?.let { "（$it）" }.orEmpty()
            "${c.def.label}: ${c.displayed}$suffix"
        }
        return lines to selected.map { it.candidate.entity }
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
        const val SECTION_GROUP = "【群的人设】（群=一个整体对象，回复语境是群公共场合）"
        const val SECTION_PLAYBOOKS = "【可参考的话术】（结合当前场景选用，不要照搬）"

        const val OUTPUT_REQUIREMENTS =
            "【输出要求】\n" +
                "以「我」的身份给出 2~3 条建议回复（标注各自意图与风险），" +
                "并给出一句话策略提示。不要复述屏幕内容。"
    }
}

/**
 * 我方人设段的关系侧面拼装（F3 纯函数，JVM 可单测）：全局 my_* 字段行为底，
 * 档案 selfFacet 标签并列追加一行（facet 非空时；空 facet 原样返回全局行）。
 */
internal fun mySectionLines(baseLines: List<String>, facet: List<String>): List<String> {
    val clean = facet.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (clean.isEmpty()) return baseLines
    return baseLines + "$MY_FACET_ROW_LABEL: ${clean.joinToString("、")}"
}

/** 我方人设段内关系侧面行的标签（与 SECTION_MY 的「label: value」行式一致）。 */
internal const val MY_FACET_ROW_LABEL = "关系侧面（在这段关系里的我）"
