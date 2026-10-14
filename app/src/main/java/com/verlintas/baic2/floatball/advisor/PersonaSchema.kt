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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 画像字段值类型（方案 §4.2）。 */
object PersonaFieldType {
    const val TEXT = "text"
    const val TEXTAREA = "textarea"
    const val TAGS = "tags"
    const val ENUM = "enum"
    const val DATE = "date"
}

@Serializable
data class PersonaFieldDef(
    val key: String,
    /** their = 对方字段；my = 我方字段。 */
    val owner: String,
    val group: String,
    val type: String,
    val label: String,
    val extract: Boolean = true,
    val options: List<String>? = null,
    /**
     * 用户 ✓ 确认后双写上游记忆的类别（方案 §6.5 写入表）：
     * null/[MEMORY_PROFILE] 稳定事实；[MEMORY_AGREEMENT] 承诺（带 whenAt）；
     * [MEMORY_FACT] 易变商情（+90 天过期）；[MEMORY_EVENT] 大事件（带 whenAt）；
     * [MEMORY_NONE] 只留档案、不写上游。owner=my 的字段固定按「我方人设」双写。
     */
    val memory: String? = null,
) {
    companion object {
        const val MEMORY_PROFILE = "profile"
        const val MEMORY_AGREEMENT = "agreement"
        const val MEMORY_FACT = "fact"
        const val MEMORY_EVENT = "event"
        const val MEMORY_NONE = "none"
    }
}

@Serializable
data class PersonaSchema(
    val fields: List<PersonaFieldDef>,
    /** 联系人「语言风格」的预设候选（画像页 chip，方案 P4 风格选择器）；仍允许自定义。 */
    val stylePresets: List<String> = emptyList(),
) {
    val theirFields: List<PersonaFieldDef> get() = fields.filter { it.owner == "their" }

    val myFields: List<PersonaFieldDef> get() = fields.filter { it.owner == "my" }

    fun def(key: String): PersonaFieldDef? = fields.firstOrNull { it.key == key }
}

/** tags 字段值的 JSON 数组编解码；渲染时空字段不参与（由调用方过滤）。 */
object PersonaValues {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeTags(tags: Collection<String>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), tags.toList())

    fun decodeTags(value: String): List<String> =
        runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), value)
        }.getOrDefault(emptyList())

    /** 单行渲染「标签: 值」；tags 顿号拼接，空值返回 null（调用方跳过）。 */
    fun renderLine(label: String, type: String, value: String): String? {
        val rendered = when (type) {
            PersonaFieldType.TAGS -> decodeTags(value).filter { it.isNotBlank() }.joinToString("、")
            else -> value.trim()
        }
        return rendered.takeIf { it.isNotEmpty() }?.let { "$label: $it" }
    }
}

/**
 * 画像字段目录：一份 assets JSON 同时驱动萃取提示词 / 编辑表单 / 提示词渲染。
 * 新增字段只改 JSON，不改表结构（方案 §4.2）。
 */
@Singleton
class PersonaSchemas @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cached: PersonaSchema? = null

    fun get(): PersonaSchema = cached ?: load().also { cached = it }

    private fun load(): PersonaSchema =
        context.assets.open(SCHEMA_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            Json { ignoreUnknownKeys = true }.decodeFromString(PersonaSchema.serializer(), reader.readText())
        }

    private companion object {
        const val SCHEMA_ASSET = "advisor/persona_schema.json"
    }
}

/** 标题/名称规范化纯函数（方案 §11.1），供识别与 contactKey 生成共用。 */
object ContactNames {

    private val unreadBadge = Regex("""[（(]\s*\d+\s*(?:条新消息)?\s*[)）]|•\s*\d+""")
    private val typingTail = Regex("""(?:对方)?正在输入…?|连接中…?|对方正在输入\.\.\.""")
    private val groupCount = Regex("""[（(]\s*\d+\s*[)）]\s*$""")
    private val whitespace = Regex("""\s+""")

    /**
     * 规范化为匹配键：NFKC 全半角统一 → 去未读角标/状态尾巴/群人数 →
     * 去所有空白 → 拉丁小写。原始标题保留为 rawAlias 展示。
     */
    fun normalize(rawTitle: String): String {
        val nfkc = Normalizer.normalize(rawTitle.trim(), Normalizer.Form.NFKC)
        return nfkc
            .replace(unreadBadge, "")
            .replace(typingTail, "")
            .replace(groupCount, "")
            .replace(whitespace, "")
            .lowercase()
    }

    /** 构造首见标识：IM 包名 + 规范化标题。 */
    fun contactKey(imPackage: String, normalizedTitle: String): String =
        "$imPackage:$normalizedTitle"

    /** 大小写不敏感的编辑距离（Levenshtein），改名模糊匹配阈值用。 */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val curr = IntArray(b.length + 1) { i }
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = curr
        }
        return prev[b.length]
    }
}
