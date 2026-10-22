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

package com.verlintas.baic2.floatball.advisor.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.verlintas.baic2.floatball.advisor.emotion.EmotionBaseline
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB v14 迁移测试（E-c）：对 MIGRATION_13_14 的真实 SQL 做结构锁——三表列定义
 * 与实体逐字对齐（Room 2.8 开库校验 defaultValue/NOT NULL/索引名）、双索引、
 * 幂等（IF NOT EXISTS）。无设备依赖：记录型 SupportSQLiteDatabase 假件捕获
 * migrate() 实际执行的 SQL。
 */
class EmotionMigrationTest {

    private fun recordMigration(): List<String> {
        val recorded = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    recorded += args!![0] as String
                    null
                }
                "toString" -> "recordingDb"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as SupportSQLiteDatabase
        MIGRATION_13_14.migrate(db)
        return recorded
    }

    @Test
    fun migrate_declaresVersionSpan13To14() {
        assertEquals(13, MIGRATION_13_14.startVersion)
        assertEquals(14, MIGRATION_13_14.endVersion)
    }

    @Test
    fun migrate_createsLexiconTableWithColumnsAndUniqueIndex() {
        val sql = recordMigration()
        val table = sql.single { it.contains("CREATE TABLE IF NOT EXISTS `emotion_lexicon`") }
        listOf(
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
            "`term` TEXT NOT NULL",
            "`library` INTEGER NOT NULL",
            "`labelsJson` TEXT NOT NULL",
            "`polarity` INTEGER NOT NULL",
            "`intensityDelta` INTEGER NOT NULL",
            "`negatable` INTEGER NOT NULL",
            "`scenario` TEXT",
            "`scopeContactId` INTEGER",
            "`weight` REAL NOT NULL",
            "`enabled` INTEGER NOT NULL",
            "`source` TEXT NOT NULL",
            "`note` TEXT NOT NULL",
        ).forEach { col -> assertTrue("missing column: $col", table.contains(col)) }

        val unique = sql.single { it.contains("CREATE UNIQUE INDEX") && it.contains("emotion_lexicon") }
        assertTrue(unique.contains("`index_emotion_lexicon_term_library_scopeContactId`"))
        assertTrue(unique.contains("(`term`, `library`, `scopeContactId`)"))
    }

    @Test
    fun migrate_createsEventsTableWithDualIndices() {
        val sql = recordMigration()
        val table = sql.single { it.contains("CREATE TABLE IF NOT EXISTS `emotion_events`") }
        listOf(
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
            "`contactId` INTEGER NOT NULL",
            "`conversationId` INTEGER",
            "`turnMessageId` INTEGER",
            "`state` TEXT NOT NULL",
            "`labelsJson` TEXT NOT NULL",
            "`intensity` INTEGER NOT NULL",
            "`confidence` TEXT NOT NULL",
            "`evidenceJson` TEXT NOT NULL",
            "`reason` TEXT NOT NULL",
            "`createdAt` INTEGER NOT NULL",
        ).forEach { col -> assertTrue("missing column: $col", table.contains(col)) }

        val timeline = sql.single { it.contains("`index_emotion_events_contactId_createdAt`") }
        assertTrue(timeline.contains("(`contactId`, `createdAt`)"))
        val retention = sql.single { it.contains("`index_emotion_events_createdAt`") }
        assertTrue(retention.contains("(`createdAt`)"))
    }

    @Test
    fun migrate_createsBaselineTableWithPrimaryKey() {
        val sql = recordMigration()
        val table = sql.single { it.contains("CREATE TABLE IF NOT EXISTS `emotion_baseline`") }
        listOf(
            "`contactId` INTEGER NOT NULL",
            "`avgLen` REAL NOT NULL",
            "`avgIntervalMs` INTEGER NOT NULL",
            "`shortReplyRatio` REAL NOT NULL",
            "`activeFollowRatio` REAL NOT NULL",
            "`sampleCount` INTEGER NOT NULL",
            "`updatedAt` INTEGER NOT NULL",
            "PRIMARY KEY(`contactId`)",
        ).forEach { col -> assertTrue("missing column: $col", table.contains(col)) }
    }

    @Test
    fun migrate_allStatementsAreIdempotent() {
        val sql = recordMigration()
        assertTrue(sql.isNotEmpty())
        sql.forEach { stmt ->
            assertTrue("non-idempotent statement: $stmt", stmt.contains("IF NOT EXISTS"))
        }
    }

    // ── 领域 ↔ 落库 映射 ────────────────────────────────────────────────

    @Test
    fun baselineMapping_roundTrips() {
        val domain = EmotionBaseline(
            avgLen = 12.5,
            avgIntervalMs = 90_000,
            shortReplyRatio = 0.4,
            activeFollowRatio = 0.1,
            sampleCount = 33,
            updatedAt = 1_700_000_000_000,
        )
        assertEquals(domain, domain.toEntity(contactId = 7).toDomain())
        assertEquals(7L, domain.toEntity(contactId = 7).contactId)
    }

    @Test
    fun labelsEncoding_roundTrips() {
        assertEquals(emptyList<String>(), decodeEmotionLabels("[]"))
        assertEquals(emptyList<String>(), decodeEmotionLabels("not-json"))
        assertEquals(listOf("anger", "grievance"), decodeEmotionLabels(encodeEmotionLabels(listOf("anger", "grievance"))))
    }
}
