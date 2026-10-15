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

import androidx.room.withTransaction
import com.verlintas.baic2.floatball.advisor.data.ContactAliasEntity
import com.verlintas.baic2.floatball.advisor.data.ContactProfileEntity
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.ExtractionHoldEntity
import com.verlintas.baic2.floatball.advisor.data.GroupMemberEntity
import com.verlintas.baic2.floatball.advisor.data.MY_PERSONA_CONTACT_ID
import com.verlintas.baic2.floatball.advisor.data.MergeRecordEntity
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import com.verlintas.baic2.floatball.advisor.data.SessionBindingEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 一次标题识别的归属结果（方案 §11.1 决策表）。 */
sealed interface ResolveOutcome {
    /** 唯一命中已有档案。[newStub]=true 表示本次懒创建的轻量档案。 */
    data class Matched(val profile: ContactProfileEntity, val newStub: Boolean) : ResolveOutcome

    /** 同一名称命中多个档案（重名），需 UI 让用户选择。 */
    data class Ambiguous(val candidates: List<ContactProfileEntity>) : ResolveOutcome

    /**
     * 未命中，但与其他档案主名/别名编辑距离 ≤ renameDistance（改名/繁简/emoji）。
     * 决策表要求**不自动合并**：由 UI 弹一次确认条，是则合并、否则新建。
     */
    data class FuzzyNear(val candidates: List<ContactProfileEntity>) : ResolveOutcome

    /** 陌生标题：未命中且未达模糊阈值，调用方可按需懒创建。 */
    data class Unknown(val normalized: String) : ResolveOutcome
}

/**
 * 联系人档案 / 别名 / 画像字段 / 会话绑定 / 合并撤销的统一入口。
 * 全部写操作在 AdvisorDatabase 单事务内完成；不触碰上游 Baic2Database。
 */
@Singleton
class ContactRepository @Inject constructor(
    private val db: AdvisorDatabase,
    private val schemas: PersonaSchemas,
    private val titleRules: TitleRules,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val aliasListSerializer = ListSerializer(AliasSnapshot.serializer())
    private val fieldListSerializer = ListSerializer(FieldSnapshot.serializer())

    // ---- 识别与懒创建（§11.1/§11.2） ----

    /**
     * 按 IM 包名 + 原始标题解析归属（方案 §11.1 决策表）。匹配键依次尝试
     * 原规范化键与 title_rules 的壳后缀剥离键；精确/别名未命中再做编辑距离
     * 模糊近邻（[ResolveOutcome.FuzzyNear]，不自动合并）；全未命中且
     * [createStub]=true 时懒创建 Stub（首次发起参谋）。
     */
    suspend fun resolve(
        imPackage: String,
        rawTitle: String,
        source: String = "a11y",
        createStub: Boolean = false,
    ): ResolveOutcome {
        val normalized = ContactNames.normalize(rawTitle)
        if (normalized.isBlank()) return ResolveOutcome.Unknown(normalized)
        val variants = titleRules.matchVariants(normalized)

        for (norm in variants) {
            // 列存的是「包名:规范化名」复合键（与建档时 ContactNames.contactKey 一致），
            // 必须传复合键，裸 norm 永远不命中、精确路径形同虚设。
            db.contactProfileDao().findByKey(imPackage, ContactNames.contactKey(imPackage, norm))
                ?.let { return match(it, norm, rawTitle, source, newStub = false) }
        }

        // 别名：任何变体唯一命中即归属；全部变体合计多档案才判重名。
        val aliasHits = LinkedHashMap<Long, ContactProfileEntity>()
        for (norm in variants) {
            val byAlias = db.contactProfileDao().findByAlias(imPackage, norm)
            if (byAlias.size == 1 && aliasHits.isEmpty()) {
                return match(byAlias.first(), norm, rawTitle, source, newStub = false)
            }
            byAlias.forEach { aliasHits[it.id] = it }
        }
        if (aliasHits.size > 1) {
            return ResolveOutcome.Ambiguous(aliasHits.values.sortedByDescending { it.updatedAt })
        }

        val fuzzy = fuzzyCandidates(imPackage, normalized, titleRules.get().renameDistance)
        if (fuzzy.isNotEmpty()) return ResolveOutcome.FuzzyNear(fuzzy)

        if (!createStub) return ResolveOutcome.Unknown(normalized)
        val now = System.currentTimeMillis()
        val id = db.contactProfileDao().insert(
            ContactProfileEntity(
                imPackage = imPackage,
                contactKey = ContactNames.contactKey(imPackage, normalized),
                displayName = rawTitle.trim(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        val stub = db.contactProfileDao().findById(id)!!
        db.contactAliasDao().insert(
            ContactAliasEntity(
                contactId = id,
                alias = normalized,
                rawAlias = rawTitle.trim(),
                source = source,
                lastSeenAt = now,
            ),
        )
        return ResolveOutcome.Matched(stub, newStub = true)
    }

    private suspend fun match(
        profile: ContactProfileEntity,
        normalized: String,
        rawTitle: String,
        source: String,
        newStub: Boolean,
    ): ResolveOutcome.Matched {
        recordAliasHit(profile.id, normalized, rawTitle, source)
        return ResolveOutcome.Matched(profile, newStub)
    }

    /** 命中后登记别名热度；标题是新变体则追加 alias（改名续档）。 */
    suspend fun recordAliasHit(contactId: Long, normalized: String, rawTitle: String, source: String) {
        val now = System.currentTimeMillis()
        val existing = db.contactAliasDao().find(contactId, normalized)
        if (existing != null) {
            db.contactAliasDao().touch(existing.id, rawTitle.trim(), source, now)
        } else {
            db.contactAliasDao().insert(
                ContactAliasEntity(
                    contactId = contactId,
                    alias = normalized,
                    rawAlias = rawTitle.trim(),
                    source = source,
                    lastSeenAt = now,
                ),
            )
        }
        db.contactProfileDao().update(
            db.contactProfileDao().findById(contactId)!!.copy(updatedAt = now),
        )
    }

    /** 编辑距离 ≤ [renameDistance] 的候选（绑定面板"这是不是同一人"用）。 */
    suspend fun fuzzyCandidates(imPackage: String, normalized: String, renameDistance: Int = 2): List<ContactProfileEntity> =
        db.contactProfileDao().listAlive(imPackage).filter { profile ->
            val names = listOf(ContactNames.normalize(profile.displayName)) +
                db.contactAliasDao().listFor(profile.id).map { it.alias }
            names.any { ContactNames.editDistance(it, normalized) in 1..renameDistance }
        }

    // ---- 会话绑定 ----

    suspend fun bindConversation(conversationId: Long, contactId: Long) {
        val now = System.currentTimeMillis()
        val existing = db.sessionBindingDao().find(conversationId)
        if (existing == null) {
            db.sessionBindingDao().upsert(SessionBindingEntity(conversationId, contactId, createdAt = now))
        } else if (existing.contactId != contactId) {
            db.sessionBindingDao().upsert(existing.copy(contactId = contactId))
        }
    }

    suspend fun contactForConversation(conversationId: Long): ContactProfileEntity? {
        val binding = db.sessionBindingDao().find(conversationId) ?: return null
        val direct = db.contactProfileDao().findById(binding.contactId) ?: return null
        return if (direct.mergedInto != null) db.contactProfileDao().findById(direct.mergedInto) else direct
    }

    suspend fun binding(conversationId: Long) = db.sessionBindingDao().find(conversationId)

    suspend fun conversationIdsFor(contactId: Long): List<Long> =
        db.sessionBindingDao().conversationIdsFor(contactId)

    suspend fun updateCursors(
        conversationId: Long,
        fingerprint: String?,
        lastSentMessageId: Long?,
        baselineAnchor: String?,
    ) = db.sessionBindingDao().updateCursors(conversationId, fingerprint, lastSentMessageId, baselineAnchor)

    suspend fun setLastExtractAt(conversationId: Long, at: Long) =
        db.sessionBindingDao().setLastExtractAt(conversationId, at)

    suspend fun bindingsForContact(contactId: Long): List<SessionBindingEntity> =
        db.sessionBindingDao().listForContact(contactId)

    /** 滚动摘要写入该联系人名下全部绑定行（同联系人多会话共享一份摘要）。 */
    suspend fun saveDigest(contactId: Long, digest: String, coveredTurns: Int) {
        val now = System.currentTimeMillis()
        db.sessionBindingDao().listForContact(contactId).forEach {
            db.sessionBindingDao().updateDigest(it.conversationId, digest, coveredTurns, now)
        }
    }

    // ---- 档案编辑 ----

    suspend fun updateGoalStyle(contactId: Long, goal: String?, style: String?) {
        val p = db.contactProfileDao().findById(contactId) ?: return
        db.contactProfileDao().update(
            p.copy(goal = goal?.trim()?.takeIf { it.isNotEmpty() }, style = style?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = System.currentTimeMillis()),
        )
    }

    /** P1：联系人详情只改业务目标；风格改走 style_cards，旧 style 列保持原样（回退链只读）。 */
    suspend fun updateGoal(contactId: Long, goal: String?) {
        val p = db.contactProfileDao().findById(contactId) ?: return
        db.contactProfileDao().update(
            p.copy(goal = goal?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = System.currentTimeMillis()),
        )
    }

    suspend fun setContactType(contactId: Long, type: String) {
        val p = db.contactProfileDao().findById(contactId) ?: return
        db.contactProfileDao().update(p.copy(contactType = type, updatedAt = System.currentTimeMillis()))
    }

    /** 改主显示名：新名同时落一条 user 别名（旧名别名保留，旧笔记仍可召回）。 */
    suspend fun renameContact(contactId: Long, displayName: String) {
        val name = displayName.trim()
        if (name.isEmpty()) return
        val p = db.contactProfileDao().findById(contactId) ?: return
        val now = System.currentTimeMillis()
        db.contactProfileDao().update(p.copy(displayName = name, updatedAt = now))
        val normalized = ContactNames.normalize(name)
        if (normalized.isNotBlank() && db.contactAliasDao().find(contactId, normalized) == null) {
            db.contactAliasDao().insert(
                ContactAliasEntity(
                    contactId = contactId,
                    alias = normalized,
                    rawAlias = name,
                    source = "user",
                    lastSeenAt = now,
                ),
            )
        }
    }

    /** 把（通常是当前参谋）会话改绑到指定档案；不存在绑定时新建绑定。 */
    suspend fun rebindConversation(conversationId: Long, contactId: Long) {
        val existing = db.sessionBindingDao().find(conversationId)
        if (existing == null) {
            bindConversation(conversationId, contactId)
        } else if (existing.contactId != contactId) {
            db.sessionBindingDao().rebind(conversationId, contactId)
        }
    }

    /** 撤销窗口内的合并记录（最新在前），供 UI 显示「撤销合并」条。 */
    suspend fun recentMerges(withinMs: Long = UNDO_WINDOW_MS): List<MergeRecordEntity> =
        db.mergeRecordDao().listAll()
            .filter { it.mergedAt > System.currentTimeMillis() - withinMs }
            .sortedByDescending { it.mergedAt }

    /**
     * 用户手动删档（区别于合并）：软删除主档 + 解绑全部会话；别名/字段/摘要
     * 保留在死档上不外泄（活跃查询一律 deletedAt IS NULL）。上游聊天会话与
     * 记忆不在此处理——记忆清除走 [ProfileMemorySync.forgetContact]，由 UI 分开确认。
     * 我方人设虚拟档与已合并档拒绝删除。
     */
    suspend fun deleteContact(contactId: Long): Boolean {
        if (contactId == MY_PERSONA_CONTACT_ID) return false
        val profile = db.contactProfileDao().findById(contactId) ?: return false
        if (profile.mergedInto != null) return false
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.contactProfileDao().setDeleted(contactId, now)
            db.sessionBindingDao().deleteForContact(contactId)
        }
        return true
    }

    // ---- 群成员二级画像（P5） ----

    /** 登记/刷新一次群成员现身；已存在则计数 +1。 */
    suspend fun upsertGroupMember(groupContactId: Long, rawNickname: String) {
        val nickname = rawNickname.trim()
        val key = ContactNames.normalize(nickname)
        if (nickname.isEmpty() || key.isEmpty()) return
        val dao = db.groupMemberDao()
        val now = System.currentTimeMillis()
        val existing = dao.find(groupContactId, key)
        if (existing == null) {
            dao.insert(
                GroupMemberEntity(
                    groupContactId = groupContactId,
                    memberKey = key,
                    displayName = nickname,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            )
        } else {
            dao.touch(existing.id, now)
        }
    }

    suspend fun groupMembers(groupContactId: Long): List<GroupMemberEntity> =
        db.groupMemberDao().listForGroup(groupContactId)

    fun observeGroupMembers(groupContactId: Long) =
        db.groupMemberDao().observeForGroup(groupContactId)

    suspend fun removeGroupMember(groupContactId: Long, memberId: Long) =
        db.groupMemberDao().delete(memberId, groupContactId)

    /** 关联/取消关联已有 person 主档（linkedContactId=null 取消）。 */
    suspend fun linkGroupMember(memberId: Long, linkedContactId: Long?) =
        db.groupMemberDao().setLink(memberId, linkedContactId)

    suspend fun profile(contactId: Long) = db.contactProfileDao().findById(contactId)

    fun observeProfile(contactId: Long) = db.contactProfileDao().observe(contactId)

    fun observeAlive() = db.contactProfileDao().observeAlive()

    suspend fun listAlive(imPackage: String? = null): List<ContactProfileEntity> =
        if (imPackage == null) db.contactProfileDao().listAllAlive()
        else db.contactProfileDao().listAlive(imPackage)

    suspend fun aliases(contactId: Long) = db.contactAliasDao().listFor(contactId)

    // ---- 画像字段（§4.2） ----

    suspend fun fields(contactId: Long): List<PersonaFieldEntity> = db.personaFieldDao().listFor(contactId)

    fun observeFields(contactId: Long) = db.personaFieldDao().observe(contactId)

    fun observeDrafts(contactId: Long) = db.personaFieldDao().observeDrafts(contactId)

    suspend fun myPersona(): List<PersonaFieldEntity> = fields(MY_PERSONA_CONTACT_ID)

    /** 用户手动填写/编辑字段：source=user，立即 confirmed。 */
    suspend fun setUserField(contactId: Long, field: String, value: String) {
        val now = System.currentTimeMillis()
        if (value.isBlank()) {
            db.personaFieldDao().deleteByFields(contactId, listOf(field))
            return
        }
        db.personaFieldDao().upsert(
            PersonaFieldEntity(
                contactId = contactId,
                field = field,
                value = encodeValue(field, value),
                source = "user",
                confirmed = true,
                evidence = null,
                updatedAt = now,
            ),
        )
    }

    /**
     * LLM 萃取草稿写入（§4.2 规则 3）：confirmed/user 值不覆盖；
     * tags 做集合 add/remove；返回真正落库的字段（供上层提示）。
     */
    suspend fun applyDraft(
        contactId: Long,
        field: String,
        value: String,
        evidence: String?,
        action: DraftAction = DraftAction.SET,
    ): PersonaFieldEntity? {
        val now = System.currentTimeMillis()
        if (db.extractionHoldDao().until(contactId, field)?.let { it > now } == true) return null

        val encoded = encodeValue(field, value)
        val current = db.personaFieldDao().listFor(contactId).firstOrNull { it.field == field }
        if (current != null && (current.confirmed || current.source == "user")) return null

        val mergedValue = when {
            typeOf(field) == PersonaFieldType.TAGS && current != null -> mergeTags(current.value, encoded, action)
            action == DraftAction.REMOVE -> null
            else -> encoded
        }
        if (mergedValue.isNullOrBlank()) {
            db.personaFieldDao().deleteDraft(contactId, field)
            return null
        }
        val entity = PersonaFieldEntity(
            contactId = contactId,
            field = field,
            value = mergedValue,
            source = "llm",
            confirmed = false,
            evidence = evidence?.take(120),
            updatedAt = now,
        )
        db.personaFieldDao().upsert(entity)
        return entity
    }

    /** 用户确认草稿：固化并返回上一行（供调用方双写上游记忆）。 */
    suspend fun confirmDraft(contactId: Long, field: String): PersonaFieldEntity? {
        val now = System.currentTimeMillis()
        val before = db.personaFieldDao().listFor(contactId).firstOrNull { it.field == field } ?: return null
        db.personaFieldDao().confirm(contactId, field, now)
        return db.personaFieldDao().listFor(contactId).firstOrNull { it.field == field } ?: before
    }

    /** 用户否决草稿：删除并写 30 天冷却（extraction_holds）。 */
    suspend fun rejectDraft(contactId: Long, field: String) {
        db.personaFieldDao().deleteDraft(contactId, field)
        db.extractionHoldDao().put(
            ExtractionHoldEntity(
                contactId = contactId,
                field = field,
                untilAt = System.currentTimeMillis() + HOLD_COOLDOWN_MS,
            ),
        )
    }

    // ---- 合并 / 撤销（§4.3） ----

    /**
     * 合并联系人：保留 [targetId]，并入 [fromId]。类型不同拒绝；
     * 返回 Unit，失败抛 IllegalStateException（UI 层映射文案）。
     */
    suspend fun mergeContacts(fromId: Long, targetId: Long) {
        require(fromId != targetId) { "cannot_merge_self" }
        db.withTransaction {
            val from = db.contactProfileDao().findById(fromId)
                ?.takeIf { it.mergedInto == null } ?: error("contact_gone")
            val target = db.contactProfileDao().findById(targetId)
                ?.takeIf { it.mergedInto == null } ?: error("contact_gone")
            require(from.contactType == target.contactType) { "type_mismatch" }

            val fromAliases = db.contactAliasDao().listFor(fromId)
            val fromBindings = db.sessionBindingDao().listForContact(fromId)
            val fromFields = db.personaFieldDao().listFor(fromId)
            val targetFields = db.personaFieldDao().listFor(targetId)
            val now = System.currentTimeMillis()

            // 1) 字段逐 key 择优并入 target。
            val targetByKey = targetFields.associateBy { it.field }
            fromFields.forEach { sourceField ->
                val existing = targetByKey[sourceField.field]
                if (existing == null) {
                    db.personaFieldDao().upsert(sourceField.copy(contactId = targetId))
                } else {
                    val winner = chooseWinner(sourceField, existing)
                    val loser = if (winner === sourceField) existing else sourceField
                    if (winner.value != loser.value) {
                        db.personaFieldDao().upsert(winner.copy(contactId = targetId))
                        db.personaFieldDao().annotateConflict(
                            targetId,
                            winner.field,
                            (winner.evidence?.let { "$it；" } ?: "") + "合并冲突旧值：${loser.value.take(80)}",
                            now,
                        )
                    }
                }
            }
            db.personaFieldDao().deleteByFields(fromId, fromFields.map { it.field })

            // 2) 别名去重后整体改指（tags 式并集：一个名字都不丢）。
            db.contactAliasDao().deleteDuplicatesWith(fromId, targetId)
            db.contactAliasDao().reassign(fromId, targetId)

            // 3) 会话绑定迁移（上游会话零搬运；各自游标保留）。
            db.sessionBindingDao().reassign(fromId, targetId)

            // 4) goal/style 目标优先，target 空缺取 from。
            val mergedGoal = target.goal ?: from.goal
            val mergedStyle = target.style ?: from.style
            db.contactProfileDao().update(
                target.copy(goal = mergedGoal, style = mergedStyle, updatedAt = now),
            )

            // 5) 快照留证后软删除 from。
            db.mergeRecordDao().put(
                MergeRecordEntity(
                    fromContactId = fromId,
                    intoContactId = targetId,
                    movedAliasesJson = json.encodeToString(
                        aliasListSerializer,
                        fromAliases.map { AliasSnapshot(it.alias, it.rawAlias, it.source) },
                    ),
                    movedBindingsJson = json.encodeToString(
                        ListSerializer(kotlinx.serialization.serializer<Long>()),
                        fromBindings.map { it.conversationId },
                    ),
                    fieldSnapshotJson = json.encodeToString(
                        fieldListSerializer,
                        fromFields.map { FieldSnapshot(it.field, it.value, it.source, it.confirmed, it.evidence) },
                    ),
                    targetFieldKeysJson = json.encodeToString(
                        ListSerializer(kotlinx.serialization.serializer<String>()),
                        targetFields.map { it.field },
                    ),
                    mergedAt = now,
                ),
            )
            db.contactProfileDao().markMerged(fromId, targetId, now)
        }
    }

    /** 30 天内撤销合并（§4.3）。 */
    suspend fun undoMerge(fromId: Long) {
        db.withTransaction {
            val record = db.mergeRecordDao().find(fromId) ?: error("no_merge_record")
            check(record.mergedAt > System.currentTimeMillis() - UNDO_WINDOW_MS) { "undo_expired" }
            val now = System.currentTimeMillis()

            val aliases = json.decodeFromString(aliasListSerializer, record.movedAliasesJson)
            db.contactAliasDao().deleteByAliases(record.intoContactId, aliases.map { it.alias })
            db.contactAliasDao().insertAll(
                aliases.map {
                    ContactAliasEntity(
                        contactId = fromId,
                        alias = it.alias,
                        rawAlias = it.rawAlias,
                        source = it.source,
                        lastSeenAt = now,
                    )
                },
            )

            val conversationIds = json.decodeFromString(
                ListSerializer(kotlinx.serialization.serializer<Long>()),
                record.movedBindingsJson,
            )
            conversationIds.forEach { db.sessionBindingDao().rebind(it, fromId) }

            val originalTargetKeys = json.decodeFromString(
                ListSerializer(kotlinx.serialization.serializer<String>()),
                record.targetFieldKeysJson,
            )
            val restoredFields = json.decodeFromString(fieldListSerializer, record.fieldSnapshotJson)
            // 合并时新增到 target、target 原本没有的字段随撤销移除。
            db.personaFieldDao().deleteByFields(record.intoContactId, restoredFields.map { it.field }.filterNot { it in originalTargetKeys })
            db.personaFieldDao().upsertAll(
                restoredFields.map {
                    PersonaFieldEntity(
                        contactId = fromId,
                        field = it.field,
                        value = it.value,
                        source = it.source,
                        confirmed = it.confirmed,
                        evidence = it.evidence,
                        updatedAt = now,
                    )
                },
            )

            db.contactProfileDao().unmarkMerged(fromId, now)
            db.mergeRecordDao().delete(fromId)
        }
    }

    // ---- 内部工具 ----

    private fun typeOf(field: String): String? = schemas.get().def(field)?.type

    private fun encodeValue(field: String, rawValue: String): String {
        if (typeOf(field) != PersonaFieldType.TAGS) return rawValue.trim()
        val tags = rawValue.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return PersonaValues.encodeTags(tags)
    }

    private fun mergeTags(currentJson: String, incomingJson: String, action: DraftAction): String {
        val current = PersonaValues.decodeTags(currentJson).toMutableSet()
        val incoming = PersonaValues.decodeTags(incomingJson)
        when (action) {
            DraftAction.ADD -> current.addAll(incoming)
            DraftAction.REMOVE -> current.removeAll(incoming.toSet())
            DraftAction.SET -> current.addAll(incoming)
        }
        return PersonaValues.encodeTags(current.toList())
    }

    private fun chooseWinner(a: PersonaFieldEntity, b: PersonaFieldEntity): PersonaFieldEntity {
        if (a.confirmed != b.confirmed) return if (a.confirmed) a else b
        if ((a.source == "user") != (b.source == "user")) return if (a.source == "user") a else b
        // tags 字段在调用前已合并，这里仅裁决标量；同源取 updatedAt 新者。
        return if (a.updatedAt >= b.updatedAt) a else b
    }

    @Serializable
    private data class AliasSnapshot(val alias: String, val rawAlias: String, val source: String)

    @Serializable
    private data class FieldSnapshot(
        val field: String,
        val value: String,
        val source: String,
        val confirmed: Boolean,
        val evidence: String?,
    )

    enum class DraftAction { SET, ADD, REMOVE }

    private companion object {
        const val HOLD_COOLDOWN_MS = 30L * 24 * 60 * 60 * 1000
        const val UNDO_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}
