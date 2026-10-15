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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.brief

import com.verlintas.baic2.core.model.Note
import com.verlintas.baic2.core.model.NoteKind
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.floatball.contact.DealFact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「交往脉络」提示词段的纯函数渲染器（设计文档 §6.5–6.6，v4 §8.1）。
 *
 * 输入全部是已经按联系人收敛、且由调用方跨段去重过的数据（recallByEntity /
 * ContactMemoryReader / ContactHistoryGateway 的产物），渲染本身不做任何
 * 检索，也不含 Android 依赖。输出拼在 customSystemPrompt 中、与上游
 * memoryContext 分轨——目标/人设/话术等业务区块由 ChatBriefBuilder 负责。
 * v4 起「转折铁律」上移归并进系统提示词首位的【规约总纲】（共享/绑定
 * 回合统一生效），本渲染器只管「带日期的历史事实」，整段不超过
 * SECTION_MAX 字。
 */
internal object ContactBriefSection {

    private val DATE = DateTimeFormatter.ofPattern("MM-dd", Locale.CHINA)
    private val ZONE: ZoneId = ZoneId.systemDefault()

    /** 每条事实/摘录的最大长度；整段的总字符预算。 */
    private const val LINE_MAX = 80
    private const val SECTION_MAX = 600

    /**
     * @param entityNotes recallByEntity 按人直取的笔记（调用方已去重）。
     * @param dueNotes 按人只读取出的窗内承诺/约见（AGREEMENT/PLAN，到期置顶）。
     * @param dealFacts 更早会话里的报价/合同等成交要件，可空。
     * @return 提示词段落；三类数据全空时返回 null（时序约束由全局总纲承载）。
     */
    fun render(
        contact: ContactRef,
        entityNotes: List<Note>,
        dueNotes: List<Note>,
        dealFacts: List<DealFact>,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (entityNotes.isEmpty() && dueNotes.isEmpty() && dealFacts.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("【与").append(contact.displayName).append("的交往脉络】")
        sb.append("（均为历史时点事实，仅供参考，不代表对方此刻态度）\n")

        var used = sb.length
        fun appendLine(line: String) {
            if (used >= SECTION_MAX) return
            val bounded = if (line.length <= SECTION_MAX - used) {
                line
            } else {
                line.take((SECTION_MAX - used).coerceAtLeast(0))
            }
            sb.append(bounded)
            used += bounded.length
        }

        // 1) 到期/将到期的承诺与约定置顶（前瞻记忆）。
        dueNotes
            .filter { it.whenAt != null }
            .sortedBy { it.whenAt }
            .take(2)
            .forEach { note ->
                appendLine("- ${md(note.whenAt!!)} 前待办 · ${kindLabel(note.kind)}：${clip(note.content)}\n")
            }

        // 2) 按人直取的事实，以时间倒序（最新的旧状态排在最前）。
        entityNotes
            .sortedByDescending { it.whenAt ?: it.updatedAt }
            .take(6)
            .forEach { note ->
                val date = note.whenAt?.let { "${md(it)} · " } ?: ""
                appendLine("- $date${kindLabel(note.kind)}：${clip(note.content)}\n")
            }

        // 3) 更早会话中的成交要件（报价/合同/账期原话摘录）。
        if (dealFacts.isNotEmpty()) {
            appendLine("【更早聊天中的成交要件】（如与当前屏冲突，以当前屏为准）\n")
            dealFacts.forEach { fact ->
                appendLine("- ${md(fact.createdAt)} ｜ ${clip(fact.content)}\n")
            }
        }

        return sb.toString()
    }

    private fun clip(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= LINE_MAX) oneLine else oneLine.take(LINE_MAX) + "…"
    }

    private fun md(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZONE).format(DATE)

    private fun kindLabel(kind: NoteKind): String = when (kind) {
        NoteKind.FACT -> "事实"
        NoteKind.PROFILE -> "画像"
        NoteKind.PREFERENCE -> "偏好"
        NoteKind.EVENT -> "事件"
        NoteKind.PLAN -> "计划"
        NoteKind.AGREEMENT -> "承诺"
        NoteKind.SUMMARY -> "小结"
    }
}
