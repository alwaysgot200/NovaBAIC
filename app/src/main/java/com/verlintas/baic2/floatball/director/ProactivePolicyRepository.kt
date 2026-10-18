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

package com.verlintas.baic2.floatball.director

import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.ProactivePolicyEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 主动策略仓储（M2-b）：proactive_policies 的唯一 UI 入口（GoalTab 主动保温卡）。
 * 频控数据的裁决在 [ProactiveCheckpoint]，这里只做配置读写。
 */
@Singleton
class ProactivePolicyRepository @Inject constructor(private val db: AdvisorDatabase) {

    private val dao get() = db.proactivePolicyDao()

    /** 联系人策略行（无行 = 全关的缺省配置，UI 据此渲染初始态）。 */
    fun observeFor(contactId: Long): Flow<ProactivePolicyEntity?> = dao.observeFor(contactId)

    suspend fun forContact(contactId: Long): ProactivePolicyEntity? =
        runCatching { dao.forContact(contactId) }.getOrNull()

    /** 整行覆盖写入（v7 表语义）；updatedAt 由调用方给定。 */
    suspend fun upsert(policy: ProactivePolicyEntity) {
        runCatching { dao.upsert(policy) }
    }
}
