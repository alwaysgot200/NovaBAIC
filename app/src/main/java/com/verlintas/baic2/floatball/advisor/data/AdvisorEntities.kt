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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** 单聊对象 person / 群聊 group。 */
const val CONTACT_TYPE_PERSON = "person"
const val CONTACT_TYPE_GROUP = "group"

/** 我方全局人设的虚拟 contactId（my_ 前缀字段都挂这里）。 */
const val MY_PERSONA_CONTACT_ID = 0L

/**
 * 联系人画像主档。唯一键 = IM 包名 + 首见标识；[mergedInto] 非空表示已软合并
 * （30 天内可经 merge_records 撤销，见方案 §4.3）。
 */
@Entity(
    tableName = "contact_profiles",
    indices = [
        Index(value = ["imPackage", "contactKey"], unique = true),
        Index(value = ["mergedInto"]),
    ],
)
data class ContactProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val imPackage: String,
    val contactKey: String,
    val displayName: String,
    val contactType: String = CONTACT_TYPE_PERSON,
    val goal: String? = null,
    val style: String? = null,
    val myPersonaId: Long? = null,
    val agentId: Long? = null,
    val mergedInto: Long? = null,
    /** 用户手动删档时间（v3）：非空即软删除，识别与列表均不出现；区别于合并。 */
    val deletedAt: Long? = null,
    /** 用户选定的场景包 code（v4）：dating / sales_b2b…；仅用于场景默认风格与后续阶段机。 */
    val scenarioCode: String? = null,
    /** 当前生效的策略卡 id（v5）：null = 回退全局卡或无卡基线。 */
    val activeCardId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 风格卡作用域：[STYLE_SCOPE_CONTACT] 按联系人覆盖；[STYLE_SCOPE_MY_DEFAULT] 全局默认（contactId=0）。 */
const val STYLE_SCOPE_MY_DEFAULT = "my_default"
const val STYLE_SCOPE_CONTACT = "contact"

/**
 * 说话风格卡（v4，方案 §6.2/§6.5）：基调单选 + 意图多选 + 一句自定义补充。
 * 唯一键 (scope, contactId)：联系人缺卡时回退全局卡；[compiledCustomJson] P3 编译流预留，
 * P1 恒为 null、[confirmed] 恒 true（选项与原文即时生效）。
 */
@Entity(
    tableName = "style_cards",
    indices = [Index(value = ["scope", "contactId"], unique = true)],
)
data class StyleCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val scope: String,
    val contactId: Long = 0L,
    val toneCode: String? = null,
    /** 资产 intent code 的 JSON 数组；保存顺序即展示/渲染顺序。 */
    val intentCodes: String = "[]",
    val customNote: String? = null,
    val compiledCustomJson: String? = null,
    val confirmed: Boolean = true,
    val updatedAt: Long = 0L,
)

/** 同一联系人的多个名称（改名/备注/昵称/群内显示名归同一档案）。 */
@Entity(
    tableName = "contact_aliases",
    indices = [
        Index(value = ["contactId"]),
        Index(value = ["alias"]),
        Index(value = ["contactId", "alias"], unique = true),
    ],
)
data class ContactAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    /** 规范化匹配键（NFKC/去空白/小写/去角标）。 */
    val alias: String,
    /** 最近一次命中的原始标题（展示用）。 */
    val rawAlias: String,
    /** ocr / a11y / user。 */
    val source: String = "a11y",
    val lastSeenAt: Long,
    val hitCount: Int = 1,
)

/**
 * 画像结构化字段：一行一字段，tags 类型 value 存 JSON 数组。
 * 复合主键 (contactId, field)；我方字段 field 以 my_ 开头、contactId=0。
 */
@Entity(tableName = "persona_fields", primaryKeys = ["contactId", "field"])
data class PersonaFieldEntity(
    val contactId: Long,
    val field: String,
    val value: String,
    /** llm（萃取草稿）/ user（人工填写或已确认）。 */
    val source: String = "llm",
    val confirmed: Boolean = false,
    /** 萃取依据 OCR 原句；合并冲突时记录被舍弃的旧值。 */
    val evidence: String? = null,
    val updatedAt: Long,
)

/** 用户否决的字段萃取在冷却期内不再重复建议。 */
@Entity(tableName = "extraction_holds", primaryKeys = ["contactId", "field"])
data class ExtractionHoldEntity(
    val contactId: Long,
    val field: String,
    val untilAt: Long,
)

/** 上游会话 ↔ 联系人档案绑定，兼作增量 OCR/萃取的游标存储。 */
@Entity(tableName = "session_bindings")
data class SessionBindingEntity(
    @PrimaryKey val conversationId: Long,
    val contactId: Long,
    val lastOcrFingerprint: String? = null,
    val lastSentMessageId: Long? = null,
    val lastExtractAt: Long? = null,
    val baselineAnchor: String? = null,
    /** 该联系人的滚动摘要（同 contactId 多绑定时以最新行为准，v2 新增）。 */
    val digest: String? = null,
    val digestCoveredTurns: Int = 0,
    val digestUpdatedAt: Long? = null,
    /** 对方最后一次新来信的时间（v6）：24h 静默时序信号用；仅来信文本指纹确实变化时更新。 */
    val lastIncomingAt: Long? = null,
    val createdAt: Long,
)

/**
 * 群成员二级画像（方案 P5）：群档案下的发言人登记。昵称行几何自动识别
 * 精度待真机灰度（§14 开放问题 c），v1 先支持手动登记与关联已有单人档案；
 * [linkedContactId] 指向 person 主档后，该成员可复用其画像/话术（brief 接入后续做）。
 */
@Entity(
    tableName = "group_members",
    indices = [
        Index(value = ["groupContactId"]),
        Index(value = ["groupContactId", "memberKey"], unique = true),
        Index(value = ["linkedContactId"]),
    ],
)
data class GroupMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val groupContactId: Long,
    /** 群内规范化昵称键（ContactNames.normalize）。 */
    val memberKey: String,
    val displayName: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int = 1,
    /** 关联的 person 联系人主档 id；null = 仅有群昵称、未建独立档案。 */
    val linkedContactId: Long? = null,
)

/** 话术库条目：内置资产（builtin）与用户自定义（user）共用一张表。 */
@Entity(
    tableName = "playbooks",
    indices = [Index(value = ["category"]), Index(value = ["source"])],
)
data class PlaybookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String,
    val scenario: String,
    /** 逗号分隔标签，如 "报价,异议,促成"。 */
    val tags: String,
    val content: String,
    val priority: Int = 5,
    val source: String = "user",
)

/**
 * playbooks 的 FTS4 影子表（独立维护，rowid 与 playbooks.id 对齐）。
 * 写入前中文按字插空格，绕开 FTS 默认分词器把整段 CJK 当一个 token 的问题。
 */
@Fts4
@Entity(tableName = "playbooks_fts")
data class PlaybookFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val scenario: String,
    val tags: String,
    val content: String,
)

/** 合并事务快照：30 天内支持一键撤销（方案 §4.3）。 */
@Entity(tableName = "merge_records")
data class MergeRecordEntity(
    @PrimaryKey val fromContactId: Long,
    val intoContactId: Long,
    /** 迁移走的 alias 实体 JSON。 */
    val movedAliasesJson: String,
    /** 迁移走的 conversationId 列表 JSON。 */
    val movedBindingsJson: String,
    /** A 档案合并前 persona_fields JSON。 */
    val fieldSnapshotJson: String,
    /** 合并前 B 已有的 field key 集合 JSON（撤销时清理 B 新增项用）。 */
    val targetFieldKeysJson: String,
    val mergedAt: Long,
)

// ─────────────────────────────── P2：三级目标 + 策略卡 ───────────────────────────────

/** 目标状态：草稿 / 观察期（AI 派生，立即参与回合）/ 生效 / 完成 / 放弃 / 待复盘（failStreak≥3）。 */
const val GOAL_DRAFT = "draft"
const val GOAL_PROVISIONAL = "provisional"
const val GOAL_ACTIVE = "active"
const val GOAL_DONE = "done"
const val GOAL_ABANDONED = "abandoned"
const val GOAL_REVIEW = "review"

/** 目标层级：3=远期愿景 L3 / 2=中期成果 L2 / 1=近期目标 L1。 */
const val HORIZON_L3 = 3
const val HORIZON_L2 = 2
const val HORIZON_L1 = 1

/** goal_decomps.kind：L3→L2 候选 / L2→路线图 / 修炼项。 */
const val DECOMP_OUTCOMES = "outcomes"
const val DECOMP_ROADMAP = "roadmap"
const val DECOMP_PRACTICES = "practices"

/** 策略卡状态：草稿（待审）/ 已确认生效 / 已归档（忽略或被新版本替代）。 */
const val CARD_DRAFT = "draft"
const val CARD_CONFIRMED = "confirmed"
const val CARD_ARCHIVED = "archived"

/** talk_missions.result：待裁 / 推进 / 维持 / 倒退 / 未采纳。 */
const val MISSION_PENDING = "pending"
const val MISSION_ADVANCED = "advanced"
const val MISSION_HELD = "held"
const val MISSION_REGRESSED = "regressed"
const val MISSION_IGNORED = "ignored"

/**
 * 三级目标（v5，方案 §4/§6.5）：L3 远期愿景 / L2 中期成果 / L1 近期目标，同表用 [horizon] 区分、
 * [parentId] 串联（L2→L3、L1→L2）。contactId=0 为跨联系人的理想自我（P2 仅消费不提供管理 UI）。
 * [promptCount] 仅 L2 使用（分屏轻确认展示计数，≤2 沉默归档）；L1 建议只在目标页提示，恒 0。
 */
@Entity(
    tableName = "goals",
    indices = [
        Index(value = ["contactId", "status"]),
        Index(value = ["parentId"]),
        Index(value = ["contactId", "horizon", "status"]),
    ],
)
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val horizon: Int,
    val parentId: Long? = null,
    val scenarioCode: String? = null,
    val title: String,
    val successCriteria: String? = null,
    val deadlineAt: Long? = null,
    val status: String = GOAL_DRAFT,
    val promptCount: Int = 0,
    val priority: Int = 5,
    /** user（手建即确认）/ ai（STRATEGIST/PLANNER 派生，走 provisional）。 */
    val source: String = "user",
    val confirmed: Boolean = false,
    val failStreak: Int = 0,
    val progressNote: String? = null,
    val reviewedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * 目标编译产物（v5）：一个 goal 的多版本 JSON。kind=outcomes/roadmap/practices；
 * 重编 version+1，旧版本 [supersededBy] 指向新版本（保留审计，不删除）。
 */
@Entity(
    tableName = "goal_decomps",
    indices = [Index(value = ["goalId", "version"])],
)
data class GoalDecompEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val goalId: Long,
    val version: Int = 1,
    val kind: String,
    val payloadJson: String,
    val confirmed: Boolean = false,
    val supersededBy: Long? = null,
    val createdAt: Long = 0L,
)

/**
 * 联系人在某场景包下的当前阶段（v5，复合主键）：线性链 P2，advanceHits 为
 * `{signalKey: 命中次数}` JSON。
 *
 * v6 有向图（P3）：[roadmapVersion] 为当前遵循的 roadmap decomp 版本，null = 退化走
 * pack 骨架线性链（=P2 行为）；[pathTag] 为当前所在私有支线标记，null = 主线；
 * [locked] = 1 时自动边不得移动本站，只放行手动改站与 reroute。
 */
@Entity(tableName = "stage_states", primaryKeys = ["contactId", "scenarioCode"])
data class StageStateEntity(
    val contactId: Long,
    val scenarioCode: String,
    val stageCode: String,
    val enteredAt: Long,
    val advanceHits: String = "{}",
    val evidence: String? = null,
    val roadmapVersion: Int? = null,
    val pathTag: String? = null,
    val locked: Boolean = false,
)

/** stage_history.edge：首站初始化 / 顺序前进 / 分叉（经确认）/ 回撤 / 跳站 / 手动 / 重规划。 */
const val STAGE_EDGE_INIT = "init"
const val STAGE_EDGE_ADVANCE = "advance"
const val STAGE_EDGE_BRANCH = "branch"
const val STAGE_EDGE_RETREAT = "retreat"
const val STAGE_EDGE_SKIP = "skip"
const val STAGE_EDGE_MANUAL = "manual"
const val STAGE_EDGE_REROUTE = "reroute"

/**
 * 阶段变迁时间线（v6，P3）：任何移动（含 init/manual/reroute）都留一行，
 * 每联系人×场景滚动保留最近 50 条（DAO trim）。
 */
@Entity(
    tableName = "stage_history",
    indices = [Index(value = ["contactId", "scenarioCode", "at"])],
)
data class StageHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val scenarioCode: String,
    val fromCode: String? = null,
    val toCode: String,
    /** STAGE_EDGE_* 常量。 */
    val edge: String,
    val reason: String? = null,
    val at: Long,
)

/**
 * 策略卡（v5，方案 §6.6）：CardCompiler 产出的五段 JSON 草稿，用户 ✓ 后 confirmed 生效。
 * contactId=0 为全局默认卡；每次重编新起一行 version+1，旧 confirmed 卡持续生效到新卡确认。
 */
@Entity(
    tableName = "strategy_cards",
    indices = [Index(value = ["contactId", "status"])],
)
data class StrategyCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val version: Int = 1,
    val scenarioCode: String? = null,
    val payloadJson: String,
    val status: String = CARD_DRAFT,
    val createdAt: Long = 0L,
)

/**
 * 本轮任务令台账（v5，方案 §5/§6.6）：每个策略激活回合一条，[result] 由 Judge 回填。
 * 每联系人/每会话各保留最近 60 条（DAO trim）。[adoptedIndex] 为 P3 行内纠偏预留，P2 恒 null。
 */
@Entity(
    tableName = "talk_missions",
    indices = [
        Index(value = ["conversationId", "id"]),
        Index(value = ["contactId"]),
    ],
)
data class TalkMissionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    val contactId: Long? = null,
    val turnMessageId: Long? = null,
    val stageCode: String? = null,
    val goalId: Long? = null,
    val mission: String,
    val actionsJson: String = "[]",
    val forbiddenJson: String = "[]",
    val signalsJson: String = "[]",
    val planBlockJson: String? = null,
    val result: String = MISSION_PENDING,
    val adoptedIndex: Int? = null,
    val createdAt: Long = 0L,
)
