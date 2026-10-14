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
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactProfileDao {

    @Insert
    suspend fun insert(profile: ContactProfileEntity): Long

    @Update
    suspend fun update(profile: ContactProfileEntity)

    @Query("SELECT * FROM contact_profiles WHERE id = :id")
    suspend fun findById(id: Long): ContactProfileEntity?

    @Query("SELECT * FROM contact_profiles WHERE id = :id")
    fun observe(id: Long): Flow<ContactProfileEntity?>

    @Query(
        """
        SELECT * FROM contact_profiles
        WHERE imPackage = :imPackage AND contactKey = :key AND mergedInto IS NULL
            AND deletedAt IS NULL
        """,
    )
    suspend fun findByKey(imPackage: String, key: String): ContactProfileEntity?

    @Query(
        """
        SELECT p.* FROM contact_profiles p
        INNER JOIN contact_aliases a ON a.contactId = p.id
        WHERE p.imPackage = :imPackage AND p.mergedInto IS NULL AND p.deletedAt IS NULL
            AND a.alias = :alias
        """,
    )
    suspend fun findByAlias(imPackage: String, alias: String): List<ContactProfileEntity>

    @Query("SELECT * FROM contact_profiles WHERE imPackage = :imPackage AND mergedInto IS NULL AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun listAlive(imPackage: String): List<ContactProfileEntity>

    @Query("SELECT * FROM contact_profiles WHERE mergedInto IS NULL AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun listAllAlive(): List<ContactProfileEntity>

    @Query("SELECT * FROM contact_profiles WHERE mergedInto IS NULL AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAlive(): Flow<List<ContactProfileEntity>>

    @Query("UPDATE contact_profiles SET deletedAt = :now, updatedAt = :now WHERE id = :id AND mergedInto IS NULL")
    suspend fun setDeleted(id: Long, now: Long)

    @Query("UPDATE contact_profiles SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun setUndeleted(id: Long, now: Long)

    @Query("UPDATE contact_profiles SET mergedInto = :intoId, updatedAt = :now WHERE id = :id")
    suspend fun markMerged(id: Long, intoId: Long, now: Long)

    @Query("UPDATE contact_profiles SET mergedInto = NULL, updatedAt = :now WHERE id = :id")
    suspend fun unmarkMerged(id: Long, now: Long)
}

@Dao
interface ContactAliasDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(alias: ContactAliasEntity): Long

    @Query("SELECT * FROM contact_aliases WHERE contactId = :contactId AND alias = :alias")
    suspend fun find(contactId: Long, alias: String): ContactAliasEntity?

    @Query("SELECT * FROM contact_aliases WHERE contactId = :contactId ORDER BY hitCount DESC, lastSeenAt DESC")
    suspend fun listFor(contactId: Long): List<ContactAliasEntity>

    @Query("SELECT * FROM contact_aliases WHERE alias = :alias")
    suspend fun listByAlias(alias: String): List<ContactAliasEntity>

    @Query("UPDATE contact_aliases SET rawAlias = :rawAlias, source = :source, lastSeenAt = :now, hitCount = hitCount + 1 WHERE id = :id")
    suspend fun touch(id: Long, rawAlias: String, source: String, now: Long)

    /** 合并：把 [from] 的别名整体改指 [to]；与目标重名的行会因唯一索引失败，由事务先清重名。 */
    @Query("UPDATE contact_aliases SET contactId = :to WHERE contactId = :from")
    suspend fun reassign(from: Long, to: Long)

    @Query("DELETE FROM contact_aliases WHERE contactId = :contactId AND alias IN (SELECT alias FROM contact_aliases WHERE contactId = :other)")
    suspend fun deleteDuplicatesWith(contactId: Long, other: Long)

    @Query("DELETE FROM contact_aliases WHERE contactId = :contactId AND alias IN (:aliases)")
    suspend fun deleteByAliases(contactId: Long, aliases: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(aliases: List<ContactAliasEntity>)
}

@Dao
interface PersonaFieldDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(field: PersonaFieldEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(fields: List<PersonaFieldEntity>)

    @Query("SELECT * FROM persona_fields WHERE contactId = :contactId ORDER BY field")
    suspend fun listFor(contactId: Long): List<PersonaFieldEntity>

    @Query("SELECT * FROM persona_fields WHERE contactId = :contactId ORDER BY field")
    fun observe(contactId: Long): Flow<List<PersonaFieldEntity>>

    @Query("SELECT * FROM persona_fields WHERE contactId = :contactId AND confirmed = 0 ORDER BY field")
    fun observeDrafts(contactId: Long): Flow<List<PersonaFieldEntity>>

    @Query("UPDATE persona_fields SET confirmed = 1, source = 'user', evidence = NULL, updatedAt = :now WHERE contactId = :contactId AND field = :field")
    suspend fun confirm(contactId: Long, field: String, now: Long)

    @Query("DELETE FROM persona_fields WHERE contactId = :contactId AND field = :field AND confirmed = 0")
    suspend fun deleteDraft(contactId: Long, field: String)

    @Query("DELETE FROM persona_fields WHERE contactId = :contactId AND field IN (:fields)")
    suspend fun deleteByFields(contactId: Long, fields: List<String>)

    @Query("SELECT field FROM persona_fields WHERE contactId = :contactId")
    suspend fun fieldKeys(contactId: Long): List<String>

    /** 合并时给被舍弃的冲突旧值留痕（方案 §4.3 字段择优冲突）。 */
    @Query("UPDATE persona_fields SET evidence = :note, updatedAt = :now WHERE contactId = :contactId AND field = :field")
    suspend fun annotateConflict(contactId: Long, field: String, note: String, now: Long)
}

@Dao
interface ExtractionHoldDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(hold: ExtractionHoldEntity)

    @Query("SELECT untilAt FROM extraction_holds WHERE contactId = :contactId AND field = :field")
    suspend fun until(contactId: Long, field: String): Long?

    @Query("DELETE FROM extraction_holds WHERE untilAt < :now")
    suspend fun purgeExpired(now: Long)
}

@Dao
interface SessionBindingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: SessionBindingEntity)

    @Query("SELECT * FROM session_bindings WHERE conversationId = :conversationId")
    suspend fun find(conversationId: Long): SessionBindingEntity?

    @Query("SELECT * FROM session_bindings WHERE contactId = :contactId ORDER BY createdAt DESC")
    suspend fun listForContact(contactId: Long): List<SessionBindingEntity>

    @Query("SELECT conversationId FROM session_bindings WHERE contactId = :contactId")
    suspend fun conversationIdsFor(contactId: Long): List<Long>

    @Query("UPDATE session_bindings SET contactId = :to WHERE contactId = :from")
    suspend fun reassign(from: Long, to: Long)

    @Query("UPDATE session_bindings SET contactId = :to WHERE conversationId = :conversationId")
    suspend fun rebind(conversationId: Long, to: Long)

    @Query(
        """
        UPDATE session_bindings SET lastOcrFingerprint = :fingerprint,
            lastSentMessageId = :messageId, baselineAnchor = :anchor
        WHERE conversationId = :conversationId
        """,
    )
    suspend fun updateCursors(
        conversationId: Long,
        fingerprint: String?,
        messageId: Long?,
        anchor: String?,
    )

    @Query("UPDATE session_bindings SET lastExtractAt = :at WHERE conversationId = :conversationId")
    suspend fun setLastExtractAt(conversationId: Long, at: Long)

    @Query(
        """
        UPDATE session_bindings SET digest = :digest, digestCoveredTurns = :coveredTurns,
            digestUpdatedAt = :now
        WHERE conversationId = :conversationId
        """,
    )
    suspend fun updateDigest(conversationId: Long, digest: String, coveredTurns: Int, now: Long)

    @Query("DELETE FROM session_bindings WHERE contactId = :contactId")
    suspend fun deleteForContact(contactId: Long)
}

/** 群成员二级画像（P5）。 */
@Dao
interface GroupMemberDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(member: GroupMemberEntity): Long

    @Query("SELECT * FROM group_members WHERE groupContactId = :groupContactId AND memberKey = :memberKey")
    suspend fun find(groupContactId: Long, memberKey: String): GroupMemberEntity?

    @Query("SELECT * FROM group_members WHERE groupContactId = :groupContactId ORDER BY seenCount DESC, lastSeenAt DESC")
    suspend fun listForGroup(groupContactId: Long): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupContactId = :groupContactId ORDER BY seenCount DESC, lastSeenAt DESC")
    fun observeForGroup(groupContactId: Long): Flow<List<GroupMemberEntity>>

    @Query("UPDATE group_members SET lastSeenAt = :now, seenCount = seenCount + 1 WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE group_members SET linkedContactId = :linkedContactId WHERE id = :id")
    suspend fun setLink(id: Long, linkedContactId: Long?)

    @Query("UPDATE group_members SET displayName = :displayName WHERE id = :id")
    suspend fun rename(id: Long, displayName: String)

    @Query("DELETE FROM group_members WHERE id = :id AND groupContactId = :groupContactId")
    suspend fun delete(id: Long, groupContactId: Long)
}

@Dao
interface PlaybookDao {

    @Insert
    suspend fun insert(playbook: PlaybookEntity): Long

    @Insert
    suspend fun insertFts(row: PlaybookFtsEntity)

    @Query("SELECT COUNT(*) FROM playbooks WHERE source = 'builtin'")
    suspend fun builtinCount(): Int

    @Query("DELETE FROM playbooks WHERE source = 'builtin'")
    suspend fun clearBuiltin()

    @Query("DELETE FROM playbooks_fts WHERE rowid IN (SELECT id FROM playbooks WHERE source = 'builtin')")
    suspend fun clearBuiltinFts()

    @Query("SELECT * FROM playbooks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<PlaybookEntity>

    // 不用 rank 排序：Room 校验器不识别 FTS 虚拟列，排序统一在检索器侧做。
    @Query("SELECT rowid FROM playbooks_fts WHERE playbooks_fts MATCH :match LIMIT :limit")
    suspend fun ftsRowIds(match: String, limit: Int): List<Long>

    @Query("SELECT * FROM playbooks WHERE category = :category ORDER BY priority DESC, id")
    suspend fun listByCategory(category: String): List<PlaybookEntity>

    @Query("SELECT * FROM playbooks ORDER BY priority DESC, id")
    suspend fun listAll(): List<PlaybookEntity>

    // ---- 用户自定义话术的增删改（P5 管理 UI 用，DAO 先行） ----

    @Query("DELETE FROM playbooks WHERE id = :id AND source = 'user'")
    suspend fun deleteUser(id: Long)

    @Query("DELETE FROM playbooks_fts WHERE rowid = :id")
    suspend fun deleteFts(id: Long)
}

@Dao
interface MergeRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: MergeRecordEntity)

    @Query("SELECT * FROM merge_records WHERE fromContactId = :from")
    suspend fun find(from: Long): MergeRecordEntity?

    @Query("SELECT * FROM merge_records ORDER BY mergedAt")
    suspend fun listAll(): List<MergeRecordEntity>

    @Query("DELETE FROM merge_records WHERE fromContactId = :from")
    suspend fun delete(from: Long)

    @Query("DELETE FROM merge_records WHERE mergedAt < :threshold")
    suspend fun purgeOlderThan(threshold: Long)
}
