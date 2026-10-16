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

/**
 * enum/tags 字段的单个码表选项（schema v2，方案 §6.1）：
 * 存储与传输一律用 [code]（[a-z0-9_]+）；[label] 是唯一的用户可见中文文案；
 * [impl] 非空时在画像提示词中追加该选项的行为暗示；[topicHook] 标记可作话题钩子。
 */
@Serializable
data class FieldOption(
    val code: String,
    val label: String,
    val impl: String = "",
    val topicHook: Boolean = false,
)

@Serializable
data class PersonaFieldDef(
    val key: String,
    /** their = 对方字段；my = 我方字段。 */
    val owner: String,
    val group: String,
    val type: String,
    val label: String,
    val extract: Boolean = true,
    /** 码表选项；tags 字段无 options 时为自由标签（原样存储，不走 code/~ 体系）。 */
    val options: List<FieldOption>? = null,
    /** 是否允许模型/用户填写码表外的值：允许时以 `~原文` 存储，不允许时萃取归一丢弃。 */
    val allowCustom: Boolean = true,
    /** 提示词显著度：[SALIENCE_HIGH] 的行可追加 [FieldOption.impl]；集合裁剪留待后续阶段。 */
    val salience: String? = null,
    /** text/textarea 字段的行为暗示模板，{v} 占位替换为显示值；P1 仅数据占位。 */
    val implTpl: String? = null,
    /** 时效天数（易变商情）；超期字段保留但渲染「可能已变化，请先验证」措辞。 */
    val staleAfterDays: Int? = null,
    /**
     * 联系人类型显隐（P3 群实体，方案 §13）：取值 person/group，缺省 null=两类档案都显示；
     * 非空时仅在 [com.verlintas.baic2.floatball.advisor.data.ContactProfileEntity.contactType]
     * 命中时渲染/萃取。
     */
    val showIfContactType: List<String>? = null,
    /**
     * 用户 ✓ 确认后双写上游记忆的类别（方案 §6.5 写入表）：
     * null/[MEMORY_PROFILE] 稳定事实；[MEMORY_AGREEMENT] 承诺（带 whenAt）；
     * [MEMORY_FACT] 易变商情（+90 天过期）；[MEMORY_EVENT] 大事件（带 whenAt）；
     * [MEMORY_NONE] 只留档案、不写上游。owner=my 的字段固定按「我方人设」双写。
     */
    val memory: String? = null,
) {
    fun optionByCode(code: String): FieldOption? = options?.firstOrNull { it.code == code }

    fun optionByLabel(label: String): FieldOption? = options?.firstOrNull { it.label == label }

    companion object {
        const val MEMORY_PROFILE = "profile"
        const val MEMORY_AGREEMENT = "agreement"
        const val MEMORY_FACT = "fact"
        const val MEMORY_EVENT = "event"
        const val MEMORY_NONE = "none"
        const val SALIENCE_HIGH = "high"
        const val SALIENCE_NORMAL = "normal"
        const val SALIENCE_LOW = "low"
    }
}

@Serializable
data class PersonaSchema(
    val fields: List<PersonaFieldDef>,
) {
    val theirFields: List<PersonaFieldDef> get() = fields.filter { it.owner == "their" }

    val myFields: List<PersonaFieldDef> get() = fields.filter { it.owner == "my" }

    fun def(key: String): PersonaFieldDef? = fields.firstOrNull { it.key == key }

    /** P3：按联系人类型（person/group）过滤可见字段；未声明显隐的字段两类都显示。 */
    fun fieldsFor(owner: String, contactType: String): List<PersonaFieldDef> =
        fields.filter { it.owner == owner && (it.showIfContactType == null || contactType in it.showIfContactType) }
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
