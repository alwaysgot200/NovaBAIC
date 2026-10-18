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
import com.verlintas.baic2.floatball.advisor.data.PersonalMaterialEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 我的素材库仓储（F6，M1 方案 §7.2 素材三源之②「用户投喂」）：GoalTab 素材
 * 投喂页的唯一数据入口（UI 不直连 DAO）。指纹复用 [TopicEngine.fingerprintOf]，
 * 同内容素材靠表上唯一索引去重（重复录入 REPLACE 以最新为准）；选题侧的
 * 消费与 lastUsedAt 标记在 [TopicEngineStore]，此处只管投喂 CRUD。
 */
@Singleton
class PersonalMaterialRepository @Inject constructor(private val db: AdvisorDatabase) {

    /** 与 AdvisorRepository/GoalRepository 同款：DAO 不进 Hilt 图，从库属性委托取。 */
    private val dao get() = db.personalMaterialDao()

    /** 全量素材（新→旧）。素材是用户主动投喂的稀缺数据，不设滚动裁剪。 */
    fun observeAll(): Flow<List<PersonalMaterialEntity>> = dao.observeAll()

    /** 添加素材：内容为空白返回 false；指纹重复时 REPLACE 覆盖（以最新录入为准）。 */
    suspend fun add(content: String, tag: String?): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return false
        val fingerprint = TopicEngine.fingerprintOf(text)
        if (fingerprint.isEmpty()) return false
        dao.upsert(
            PersonalMaterialEntity(
                content = text,
                tag = tag?.trim()?.takeIf { it.isNotEmpty() },
                fingerprint = fingerprint,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    suspend fun remove(id: Long) = dao.deleteById(id)
}
