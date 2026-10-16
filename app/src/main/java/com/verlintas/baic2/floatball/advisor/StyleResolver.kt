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
import com.verlintas.baic2.floatball.advisor.strategy.CompiledCustomNote
import com.verlintas.baic2.floatball.advisor.strategy.StyleNoteRules

/**
 * 风格卡 → 提示词【说话方式】段的纯函数渲染（方案 §8.1，P1）。零 Android 依赖。
 * 回退链由调用方（StyleCardRepository/ChatBriefBuilder）决定：联系人卡 → 全局卡 → 旧 style 原文。
 */
object StyleResolver {

    const val SECTION_TITLE = "【说话方式】"

    /**
     * 渲染风格卡。基调与意图均按资产解析，失效 code 静默跳过；
     * [stageCode] 非空时按 [StyleIntent.fitStages] 过滤，null 时不过滤（P1 固定 null）。
     * [packStageCodes] P2 跨包兼容（§7.5）：非空时，意图仅在「对本包某些站声明过门控
     * （fitStages 与本包站码有交集）但不含当前站」才被过滤；从未对本包声明门控的意图
     * 视为通用意图，全站点适用。为 null 时保持 P1 旧过滤行为。
     * [pathKind] P3：非空时再按 [StyleIntent.pathKind] 门控——意图声明了专属路径且与
     * 当前路径不一致即过滤（如防守类意图只在 defend 包上浮）；null=不做路径过滤。
     * 基调/意图/自定义补充全为空时返回 null（调用方整段省略）。
     */
    fun render(
        card: StyleCardEntity?,
        playbook: StylePlaybook,
        stageCode: String? = null,
        packStageCodes: Set<String>? = null,
        pathKind: String? = null,
    ): String? {
        if (card == null) return null
        // P3 §八：customNote 编译确认流期间整卡是草稿，基调/意图/补充一律不进提示词；
        // 调用方（StyleCardRepository.cardFor）对联系人草稿会回退全局卡。
        if (!card.confirmed) return null
        val tone = playbook.tone(card.toneCode)
        val intents = PersonaValues.decodeTags(card.intentCodes)
            .mapNotNull { code ->
                val intent = playbook.intent(code) ?: return@mapNotNull null
                if (isStageFiltered(intent.fitStages, stageCode, packStageCodes)) return@mapNotNull null
                if (isPathFiltered(intent.pathKind, pathKind)) return@mapNotNull null
                intent
            }
        // P3：已确认的编译结果按「要这样做/避免」渲染；
        // compiled 为空但 customNote 有值（P1/P2 旧卡）保留旧口径原文兜底。
        val compiled: CompiledCustomNote? = card.compiledCustomJson?.let { StyleNoteRules.parse(it) }
        val rawNote = card.customNote?.trim().orEmpty()
        if (tone == null && intents.isEmpty() && compiled == null && rawNote.isEmpty()) return null

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
            if (compiled != null) {
                if (compiled.dos.isNotEmpty()) {
                    appendLine("要这样做：${compiled.dos.joinToString("；")}")
                }
                if (compiled.dont.isNotEmpty()) {
                    append("避免：${compiled.dont.joinToString("；")}")
                }
            } else if (rawNote.isNotEmpty()) {
                append("其他要求：$rawNote")
            }
        }.trimEnd()
    }

    /** 旧 contact_profiles.style 自由文本的兜底段（标题统一改为【说话方式】，正文原样）。 */
    fun renderLegacy(style: String?): String? =
        style?.trim()?.takeIf { it.isNotEmpty() }?.let { "$SECTION_TITLE\n$it" }

    /**
     * 意图是否在当前站被过滤：
     * - 无当前站/意图未声明 fitStages → 不过滤；
     * - 未提供 packStageCodes（P1 口径）→ fitStages 不含当前站即过滤；
     * - 提供 packStageCodes（P2 口径）→ 仅当 fitStages 对本包声明过门控（有交集）
     *   却不含当前站时过滤；无交集说明该意图从未门控本包，按通用意图保留。
     */
    private fun isStageFiltered(
        fitStages: List<String>?,
        stageCode: String?,
        packStageCodes: Set<String>?,
    ): Boolean {
        if (stageCode == null || fitStages == null) return false
        if (packStageCodes == null) return stageCode !in fitStages
        val gatesThisPack = fitStages.intersect(packStageCodes).isNotEmpty()
        return gatesThisPack && stageCode !in fitStages
    }

    /**
     * 意图是否在当前路径被过滤：意图未声明专属路径（null）或调用方未提供路径 → 不过滤；
     * 声明了专属路径（如 defend）则只在同路径包渲染。
     */
    private fun isPathFiltered(intentPathKind: String?, pathKind: String?): Boolean =
        intentPathKind != null && pathKind != null && intentPathKind != pathKind

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
