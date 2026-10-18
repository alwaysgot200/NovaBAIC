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

import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.floatball.advisor.data.ContactProfileEntity
import com.verlintas.baic2.floatball.contact.BoundConversation
import com.verlintas.baic2.floatball.contact.ContactDigest
import com.verlintas.baic2.floatball.contact.ContactDirectory
import com.verlintas.baic2.floatball.contact.ContactRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ContactDirectory] 的真实实现：识别走 [ContactRepository.resolve]（§11.1
 * 决策表），会话建在上游 [ConversationRepository]，绑定关系落在 advisor.db
 * session_bindings。老参谋每次参谋即调用 [resolve]，因此完全陌生 + 未绑定
 * 的人在此懒创建 Stub（决策表 L3）；重名/模糊近邻返回 null 回退共享会话，
 * 由 P4 绑定面板让用户手工选择，不阻断参谋。
 */
@Singleton
class AdvisorContactDirectory @Inject constructor(
    private val contacts: ContactRepository,
    private val conversationRepository: ConversationRepository,
) : ContactDirectory {

    override suspend fun resolve(imPackage: String?, rawTitle: String?, allowStub: Boolean): ContactRef? {
        if (imPackage.isNullOrBlank() || rawTitle.isNullOrBlank()) return null
        val outcome = contacts.resolve(
            imPackage = imPackage,
            rawTitle = rawTitle,
            source = "a11y",
            createStub = allowStub,
        )
        val profile = (outcome as? ResolveOutcome.Matched)?.profile ?: return null
        return toRef(profile)
    }

    override suspend fun resolveGroup(imPackage: String?, rawTitle: String?): ContactRef? {
        if (imPackage.isNullOrBlank() || rawTitle.isNullOrBlank()) return null
        val outcome = contacts.resolve(
            imPackage = imPackage,
            rawTitle = rawTitle,
            source = "a11y",
            createStub = true,
        )
        val matched = outcome as? ResolveOutcome.Matched ?: return null
        // 只有本次懒建的 stub 落群壳；命中既有档案（可能是同名个人）不改其属性。
        if (matched.newStub) contacts.markGroup(matched.profile.id)
        return toRef(matched.profile)
    }

    override suspend fun markScene(conversationId: Long, scene: String) {
        contacts.markScene(conversationId, scene)
    }

    override suspend fun ensureAdvisorConversation(contact: ContactRef): Long {
        contacts.conversationIdsFor(contact.contactId).firstOrNull()?.let { return it }
        return createConversationFor(contact)
    }

    override suspend fun createConversationFor(contact: ContactRef): Long {
        // 档案被合并/删除等异常：直接抛出，由参谋管道按失败处理，不新建孤儿会话。
        val profile = contacts.profile(contact.contactId)
            ?: error("contact ${contact.contactId} missing when creating advisor conversation")
        val conversationId = conversationRepository.create(
            agentId = profile.agentId,
            mode = AppMode.CHAT,
            title = profile.displayName,
        )
        contacts.bindConversation(conversationId, contact.contactId)
        return conversationId
    }

    override suspend fun boundConversations(contactId: Long): List<BoundConversation> =
        contacts.bindingsForContact(contactId).map {
            BoundConversation(it.conversationId, it.scene, it.createdAt)
        }

    override suspend fun sceneFor(conversationId: Long): String =
        contacts.sceneFor(conversationId)

    override suspend fun conversationIds(contactId: Long): List<Long> =
        contacts.conversationIdsFor(contactId)

    override suspend fun digest(contactId: Long): ContactDigest? =
        contacts.bindingsForContact(contactId)
            .filter { it.digest != null }
            .maxByOrNull { it.digestUpdatedAt ?: 0L }
            ?.let { row ->
                ContactDigest(
                    digest = row.digest!!,
                    coveredTurns = row.digestCoveredTurns,
                    updatedAt = row.digestUpdatedAt ?: 0L,
                )
            }

    override suspend fun saveDigest(contactId: Long, digest: String, coveredTurns: Int) {
        contacts.saveDigest(contactId, digest, coveredTurns)
    }

    override suspend fun markIncomingAt(conversationId: Long, at: Long) {
        contacts.setLastIncomingAt(conversationId, at)
    }

    override suspend fun lastIncomingAt(conversationId: Long): Long? =
        contacts.lastIncomingAt(conversationId)

    override suspend fun recordHeatDelta(contactId: Long, delta: Int) {
        contacts.recordHeatDelta(contactId, delta)
    }

    private suspend fun toRef(profile: ContactProfileEntity): ContactRef {
        val names = LinkedHashSet<String>()
        names += profile.displayName
        contacts.aliases(profile.id).forEach { names += it.rawAlias.ifBlank { it.alias } }
        return ContactRef(
            contactId = profile.id,
            displayName = profile.displayName,
            aliases = names.toList(),
            goal = profile.goal,
            style = profile.style,
            engagement = EngagementRules.normalize(profile.engagement),
        )
    }
}
