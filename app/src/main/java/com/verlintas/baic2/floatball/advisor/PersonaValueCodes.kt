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

/**
 * 画像值 code 体系纯函数（方案 §6.1，P1）。
 *
 * 存储约定：
 * - 码表选项存 code（[a-z0-9_]+）；码表外的用户/模型原文存 `~原文`（[CUSTOM_PREFIX]）；
 * - 无码表字段（text/textarea/自由 tags）原样存储，不加前缀；
 * - tags 存 JSON 数组（[PersonaValues.encodeTags]），元素同样按上面两条。
 *
 * 判定顺序统一为：code 精确命中 → label 精确命中 → custom 处理，避免 code/label 歧义。
 * 本对象零 Android 依赖，可供 JVM 单测。
 */
object PersonaValueCodes {

    const val CUSTOM_PREFIX = "~"

    private val codeRegex = Regex("[a-z0-9_]+")

    fun isCode(value: String): Boolean = codeRegex.matches(value)

    /** 原文转自定义存储值；已带前缀的不重复添加。 */
    fun customValue(raw: String): String {
        val v = raw.trim()
        return if (v.startsWith(CUSTOM_PREFIX)) v else CUSTOM_PREFIX + v
    }

    /** 去掉自定义前缀（若有），得到人类可读原文。 */
    fun unwrapCustom(stored: String): String =
        if (stored.startsWith(CUSTOM_PREFIX)) stored.substring(CUSTOM_PREFIX.length) else stored

    /**
     * 编辑器/萃取把单值落库：label/code 命中码表 → code；空串 → null；
     * 码表外值 → `~原文`（调用方需先按 [PersonaFieldDef.allowCustom] 决定是否允许）。
     */
    fun storedOption(def: PersonaFieldDef, input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        def.optionByCode(raw)?.let { return it.code }
        def.optionByLabel(raw)?.let { return it.code }
        return customValue(raw)
    }

    /** 单个存储值 → 用户可见文本：code→label，`~x`→x，未迁移旧 label 原样回显，其余原值兜底。 */
    fun displaySingle(def: PersonaFieldDef?, stored: String): String {
        if (stored.startsWith(CUSTOM_PREFIX)) return stored.substring(CUSTOM_PREFIX.length)
        val options = def?.options ?: return stored
        options.firstOrNull { it.code == stored }?.let { return it.label }
        options.firstOrNull { it.label == stored }?.let { return it.label }
        return stored
    }

    /**
     * 整字段值 → 用户可见文本。tags 逐元素解码后顿号拼接；其余按单值处理。
     * 无码表 tags 的元素原样返回。
     */
    fun displayValue(def: PersonaFieldDef?, stored: String): String {
        if (def?.type == PersonaFieldType.TAGS) {
            return PersonaValues.decodeTags(stored)
                .filter { it.isNotBlank() }
                .joinToString("、") { displaySingle(def, it) }
        }
        return displaySingle(def, stored)
    }

    /**
     * 萃取归一单个值：命中码表 → code；码表外：允许自定义 → `~原文`，不允许 → null（丢弃）；
     * 无码表字段（自由 tags/text）trim 后原样保留。
     */
    fun normalizeExtracted(def: PersonaFieldDef, raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        val options = def.options ?: return v
        options.firstOrNull { it.code == v }?.let { return it.code }
        options.firstOrNull { it.label == v }?.let { return it.code }
        return if (def.allowCustom) customValue(v) else null
    }

    /** 萃取归一 tags：逐元素归一后重新编码；被丢弃的元素（不允许的自造值）不保留。 */
    fun normalizeTags(def: PersonaFieldDef, elements: Collection<String>): String {
        val kept = elements.mapNotNull { normalizeExtracted(def, it) }.distinct()
        return PersonaValues.encodeTags(kept)
    }

    /**
     * 存量迁移单个值（幂等）：已是 code/`~x`/空 → 不动；旧 label → code；
     * 码表无法识别的旧值 → `~原文`（不丢数据）。无码表字段原样返回。
     */
    fun migrateSingle(def: PersonaFieldDef, stored: String): String {
        val v = stored.trim()
        if (v.isEmpty() || v.startsWith(CUSTOM_PREFIX)) return stored
        val options = def.options ?: return stored
        if (options.any { it.code == v }) return v
        options.firstOrNull { it.label == v }?.let { return it.code }
        return customValue(v)
    }

    /** 存量迁移整字段值：tags 逐元素迁移后重新编码；其余按单值迁移。 */
    fun migrateValue(def: PersonaFieldDef, stored: String): String {
        if (def.type != PersonaFieldType.TAGS) return migrateSingle(def, stored)
        if (def.options == null) return stored
        val tags = PersonaValues.decodeTags(stored)
        val migrated = tags.map { migrateSingle(def, it) }
        return PersonaValues.encodeTags(migrated)
    }
}
