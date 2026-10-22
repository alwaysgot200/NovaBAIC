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

package com.verlintas.baic2.floatball.advisor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 情绪词条：可运营数据资产（查/增/改权/停用/删除走这里，绝不整表清刷）。 */
@Dao
interface EmotionLexiconDao {
    /** IGNORE 语义保用户行：同身份冲突即跳过，覆盖只能走 [update] 显式改。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<EmotionLexiconEntity>)

    @Query("SELECT * FROM emotion_lexicon")
    suspend fun all(): List<EmotionLexiconEntity>

    @Query("SELECT * FROM emotion_lexicon")
    fun observeAll(): Flow<List<EmotionLexiconEntity>>

    /** 身份查询（term+library+scope，IS 语法兼容全局 scope NULL）。 */
    @Query(
        "SELECT * FROM emotion_lexicon WHERE term = :term AND library = :library " +
            "AND scopeContactId IS :scopeContactId",
    )
    suspend fun findIdentity(term: String, library: Int, scopeContactId: Long?): List<EmotionLexiconEntity>

    @Update
    suspend fun update(row: EmotionLexiconEntity)

    @Query("DELETE FROM emotion_lexicon WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM emotion_lexicon")
    suspend fun count(): Int
}

/** 情绪判定事件：写入（E-d 台账）+ 联系人时间线（E-h 画像页）+ 保留期清理（D4）。 */
@Dao
interface EmotionEventDao {
    @Insert
    suspend fun insert(row: EmotionEventEntity): Long

    @Query(
        "SELECT * FROM emotion_events WHERE contactId = :contactId " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun recentFor(contactId: Long, limit: Int): List<EmotionEventEntity>

    /** 保留期清理（默认 90 天，AdvisorPrefs 可调）。 */
    @Query("DELETE FROM emotion_events WHERE createdAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    /** 某时刻起的事件（时间升序）：E-g 情绪轨迹曲线的取数口径。 */
    @Query(
        "SELECT * FROM emotion_events WHERE contactId = :contactId AND createdAt >= :sinceMs " +
            "ORDER BY createdAt ASC",
    )
    suspend fun sinceFor(contactId: Long, sinceMs: Long): List<EmotionEventEntity>

    @Query("SELECT COUNT(*) FROM emotion_events WHERE contactId = :contactId")
    suspend fun countFor(contactId: Long): Int
}

/** 词库挖掘待审（E-g）：模型只提案，采纳/拒绝由主人驱动，拒绝行保留供去重。 */
@Dao
interface EmotionMiningDao {
    /** IGNORE 语义保已提候选：同 term 冲突即跳过。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<EmotionMiningCandidateEntity>)

    @Query("SELECT * FROM emotion_mining_candidates WHERE status = 'pending' ORDER BY createdAt DESC")
    suspend fun pending(): List<EmotionMiningCandidateEntity>

    @Query("SELECT * FROM emotion_mining_candidates WHERE term = :term LIMIT 1")
    suspend fun find(term: String): EmotionMiningCandidateEntity?

    @Query("SELECT * FROM emotion_mining_candidates WHERE id = :id")
    suspend fun get(id: Long): EmotionMiningCandidateEntity?

    @Update
    suspend fun update(row: EmotionMiningCandidateEntity)
}

/** 联系人滚动基线：一联系人一行，滚动重算后 upsert。 */
@Dao
interface EmotionBaselineDao {
    @Upsert
    suspend fun save(row: EmotionBaselineEntity)

    @Query("SELECT * FROM emotion_baseline WHERE contactId = :contactId")
    suspend fun get(contactId: Long): EmotionBaselineEntity?

    @Query("SELECT * FROM emotion_baseline")
    suspend fun all(): List<EmotionBaselineEntity>

    @Query("DELETE FROM emotion_baseline WHERE contactId = :contactId")
    suspend fun delete(contactId: Long)
}
