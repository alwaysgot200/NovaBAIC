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

import com.verlintas.baic2.core.data.repository.MemoryRepository
import com.verlintas.baic2.core.model.NoteKind
import com.verlintas.baic2.core.model.NoteSource
import com.verlintas.baic2.floatball.advisor.data.MY_PERSONA_CONTACT_ID
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 画像确认 → 上游记忆双写的唯一入口（方案 §6.5 写入纪律）。
 *
 * - 只有用户 ✓ 确认（或手填我方人设）才写上游；LLM 草稿/拒绝/goal 一律不写；
 * - kind/source/importance/entities/特殊字段按字段的 [PersonaFieldDef.memory]
 *   类别映射，映射规则随 persona_schema.json 资产演进；
 * - 去重/合并/裁决交给 [MemoryRepository.addNote] 既有管线，本类不自己判重；
 * - 双写失败不回滚档案固化（档案是事实主，上游只是索引投影）。
 */
@Singleton
class ProfileMemorySync @Inject constructor(
    private val contacts: ContactRepository,
    private val schemas: PersonaSchemas,
    private val memoryRepository: MemoryRepository,
) {
    /**
     * 确认对方字段草稿并双写。返回固化后的字段（不存在/被冷却拦截返回 null）。
     * [conversationId] 透传给上游 Note 做来源关联，可空。
     */
    suspend fun confirmAndSync(
        contactId: Long,
        field: String,
        conversationId: Long? = null,
    ): PersonaFieldEntity? {
        if (contactId == MY_PERSONA_CONTACT_ID) return null
        val confirmed = contacts.confirmDraft(contactId, field) ?: return null
        runCatching { syncTheirField(contactId, confirmed, conversationId) }
        return confirmed
    }

    /**
     * 清除与该联系人相关的上游记忆（双写纪律的逆操作，P5「清除相关记忆」）。
     * 按主名 entity 召回（双写每条都含主名），再用解码后的实体集合精确比对，
     * 防止同名陌生人被 LIKE 误伤；逐条走软删除（archive + 抑制指纹，重学被拦）。
     * 不触碰 advisor 档案；goal 从不双写，本来就无记忆可清。返回清除条数。
     */
    suspend fun forgetContact(contactId: Long): Int {
        if (contactId == MY_PERSONA_CONTACT_ID) return 0
        val profile = contacts.profile(contactId) ?: return 0
        val scored = runCatching {
            memoryRepository.recallByEntity(
                entities = listOf(profile.displayName),
                limit = FORGET_BATCH,
                feedback = false,
            )
        }.getOrDefault(emptyList())
        val ids = scored.asSequence()
            .map { it.note }
            .filter { note -> note.entities.any { it == profile.displayName } }
            .map { it.id }
            .toList()
        ids.forEach { runCatching { memoryRepository.delete(it) } }
        return ids.size
    }

    /**
     * 用户手填/修改我方人设字段（[ContactRepository.setUserField] 之后调用）。
     * 空值（删除）不写上游。§6.5：PROFILE/USER/imp4，entities=["我"]。
     */
    suspend fun syncMyPersonaField(field: String, rawValue: String) {
        if (rawValue.isBlank()) return
        val def = schemas.get().def(field) ?: return
        val rendered = renderValue(def.type, rawValue) ?: return
        runCatching {
            memoryRepository.addNote(
                kind = NoteKind.PROFILE,
                content = "我的${def.label}：$rendered",
                importance = IMPORTANCE_MINE,
                source = NoteSource.USER,
                entities = listOf(SELF_ENTITY),
            )
        }
    }

    private suspend fun syncTheirField(
        contactId: Long,
        field: PersonaFieldEntity,
        conversationId: Long?,
    ) {
        val def = schemas.get().def(field.field) ?: return
        if (def.owner != "their") return
        if (def.memory == PersonaFieldDef.MEMORY_NONE) return

        val profile = contacts.profile(contactId) ?: return
        val aliases = contacts.aliases(contactId).map { it.alias }
        val entities = (listOf(profile.displayName) + aliases).filter { it.isNotBlank() }.distinct()
        val rendered = renderValue(def.type, field.value) ?: return
        val content = "${profile.displayName}的${def.label}：$rendered"
        val now = System.currentTimeMillis()

        when (def.memory) {
            PersonaFieldDef.MEMORY_AGREEMENT -> memoryRepository.addNote(
                kind = NoteKind.AGREEMENT,
                content = content,
                importance = IMPORTANCE_STRONG,
                conversationId = conversationId,
                whenAt = parseDate(rendered) ?: now,
                source = NoteSource.USER,
                entities = listOf(profile.displayName),
            )

            PersonaFieldDef.MEMORY_EVENT -> memoryRepository.addNote(
                kind = NoteKind.EVENT,
                content = content,
                importance = IMPORTANCE_STRONG,
                conversationId = conversationId,
                whenAt = parseDate(rendered) ?: now,
                source = NoteSource.USER,
                entities = listOf(profile.displayName),
            )

            PersonaFieldDef.MEMORY_FACT -> memoryRepository.addNote(
                kind = NoteKind.FACT,
                content = content,
                importance = IMPORTANCE_NORMAL,
                conversationId = conversationId,
                source = NoteSource.ASSISTANT,
                entities = listOf(profile.displayName),
                expiresAt = now + FACT_TTL_MS,
            )

            // null / profile：稳定事实，全别名进 entities 以便改名后仍可召回。
            else -> memoryRepository.addNote(
                kind = NoteKind.PROFILE,
                content = content,
                importance = IMPORTANCE_NORMAL,
                conversationId = conversationId,
                source = NoteSource.USER,
                entities = entities,
            )
        }
    }

    /** tags 存的是 JSON 数组，其余类型即原文；空值返回 null。 */
    private fun renderValue(type: String, stored: String): String? {
        val text = if (type == PersonaFieldType.TAGS) {
            PersonaValues.decodeTags(stored).joinToString("、")
        } else {
            stored.trim()
        }
        return text.takeIf { it.isNotBlank() }
    }

    /** 宽松解析常见日期写法为当天 0 点毫秒；解析不了返回 null（回退调用方用当前时间）。 */
    private fun parseDate(text: String): Long? {
        val candidate = DATE_PATTERN.find(text)?.value ?: return null
        for (pattern in PATTERNS) {
            val parsed = runCatching {
                val normalized = candidate.replace('年', '-').replace('月', '-').removeSuffix("日")
                LocalDate.parse(normalized, DateTimeFormatter.ofPattern(pattern))
            }.getOrNull() ?: continue
            return parsed.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return null
    }

    private companion object {
        const val IMPORTANCE_NORMAL = 3
        const val IMPORTANCE_STRONG = 4
        const val IMPORTANCE_MINE = 4
        const val SELF_ENTITY = "我"
        const val FACT_TTL_MS = 90L * 24 * 60 * 60 * 1000
        const val FORGET_BATCH = 200

        val DATE_PATTERN = Regex("""\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?""")
        val PATTERNS = listOf("yyyy-M-d", "yyyy/M/d")
    }
}
