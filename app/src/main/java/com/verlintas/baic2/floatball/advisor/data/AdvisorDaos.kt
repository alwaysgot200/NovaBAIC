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

    @Query("UPDATE contact_profiles SET scenarioCode = :code, updatedAt = :now WHERE id = :id")
    suspend fun setScenario(id: Long, code: String?, now: Long)

    @Query("UPDATE contact_profiles SET activeCardId = :cardId, updatedAt = :now WHERE id = :id")
    suspend fun setActiveCard(id: Long, cardId: Long?, now: Long)

    /** 维持度三档设置（v10，方案 §2.6）：画像页三态开关的第一手自动参与开关。 */
    @Query("UPDATE contact_profiles SET engagement = :engagement, updatedAt = :now WHERE id = :id")
    suspend fun setEngagement(id: Long, engagement: String, now: Long)

    /** isGroup 手动切换（v10，方案 §1.4）：误判修正（把群标成人/把人标成群）。 */
    @Query("UPDATE contact_profiles SET isGroup = :isGroup, updatedAt = :now WHERE id = :id")
    suspend fun setGroupFlag(id: Long, isGroup: Boolean, now: Long)
}

/** 说话风格卡（v4）：全局卡 (my_default,0) 与按人卡 (contact,contactId) 各至多一行。 */
@Dao
interface StyleCardDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: StyleCardEntity): Long

    @Query("SELECT * FROM style_cards WHERE scope = :scope AND contactId = :contactId")
    suspend fun find(scope: String, contactId: Long): StyleCardEntity?

    @Query("SELECT * FROM style_cards WHERE scope = :scope AND contactId = :contactId")
    fun observe(scope: String, contactId: Long): Flow<StyleCardEntity?>

    /** P3 §八：customNote 编译结果写回为待确认草稿（confirmed=0）。 */
    @Query(
        "UPDATE style_cards SET compiledCustomJson = :json, confirmed = 0, updatedAt = :now " +
            "WHERE id = :id",
    )
    suspend fun setCompiledDraft(id: Long, json: String, now: Long)

    /** P3 §八：用户确认编译结果，整卡生效。 */
    @Query("UPDATE style_cards SET confirmed = 1, updatedAt = :now WHERE id = :id")
    suspend fun confirmCard(id: Long, now: Long)

    /** P3 §八：放弃 customNote（同时清旧编译），纯选项组合即时生效。 */
    @Query(
        "UPDATE style_cards SET customNote = NULL, compiledCustomJson = NULL, confirmed = 1, " +
            "updatedAt = :now WHERE id = :id",
    )
    suspend fun clearCustomNote(id: Long, now: Long)

    @Query("SELECT * FROM style_cards WHERE id = :id")
    suspend fun findById(id: Long): StyleCardEntity?

    @Query("DELETE FROM style_cards WHERE scope = 'contact' AND contactId = :contactId")
    suspend fun deleteContactCard(contactId: Long)
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

    @Query("SELECT * FROM persona_fields")
    suspend fun listAll(): List<PersonaFieldEntity>

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

    /** v6：仅来信文本指纹确实变化时调用（调用方负责指纹判重，防 OCR 抖动反复刷）。 */
    @Query("UPDATE session_bindings SET lastIncomingAt = :at WHERE conversationId = :conversationId")
    suspend fun setLastIncomingAt(conversationId: Long, at: Long)

    /** v10 场景归属（方案 §1.2）：非聊天屏孤立会话标记 non_chat；联系人绑定行恒 chat。 */
    @Query("UPDATE session_bindings SET scene = :scene WHERE conversationId = :conversationId")
    suspend fun setScene(conversationId: Long, scene: String)

    /** 联系人全部绑定会话的最近来信时间（v6 lastIncomingAt 聚合读，§7.3 incoming 优先消费；null=从未记录）。 */
    @Query("SELECT MAX(lastIncomingAt) FROM session_bindings WHERE contactId = :contactId")
    suspend fun lastIncomingAtFor(contactId: Long): Long?

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

// ─────────────────────────────── P2：三级目标 + 策略卡 ───────────────────────────────

/** 三级目标 DAO（v5）：状态机与互斥上限由 Repository 组合 DAO 原语实现。 */
@Dao
interface GoalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: GoalEntity): Long

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun find(id: Long): GoalEntity?

    @Query("SELECT * FROM goals WHERE id = :id")
    fun observe(id: Long): Flow<GoalEntity?>

    @Query("SELECT * FROM goals WHERE contactId = :contactId ORDER BY horizon DESC, priority DESC, updatedAt DESC")
    fun observeByContact(contactId: Long): Flow<List<GoalEntity>>

    @Query(
        """
        SELECT * FROM goals
        WHERE contactId = :contactId AND horizon = :horizon
            AND status IN ('$GOAL_PROVISIONAL', '$GOAL_ACTIVE')
        ORDER BY priority DESC, updatedAt DESC
        """,
    )
    suspend fun listActive(contactId: Long, horizon: Int): List<GoalEntity>

    @Query(
        """
        SELECT * FROM goals
        WHERE contactId = :contactId AND horizon = :horizon
            AND status IN ('$GOAL_PROVISIONAL', '$GOAL_ACTIVE')
        ORDER BY priority DESC, updatedAt DESC
        """,
    )
    fun observeActive(contactId: Long, horizon: Int): Flow<List<GoalEntity>>

    @Query("SELECT COUNT(*) FROM goals WHERE contactId = :contactId AND horizon = :horizon AND status IN ('$GOAL_PROVISIONAL', '$GOAL_ACTIVE')")
    suspend fun countActive(contactId: Long, horizon: Int): Int

    /** 采纳 provisional 行是状态转换而非新增：计数需排除被采纳的那一行本身。 */
    @Query("SELECT COUNT(*) FROM goals WHERE contactId = :contactId AND horizon = :horizon AND id != :exceptId AND status IN ('$GOAL_PROVISIONAL', '$GOAL_ACTIVE')")
    suspend fun countActiveExcept(contactId: Long, horizon: Int, exceptId: Long): Int

    @Query("SELECT * FROM goals WHERE contactId = :contactId AND horizon = $HORIZON_L2 AND status = '$GOAL_PROVISIONAL' AND promptCount < 2 ORDER BY priority DESC, updatedAt DESC")
    suspend fun listPromptableProvisionalL2(contactId: Long): List<GoalEntity>

    /** L1 观察期建议 7 天未确认 → 惰性归档（Repository 在 T1 取数时调用）。 */
    @Query("UPDATE goals SET status = '$GOAL_ABANDONED', updatedAt = :now WHERE contactId = :contactId AND horizon = $HORIZON_L1 AND status = '$GOAL_PROVISIONAL' AND createdAt < :threshold")
    suspend fun archiveStaleProvisionalL1(contactId: Long, threshold: Long, now: Long): Int

    @Query("UPDATE goals SET status = :status, reviewedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, now: Long)

    @Query("UPDATE goals SET promptCount = promptCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementPromptCount(id: Long, now: Long)

    @Query("UPDATE goals SET confirmed = :confirmed, status = :status, promptCount = 0, reviewedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun setConfirmed(id: Long, confirmed: Boolean, status: String, now: Long)

    @Query("UPDATE goals SET progressNote = :note, failStreak = :failStreak, updatedAt = :now WHERE id = :id")
    suspend fun setProgress(id: Long, note: String?, failStreak: Int, now: Long)

    @Query("UPDATE goals SET failStreak = :failStreak, status = :status, reviewedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun setFailStreak(id: Long, failStreak: Int, status: String, now: Long)
}

/** 目标编译产物 DAO（v5）：同 kind 取 version 最大且未被取代的一行。 */
@Dao
interface GoalDecompDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(decomp: GoalDecompEntity): Long

    @Query("SELECT * FROM goal_decomps WHERE id = :id")
    suspend fun find(id: Long): GoalDecompEntity?

    @Query(
        """
        SELECT * FROM goal_decomps
        WHERE goalId = :goalId AND kind = :kind AND supersededBy IS NULL
        ORDER BY version DESC LIMIT 1
        """,
    )
    suspend fun latest(goalId: Long, kind: String): GoalDecompEntity?

    @Query(
        """
        SELECT * FROM goal_decomps
        WHERE goalId = :goalId AND kind = :kind AND supersededBy IS NULL
        ORDER BY version DESC LIMIT 1
        """,
    )
    fun observeLatest(goalId: Long, kind: String): Flow<GoalDecompEntity?>

    @Query("UPDATE goal_decomps SET supersededBy = :byId WHERE id = :oldId")
    suspend fun supersede(oldId: Long, byId: Long)

    /** 按（goal, kind, version）精确定位（P3：stage_states.roadmapVersion 存的是版本号而非行 id）。 */
    @Query(
        """
        SELECT * FROM goal_decomps
        WHERE goalId = :goalId AND kind = :kind AND version = :version LIMIT 1
        """,
    )
    suspend fun byVersion(goalId: Long, kind: String, version: Int): GoalDecompEntity?

    /** P3 reroute 采用：新行置确认态，其余未取代旧行全部指向新行。 */
    @Query("UPDATE goal_decomps SET confirmed = :confirmed WHERE id = :id")
    suspend fun setConfirmed(id: Long, confirmed: Boolean)

    @Query(
        """
        UPDATE goal_decomps SET supersededBy = :byId
        WHERE goalId = :goalId AND kind = :kind AND id != :byId AND supersededBy IS NULL
        """,
    )
    suspend fun supersedeOthers(goalId: Long, kind: String, byId: Long)

    @Query("SELECT MAX(version) FROM goal_decomps WHERE goalId = :goalId AND kind = :kind")
    suspend fun maxVersion(goalId: Long, kind: String): Int?
}

/** 联系人阶段状态 DAO（v5，复合主键）。 */
@Dao
interface StageStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: StageStateEntity)

    @Query("SELECT * FROM stage_states WHERE contactId = :contactId AND scenarioCode = :scenarioCode")
    suspend fun get(contactId: Long, scenarioCode: String): StageStateEntity?

    @Query("SELECT * FROM stage_states WHERE contactId = :contactId AND scenarioCode = :scenarioCode")
    fun observe(contactId: Long, scenarioCode: String): Flow<StageStateEntity?>

    @Query("UPDATE stage_states SET locked = :locked WHERE contactId = :contactId AND scenarioCode = :scenarioCode")
    suspend fun setLocked(contactId: Long, scenarioCode: String, locked: Boolean)
}

/** 阶段变迁时间线 DAO（v6，P3）：追加写 + 最近 N 条读取 + 滚动裁剪。 */
@Dao
interface StageHistoryDao {

    @Insert
    suspend fun insert(entry: StageHistoryEntity): Long

    @Query("SELECT * FROM stage_history WHERE contactId = :contactId AND scenarioCode = :scenarioCode ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(contactId: Long, scenarioCode: String, limit: Int = 50): List<StageHistoryEntity>

    @Query("SELECT * FROM stage_history WHERE contactId = :contactId AND scenarioCode = :scenarioCode ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeRecent(contactId: Long, scenarioCode: String, limit: Int = 50): Flow<List<StageHistoryEntity>>

    /** 同站连续 edge 命中计数（reroute 触发条件之一：连续 retreat×2）。 */
    @Query(
        """
        SELECT COUNT(*) FROM stage_history
        WHERE contactId = :contactId AND scenarioCode = :scenarioCode
            AND toCode = :stageCode AND edge = :edge
            AND id > COALESCE((SELECT id FROM stage_history
                WHERE contactId = :contactId AND scenarioCode = :scenarioCode
                    AND edge != :edge ORDER BY id DESC LIMIT 1), 0)
        """,
    )
    suspend fun consecutiveAtStage(contactId: Long, scenarioCode: String, stageCode: String, edge: String): Int

    /** 每联系人×场景只保留最近 [keep] 条。 */
    @Query(
        """
        DELETE FROM stage_history
        WHERE contactId = :contactId AND scenarioCode = :scenarioCode
            AND id NOT IN (SELECT id FROM stage_history
                WHERE contactId = :contactId AND scenarioCode = :scenarioCode
                ORDER BY at DESC, id DESC LIMIT :keep)
        """,
    )
    suspend fun trim(contactId: Long, scenarioCode: String, keep: Int = 50)
}

/** 策略卡 DAO（v5）：最新草稿/已确认卡查询，确认时归档同联系人其余卡。 */
@Dao
interface StrategyCardDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: StrategyCardEntity): Long

    @Query("SELECT * FROM strategy_cards WHERE id = :id")
    suspend fun find(id: Long): StrategyCardEntity?

    @Query("SELECT * FROM strategy_cards WHERE contactId = :contactId AND status = :status ORDER BY version DESC LIMIT 1")
    suspend fun latest(contactId: Long, status: String): StrategyCardEntity?

    @Query("SELECT * FROM strategy_cards WHERE contactId = :contactId ORDER BY version DESC LIMIT 1")
    suspend fun latestAny(contactId: Long): StrategyCardEntity?

    @Query("SELECT * FROM strategy_cards WHERE contactId = :contactId ORDER BY version DESC LIMIT 1")
    fun observeLatest(contactId: Long): Flow<StrategyCardEntity?>

    @Query("UPDATE strategy_cards SET status = :status WHERE contactId = :contactId AND id != :keepId AND status != '$CARD_ARCHIVED'")
    suspend fun archiveOthers(contactId: Long, keepId: Long, status: String = CARD_ARCHIVED)
}

/** 本轮任务令台账 DAO（v5）：插入/回填/最近列表 + 每联系人每会话各留 60 条。 */
@Dao
interface TalkMissionDao {

    @Insert
    suspend fun insert(mission: TalkMissionEntity): Long

    @Query("SELECT * FROM talk_missions WHERE contactId = :contactId AND result = '$MISSION_PENDING' ORDER BY id DESC LIMIT 1")
    suspend fun latestPendingForContact(contactId: Long): TalkMissionEntity?

    @Query("SELECT * FROM talk_missions WHERE conversationId = :conversationId AND result = '$MISSION_PENDING' ORDER BY id DESC LIMIT 1")
    suspend fun latestPendingForConversation(conversationId: Long): TalkMissionEntity?

    @Query("UPDATE talk_missions SET result = :result WHERE id = :id")
    suspend fun settle(id: Long, result: String)

    /** P3 §九：行内「✓采纳」：回写用户采用的建议编号（1 起）。 */
    @Query("UPDATE talk_missions SET adoptedIndex = :index WHERE id = :id")
    suspend fun adopt(id: Long, index: Int)

    @Query("SELECT * FROM talk_missions WHERE contactId = :contactId ORDER BY id DESC LIMIT :limit")
    fun observeRecentForContact(contactId: Long, limit: Int): Flow<List<TalkMissionEntity>>

    /** Judge 节流计数用：倒序最近若干条任务令（含已裁决）。 */
    @Query("SELECT * FROM talk_missions WHERE contactId = :contactId ORDER BY id DESC LIMIT :limit")
    suspend fun recentForContact(contactId: Long, limit: Int): List<TalkMissionEntity>

    @Query("SELECT * FROM talk_missions WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit")
    fun observeRecentForConversation(conversationId: Long, limit: Int): Flow<List<TalkMissionEntity>>

    @Query("DELETE FROM talk_missions WHERE contactId = :contactId AND id NOT IN (SELECT id FROM talk_missions WHERE contactId = :contactId ORDER BY id DESC LIMIT :keep)")
    suspend fun trimContact(contactId: Long, keep: Int)

    @Query("DELETE FROM talk_missions WHERE contactId IS NULL AND conversationId = :conversationId AND id NOT IN (SELECT id FROM talk_missions WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :keep)")
    suspend fun trimConversation(conversationId: Long, keep: Int)
}

// ─────────────────────────────── M0：Director 调度中枢 + 简报 + 行动建议 ───────────────────────────────

/** 调度日志 DAO（v7）：追加写 + 最近列表 + 每联系人滚动 200 条。 */
@Dao
interface DirectorLogDao {

    @Insert
    suspend fun insert(entry: DirectorLogEntity): Long

    @Query("SELECT * FROM director_logs WHERE contactId = :contactId ORDER BY id DESC LIMIT :limit")
    suspend fun latestFor(contactId: Long, limit: Int = 50): List<DirectorLogEntity>

    @Query(
        "DELETE FROM director_logs WHERE contactId = :contactId AND id NOT IN " +
            "(SELECT id FROM director_logs WHERE contactId = :contactId ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun trimFor(contactId: Long, keep: Int = 200)

    // ---- M2-b 频控与对账（追加式）：频控数据一律从台账查出，不加新表 ----

    /** 当日主动计数：trigger+route 组合在 [sinceMs]（当日零点）之后的行数。 */
    @Query(
        "SELECT COUNT(*) FROM director_logs WHERE contactId = :contactId " +
            "AND trigger = :trigger AND route = :route AND createdAt >= :sinceMs",
    )
    suspend fun countSince(contactId: Long, trigger: String, route: String, sinceMs: Long): Int

    /** 上次主动时间：trigger+route 组合的最新一条时间戳；从未发生为 null。 */
    @Query(
        "SELECT MAX(createdAt) FROM director_logs WHERE contactId = :contactId " +
            "AND trigger = :trigger AND route = :route",
    )
    suspend fun latestAt(contactId: Long, trigger: String, route: String): Long?

    /** F7 消费（方案 §5.6：UI 只在用户打开 GoalTab 时消费）：[sinceMs] 新鲜度窗口内
     *  最近一条 actionHint 决策行；过期信号不再提示（驻留解除后旧横幅不复活）。 */
    @Query(
        "SELECT * FROM director_logs WHERE contactId = :contactId AND actionHint = 1 " +
            "AND createdAt >= :sinceMs ORDER BY id DESC LIMIT 1",
    )
    suspend fun latestActionHint(contactId: Long, sinceMs: Long): DirectorLogEntity?

    /** 对账消化（方案 §13.1）：近窗口全量台账，按触发源×路由分布统计。 */
    @Query("SELECT * FROM director_logs WHERE createdAt >= :sinceMs ORDER BY id DESC LIMIT :limit")
    suspend fun recentSince(sinceMs: Long, limit: Int = 500): List<DirectorLogEntity>

    /**
     * 回填回合锚点（§6 三层回放链）：把最新一条 trigger+route 组合行的
     * turnMessageId 补上——检点路径先落决策行、回合消息后产生，由此闭合
     * 「决策→回合→结局」链。无匹配行时 UPDATE 静默无操作（旁路安全）。
     */
    @Query(
        "UPDATE director_logs SET turnMessageId = :turnMessageId WHERE id = " +
            "(SELECT id FROM director_logs WHERE contactId = :contactId " +
            "AND trigger = :trigger AND route = :route ORDER BY id DESC LIMIT 1)",
    )
    suspend fun attachTurnMessageToLatest(contactId: Long, trigger: String, route: String, turnMessageId: Long)
}

/** 行动建议 DAO（v7，M1 ActionPlanner 读写）：插入 / 状态流转 / 草稿与最近列表。 */
@Dao
interface ActionPlayDao {

    @Insert
    suspend fun insert(play: ActionPlayEntity): Long

    @Query("UPDATE action_plays SET status = :status, resultNote = :resultNote WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, resultNote: String? = null)

    @Query("SELECT * FROM action_plays WHERE contactId = :contactId AND status = '$ACTION_STATUS_DRAFT' ORDER BY id DESC")
    suspend fun openDrafts(contactId: Long): List<ActionPlayEntity>

    @Query("SELECT * FROM action_plays WHERE contactId = :contactId ORDER BY id DESC LIMIT :limit")
    suspend fun recentFor(contactId: Long, limit: Int = 20): List<ActionPlayEntity>
}

/** 简报产物 DAO（v7，BriefCompiler 读写）：最新一份 + 每联系人滚动 10 份。 */
@Dao
interface BriefOutputDao {

    @Insert
    suspend fun insert(output: BriefOutputEntity): Long

    @Query("SELECT * FROM brief_outputs WHERE contactId = :contactId AND kind = :kind ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latest(contactId: Long, kind: String): BriefOutputEntity?

    @Query(
        "DELETE FROM brief_outputs WHERE contactId = :contactId AND id NOT IN " +
            "(SELECT id FROM brief_outputs WHERE contactId = :contactId ORDER BY createdAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimFor(contactId: Long, keep: Int = 10)
}

/** 主动策略 DAO（v7，M2 ProactivePolicy 读写）：每联系人一行，整行覆盖。 */
@Dao
interface ProactivePolicyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: ProactivePolicyEntity)

    @Query("SELECT * FROM proactive_policies WHERE contactId = :contactId")
    suspend fun forContact(contactId: Long): ProactivePolicyEntity?

    // ---- M2-b（追加式）：检点枚举 + 设置页观察 ----

    /** 全部已启用的主动策略（周期检点逐个过频控规则）。 */
    @Query("SELECT * FROM proactive_policies WHERE enabled = 1")
    suspend fun listEnabled(): List<ProactivePolicyEntity>

    /** 全部策略行（V-C §4.5：显式行优先，无行按风格分档给缺省，需读全量判重）。 */
    @Query("SELECT * FROM proactive_policies")
    suspend fun listAll(): List<ProactivePolicyEntity>

    @Query("SELECT * FROM proactive_policies WHERE contactId = :contactId")
    fun observeFor(contactId: Long): Flow<ProactivePolicyEntity?>
}

// ─────────────────────── F6：我的素材库（personal_materials，v9） ───────────────────────

/**
 * 我的素材库 DAO（v9，M1 方案 §7.2「用户投喂」源）：upsert 按指纹唯一键去重
 * （重复录入以最新为准）+ 全量观察/查询 + 选题后标记 lastUsedAt。
 */
@Dao
interface PersonalMaterialDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(material: PersonalMaterialEntity): Long

    @Query("DELETE FROM personal_materials WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM personal_materials ORDER BY id DESC")
    suspend fun all(): List<PersonalMaterialEntity>

    @Query("SELECT * FROM personal_materials ORDER BY id DESC")
    fun observeAll(): Flow<List<PersonalMaterialEntity>>

    /** 素材被保温选题采用后标记（防短期重选）。 */
    @Query("UPDATE personal_materials SET lastUsedAt = :atMs WHERE fingerprint = :fingerprint")
    suspend fun markUsedByFingerprint(fingerprint: String, atMs: Long)
}

// ─────────────────────── v10：双方人设 + 场景归属（UltraAdvisor v2.2 方案 §2.4/§4.4） ───────────────────────

/** 全局我方人设标签库 DAO（v10）：全量查 / upsert（tag 唯一去重）/ 删除。 */
@Dao
interface SelfPersonaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(persona: SelfPersonaEntity)

    @Query("SELECT * FROM self_personas ORDER BY id")
    suspend fun listAll(): List<SelfPersonaEntity>

    @Query("SELECT COUNT(*) FROM self_personas")
    suspend fun count(): Int

    @Query("DELETE FROM self_personas WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/** 周期废话方向计划 DAO（v10，V-C Planner 消费）：每联系人×每周一条，REPLACE upsert。 */
@Dao
interface TopicPlanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: TopicPlanEntity)

    @Query("SELECT * FROM topic_plans WHERE contactId = :contactId ORDER BY cycleStart DESC, id DESC")
    suspend fun forContact(contactId: Long): List<TopicPlanEntity>

    @Query("SELECT * FROM topic_plans WHERE contactId = :contactId ORDER BY cycleStart DESC, id DESC LIMIT 1")
    suspend fun latest(contactId: Long): TopicPlanEntity?
}
