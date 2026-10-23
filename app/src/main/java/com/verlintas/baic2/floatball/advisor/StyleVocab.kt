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

/**
 * 对方风格三标签的中文词表（v10 方案 §4.1，V-C 废话分层的同源事实来源）。
 *
 * 单一来源原则（F8 起强化）：UI（AdvisorPersonaCard 的下拉芯片）与判定侧
 * （PersonaExtractor 归一、TopicStyleRules/ProactiveStyleTiers 分档、
 * topic_seeds.json 种子 minCloseness）全部直接引用本对象常量——写库值必在
 * 词表内，不存在第二份手抄中文值；禁止散落硬编码字符串。
 * 纯对象、零 Android 依赖，JVM 可直接单测。
 */
object StyleVocab {

    const val IDENTITY_LEADER = "领导"
    const val IDENTITY_CLIENT = "客户"
    const val IDENTITY_COLLEAGUE = "同事"
    const val IDENTITY_FRIEND = "朋友"
    const val IDENTITY_FAMILY = "家人"
    const val IDENTITY_ROMANCE = "暧昧对象"
    const val IDENTITY_OTHER = "其他"

    const val STYLE_SERIOUS = "严肃"
    const val STYLE_EASY = "随和"
    const val STYLE_HUMOR = "幽默"
    const val STYLE_WARM = "热情"
    const val STYLE_COLD = "冷淡"

    const val CLOSENESS_STRANGER = "陌生"
    const val CLOSENESS_FAMILIAR = "熟识"
    const val CLOSENESS_CLOSE = "亲近"
    const val CLOSENESS_INTIMATE = "亲密"

    /** 身份词表（声明序即 UI 展示序）。 */
    val IDENTITIES = listOf(
        IDENTITY_LEADER, IDENTITY_CLIENT, IDENTITY_COLLEAGUE,
        IDENTITY_FRIEND, IDENTITY_FAMILY, IDENTITY_ROMANCE, IDENTITY_OTHER,
    )

    /** 聊天风格词表。 */
    val CHAT_STYLES = listOf(STYLE_SERIOUS, STYLE_EASY, STYLE_HUMOR, STYLE_WARM, STYLE_COLD)

    /** 亲疏中文词（下标即等级 0~3）。 */
    val CLOSENESS_LEVELS = listOf(CLOSENESS_STRANGER, CLOSENESS_FAMILIAR, CLOSENESS_CLOSE, CLOSENESS_INTIMATE)

    /**
     * 模型输出的身份词归一：精确命中词表原样收下；包含式近义（如「他是领导」）
     * 只认词表词出现在输出中的情形，取最先命中的词表词；词表外一律 null（丢弃），
     * 绝不自造枚举值。
     */
    fun normalizeIdentity(raw: String?): String? {
        val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        IDENTITIES.firstOrNull { text == it }?.let { return it }
        return IDENTITIES.firstOrNull { text.contains(it) }
    }

    // ── 身份多选（2026-10-23 方案 §2.1）：一人多身份（领导+亲人、同事+恋人），
    //    列存编码多值（JSON 数组），decode 向后兼容旧单值裸字符串。写库走
    //    [encodeIdentities]，判定走 [hasIdentity]，展示走 [decodeIdentities]。 ──

    /** 身份列表 → 编码多值（去重保序；空集返回 null，落库保持列空语义）。 */
    fun encodeIdentities(tags: List<String>): String? {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (clean.isEmpty()) null else PersonaValues.encodeTags(clean)
    }

    /**
     * 编码多值/旧单值 → 身份列表（词表外丢弃，防御不抛）：
     * `["领导","家人"]` → `[领导, 家人]`；旧裸字符串 `领导` → `[领导]`；
     * `领导+亲人`/`领导、亲人` 式拼接按分隔符拆段逐段归一。
     */
    fun decodeIdentities(raw: String?): List<String> {
        val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
        val parts = if (text.firstOrNull() == '[') {
            PersonaValues.decodeTags(text)
        } else {
            text.split('+', '、', '，', ',', '/', '／').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return parts.mapNotNull { part -> IDENTITIES.firstOrNull { part == it } ?: IDENTITIES.firstOrNull { part.contains(it) } }
            .distinct()
    }

    /**
     * 模型输出的多身份归一：收集文本中**全部**词表命中（声明序、去重、默认
     * 封顶 3 个），词表外丢弃——「他是我的领导也是我爸」→ `[领导, 家人]`。
     */
    fun normalizeIdentities(raw: String?, limit: Int = 3): List<String> {
        val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
        return IDENTITIES.filter { text.contains(it) }.distinct().take(limit.coerceAtLeast(1))
    }

    /** 判定侧统一入口：多身份命中任一即 true（兼容旧单值列数据）。 */
    fun hasIdentity(raw: String?, identity: String): Boolean = decodeIdentities(raw).contains(identity)

    /** 模型输出的聊天风格归一（口径同 [normalizeIdentity]）。 */
    fun normalizeChatStyle(raw: String?): String? {
        val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        CHAT_STYLES.firstOrNull { text == it }?.let { return it }
        return CHAT_STYLES.firstOrNull { text.contains(it) }
    }

    /**
     * 模型输出的亲疏等级归一：中文词或 0~3 整数皆可；越界/畸形/未知词 null（丢弃）。
     */
    fun closenessOf(raw: String?): Int? {
        val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        CLOSENESS_LEVELS.indexOf(text).takeIf { it >= 0 }?.let { return it }
        return text.toIntOrNull()?.takeIf { it in CLOSENESS_LEVELS.indices }
    }

    /** 亲疏等级 → 中文词（越界按最浅档 0 显示，仅展示用）。 */
    fun closenessLabel(level: Int): String =
        CLOSENESS_LEVELS.getOrNull(level.coerceIn(CLOSENESS_LEVELS.indices)) ?: CLOSENESS_STRANGER
}
