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

import com.verlintas.baic2.floatball.advisor.StyleVocab

/**
 * 保温选题的风格词与三重过滤（v10 方案 §4.2，V-C）。种子/素材 tags 里的
 * **风格词**（吃喝/玩乐/段子…）与对方画像 identity/chatStyle/closeness、
 * 我方 facet、heatScore 共同裁决「哪些话题不许出现/降权/超深度剔除」。
 *
 * 风格词取中文，与种子库、素材 tag（用户自由输入）生态一致：匹配走
 * [TopicStyleTags.matchOf] 归一/包含（素材 tag「美食」「food」都能命中吃喝），
 * 注释口径——自由文本按**包含**匹配，宁多放权重不误杀。
 * 纯对象、零 Android 依赖，JVM 可直接单测。
 */
object TopicStyleTags {

    const val INDUSTRY = "行业"
    const val WORK = "工作"
    const val FOOD = "吃喝"
    const val FUN = "玩乐"
    const val JOKE = "段子"
    const val LIFE = "生活"
    const val HOBBY = "爱好"

    /** 风格词 → 近义词/包含线索（素材 tag 自由文本的宽松匹配表）。 */
    private val ALIASES: Map<String, List<String>> = mapOf(
        INDUSTRY to listOf("行业", "industry", "职场", "业务"),
        WORK to listOf("工作", "work", "上班", "职场"),
        FOOD to listOf("吃喝", "food", "美食", "饮食", "吃饭", "零食", "咖啡", "茶"),
        FUN to listOf("玩乐", "fun", "娱乐", "游玩", "旅行", "运动", "影视", "剧", "游戏", "音乐", "宠物"),
        JOKE to listOf("段子", "joke", "搞笑", "趣事", "吐槽"),
        LIFE to listOf("生活", "life", "日常", "周末", "作息"),
        HOBBY to listOf("爱好", "hobby", "兴趣", "阅读", "手艺", "收藏"),
    )

    /**
     * 自由文本 tag → 风格词（可多命中；无法归一返回空表）。
     * 匹配顺序：先精确等值（风格词/别名），再包含（raw 含别名或别名含 raw）。
     */
    fun matchOf(rawTag: String?): List<String> {
        val raw = rawTag?.trim()?.lowercase().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
        val hits = LinkedHashSet<String>()
        ALIASES.forEach { (style, aliases) ->
            if (raw == style || aliases.any { raw == it } || aliases.any { raw.contains(it) || it.contains(raw) }) {
                hits.add(style)
            }
        }
        return hits.toList()
    }

    /** 一组自由文本 tags（素材 tag 列、种子 tags 列）归并出的风格词集合。 */
    fun styleTagsOf(rawTags: List<String?>): List<String> =
        rawTags.flatMap { matchOf(it) }.distinct()

    /** 我方 facet 标签是否工作向（方案 §4.2：办事周到类标签降权吃喝玩乐）。 */
    fun isWorkFacet(facetTags: Collection<String>): Boolean {
        val joined = facetTags.joinToString(" ").lowercase()
        return WORK_FACET_KEYWORDS.any { joined.contains(it) }
    }

    /** 工作向 facet 的判定关键词（子串包含，取常见工作人设词）。 */
    private val WORK_FACET_KEYWORDS = listOf(
        "办事", "周到", "集体", "领导", "付出", "专业", "可靠", "上进", "责任", "工作",
    )
}

/**
 * 三重过滤的裁决结果（方案 §4.2）：[forbiddenTags] 命中即剔除种子/素材候选；
 * [penaltyTags] 命中只扣 tagMatches 权重；[maxDepth] 为话题深度上限（与阶段
 * 门控取更严）；[closeness] 供种子的 minCloseness 门（null=未萃取不设限）。
 */
data class TopicFilter(
    val forbiddenTags: Set<String> = emptySet(),
    val penaltyTags: Set<String> = emptySet(),
    val maxDepth: Int = 3,
    val closeness: Int? = null,
) {
    /** 种子 minCloseness 门：未萃取（null）或种子要求 ≤ 当前亲疏时放行。 */
    fun seedAllowed(minCloseness: Int): Boolean =
        closeness == null || minCloseness <= closeness
}

/** 三重过滤 + heatScore 调权的纯函数裁决（方案 §4.2/§4.3，JVM 可单测）。 */
object TopicStyleRules {

    /**
     * 由对方画像与我方 facet 推导过滤规则：
     * - identity 定禁区：领导 → 段子/吃喝/玩乐 全剔除（方案 §8 V-C 验收行）；
     * - chatStyle 定池：严肃 → 玩乐/吃喝 降权；
     * - 我方 facet 工作向 → 吃喝/玩乐 降权；
     * - closeness 定深度：maxDepth = min(3, closeness+1)；未萃取不设限（全靠
     *   阶段门控兜底——全剔除会开天窗，违背逐级放宽的兜底出题原则）；
     * - heatScore 调权：热 ≥70 放深一档（≤3）；冷 <30 收到浅层并降权段子/玩乐。
     */
    fun filterFor(
        identity: String?,
        chatStyle: String?,
        closeness: Int?,
        facetTags: Collection<String>,
        heatScore: Int?,
    ): TopicFilter {
        val forbidden = LinkedHashSet<String>()
        val penalty = LinkedHashSet<String>()
        if (identity == StyleVocab.IDENTITY_LEADER) {
            forbidden += listOf(TopicStyleTags.JOKE, TopicStyleTags.FOOD, TopicStyleTags.FUN)
        }
        if (chatStyle == StyleVocab.STYLE_SERIOUS) {
            penalty += listOf(TopicStyleTags.FUN, TopicStyleTags.FOOD)
        }
        if (TopicStyleTags.isWorkFacet(facetTags)) {
            penalty += listOf(TopicStyleTags.FOOD, TopicStyleTags.FUN)
        }
        // 亲疏深度门：陌生只许浅层；未萃取（null）不设限（交由阶段门控兜底）。
        var maxDepth = closeness?.let { (it + 1).coerceAtMost(3) } ?: 3
        if (heatScore != null && heatScore < 30) {
            maxDepth = 1
            penalty += listOf(TopicStyleTags.JOKE, TopicStyleTags.FUN)
        } else if (heatScore != null && heatScore >= 70) {
            maxDepth = (maxDepth + 1).coerceAtMost(3)
        }
        return TopicFilter(
            forbiddenTags = forbidden,
            penaltyTags = penalty,
            maxDepth = maxDepth,
            closeness = closeness,
        )
    }
}
