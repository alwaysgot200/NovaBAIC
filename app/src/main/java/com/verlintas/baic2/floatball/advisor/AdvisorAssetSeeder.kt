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

import android.content.Context
import androidx.room.withTransaction
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.PlaybookEntity
import com.verlintas.baic2.floatball.advisor.data.PlaybookFtsEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class AdvisorAssetSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AdvisorDatabase,
) {
    @Volatile
    private var done: Boolean = false

    private val mutex = Mutex()

    suspend fun ensureSeed() {
        if (done) return
        mutex.withLock {
            if (done) return
            withContext(Dispatchers.IO) { seedPlaybooks() }
            done = true
        }
    }

    private suspend fun seedPlaybooks() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= SEED_VERSION && db.playbookDao().builtinCount() > 0) return

        val files = context.assets.list(PLAYBOOK_DIR)?.toList().orEmpty().filter { it.endsWith(".json") }
        if (files.isEmpty()) return
        val json = Json { ignoreUnknownKeys = true }

        db.withTransaction {
            // FTS 清理依赖主表子查询，顺序不可颠倒。
            db.playbookDao().clearBuiltinFts()
            db.playbookDao().clearBuiltin()
            files.forEach { fileName ->
                val text = context.assets.open("$PLAYBOOK_DIR/$fileName")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val bundle = runCatching { json.decodeFromString(PlaybookBundle.serializer(), text) }.getOrNull()
                    ?: return@forEach
                bundle.entries.forEach { seed ->
                    val scenario = seed.scenario.trim()
                    val content = seed.content.trim()
                    if (scenario.isEmpty() || content.isEmpty()) return@forEach
                    val id = db.playbookDao().insert(
                        PlaybookEntity(
                            category = bundle.category.ifBlank { fileName.removeSuffix(".json") },
                            scenario = scenario,
                            tags = seed.tags,
                            content = content,
                            priority = seed.priority.coerceIn(1, 10),
                            source = "builtin",
                        ),
                    )
                    db.playbookDao().insertFts(
                        PlaybookFtsEntity(
                            rowid = id,
                            scenario = PlaybookIndex.tokenize(scenario),
                            tags = PlaybookIndex.tokenize(seed.tags),
                            content = PlaybookIndex.tokenize(content),
                        ),
                    )
                }
            }
            prefs.edit().putInt(KEY_VERSION, SEED_VERSION).apply()
        }
    }

    private companion object {
        const val PREFS = "advisor_data"
        const val KEY_VERSION = "playbook_seed_version"
        const val PLAYBOOK_DIR = "playbooks"
        const val SEED_VERSION = 3
    }
}

@Serializable
data class PlaybookBundle(
    val category: String = "",
    val entries: List<PlaybookSeed> = emptyList(),
)

@Serializable
data class PlaybookSeed(
    val scenario: String,
    val tags: String = "",
    val content: String,
    val priority: Int = 5,
)
