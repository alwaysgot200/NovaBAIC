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
import com.verlintas.baic2.floatball.advisor.data.DECOMP_OUTCOMES
import com.verlintas.baic2.floatball.advisor.data.HORIZON_L3
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import com.verlintas.baic2.floatball.advisor.strategy.CardItem
import com.verlintas.baic2.floatball.advisor.strategy.OutcomesPayload
import com.verlintas.baic2.floatball.advisor.strategy.StrategyCardPayload
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 策略卡编译器（§6）：五段中四段确定性组装（零 LLM），只有 roleLine 是一次轻 LLM
 * （180/0.4），失败按 pathKind 走兜底模板。产出 draft 行落库（用户句保留由 Repository 合并）。
 *
 * 触发：L2 roadmap 入库后 / 审核页手动重编 / 设置页编全局卡。任何异常只 Log.d，
 * 调用方回合继续用旧 confirmed 卡或基线。
 */
@Singleton
class CardCompiler @Inject constructor(
    private val goals: GoalRepository,
    private val cards: StrategyCardRepository,
    private val contacts: ContactRepository,
    private val schemas: PersonaSchemas,
    private val styleAssets: StyleAssets,
    private val planner: GoalPlanner,
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {
    /**
     * 编译一张新草稿。[contactId]=0 为全局卡（无场景、无对方段）。
     * @return 新草稿行 id；前提数据全缺（连「我的忌口/理想自我」都没有且非联系人卡）时返回 null。
     */
    suspend fun compile(contactId: Long): Long? {
        val profile = if (contactId == 0L) null else contacts.profile(contactId)
        val pack = styleAssets.scenarioPacks().byCode(profile?.scenarioCode)

        val schema = schemas.get()
        val myEntities = contacts.myPersona().filter { it.confirmed }
        val theirEntities = if (contactId == 0L) {
            emptyList()
        } else {
            contacts.fields(contactId).filter { it.confirmed }
        }
        val defs = schema.fields.associateBy { it.key }

        val aspiration = aspirationLabel(defs["my_aspiration"], myEntities)
        val practices = collectPractices(contactId)

        val selfConstraints = buildList {
            if (aspiration.isNotBlank()) add(CardItem("成为${aspiration}的人"))
            practices.forEach { add(CardItem(it)) }
        }

        val theirImplications = theirImplications(defs, theirEntities)

        // P3：pursue 路径话题来自画像 topicHook；maintain/social 路径用资产 topicBank
        // （可聊话题池），两者都声明时资产池补充在后，统一去重限量。
        val topicBank = buildList {
            if (pack?.pathKind == PATH_PURSUE) addAll(topicHooks(defs, theirEntities))
            pack?.topicBank?.forEach { line ->
                line.trim().takeIf { it.isNotEmpty() }?.let { add(CardItem(it)) }
            }
        }.distinctBy { it.text }.take(MAX_TOPICS)

        val myTabooLines = textareaLines(entityValue(myEntities, "my_taboo"))
        val theirTabooLines = if (contactId == 0L) {
            emptyList()
        } else {
            // P3：群实体的群内禁忌（groupTaboos）与普通雷区一并进入红线段。
            textareaLines(entityValue(theirEntities, "taboos")) +
                textareaLines(entityValue(theirEntities, "groupTaboos"))
        }
        val taboos = buildList {
            if (pack != null) {
                pack.taboos.forEach { line ->
                    val text = if (pack.pathKind == PATH_DEFEND) "冲突期·$line" else line
                    add(CardItem(text))
                }
            }
            theirTabooLines.forEach { add(CardItem(it)) }
            myTabooLines.forEach { add(CardItem(it)) }
        }.distinctBy { it.text }

        val stageLabel = pack?.let { p ->
            val currentCode = goals.stage(contactId, p.code)?.stageCode ?: p.stages.firstOrNull()?.code
            p.stages.firstOrNull { it.code == currentCode }?.label ?: currentCode
        }
        val roleLine = resolveRoleLine(
            contactId = contactId,
            name = profile?.displayName?.trim().orEmpty(),
            aspiration = aspiration,
            pathKind = pack?.pathKind,
            stageLabel = stageLabel,
            implicationHints = theirImplications.mapNotNull { item ->
                // 兜底模板用行为暗示短句：从整行中取括号内文本。
                IMPLICATION_HINT_REGEX.find(item.text)?.groupValues?.get(1)
            }.take(2),
        )

        val payload = StrategyCardPayload(
            roleLine = roleLine,
            selfConstraints = selfConstraints,
            theirImplications = theirImplications.take(MAX_IMPLICATIONS),
            topicBank = topicBank,
            taboos = taboos,
            cardVersion = 1,
        )
        return cards.compileDraft(contactId, profile?.scenarioCode, payload)
    }

    /** selfConstraints 的修炼项：该联系人全部 active L3 的最新 outcomes decomp 聚合去重。 */
    private suspend fun collectPractices(contactId: Long): List<String> {
        if (contactId == 0L) return emptyList()
        return goals.activeGoals(contactId, HORIZON_L3).flatMap { l3 ->
            val json = goals.latestDecomp(l3.id, DECOMP_OUTCOMES)?.payloadJson ?: return@flatMap emptyList()
            runCatching {
                strategyJson.decodeFromString(OutcomesPayload.serializer(), json).practices
            }.getOrDefault(emptyList())
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_PRACTICES)
    }

    /** 对方字段中带行为暗示（option.impl / implTpl）的已确认行，salience low 跳过。 */
    private fun theirImplications(
        defs: Map<String, PersonaFieldDef>,
        entities: List<PersonaFieldEntity>,
    ): List<CardItem> = entities.mapNotNull { e ->
        val def = defs[e.field] ?: return@mapNotNull null
        if (def.owner != "their" || def.salience == PersonaFieldDef.SALIENCE_LOW) return@mapNotNull null
        val displayed = PersonaValueCodes.displayValue(def, e.value).trim()
        if (displayed.isEmpty()) return@mapNotNull null
        val annotation = annotationFor(def, e.value, displayed) ?: return@mapNotNull null
        CardItem("${def.label}: $displayed（$annotation）")
    }.distinctBy { it.text }.take(MAX_IMPLICATIONS)

    /** 话题素材：enum 中 topicHook 选项的 label + tags 字段的自由标签。 */
    private fun topicHooks(
        defs: Map<String, PersonaFieldDef>,
        entities: List<PersonaFieldEntity>,
    ): List<CardItem> {
        val out = LinkedHashSet<String>()
        entities.forEach { e ->
            val def = defs[e.field] ?: return@forEach
            if (def.owner != "their") return@forEach
            when (def.type) {
                PersonaFieldType.ENUM -> {
                    val opt = def.optionByCode(e.value.trim()) ?: def.optionByLabel(e.value.trim())
                    if (opt?.topicHook == true) out.add(opt.label)
                }
                PersonaFieldType.TAGS -> {
                    PersonaValues.decodeTags(e.value)
                        .map { PersonaValueCodes.displaySingle(def, it).trim() }
                        .filter { it.length >= MIN_TAG_CHARS }
                        .forEach { out.add(it) }
                }
                else -> Unit
            }
        }
        return out.take(MAX_TOPICS).map { CardItem(it) }
    }

    private suspend fun resolveRoleLine(
        contactId: Long,
        name: String,
        aspiration: String,
        pathKind: String?,
        stageLabel: String?,
        implicationHints: List<String>,
    ): String {
        if (contactId != 0L) {
            val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            if (config != null) {
                val llm = runCatching {
                    auxiliaryTasks.complete(
                        config = config,
                        systemPrompt = roleLineSystemPrompt(pathKind),
                        userPrompt = buildString {
                            if (name.isNotBlank()) appendLine("对方称呼：$name")
                            if (implicationHints.isNotEmpty()) {
                                appendLine("关键事实：")
                                implicationHints.forEach { appendLine("- $it") }
                            }
                            if (!stageLabel.isNullOrBlank()) appendLine("当前关系阶段：$stageLabel")
                        },
                        maxTokens = TOKENS_ROLE_LINE,
                        temperature = TEMP_ROLE_LINE,
                    )
                }.getOrNull()
                val cleaned = llm?.trim()?.take(ROLE_LINE_MAX_CHARS)?.takeIf { it.isNotEmpty() }
                if (cleaned != null) return cleaned
            }
        }
        return fallbackRoleLine(contactId, aspiration, name, pathKind, stageLabel, implicationHints)
    }

    private fun roleLineSystemPrompt(pathKind: String?): String {
        val style = when (pathKind) {
            PATH_MAINTAIN -> "长期经营：稳重、有边界、不越界承诺"
            PATH_DEFEND -> "冲突止损：只谈事实与边界，不激化、不露底牌"
            else -> "关系推进：自然、有分寸地推动下一阶段"
        }
        return """
            你为用户写一句「角色自我提醒」（他将以第一人称照此聊天）。要求：
            1. 第二人称「你」开头，≤60 字，含关系阶段与 1~2 个给定关键事实；
            2. 口径：$style；
            3. 口语、具体，不要助手腔、不要引号、不要分条、不要解释。
        """.trimIndent()
    }

    private fun fallbackRoleLine(
        contactId: Long,
        aspiration: String,
        name: String,
        pathKind: String?,
        stageLabel: String?,
        implicationHints: List<String>,
    ): String {
        if (contactId == 0L) {
            return if (aspiration.isNotBlank()) {
                "你是用户本人，以${aspiration}的状态和对方聊天。"
            } else {
                "你是用户本人，按自己舒服、得体的方式和对方聊天。"
            }
        }
        val who = name.ifBlank { "对方" }
        val facts = implicationHints.take(2).joinToString("；").ifBlank { "按已确认的画像与边界回应" }
        val stage = stageLabel ?: return "你是用户本人，正在和「$who」聊天；$facts。"
        return when (pathKind) {
            PATH_MAINTAIN -> "你是用户本人，正在和「$who」聊天；$facts。当前重点是把${stage}的位置站稳。"
            PATH_DEFEND -> "你是用户本人，正与「$who」处理冲突；当前在「$stage」，只谈事实与边界，不激化、不露底牌。"
            else -> "你是用户本人，正在和「$who」聊天；$facts。当前按「$stage」节奏推进。"
        }
    }

    private fun annotationFor(def: PersonaFieldDef, stored: String, displayed: String): String? {
        if (def.type == PersonaFieldType.ENUM) {
            val raw = stored.trim()
            def.optionByCode(raw)?.impl?.takeIf { it.isNotBlank() }?.let { return it }
            def.optionByLabel(raw)?.impl?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if ((def.type == PersonaFieldType.TEXT || def.type == PersonaFieldType.TEXTAREA) &&
            !def.implTpl.isNullOrBlank()
        ) {
            return def.implTpl.replace("{v}", displayed)
        }
        return null
    }

    private fun entityValue(entities: List<PersonaFieldEntity>, key: String): String? =
        entities.firstOrNull { it.field == key }?.value

    private fun aspirationLabel(def: PersonaFieldDef?, entities: List<PersonaFieldEntity>): String {
        def ?: return ""
        val entity = entities.firstOrNull { it.field == "my_aspiration" } ?: return ""
        return PersonaValues.decodeTags(entity.value)
            .map { PersonaValueCodes.displaySingle(def, it).trim() }
            .filter { it.isNotEmpty() }
            .joinToString("、")
    }

    /** textarea 画像值按换行拆行（兼容顿号/分号连写的旧数据）。 */
    private fun textareaLines(value: String?): List<String> =
        value?.split("\n", "；", ";", "、")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    companion object {
        private const val TOKENS_ROLE_LINE = 180
        private const val TEMP_ROLE_LINE = 0.4
        private const val ROLE_LINE_MAX_CHARS = 60
        private const val MAX_IMPLICATIONS = 8
        private const val MAX_PRACTICES = 6
        private const val MAX_TOPICS = 10
        private const val MIN_TAG_CHARS = 2

        /** 从「label: 值（暗示）」整行中取括号内暗示短句。 */
        private val IMPLICATION_HINT_REGEX = Regex("（(.+)）$")
    }
}
