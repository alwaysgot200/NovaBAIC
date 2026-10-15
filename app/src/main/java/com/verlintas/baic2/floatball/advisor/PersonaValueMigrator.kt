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

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * persona_fields 存量值 label→code 一次性幂等迁移（方案 §6.1，P1）。
 *
 * 只迁移码表字段（options 非空的 enum/tags）；自由 tags/text 整行跳过。
 * 失败不抛异常、不置版本位，下次冷启动再试；渲染侧本身兼容旧 label（第二道保险）。
 */
@Singleton
class PersonaValueMigrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AdvisorDatabase,
    private val schemas: PersonaSchemas,
) {
    @Volatile
    private var done: Boolean = false

    private val mutex = Mutex()

    suspend fun runOnce() {
        if (done) return
        mutex.withLock {
            if (done) return
            withContext(Dispatchers.IO) { migrate() }
            done = true
        }
    }

    private suspend fun migrate() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= CODE_VERSION) return

        val outcome = runCatching {
            val defs = schemas.get().fields.filter { it.options != null }.associateBy { it.key }
            if (defs.isEmpty()) return@runCatching 0 to 0

            var converted = 0
            var scanned = 0
            db.withTransaction {
                val dao = db.personaFieldDao()
                dao.listAll().forEach { entity ->
                    val def = defs[entity.field] ?: return@forEach
                    scanned++
                    val migrated = PersonaValueCodes.migrateValue(def, entity.value)
                    if (migrated != entity.value) {
                        dao.upsert(entity.copy(value = migrated))
                        converted++
                    }
                }
            }
            prefs.edit().putInt(KEY_VERSION, CODE_VERSION).apply()
            converted to scanned
        }
        outcome
            .onSuccess { (converted, scanned) ->
                Log.d(TAG, "converted $converted/$scanned persona field rows to codes")
            }
            .onFailure { Log.w(TAG, "persona value migration deferred", it) }
    }

    private companion object {
        const val TAG = "PersonaMigrator"
        const val PREFS = "advisor_data"
        const val KEY_VERSION = "persona_value_codes_version"
        const val CODE_VERSION = 1
    }
}
