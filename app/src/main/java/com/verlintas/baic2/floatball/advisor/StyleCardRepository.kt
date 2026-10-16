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
import com.verlintas.baic2.floatball.advisor.strategy.StrategyRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * 风格卡读写与回退链（方案 §6.2，P1；P3 §八 customNote 编译确认流）：
 * 提示词取卡顺序 = 联系人卡 → 全局卡；联系人卡全空保存时视为「跟随默认」直接删卡。
 *
 * P3：纯选项组合 confirmed=1 即时生效；customNote 非空（或被改写）时整卡 confirmed=0、
 * 旧编译作废并入慢车道 [StrategyRuntime.requestStyleCompile]，用户确认编译结果前
 * 不进任何提示词（联系人草稿期间回退全局卡）。
 */
@Singleton
class StyleCardRepository @Inject constructor(
    private val db: AdvisorDatabase,
    private val assets: StyleAssets,
    private val runtime: StrategyRuntime,
    private val compileState: StyleNoteCompileState,
) {
    /** customNote 编译失败的卡 id 内存态（编辑页展示重试/放弃用）。 */
    val noteCompileFailed: StateFlow<Set<Long>> = compileState.failed

    /**
     * 提示词用：contactId=0 取全局卡；否则联系人卡缺失/未确认时回退全局卡
     * （P3：customNote 草稿整卡不进提示词）。
     */
    suspend fun cardFor(contactId: Long): StyleCardEntity? {
        val dao = db.styleCardDao()
        if (contactId == 0L) return dao.find(STYLE_SCOPE_MY_DEFAULT, 0L)
        val contact = dao.find(STYLE_SCOPE_CONTACT, contactId)
        if (contact != null && contact.confirmed) return contact
        return dao.find(STYLE_SCOPE_MY_DEFAULT, 0L)
    }

    fun observeContactCard(contactId: Long): kotlinx.coroutines.flow.Flow<StyleCardEntity?> =
        db.styleCardDao().observe(STYLE_SCOPE_CONTACT, contactId)

    fun observeDefaultCard(): kotlinx.coroutines.flow.Flow<StyleCardEntity?> =
        db.styleCardDao().observe(STYLE_SCOPE_MY_DEFAULT, 0L)

    suspend fun defaultCard(): StyleCardEntity? =
        db.styleCardDao().find(STYLE_SCOPE_MY_DEFAULT, 0L)

    suspend fun contactCard(contactId: Long): StyleCardEntity? =
        db.styleCardDao().find(STYLE_SCOPE_CONTACT, contactId)

    suspend fun scenarioCode(contactId: Long): String? =
        db.contactProfileDao().findById(contactId)?.scenarioCode

    /**
     * 保存全局默认风格卡（contactId=0）。
     * @return 卡 id；customNote 非空时已入编译队列，卡为待确认草稿。
     */
    suspend fun saveDefault(toneCode: String?, intentCodes: List<String>, customNote: String?): Long {
        val dao = db.styleCardDao()
        val existing = dao.find(STYLE_SCOPE_MY_DEFAULT, 0L)
        val card = buildCard(
            existing = existing,
            scope = STYLE_SCOPE_MY_DEFAULT,
            contactId = 0L,
            toneCode = toneCode,
            intentCodes = intentCodes,
            note = customNote,
        )
        val id = dao.upsert(card)
        enqueueNoteCompileIfNeeded(id, card.customNote, existing?.customNote, card.compiledCustomJson)
        return id
    }

    /**
     * 保存联系人风格卡；基调/意图/补充全空时删卡（语义=恢复跟随全局默认）。
     * 同时落场景标记（[scenarioCode] 为 null 表示不套用场景，场景与风格卡相互独立）。
     * @return 卡 id（全空删卡时为 0）；customNote 非空时已入编译队列。
     */
    suspend fun saveContact(
        contactId: Long,
        toneCode: String?,
        intentCodes: List<String>,
        customNote: String?,
        scenarioCode: String?,
    ): Long = db.withTransaction {
        val dao = db.styleCardDao()
        val note = customNote?.trim()?.takeIf { it.isNotEmpty() }
        val existing = dao.find(STYLE_SCOPE_CONTACT, contactId)
        if (toneCode == null && intentCodes.isEmpty() && note == null) {
            dao.deleteContactCard(contactId)
            0L
        } else {
            val card = buildCard(
                existing = existing,
                scope = STYLE_SCOPE_CONTACT,
                contactId = contactId,
                toneCode = toneCode,
                intentCodes = intentCodes,
                note = note,
            )
            val id = dao.upsert(card)
            db.contactProfileDao().setScenario(contactId, scenarioCode, System.currentTimeMillis())
            enqueueNoteCompileIfNeeded(
                cardId = id,
                note = card.customNote,
                previousNote = existing?.customNote,
                compiled = card.compiledCustomJson,
            )
            id
        }
    }

    /** 组装落库行：note 被改写/新增 → confirmed=0、旧编译作废重编；note 清空 → 即时生效。 */
    private fun buildCard(
        existing: StyleCardEntity?,
        scope: String,
        contactId: Long,
        toneCode: String?,
        intentCodes: List<String>,
        note: String?,
    ): StyleCardEntity {
        val noteChanged = note != null && note != existing?.customNote
        val noteCleared = note == null && existing?.customNote != null
        val confirmed = when {
            note != null && noteChanged -> false
            noteCleared -> true
            else -> existing?.confirmed ?: true
        }
        val compiled = if (noteChanged || noteCleared) null else existing?.compiledCustomJson
        return StyleCardEntity(
            id = existing?.id ?: 0L,
            scope = scope,
            contactId = contactId,
            toneCode = toneCode,
            intentCodes = PersonaValues.encodeTags(intentCodes),
            customNote = note,
            compiledCustomJson = compiled,
            confirmed = confirmed,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** note 新增或被改写时排编译（清失败标记；inFlight 去重防连点）。 */
    private fun enqueueNoteCompileIfNeeded(
        cardId: Long,
        note: String?,
        previousNote: String?,
        compiled: String?,
    ) {
        note ?: return
        if (note == previousNote && compiled != null) return
        compileState.clear(cardId)
        runtime.requestStyleCompile(cardId)
    }

    /** 用户确认编译结果：整卡生效。 */
    suspend fun confirmCompiled(cardId: Long) {
        db.styleCardDao().confirmCard(cardId, System.currentTimeMillis())
        compileState.clear(cardId)
    }

    /** 用户点「重新理解」：清失败标记并重排编译。 */
    fun recompileNote(cardId: Long) {
        compileState.clear(cardId)
        runtime.requestStyleCompile(cardId)
    }

    /** 用户放弃 customNote：清补充与编译，纯选项组合即时生效。 */
    suspend fun discardCustomNote(cardId: Long) {
        db.styleCardDao().clearCustomNote(cardId, System.currentTimeMillis())
        compileState.clear(cardId)
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
