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
        StyleCardEntity::class,
        GoalEntity::class,
        GoalDecompEntity::class,
        StageStateEntity::class,
        StageHistoryEntity::class,
        StrategyCardEntity::class,
        TalkMissionEntity::class,
    ],
    version = 6,
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

    abstract fun styleCardDao(): StyleCardDao

    abstract fun goalDao(): GoalDao

    abstract fun goalDecompDao(): GoalDecompDao

    abstract fun stageStateDao(): StageStateDao

    abstract fun stageHistoryDao(): StageHistoryDao

    abstract fun strategyCardDao(): StrategyCardDao

    abstract fun talkMissionDao(): TalkMissionDao
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
}

/**
 * v3 → v4（P1 风格行为化）：新增 style_cards 风格卡表；contact_profiles 补 scenarioCode。
 * 不做 enum 值搬运（label→code 由 PersonaValueMigrator 在 Room 之外惰性完成）。
 * goals/stage_states 等 P2 表留给后续 4→5 迁移，保持 P1 灰度包结构最小。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `style_cards` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `scope` TEXT NOT NULL,
                `contactId` INTEGER NOT NULL DEFAULT 0,
                `toneCode` TEXT,
                `intentCodes` TEXT NOT NULL DEFAULT '[]',
                `customNote` TEXT,
                `compiledCustomJson` TEXT,
                `confirmed` INTEGER NOT NULL DEFAULT 1,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_style_cards_scope_contactId` " +
                "ON `style_cards` (`scope`, `contactId`)",
        )
        ensureColumn(db, "contact_profiles", "scenarioCode", "scenarioCode TEXT")
    }
}

/**
 * v4 → v5（P2 三级目标 + 策略卡）：新增 goals / goal_decomps / stage_states /
 * strategy_cards / talk_missions 五表；contact_profiles 补 activeCardId。
 * 只建结构不搬数据——目标/卡片均由用户新建或慢车道编译产生。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `goals` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `horizon` INTEGER NOT NULL,
                `parentId` INTEGER,
                `scenarioCode` TEXT,
                `title` TEXT NOT NULL,
                `successCriteria` TEXT,
                `deadlineAt` INTEGER,
                `status` TEXT NOT NULL DEFAULT 'draft',
                `promptCount` INTEGER NOT NULL DEFAULT 0,
                `priority` INTEGER NOT NULL DEFAULT 5,
                `source` TEXT NOT NULL DEFAULT 'user',
                `confirmed` INTEGER NOT NULL DEFAULT 0,
                `failStreak` INTEGER NOT NULL DEFAULT 0,
                `progressNote` TEXT,
                `reviewedAt` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_contactId_status` ON `goals` (`contactId`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_parentId` ON `goals` (`parentId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_contactId_horizon_status` ON `goals` (`contactId`, `horizon`, `status`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `goal_decomps` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `goalId` INTEGER NOT NULL,
                `version` INTEGER NOT NULL DEFAULT 1,
                `kind` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `confirmed` INTEGER NOT NULL DEFAULT 0,
                `supersededBy` INTEGER,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_decomps_goalId_version` ON `goal_decomps` (`goalId`, `version`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stage_states` (
                `contactId` INTEGER NOT NULL,
                `scenarioCode` TEXT NOT NULL,
                `stageCode` TEXT NOT NULL,
                `enteredAt` INTEGER NOT NULL,
                `advanceHits` TEXT NOT NULL DEFAULT '{}',
                `evidence` TEXT,
                PRIMARY KEY(`contactId`, `scenarioCode`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `strategy_cards` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `version` INTEGER NOT NULL DEFAULT 1,
                `scenarioCode` TEXT,
                `payloadJson` TEXT NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'draft',
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_strategy_cards_contactId_status` ON `strategy_cards` (`contactId`, `status`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `talk_missions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `conversationId` INTEGER NOT NULL,
                `contactId` INTEGER,
                `turnMessageId` INTEGER,
                `stageCode` TEXT,
                `goalId` INTEGER,
                `mission` TEXT NOT NULL,
                `actionsJson` TEXT NOT NULL DEFAULT '[]',
                `forbiddenJson` TEXT NOT NULL DEFAULT '[]',
                `signalsJson` TEXT NOT NULL DEFAULT '[]',
                `planBlockJson` TEXT,
                `result` TEXT NOT NULL DEFAULT 'pending',
                `adoptedIndex` INTEGER,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_talk_missions_conversationId_id` ON `talk_missions` (`conversationId`, `id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_talk_missions_contactId` ON `talk_missions` (`contactId`)")

        ensureColumn(db, "contact_profiles", "activeCardId", "activeCardId INTEGER")
    }
}

/**
 * v5 → v6（P3 有向图阶段机）：
 * 1. stage_states 加 roadmapVersion（遵循的 roadmap decomp 版本，null=退化线性链）、
 *    pathTag（私有支线标记，null=主线）、locked（手动锁定，自动边不得移动）；
 * 2. 新增 stage_history 阶段变迁时间线（每联系人×场景 DAO trim 50 条）；
 * 3. session_bindings 加 lastIncomingAt（对方最后来信时间，24h 时序信号）。
 * 旧数据 roadmapVersion=null，行为逐字退化同 v5 线性链。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureColumn(db, "stage_states", "roadmapVersion", "roadmapVersion INTEGER")
        ensureColumn(db, "stage_states", "pathTag", "pathTag TEXT")
        ensureColumn(db, "stage_states", "locked", "locked INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stage_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `scenarioCode` TEXT NOT NULL,
                `fromCode` TEXT,
                `toCode` TEXT NOT NULL,
                `edge` TEXT NOT NULL,
                `reason` TEXT,
                `at` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_stage_history_contactId_scenarioCode_at` " +
                "ON `stage_history` (`contactId`, `scenarioCode`, `at`)",
        )

        ensureColumn(db, "session_bindings", "lastIncomingAt", "lastIncomingAt INTEGER")
    }
}

/** 列不存在时才 ADD COLUMN（PRAGMA table_info 幂等保护，SQLite 无 ADD COLUMN IF NOT EXISTS）。 */
internal fun ensureColumn(
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
