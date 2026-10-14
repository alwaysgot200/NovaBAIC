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

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 老参谋私有 Room 库（advisor.db）：联系人画像 / 会话绑定 / 话术库。
 * 与上游 Baic2Database（v18）完全隔离，仅以 conversationId/agentId 逻辑引用，
 * 避免上游 DB 版本迁移冲突（方案 §4）。
 */
@Database(
    entities = [
        ContactProfileEntity::class,
        ContactAliasEntity::class,
        PersonaFieldEntity::class,
        ExtractionHoldEntity::class,
        SessionBindingEntity::class,
        GroupMemberEntity::class,
        PlaybookEntity::class,
        PlaybookFtsEntity::class,
        MergeRecordEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AdvisorDatabase : RoomDatabase() {

    abstract fun contactProfileDao(): ContactProfileDao

    abstract fun contactAliasDao(): ContactAliasDao

    abstract fun personaFieldDao(): PersonaFieldDao

    abstract fun extractionHoldDao(): ExtractionHoldDao

    abstract fun sessionBindingDao(): SessionBindingDao

    abstract fun groupMemberDao(): GroupMemberDao

    abstract fun playbookDao(): PlaybookDao

    abstract fun mergeRecordDao(): MergeRecordDao
}

/**
 * v2 → v3：contact_profiles 加软删列 deletedAt；新增 group_members 群成员表；
 * session_bindings 补滚动摘要三列（§6.4）。
 *
 * digest 三列随 §6 开发期加入，历史 v2 开发库可能带列也可能不带——
 * ADD COLUMN 无 IF NOT EXISTS 语法，故先查 PRAGMA table_info 幂等补列，
 * 两种 v2 库形态都能安全升到 v3，且最终结构与 Room 期望完全一致。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact_profiles ADD COLUMN deletedAt INTEGER")
        ensureColumn(db, "session_bindings", "digest", "digest TEXT")
        ensureColumn(
            db, "session_bindings", "digestCoveredTurns",
            "digestCoveredTurns INTEGER NOT NULL DEFAULT 0",
        )
        ensureColumn(db, "session_bindings", "digestUpdatedAt", "digestUpdatedAt INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `group_members` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `groupContactId` INTEGER NOT NULL,
                `memberKey` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `firstSeenAt` INTEGER NOT NULL,
                `lastSeenAt` INTEGER NOT NULL,
                `seenCount` INTEGER NOT NULL,
                `linkedContactId` INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_group_members_groupContactId` " +
                "ON `group_members` (`groupContactId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_group_members_groupContactId_memberKey` " +
                "ON `group_members` (`groupContactId`, `memberKey`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_group_members_linkedContactId` " +
                "ON `group_members` (`linkedContactId`)",
        )
    }

    /** 列不存在时才 ADD COLUMN（PRAGMA table_info 幂等保护，SQLite 无 ADD COLUMN IF NOT EXISTS）。 */
    private fun ensureColumn(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        columnDdl: String,
    ) {
        val exists = db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
                .any { it == column }
        }
        if (!exists) db.execSQL("ALTER TABLE `$table` ADD COLUMN $columnDdl")
    }
}
