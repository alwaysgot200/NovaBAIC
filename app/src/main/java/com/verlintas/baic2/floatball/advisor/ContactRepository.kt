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
import com.verlintas.baic2.floatball.advisor.data.CONTACT_TYPE_GROUP
import com.verlintas.baic2.floatball.advisor.data.CONTACT_TYPE_PERSON
import com.verlintas.baic2.floatball.advisor.data.SCENE_CHAT
import com.verlintas.baic2.floatball.advisor.data.SCENE_NON_CHAT
import com.verlintas.baic2.floatball.advisor.data.ContactAliasEntity
import com.verlintas.baic2.floatball.advisor.data.ContactProfileEntity
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.ExtractionHoldEntity
import com.verlintas.baic2.floatball.advisor.data.GroupMemberEntity
import com.verlintas.baic2.floatball.advisor.data.MY_PERSONA_CONTACT_ID
import com.verlintas.baic2.floatball.advisor.data.MergeRecordEntity
import com.verlintas.baic2.floatball.advisor.data.SelfPersonaEntity
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
        // 建档即双建（v10，方案 §2.4）：我方侧面从全局 self_personas 复制为起点，
        // 对方侧 identity/chatStyle/closeness 留空待萃取；建档+登记别名同事务原子。
        val facetSeed = SelfFacets.encode(db.selfPersonaDao().listAll().map { it.tag })
        val id = db.withTransaction {
            val newId = db.contactProfileDao().insert(
                ContactProfileEntity(
                    imPackage = imPackage,
                    contactKey = ContactNames.contactKey(imPackage, normalized),
                    displayName = rawTitle.trim(),
                    selfFacetJson = facetSeed,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.contactAliasDao().insert(
                ContactAliasEntity(
                    contactId = newId,
                    alias = normalized,
                    rawAlias = rawTitle.trim(),
                    source = source,
                    lastSeenAt = now,
                ),
            )
            newId
        }
        val stub = db.contactProfileDao().findById(id)!!
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
        } else {
            if (existing.contactId != contactId) {
                db.sessionBindingDao().upsert(existing.copy(contactId = contactId))
            }
            // 绑定了真实联系人即聊天会话：孤立会话（contactId=0 的 non_chat 行）
            // 被手动关联后场景复位，人设区恢复显示（方案 §1.2 孤立可稍后手动关联）。
            if (contactId != 0L && existing.scene != SCENE_CHAT) {
                db.sessionBindingDao().setScene(conversationId, SCENE_CHAT)
            }
        }
    }

    /**
     * 会话归属场景落行（v10，方案 §1.2）：非聊天屏孤立会话标记 non_chat——
     * 会话可能尚无绑定行（共享会话），此时以 contactId=0（虚拟占位，与
     * [MY_PERSONA_CONTACT_ID] 同口径的「非真实联系人」）建行再写场景，
     * contactForConversation 对 0 行自然返回 null，下游行为不变。
     *
     * 守卫：chat 场景无绑定行时 no-op（共享会话本就按 chat 处理，不污染建行）；
     * 已绑定真实联系人的行拒绝 non_chat 标记（联系人会话被相似度误复用时，
     * 不得把联系人绑定改写成 non_chat，会话复用分区 V-B 另行处理）。
     */
    suspend fun markScene(conversationId: Long, scene: String) {
        val existing = db.sessionBindingDao().find(conversationId)
        if (existing == null) {
            if (scene != SCENE_NON_CHAT) return
            db.sessionBindingDao().upsert(
                SessionBindingEntity(
                    conversationId = conversationId,
                    contactId = 0L,
                    scene = scene,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } else if (existing.scene != scene) {
            if (scene == SCENE_NON_CHAT && existing.contactId != 0L) return
            db.sessionBindingDao().setScene(conversationId, scene)
        }
    }

    /** 读会话归属场景；无绑定行按 chat 处理（历史共享会话无行，不臆测）。 */
    suspend fun sceneFor(conversationId: Long): String =
        db.sessionBindingDao().find(conversationId)?.scene ?: SCENE_CHAT

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

    /** P3 §12.2：对方新来信时间（24h 时序信号）；仅绑定会话有行，共享会话静默无效。 */
    suspend fun setLastIncomingAt(conversationId: Long, at: Long) =
        db.sessionBindingDao().setLastIncomingAt(conversationId, at)

    /** P3 §12.2：读对方最后来信时间；无绑定行/从未更新均返回 null（无信号，不臆测）。 */
    suspend fun lastIncomingAt(conversationId: Long): Long? =
        db.sessionBindingDao().find(conversationId)?.lastIncomingAt

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

    // ---- 维持度 / 群标识 / 双方人设（v10，UltraAdvisor v2.2 方案 §2.4/§2.6） ----

    /** 维持度读：空/未知档位归一 reactive（EngagementRules 同口径，默认「问了才答」）。 */
    suspend fun engagement(contactId: Long): String =
        EngagementRules.normalize(db.contactProfileDao().findById(contactId)?.engagement)

    /** 维持度三档设置（画像页开关）：proactive/reactive/mute。 */
    suspend fun setEngagement(contactId: Long, engagement: String) {
        val now = System.currentTimeMillis()
        db.contactProfileDao().setEngagement(contactId, EngagementRules.normalize(engagement), now)
    }

    /** isGroup 手动切换（误判修正）：与 contactType=group 同步写，保持两列同义。 */
    suspend fun setGroup(contactId: Long, isGroup: Boolean) {
        val p = db.contactProfileDao().findById(contactId) ?: return
        val now = System.currentTimeMillis()
        val type = if (isGroup) CONTACT_TYPE_GROUP else CONTACT_TYPE_PERSON
        db.contactProfileDao().update(p.copy(isGroup = isGroup, contactType = type, updatedAt = now))
    }

    /** 群档案标记（EntityResolver GROUP 自动建群壳后调用）：isGroup 与 contactType 一并落定。 */
    suspend fun markGroup(contactId: Long) = setGroup(contactId, true)

    /** 我方侧面整集改写（画像页手动增删改，v10 §2.4 每关系独立）。
     * 读改写包单事务（F4）：与并发 facet/pending 通道互覆竞态由此消除。 */
    suspend fun setSelfFacet(contactId: Long, tags: List<String>) {
        db.withTransaction {
            val p = db.contactProfileDao().findById(contactId) ?: return@withTransaction
            db.contactProfileDao().update(
                p.copy(selfFacetJson = SelfFacets.encode(tags), updatedAt = System.currentTimeMillis()),
            )
        }
    }

    /** 待确认差异标签转正（画像页核阅后并入 facet，v10 §2.5 不静默丢弃的兑现口）。 */
    suspend fun confirmFacetPending(contactId: Long) {
        db.withTransaction {
            val p = db.contactProfileDao().findById(contactId) ?: return@withTransaction
            val promoted = SelfFacets.promoteAll(p.selfFacetJson, p.selfFacetPendingJson)
            db.contactProfileDao().update(
                p.copy(
                    selfFacetJson = promoted.facetJson,
                    selfFacetPendingJson = null,
                    // style 三值只在列为空时转正（promoteAll 契约）：手动设置绝不覆盖。
                    identity = p.identity ?: promoted.identity,
                    chatStyle = p.chatStyle ?: promoted.chatStyle,
                    closeness = p.closeness ?: promoted.closeness,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * 风格标签萃取的待确认入口（V-C，方案 §4.1）：AI 萃取的一问双产出
     * （对方 style 三标签 + 我方 facet 增量）先落待确认区，画像页核阅转正后
     * 才生效，绝不直接写 [ContactProfileEntity.identity] 等列防静默覆盖手动值。
     * 语义：facet 并集去重；style 值仅在**档案列为空**时进待确认（手动值优先）；
     * 新萃取的 style 覆盖旧 pending 的 style 值（最新观察为准）。全空则清列。
     */
    suspend fun proposeStylePending(contactId: Long, incoming: SelfFacets.StylePending) {
        if (incoming.isEmpty) return
        db.withTransaction {
            val p = db.contactProfileDao().findById(contactId) ?: return@withTransaction
            val old = SelfFacets.decodePending(p.selfFacetPendingJson) ?: SelfFacets.StylePending()
            val pending = SelfFacets.StylePending(
                facet = (old.facet + incoming.facet).distinct(),
                identity = p.identity?.takeIf { it.isNotBlank() } ?: incoming.identity,
                chatStyle = p.chatStyle?.takeIf { it.isNotBlank() } ?: incoming.chatStyle,
                closeness = p.closeness ?: incoming.closeness,
            )
            db.contactProfileDao().update(
                p.copy(selfFacetPendingJson = SelfFacets.encodePending(pending), updatedAt = System.currentTimeMillis()),
            )
        }
    }

    /**
     * 反馈热度增量落分（V-C，方案 §4.3）：两路信号（连续模式来信指纹变化 /
     * 通知链来信正文）算出的增量经 [HeatRules.clamp] 夹逼到 0~100 后整列回写。
     * 档案缺失静默忽略；不碰 updatedAt（热度属观测值，不让列表因它重排）。
     * 读改写包单事务（F4）：并发多路信号（连续差分/通知链/检点）同刻落分不互覆。
     */
    suspend fun recordHeatDelta(contactId: Long, delta: Int) {
        db.withTransaction {
            val p = db.contactProfileDao().findById(contactId) ?: return@withTransaction
            db.contactProfileDao().update(p.copy(heatScore = HeatRules.clamp(p.heatScore, delta)))
        }
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
            if (existing.scene != SCENE_CHAT) {
                db.sessionBindingDao().setScene(conversationId, SCENE_CHAT)
            }
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
     * 跨包名手动合并协议入口（v10，方案 §2.5 五步，选主档案为 main）：①对方标签
     * 并集去重（mergeContacts 字段步）；②双方人设 facet 主档案为准、other 差异标签
     * 落主档案 selfFacetPendingJson 待确认区；③other 别名全转 main；④绑定会话
     * 全量原子改绑（withTransaction 内 reassign）；⑤other 软合并降级（mergedInto
     * 标记 + merge_records 快照，列表查询过滤，30 天可撤销）。全部复用
     * [mergeContacts] 的单事务实现，类型不同（person≠group）仍拒绝。
     */
    suspend fun mergeInto(mainId: Long, otherId: Long) {
        mergeContacts(fromId = otherId, targetId = mainId)
    }

    /**
     * 合并联系人：保留 [targetId]，并入 [fromId]。类型不同拒绝；任一方已软删
     * （deletedAt 非空，findById 不过滤软删行）或已合并拒绝（F5 守卫——软删档
     * 不许并入活档、也不许作为容器）；返回 Unit，失败抛 IllegalStateException
     * （UI 层映射文案）。
     */
    suspend fun mergeContacts(fromId: Long, targetId: Long) {
        require(fromId != targetId) { "cannot_merge_self" }
        db.withTransaction {
            val from = db.contactProfileDao().findById(fromId)
                ?.takeIf { it.mergedInto == null && it.deletedAt == null } ?: error("contact_gone")
            val target = db.contactProfileDao().findById(targetId)
                ?.takeIf { it.mergedInto == null && it.deletedAt == null } ?: error("contact_gone")
            require(from.contactType == target.contactType) { "type_mismatch" }

            val fromAliases = db.contactAliasDao().listFor(fromId)
            val fromBindings = db.sessionBindingDao().listForContact(fromId)
            val fromFields = db.personaFieldDao().listFor(fromId)
            val targetFields = db.personaFieldDao().listFor(targetId)
            val now = System.currentTimeMillis()

            // 1) 字段逐 key 择优并入 target；tags 类型（对方标签）按并集去重合并
            //    （v10 §2.5 步骤①：一个标签都不丢），其余标量维持 winner 裁决。
            val targetByKey = targetFields.associateBy { it.field }
            fromFields.forEach { sourceField ->
                val existing = targetByKey[sourceField.field]
                if (existing == null) {
                    db.personaFieldDao().upsert(sourceField.copy(contactId = targetId))
                } else if (typeOf(sourceField.field) == PersonaFieldType.TAGS) {
                    val union = SelfFacets.encode(
                        PersonaValues.decodeTags(existing.value) + PersonaValues.decodeTags(sourceField.value),
                    ) ?: existing.value
                    if (union != existing.value) {
                        db.personaFieldDao().upsert(
                            existing.copy(value = union, evidence = sourceField.evidence, updatedAt = now),
                        )
                    }
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

            // 1.5) 双方人设合并（v10 §2.5 步骤②）：facet 主档案为准，other 差异标签
            //      与既有 pending 并集后落 selfFacetPendingJson 待确认区（不静默丢弃）。
            val (facet, pending) = SelfFacets.mergePending(
                mainJson = target.selfFacetJson,
                otherJson = from.selfFacetJson,
                mainPendingJson = target.selfFacetPendingJson,
            )

            // 2) 别名去重后整体改指（tags 式并集：一个名字都不丢）。
            db.contactAliasDao().deleteDuplicatesWith(fromId, targetId)
            db.contactAliasDao().reassign(fromId, targetId)

            // 3) 会话绑定迁移（上游会话零搬运；各自游标保留）。
            db.sessionBindingDao().reassign(fromId, targetId)

            // 4) goal/style 目标优先，target 空缺取 from；facet 差异按 1.5 落定。
            val mergedGoal = target.goal ?: from.goal
            val mergedStyle = target.style ?: from.style
            db.contactProfileDao().update(
                target.copy(
                    goal = mergedGoal,
                    style = mergedStyle,
                    selfFacetJson = facet,
                    selfFacetPendingJson = pending,
                    updatedAt = now,
                ),
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
            // F5：被并期间同名别名可能已被第三档重新取得——仅收回仍属本档（或
            // 无主）的别名，第三档在册的跳过，insertAll REPLACE 不得偷档。
            val restorable = aliases.filter { snapshot ->
                isAliasRestorable(db.contactAliasDao().listByAlias(snapshot.alias), fromId)
            }
            db.contactAliasDao().insertAll(
                restorable.map {
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

    // ---- VA-2 UI 层写入口（v10 §2.4/§2.5，纯增量追加）：画像卡与人设库页经此落库，
    // 不直连 DAO；只读改 contact_profiles 行，未触碰上方 VA-1 判定链。 ----

    /** 对方风格三标签手动设置（方案 §4.1 手动通道）：空/空白按清空处理，closeness null=待定。 */
    suspend fun setStyleTags(contactId: Long, identity: String?, chatStyle: String?, closeness: Int?) {
        val p = db.contactProfileDao().findById(contactId) ?: return
        db.contactProfileDao().update(
            p.copy(
                identity = identity?.trim()?.takeIf { it.isNotEmpty() },
                chatStyle = chatStyle?.trim()?.takeIf { it.isNotEmpty() },
                closeness = closeness,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 待确认差异标签丢弃（与 [confirmFacetPending] 对偶的核阅出口，方案 §2.5 不静默生效）。 */
    suspend fun discardFacetPending(contactId: Long) {
        val p = db.contactProfileDao().findById(contactId) ?: return
        if (p.selfFacetPendingJson == null) return
        db.contactProfileDao().update(
            p.copy(selfFacetPendingJson = null, updatedAt = System.currentTimeMillis()),
        )
    }

    /** 全局我方人设标签库读（建档双建的模板源，方案 §2.4）。 */
    suspend fun selfPersonas(): List<SelfPersonaEntity> = db.selfPersonaDao().listAll()

    /** 标签 upsert（tag 唯一键 REPLACE 去重；空标签忽略，备注空白按 null）。 */
    suspend fun upsertSelfPersona(tag: String, note: String?) {
        val clean = tag.trim()
        if (clean.isEmpty()) return
        db.selfPersonaDao().upsert(
            SelfPersonaEntity(
                tag = clean,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 全局标签删除（各关系侧面独立副本，删除模板不回溯既有 facet）。 */
    suspend fun deleteSelfPersona(id: Long) = db.selfPersonaDao().deleteById(id)

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

/**
 * undoMerge 别名收回判定（F5 纯函数，JVM 可单测）：该别名的现存行全部属
 * [fromId]（或已无任何行）才可收回；存在第三档在册行时返回 false 跳过，
 * 防 insertAll REPLACE 偷走别档别名。
 */
internal fun isAliasRestorable(existingRows: List<ContactAliasEntity>, fromId: Long): Boolean =
    existingRows.all { it.contactId == fromId }
