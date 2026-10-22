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
        DirectorLogEntity::class,
        ActionPlayEntity::class,
        BriefOutputEntity::class,
        ProactivePolicyEntity::class,
        PersonalMaterialEntity::class,
        SelfPersonaEntity::class,
        TopicPlanEntity::class,
        ChatChannelEntity::class,
        EmotionLexiconEntity::class,
        EmotionEventEntity::class,
        EmotionBaselineEntity::class,
    ],
    version = 14,
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

    abstract fun directorLogDao(): DirectorLogDao

    abstract fun actionPlayDao(): ActionPlayDao

    abstract fun briefOutputDao(): BriefOutputDao

    abstract fun proactivePolicyDao(): ProactivePolicyDao

    abstract fun personalMaterialDao(): PersonalMaterialDao

    abstract fun selfPersonaDao(): SelfPersonaDao

    abstract fun topicPlanDao(): TopicPlanDao

    abstract fun chatChannelDao(): ChatChannelDao

    abstract fun emotionLexiconDao(): EmotionLexiconDao

    abstract fun emotionEventDao(): EmotionEventDao

    abstract fun emotionBaselineDao(): EmotionBaselineDao
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

/**
 * v6 → v7（多代理协作自动参谋方案 §6，M0 期）：新增四表，只建结构不搬数据——
 * 1. director_logs：Director 决策台账（M0 接线，每联系人滚动 200 条）；
 * 2. action_plays：行动建议（M1 ActionPlanner 写入）；
 * 3. brief_outputs：简报产物（BriefCompiler 慢车道，每联系人滚动 10 份）；
 * 4. proactive_policies：主动频控配置（M2 读写）。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `director_logs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `conversationId` INTEGER,
                `trigger` TEXT NOT NULL,
                `route` TEXT NOT NULL,
                `reason` TEXT,
                `turnMessageId` INTEGER,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_director_logs_contactId_id` " +
                "ON `director_logs` (`contactId`, `id`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `action_plays` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `goalId` INTEGER,
                `stageCode` TEXT,
                `kind` TEXT NOT NULL,
                `stratagemCode` TEXT,
                `payloadJson` TEXT NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'draft',
                `resultNote` TEXT,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_action_plays_contactId_status` " +
                "ON `action_plays` (`contactId`, `status`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `brief_outputs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `rangeStart` INTEGER NOT NULL,
                `rangeEnd` INTEGER NOT NULL,
                `contentJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_brief_outputs_contactId_createdAt` " +
                "ON `brief_outputs` (`contactId`, `createdAt`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `proactive_policies` (
                `contactId` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL DEFAULT 0,
                `dailyCap` INTEGER NOT NULL DEFAULT 1,
                `minGapHours` INTEGER NOT NULL DEFAULT 48,
                `quietStart` TEXT NOT NULL DEFAULT '22:30',
                `quietEnd` TEXT NOT NULL DEFAULT '08:30',
                `preferSmalltalk` INTEGER NOT NULL DEFAULT 1,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`contactId`)
            )
            """.trimIndent(),
        )
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

/**
 * v7 → v8（UltraAdvisor 修复 F2，方案 §5.6 双轨「记录面」补全）：director_logs
 * 加 replanHint（0/1）。Director 已计算的重规划观察信号（failStreak≥3/连续
 * retreat×2）此前只随决策内存存在、未落台账，回放缺一轨。ensureColumn 幂等
 * 补列，历史行默认 0，不破坏任何既有数据；表结构其余不动。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureColumn(db, "director_logs", "replanHint", "replanHint INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v8 → v9（UltraAdvisor 修复 F6/F7，M1 方案 §7.2 素材三源之② + §5.6 action_hint
 * 旁路）：① 新表 personal_materials（我的素材库，fingerprint 唯一索引去重）；
 * ② director_logs 加 actionHint 列（观察性行动建议信号，与 v8 replanHint 同为
 * 旁路记录面）。ensureColumn 幂等补列、建表 IF NOT EXISTS，历史行 actionHint
 * 恒 0、素材表为空，不搬不删任何既有数据。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `personal_materials` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `content` TEXT NOT NULL,
                `tag` TEXT,
                `fingerprint` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `lastUsedAt` INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_personal_materials_fingerprint` " +
                "ON `personal_materials` (`fingerprint`)",
        )
        ensureColumn(db, "director_logs", "actionHint", "actionHint INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v9 → v10（UltraAdvisor v2.2 场景归属与废话分层，方案 §5，幂等迁移保数据）：
 * 1. contact_profiles 加七列：isGroup（群标识，方案 §1.4）、engagement（维持度三档
 *    默认 reactive，§2.6）、identity/chatStyle/closeness（对方风格三标签，§4.1）、
 *    heatScore（反馈热度分默认 50 中性，§4.3）、selfFacetJson/selfFacetPendingJson
 *    （我方关系侧面 + 合并差异待确认区，§2.4/§2.5）；
 * 2. 新表 self_personas（全局我方人设标签库，建档双建的模板源，§2.4）；
 * 3. session_bindings 加 scene（会话归属 chat/non_chat，§1）；
 * 4. 新表 topic_plans（每联系人×每周一条的废话方向计划，V-C 消费，§4.4）。
 * 列走 ensureColumn 幂等补列（Room 2.8 校验 defaultValue，实体 @ColumnInfo 与
 * DDL DEFAULT 逐字对齐），建表 IF NOT EXISTS，不搬不删任何既有数据。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureColumn(db, "contact_profiles", "isGroup", "isGroup INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "contact_profiles", "engagement", "engagement TEXT NOT NULL DEFAULT 'reactive'")
        ensureColumn(db, "contact_profiles", "identity", "identity TEXT")
        ensureColumn(db, "contact_profiles", "chatStyle", "chatStyle TEXT")
        ensureColumn(db, "contact_profiles", "closeness", "closeness INTEGER")
        ensureColumn(db, "contact_profiles", "heatScore", "heatScore INTEGER DEFAULT 50")
        ensureColumn(db, "contact_profiles", "selfFacetJson", "selfFacetJson TEXT")
        ensureColumn(db, "contact_profiles", "selfFacetPendingJson", "selfFacetPendingJson TEXT")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `self_personas` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `tag` TEXT NOT NULL,
                `note` TEXT,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_self_personas_tag` ON `self_personas` (`tag`)",
        )

        ensureColumn(db, "session_bindings", "scene", "scene TEXT NOT NULL DEFAULT 'chat'")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `topic_plans` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `cycleStart` INTEGER NOT NULL,
                `directionsJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_topic_plans_contactId_cycleStart` " +
                "ON `topic_plans` (`contactId`, `cycleStart`)",
        )
    }
}

/**
 * v10 → v11（隐私闸门与事实记忆方案 F1，方案 §3.2）：contact_profiles 加
 * privacyPolicy 列（联系人级隐私策略，TEXT NOT NULL DEFAULT 'handoff'）。
 * ensureColumn 幂等补列，历史行默认 handoff（交主人，宁严勿松），不搬不删
 * 任何既有数据；contact_facts 表留 F2 迁移。
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureColumn(
            db, "contact_profiles", "privacyPolicy",
            "privacyPolicy TEXT NOT NULL DEFAULT 'handoff'",
        )
    }
}

/**
 * v11 → v12（聊天策略裁量层 T1-a，2026-10-21 总方案 §四）：contact_profiles 加
 * talkerStrategyJson（撒谎/许愿/举例三维档位，缺键=默认档 D1「全部自评」）与
 * stageIntent（主人手填的阶段意图）；privacyPolicy 扩第三档 assess 无需迁移
 * （列仍 TEXT，归一在 normalizePrivacyPolicy）。ensureColumn 幂等补列，
 * 不搬不删任何既有数据；contact_facts 表留 F2 迁移（v13）。
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureColumn(
            db, "contact_profiles", "talkerStrategyJson",
            "talkerStrategyJson TEXT NOT NULL DEFAULT '{}'",
        )
        ensureColumn(
            db, "contact_profiles", "stageIntent",
            "stageIntent TEXT NOT NULL DEFAULT ''",
        )
    }
}

/**
 * v12 → v13（术语铁律 §1.5，2026-10-21）：新表 chat_channels（聊天会话行：
 * 哪个 APP 的哪个聊天对象，不存聊天内容）。存量迁移：每个存活档案的
 * (imPackage, contactKey, displayName) 生成一条聊天会话行——主 IM 自动
 * 在册；此后识别/手动绑定 upsert，跨 APP（企微/钉钉/飞书…）的同一人
 * 追加行即归并到同一联系人。建表 IF NOT EXISTS，不搬不删既有数据。
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_channels` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `imPackage` TEXT NOT NULL,
                `channelKey` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `lastSeenAt` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_channels_contactId` ON `chat_channels` (`contactId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_channels_imPackage_channelKey` " +
                "ON `chat_channels` (`imPackage`, `channelKey`)",
        )
        // 存量搬运：contact_profiles.contactKey 是「pkg:规范化名」复合键，剥掉
        // 「pkg:」前缀才是该 APP 内的 channelKey（substr 跳过 imPackage 长度+1）。
        db.execSQL(
            """
            INSERT OR IGNORE INTO `chat_channels`
                (`contactId`, `imPackage`, `channelKey`, `displayName`, `lastSeenAt`, `createdAt`)
            SELECT `id`, `imPackage`, substr(`contactKey`, length(`imPackage`) + 2),
                   `displayName`, `updatedAt`, `createdAt`
            FROM `contact_profiles`
            WHERE `mergedInto` IS NULL AND `deletedAt` IS NULL
            """.trimIndent(),
        )
    }
}

/**
 * v13 → v14（情感分析师 E-c，方案 §4.3/§4.7）：新表 emotion_lexicon（情绪信号
 * 词条，可运营数据资产）、emotion_events（判定事件台账，双索引 + 90 天保留）、
 * emotion_baseline（联系人 30 天滚动基线，一联系人一行）。建表 IF NOT EXISTS，
 * 不搬不删任何既有数据；词条内容由 EmotionLexiconSeeder 按身份补缺播种，
 * 用户改动保护见 planSeed（绝不覆盖）。DDL 与实体逐字对齐（Room 2.8 开库校验）。
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `emotion_lexicon` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `term` TEXT NOT NULL,
                `library` INTEGER NOT NULL,
                `labelsJson` TEXT NOT NULL,
                `polarity` INTEGER NOT NULL,
                `intensityDelta` INTEGER NOT NULL,
                `negatable` INTEGER NOT NULL,
                `scenario` TEXT,
                `scopeContactId` INTEGER,
                `weight` REAL NOT NULL,
                `enabled` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `note` TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_emotion_lexicon_term_library_scopeContactId` " +
                "ON `emotion_lexicon` (`term`, `library`, `scopeContactId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `emotion_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `contactId` INTEGER NOT NULL,
                `conversationId` INTEGER,
                `turnMessageId` INTEGER,
                `state` TEXT NOT NULL,
                `labelsJson` TEXT NOT NULL,
                `intensity` INTEGER NOT NULL,
                `confidence` TEXT NOT NULL,
                `evidenceJson` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_emotion_events_contactId_createdAt` " +
                "ON `emotion_events` (`contactId`, `createdAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_emotion_events_createdAt` " +
                "ON `emotion_events` (`createdAt`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `emotion_baseline` (
                `contactId` INTEGER NOT NULL,
                `avgLen` REAL NOT NULL,
                `avgIntervalMs` INTEGER NOT NULL,
                `shortReplyRatio` REAL NOT NULL,
                `activeFollowRatio` REAL NOT NULL,
                `sampleCount` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`contactId`)
            )
            """.trimIndent(),
        )
    }
}
