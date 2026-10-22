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
import com.verlintas.baic2.floatball.advisor.data.EmotionLexiconEntity
import com.verlintas.baic2.floatball.advisor.data.toEntity
import com.verlintas.baic2.floatball.advisor.emotion.EmotionLexiconEntry
import com.verlintas.baic2.floatball.advisor.emotion.parseEmotionLexiconSeed
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 内置情绪词库播种（方案 §4.3.3 / §4.7）：把 assets/advisor/emotion_lexicon.json
 * 的内置词条补进 emotion_lexicon 表。
 *
 * **用户改动保护是硬红线**：词库是可运营数据资产——用户调过的权重 / 停用 /
 * 改注 / 自建词条绝不被播种覆盖或重复，播种只按 (term, library, scopeContactId)
 * 身份**补缺**（[planSeed]，纯函数可单测）。屏蔽内置词条请用停用（enabled=false）；
 * 删除的内置词条会在内置词库版本升级（SEED_VERSION 提升）时随补缺回归。
 */
@Singleton
class EmotionLexiconSeeder @Inject constructor(
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
            withContext(Dispatchers.IO) { seedLexicon() }
            done = true
        }
    }

    private suspend fun seedLexicon() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= SEED_VERSION) return
        val text = context.assets.open(ASSET_PATH)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val seed = parseEmotionLexiconSeed(text)
        if (seed.isEmpty()) return

        db.withTransaction {
            val missing = planSeed(db.emotionLexiconDao().all(), seed)
            if (missing.isNotEmpty()) db.emotionLexiconDao().insertAll(missing)
            prefs.edit().putInt(KEY_VERSION, SEED_VERSION).apply()
        }
    }

    companion object {
        private const val PREFS = "advisor_data"
        private const val KEY_VERSION = "emotion_lexicon_seed_version"
        private const val ASSET_PATH = "advisor/emotion_lexicon.json"
        private const val SEED_VERSION = 1

        /**
         * 播种计划（纯函数）：只返回**缺失身份**的新行——已有身份（含用户改过
         * 权重/停用/注释的、用户自建同名的、seed 内部重复的）一律跳过，
         * 绝不产生覆盖。身份键 = (term, library, scopeContactId)。
         */
        fun planSeed(
            existing: List<EmotionLexiconEntity>,
            seed: List<EmotionLexiconEntry>,
        ): List<EmotionLexiconEntity> {
            val seen = existing.map { identity(it.term, it.library, it.scopeContactId) }.toHashSet()
            return seed.mapNotNull { entry ->
                val key = identity(entry.term, entry.library, entry.scopeContactId)
                if (seen.add(key)) entry.toEntity() else null
            }
        }

        private fun identity(term: String, library: Int, scopeContactId: Long?): String =
            "$term#$library#$scopeContactId"
    }
}
