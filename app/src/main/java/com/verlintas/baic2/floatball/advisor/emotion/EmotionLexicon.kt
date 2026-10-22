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

package com.verlintas.baic2.floatball.advisor.emotion

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 情绪信号词库条目（情感分析师方案 §4.3.2）。
 *
 * 词库是**可运营数据资产**（可查看/增补/调权重/停用/按联系人限定），不是硬编码
 * 常量；判定引擎只消费条目、不内置词表。中性确认词（如"好的"）以 polarity=0
 * 落库，从数据模型层面杜绝单词定论（方案 §4.4 原则 1）。
 *
 * @property term 词条：词/短语/emoji/标点正则（子库 4）/行为规则 id（子库 8）
 * @property library 所属子库 1-8，见 [EmotionLibraries]
 * @property labels 关联情绪标签（可多标签），见 [EmotionLabels]；空 = 不参与标签计分
 * @property polarity 极性 -2..+2；0 = 中性（不构成任何情绪证据）
 * @property intensityDelta 强度修正 -2..+2（叠加到判定强度，不改极性方向）
 * @property negatable 可被否定词反转（"开心"可；"哈哈哈"不可）
 * @property scenario 子库 7 专用：限定场景包 code（scenario_packs 对齐）；null = 全场景
 * @property scopeContactId null = 全局生效；否则仅对该联系人生效（人群差异）
 * @property weight 判错反馈落点（0..2），内置 1.0、AI 挖掘初始 0.8
 * @property enabled 用户可停用单条
 * @property source builtin / user / ai_mined（经人审）
 * @property note 给用户看的解释（词库管理页展示）
 */
data class EmotionLexiconEntry(
    val term: String,
    val library: Int,
    val labels: List<String> = emptyList(),
    val polarity: Int = 0,
    val intensityDelta: Int = 0,
    val negatable: Boolean = false,
    val scenario: String? = null,
    val scopeContactId: Long? = null,
    val weight: Float = 1f,
    val enabled: Boolean = true,
    val source: String = SOURCE_BUILTIN,
    val note: String = "",
) {
    companion object {
        const val SOURCE_BUILTIN = "builtin"
        const val SOURCE_USER = "user"
        const val SOURCE_AI_MINED = "ai_mined"
    }
}

/** 8 子库（方案 §4.3.1）：1-7 为文本匹配，8 为行为规则（term=规则 id）。 */
object EmotionLibraries {
    const val TRIGGER = 1
    const val INTENSIFIER = 2
    const val NEGATION = 3
    const val PUNCTUATION = 4
    const val EMOJI = 5
    const val NET_SLANG = 6
    const val SUBTEXT = 7
    const val BEHAVIOR = 8

    val ALL = listOf(TRIGGER, INTENSIFIER, NEGATION, PUNCTUATION, EMOJI, NET_SLANG, SUBTEXT, BEHAVIOR)

    /** 子库中文名（词库管理页分组标题，E-h 经 strings.xml 渲染时对照用）。 */
    fun name(library: Int): String = when (library) {
        TRIGGER -> "情绪触发词库"
        INTENSIFIER -> "强度修饰库"
        NEGATION -> "否定反转库"
        PUNCTUATION -> "语气标点模式库"
        EMOJI -> "Emoji 表情库"
        NET_SLANG -> "中文网络语气库"
        SUBTEXT -> "场景潜台词库"
        BEHAVIOR -> "行为信号规则库"
        else -> "未知子库"
    }
}

/**
 * 12 情绪标签（方案 §4.3.1：5 基础 + 7 关系信号）。
 * 派生态（盛怒 = anger 强度≥4；兴奋 = joy 强度≥3）在军师映射层派生，不占标签位。
 */
object EmotionLabels {
    // 基础 5
    const val ANGER = "anger"
    const val JOY = "joy"
    const val SADNESS = "sadness"
    const val FEAR = "fear"
    const val DISGUST = "disgust"

    // 关系信号 7
    const val DISSATISFACTION = "dissatisfaction"
    const val HESITATION = "hesitation"
    const val PERFUNCTORY = "perfunctory"
    const val DISAPPOINTMENT = "disappointment"
    const val GRIEVANCE = "grievance"
    const val PROBING = "probing"
    const val LOW = "low"

    val ALL = listOf(
        ANGER, JOY, SADNESS, FEAR, DISGUST,
        DISSATISFACTION, HESITATION, PERFUNCTORY, DISAPPOINTMENT, GRIEVANCE, PROBING, LOW,
    )

    /** 负面标签集（判定偏保守：误判负面比漏判更伤关系）。 */
    val NEGATIVE = listOf(ANGER, SADNESS, FEAR, DISGUST, DISSATISFACTION, PERFUNCTORY, DISAPPOINTMENT, GRIEVANCE, LOW)

    /** 标签中文名（内部注释/测试/台账用；用户可见文案 E-h 走 strings.xml）。 */
    fun display(label: String): String = when (label) {
        ANGER -> "怒"
        JOY -> "喜"
        SADNESS -> "悲"
        FEAR -> "惧"
        DISGUST -> "厌"
        DISSATISFACTION -> "不满"
        HESITATION -> "犹豫"
        PERFUNCTORY -> "敷衍"
        DISAPPOINTMENT -> "失望"
        GRIEVANCE -> "委屈"
        PROBING -> "试探"
        LOW -> "低落"
        else -> label
    }
}

/** 内置词库 JSON 资产（app assets/advisor/emotion_lexicon.json）的解码模型。 */
@Serializable
data class EmotionLexiconSeedFile(
    val version: Int = 1,
    val entries: List<EmotionLexiconSeed> = emptyList(),
)

@Serializable
data class EmotionLexiconSeed(
    val term: String,
    val library: Int,
    val labels: List<String> = emptyList(),
    val polarity: Int = 0,
    val intensityDelta: Int = 0,
    val negatable: Boolean = false,
    val scenario: String? = null,
    val note: String = "",
)

private val seedJson = Json { ignoreUnknownKeys = true }

/** 解析内置词库 JSON（纯函数，播种器与单测共用；畸形条目跳过不炸库）。 */
fun parseEmotionLexiconSeed(json: String): List<EmotionLexiconEntry> {
    val file = runCatching { seedJson.decodeFromString(EmotionLexiconSeedFile.serializer(), json) }
        .getOrNull() ?: return emptyList()
    return file.entries.mapNotNull { seed ->
        val term = seed.term.trim()
        if (term.isEmpty() || seed.library !in EmotionLibraries.ALL) return@mapNotNull null
        EmotionLexiconEntry(
            term = term,
            library = seed.library,
            labels = seed.labels.filter { it in EmotionLabels.ALL },
            polarity = seed.polarity.coerceIn(-2, 2),
            intensityDelta = seed.intensityDelta.coerceIn(-2, 2),
            negatable = seed.negatable,
            scenario = seed.scenario?.takeIf { it.isNotBlank() },
            note = seed.note,
        )
    }
}
