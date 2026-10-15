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

import com.verlintas.baic2.floatball.advisor.data.StyleCardEntity

/**
 * 风格卡 → 提示词【说话方式】段的纯函数渲染（方案 §8.1，P1）。零 Android 依赖。
 * 回退链由调用方（StyleCardRepository/ChatBriefBuilder）决定：联系人卡 → 全局卡 → 旧 style 原文。
 */
object StyleResolver {

    const val SECTION_TITLE = "【说话方式】"

    /**
     * 渲染风格卡。基调与意图均按资产解析，失效 code 静默跳过；
     * [stageCode] 非空时按 [StyleIntent.fitStages] 过滤，null 时不过滤（P1 固定 null）。
     * 基调/意图/自定义补充全为空时返回 null（调用方整段省略）。
     */
    fun render(card: StyleCardEntity?, playbook: StylePlaybook, stageCode: String? = null): String? {
        if (card == null) return null
        val tone = playbook.tone(card.toneCode)
        val intents = PersonaValues.decodeTags(card.intentCodes)
            .mapNotNull { code ->
                val intent = playbook.intent(code) ?: return@mapNotNull null
                val stages = intent.fitStages
                if (stageCode != null && stages != null && stageCode !in stages) null else intent
            }
        val note = card.customNote?.trim().orEmpty()
        if (tone == null && intents.isEmpty() && note.isEmpty()) return null

        return buildString {
            appendLine(SECTION_TITLE)
            if (tone != null) appendLine("基调：${tone.label}。${tone.voiceRule}")
            intents.forEach { intent ->
                val doPart = intent.`do`.filter { it.isNotBlank() }.joinToString("；")
                val dontPart = intent.dont.filter { it.isNotBlank() }.joinToString("；")
                val body = buildList {
                    if (doPart.isNotEmpty()) add("要$doPart")
                    if (dontPart.isNotEmpty()) add("不要$dontPart")
                }.joinToString("。")
                appendLine("意图「${intent.label}」：$body。")
            }
            if (note.isNotEmpty()) append("其他要求：$note")
        }.trimEnd()
    }

    /** 旧 contact_profiles.style 自由文本的兜底段（标题统一改为【说话方式】，正文原样）。 */
    fun renderLegacy(style: String?): String? =
        style?.trim()?.takeIf { it.isNotEmpty() }?.let { "$SECTION_TITLE\n$it" }

    /** UI 副行摘要：「基调 · 意图1、意图2」；无有效内容返回 null（customNote 非空由调用方自行补标记）。 */
    fun summary(card: StyleCardEntity?, playbook: StylePlaybook): String? {
        if (card == null) return null
        val tone = playbook.tone(card.toneCode)?.label
        val intents = PersonaValues.decodeTags(card.intentCodes)
            .mapNotNull { playbook.intent(it)?.label }
            .joinToString("、")
        return listOfNotNull(tone, intents.takeIf { it.isNotEmpty() })
            .joinToString(" · ")
            .takeIf { it.isNotEmpty() }
    }
}
