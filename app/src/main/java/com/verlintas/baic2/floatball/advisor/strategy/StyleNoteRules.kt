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

package com.verlintas.baic2.floatball.advisor.strategy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * customNote 行为化编译结果（P3 §八）：一句手写补充被小模型解释成
 * 「要这样做 / 避免」两组短句，经用户确认后才进提示词。
 */
@Serializable
data class CompiledCustomNote(
    @SerialName("do") val dos: List<String> = emptyList(),
    val dont: List<String> = emptyList(),
)

/**
 * customNote 编译 JSON 的确定性校验（纯函数，零 Android 依赖，供 JVM 单测）。
 *
 * LLM 输出永远不可信：宽容去围栏截取 `{…}` 后按硬规则裁剪——
 * 每条 trim、非空白、长度 ≤[MAX_ITEM_CHARS]、每组至多 [MAX_ITEMS] 条、组内去重；
 * 两组全空视为编译失败（返回 null，调用方保留原因、不写库、不阻塞其他链路）。
 */
object StyleNoteRules {

    const val MAX_ITEMS = 3
    const val MAX_ITEM_CHARS = 20

    /**
     * 解析并裁剪模型输出。[raw] 可含 ```json 围栏或解释性前后缀；
     * 格式错误、非数组元素、裁剪后两组皆空 → null。
     */
    fun parse(raw: String?): CompiledCustomNote? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val noFence = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = noFence.indexOf('{')
        val end = noFence.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val parsed = runCatching {
            strategyJson.decodeFromString(
                CompiledCustomNote.serializer(),
                noFence.substring(start, end + 1),
            )
        }.getOrNull() ?: return null
        val dos = sanitize(parsed.dos)
        val donts = sanitize(parsed.dont)
        if (dos.isEmpty() && donts.isEmpty()) return null
        return CompiledCustomNote(dos = dos, dont = donts)
    }

    private fun sanitize(items: List<String>): List<String> =
        items.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_ITEM_CHARS) }
            .distinct()
            .take(MAX_ITEMS)
            .toList()
}
