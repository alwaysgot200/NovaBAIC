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

package com.verlintas.baic2.floatball.contact

import com.verlintas.baic2.core.data.db.Baic2Database
import com.verlintas.baic2.core.data.mapper.ChatMapper
import com.verlintas.baic2.core.model.MemoryText
import com.verlintas.baic2.core.model.Note
import com.verlintas.baic2.core.model.NoteKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按联系人读取前瞻记忆（设计文档 §6.5「承诺/约见」）的只读薄封装。
 *
 * 不走上游 [com.verlintas.baic2.core.data.repository.MemoryRepository.upcoming]，
 * 原因有二：
 *  - upcoming() 会对取到的笔记 touch 强化，而自动看屏回合是只读浏览
 *    （feedback=false 纪律），且被实体过滤掉的条目也会被误强化；
 *  - upcoming() 先做全局 limit 再由调用方按实体过滤，记忆库一忙，当前
 *    联系人的待办会在过滤前被截断。
 *
 * 本类先按人实体收敛、再按时间窗截断；纯 SELECT，不 touch、不写任何列。
 * 零上游改动：仅复用 public 的 Baic2Database / NoteDao / ChatMapper。
 */
@Singleton
class ContactMemoryReader @Inject constructor(
    private val db: Baic2Database,
    private val mapper: ChatMapper,
) {
    /**
     * 取 [entities]（主名+别名）关联、whenAt 落在
     * ([now]-[overdueMs] .. [now]+[windowMs]) 内的承诺/约见，按到期时间
     * 正序、最多 [limit] 条。过期未销项的承诺一并浮出（仍可提醒）。
     */
    suspend fun dueFor(
        entities: List<String>,
        now: Long = System.currentTimeMillis(),
        windowMs: Long = DEFAULT_WINDOW_MS,
        overdueMs: Long = DEFAULT_OVERDUE_MS,
        limit: Int = DUE_LIMIT,
    ): List<Note> {
        val names = entities.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(NAME_LIMIT)
            .toList()
        if (names.isEmpty()) return emptyList()

        val merged = LinkedHashMap<Long, Note>()
        names.forEach { name ->
            db.noteDao().getByEntity(MemoryText.entityLikePattern(name), ENTITY_PAGE)
                .forEach { row -> merged.putIfAbsent(row.id, mapper.noteToModel(row)) }
        }

        val from = now - overdueMs
        val to = now + windowMs
        return merged.values
            .filter { note ->
                (note.kind == NoteKind.AGREEMENT || note.kind == NoteKind.PLAN) &&
                    note.whenAt != null && note.whenAt in from..to
            }
            .sortedBy { it.whenAt }
            .take(limit)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_WINDOW_MS = 7L * DAY_MS
        const val DEFAULT_OVERDUE_MS = 30L * DAY_MS
        const val DUE_LIMIT = 3
        const val NAME_LIMIT = 6

        /** 与上游 MemoryRepository.ENTITY_PAGE 同值的单实体取数页大小。 */
        const val ENTITY_PAGE = 60
    }
}
