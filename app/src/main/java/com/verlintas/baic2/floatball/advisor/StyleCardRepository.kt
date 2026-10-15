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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.advisor

import androidx.room.withTransaction
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.STYLE_SCOPE_CONTACT
import com.verlintas.baic2.floatball.advisor.data.STYLE_SCOPE_MY_DEFAULT
import com.verlintas.baic2.floatball.advisor.data.StyleCardEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 风格卡读写与回退链（方案 §6.2，P1）：
 * 提示词取卡顺序 = 联系人卡 → 全局卡；联系人卡全空保存时视为「跟随默认」直接删卡。
 */
@Singleton
class StyleCardRepository @Inject constructor(
    private val db: AdvisorDatabase,
    private val assets: StyleAssets,
) {
    /** 提示词用：contactId=0 取全局卡；否则联系人卡缺失时回退全局卡。 */
    suspend fun cardFor(contactId: Long): StyleCardEntity? {
        val dao = db.styleCardDao()
        if (contactId == 0L) return dao.find(STYLE_SCOPE_MY_DEFAULT, 0L)
        return dao.find(STYLE_SCOPE_CONTACT, contactId) ?: dao.find(STYLE_SCOPE_MY_DEFAULT, 0L)
    }

    fun observeContactCard(contactId: Long): Flow<StyleCardEntity?> =
        db.styleCardDao().observe(STYLE_SCOPE_CONTACT, contactId)

    fun observeDefaultCard(): Flow<StyleCardEntity?> =
        db.styleCardDao().observe(STYLE_SCOPE_MY_DEFAULT, 0L)

    suspend fun defaultCard(): StyleCardEntity? =
        db.styleCardDao().find(STYLE_SCOPE_MY_DEFAULT, 0L)

    suspend fun contactCard(contactId: Long): StyleCardEntity? =
        db.styleCardDao().find(STYLE_SCOPE_CONTACT, contactId)

    suspend fun scenarioCode(contactId: Long): String? =
        db.contactProfileDao().findById(contactId)?.scenarioCode

    /** 保存全局默认风格卡（contactId=0）。 */
    suspend fun saveDefault(toneCode: String?, intentCodes: List<String>, customNote: String?) {
        db.styleCardDao().upsert(
            StyleCardEntity(
                scope = STYLE_SCOPE_MY_DEFAULT,
                contactId = 0L,
                toneCode = toneCode,
                intentCodes = PersonaValues.encodeTags(intentCodes),
                customNote = customNote?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 保存联系人风格卡；基调/意图/补充全空时删卡（语义=恢复跟随全局默认）。
     * 同时落场景标记（[scenarioCode] 为 null 表示不套用场景，场景与风格卡相互独立）。
     */
    suspend fun saveContact(
        contactId: Long,
        toneCode: String?,
        intentCodes: List<String>,
        customNote: String?,
        scenarioCode: String?,
    ) = db.withTransaction {
        val dao = db.styleCardDao()
        val note = customNote?.trim()?.takeIf { it.isNotEmpty() }
        val existing = dao.find(STYLE_SCOPE_CONTACT, contactId)
        if (toneCode == null && intentCodes.isEmpty() && note == null) {
            dao.deleteContactCard(contactId)
        } else {
            dao.upsert(
                StyleCardEntity(
                    id = existing?.id ?: 0L,
                    scope = STYLE_SCOPE_CONTACT,
                    contactId = contactId,
                    toneCode = toneCode,
                    intentCodes = PersonaValues.encodeTags(intentCodes),
                    customNote = note,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        db.contactProfileDao().setScenario(contactId, scenarioCode, System.currentTimeMillis())
    }

    /** 联系人「恢复跟随默认风格」：删联系人卡；场景标记保留不动。 */
    suspend fun resetContactCard(contactId: Long) {
        db.styleCardDao().deleteContactCard(contactId)
    }

    /** 场景默认风格组合（tone + intents）；未知场景返回 null。 */
    fun scenarioDefaults(packCode: String?): Pair<String?, List<String>>? {
        val pack = assets.scenarioPacks().byCode(packCode) ?: return null
        return pack.defaultTone to pack.defaultIntents
    }
}
