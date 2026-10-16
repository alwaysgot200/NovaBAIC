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
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.CARD_ARCHIVED
import com.verlintas.baic2.floatball.advisor.data.CARD_CONFIRMED
import com.verlintas.baic2.floatball.advisor.data.CARD_DRAFT
import com.verlintas.baic2.floatball.advisor.data.StrategyCardEntity
import com.verlintas.baic2.floatball.advisor.strategy.CardPayloads
import com.verlintas.baic2.floatball.advisor.strategy.StrategyCardPayload
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * P2 策略卡（strategy_cards 五段卡）读写与回退链，区别于 P1 的 StyleCardRepository
 * （style_cards 说话基调卡，两者并存）。
 *
 * 版本口径（§6.1）：每次编译新起一行 version+1、status=draft；旧 confirmed 卡持续生效，
 * 直到新卡 ✓ 确认——确认事务内归档同联系人其余卡并回写 contact_profiles.activeCardId。
 */
@Singleton
class StrategyCardRepository @Inject constructor(
    private val db: AdvisorDatabase,
) {
    private val cardDao get() = db.strategyCardDao()

    /** 最新一行（任意状态）；空态判断用。 */
    suspend fun latestAny(contactId: Long): StrategyCardEntity? = cardDao.latestAny(contactId)

    suspend fun latestDraft(contactId: Long): StrategyCardEntity? = cardDao.latest(contactId, CARD_DRAFT)

    suspend fun latestConfirmed(contactId: Long): StrategyCardEntity? =
        cardDao.latest(contactId, CARD_CONFIRMED)

    fun observeLatest(contactId: Long): Flow<StrategyCardEntity?> = cardDao.observeLatest(contactId)

    suspend fun card(id: Long): StrategyCardEntity? = cardDao.find(id)

    /**
     * 回合取卡回退链：profile.activeCardId 指定的 confirmed 卡 → 联系人最新 confirmed
     * → 全局（contactId=0）confirmed；任一环节失效即向下回退。
     */
    suspend fun effective(contactId: Long): Pair<StrategyCardEntity, StrategyCardPayload>? {
        val profile = db.contactProfileDao().findById(contactId)
        val pinned = profile?.activeCardId?.let { cardDao.find(it) }
            ?.takeIf { it.status == CARD_CONFIRMED }
        val contactConfirmed = pinned ?: cardDao.latest(contactId, CARD_CONFIRMED)
        val resolved = contactConfirmed
            ?: cardDao.latest(GLOBAL_CONTACT_ID, CARD_CONFIRMED)
            ?: return null
        val payload = CardPayloads.parse(resolved.payloadJson) ?: return null
        return resolved to payload
    }

    /**
     * 编译器落新草稿：与最近一版（draft/confirmed 均可）做用户句保留合并，
     * version+1 新起一行（旧行原地保留状态，确认时才归档）。
     */
    suspend fun compileDraft(
        contactId: Long,
        scenarioCode: String?,
        newAiPayload: StrategyCardPayload,
    ): Long {
        val previous = cardDao.latestAny(contactId)
        val oldPayload = previous?.let { CardPayloads.parse(it.payloadJson) }
        val merged = CardPayloads.mergeRetained(oldPayload, newAiPayload)
        val nextVersion = (previous?.version ?: 0) + 1
        val now = System.currentTimeMillis()
        return cardDao.upsert(
            StrategyCardEntity(
                contactId = contactId,
                version = nextVersion,
                scenarioCode = scenarioCode,
                payloadJson = CardPayloads.encode(merged.copy(cardVersion = nextVersion)),
                status = CARD_DRAFT,
                createdAt = now,
            ),
        )
    }

    /**
     * 审核页保存编辑：原地更新该行（version 不变、status 保持 draft）。
     * 用户动过的 roleLine/条目的 source 标记由调用方在 [payload] 中完成。
     */
    suspend fun saveEditedDraft(cardId: Long, payload: StrategyCardPayload) {
        val existing = cardDao.find(cardId) ?: return
        cardDao.upsert(
            existing.copy(
                payloadJson = CardPayloads.encode(payload.copy(cardVersion = existing.version)),
                status = CARD_DRAFT,
            ),
        )
    }

    /** ✓ 确认生效：卡置 confirmed、归档同联系人其余卡、回写 activeCardId（全局卡不回写）。 */
    suspend fun confirm(cardId: Long): Boolean = db.withTransaction {
        val card = cardDao.find(cardId) ?: return@withTransaction false
        val now = System.currentTimeMillis()
        cardDao.upsert(card.copy(status = CARD_CONFIRMED, createdAt = now))
        cardDao.archiveOthers(card.contactId, cardId)
        if (card.contactId != GLOBAL_CONTACT_ID) {
            db.contactProfileDao().setActiveCard(card.contactId, cardId, now)
        }
        true
    }

    /** 忽略此草稿：单行归档（不影响已 confirmed 卡）。 */
    suspend fun discardDraft(cardId: Long) {
        val card = cardDao.find(cardId) ?: return
        cardDao.upsert(card.copy(status = CARD_ARCHIVED))
    }

    companion object {
        /** 全局默认策略卡的 contactId。 */
        const val GLOBAL_CONTACT_ID = 0L
    }
}
