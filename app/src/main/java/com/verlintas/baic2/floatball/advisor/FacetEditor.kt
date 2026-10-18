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
 * 我方侧面标签编辑的纯函数面（VA-2 U5，v10 方案 §2.4）：画像卡 facet 编辑器
 * 的输入拆分/增删/去重逻辑抽离，UI 组合函数不含业务；JVM 可直接单测。
 * 落库编码仍走 [SelfFacets.encode]（去重保序、空集 null 语义），本对象只管
 * 编辑过程中的内存列表形态。
 */
object FacetEditor {

    /** 自由输入的合法分隔符：中文逗号/顿号/分号、英文逗号/分号、换行。 */
    private val SEPARATORS = charArrayOf('，', '、', '；', ',', ';', '\n', '\r')

    /** 自由输入拆成标签：按分隔符切、去首尾空白、丢空段。 */
    fun parseInput(raw: String): List<String> =
        raw.split(*SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

    /** 把自由输入并入既有标签集：去重保序（既有顺序不动，新标签追加在尾部）。 */
    fun addTags(current: List<String>, rawInput: String): List<String> {
        val merged = current.filter { it.isNotBlank() }.toMutableList()
        parseInput(rawInput).forEach { tag ->
            if (merged.none { it == tag }) merged.add(tag)
        }
        return merged
    }

    /** 精确移除一个标签（不影响其他同名前后缀标签）。 */
    fun removeTag(current: List<String>, tag: String): List<String> =
        current.filter { it != tag }

    /** 库选芯片的切换语义：已选则移除、未选则追加（尾部）。 */
    fun toggleTag(current: List<String>, tag: String): List<String> =
        if (tag in current) removeTag(current, tag) else addTag(current, tag)

    /** 追加单个标签；空白或已存在时原样返回。 */
    fun addTag(current: List<String>, tag: String): List<String> {
        val clean = tag.trim()
        return if (clean.isEmpty() || clean in current) current else current + clean
    }
}
