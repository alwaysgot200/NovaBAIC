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

/** 维持度三档（v10，UltraAdvisor v2.2 方案 §2.6）：联系人的第一手自动参与开关。 */
const val ENGAGEMENT_PROACTIVE = "proactive"
const val ENGAGEMENT_REACTIVE = "reactive"
const val ENGAGEMENT_MUTE = "mute"

/** 联系人级隐私策略三档（F1 两档 + T1 扩第三档 assess，2026-10-21 总方案 §三 D1）。 */
const val PRIVACY_POLICY_HANDOFF = "handoff"
const val PRIVACY_POLICY_BYPASS = "bypass"
const val PRIVACY_POLICY_ASSESS = "assess"

/**
 * 隐私策略归一：显式 bypass/assess 生效，其余（含 null/历史脏值）一律归
 * handoff——默认交主人，宁严勿松。assess=自评档：军师逐回合许可+审查官
 * 验稿+轻通报后才可披露（执法细节 T1-c/d 接线）。
 */
fun normalizePrivacyPolicy(policy: String?): String =
    when (policy) {
        PRIVACY_POLICY_BYPASS -> PRIVACY_POLICY_BYPASS
        PRIVACY_POLICY_ASSESS -> PRIVACY_POLICY_ASSESS
        else -> PRIVACY_POLICY_HANDOFF
    }

/** 会话归属场景（v10，方案 §1）：session_bindings.scene；NON_CHAT 会话零人设管线。 */
const val SCENE_CHAT = "chat"
const val SCENE_NON_CHAT = "non_chat"

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
    /**
     * 群标识（v10，方案 §1.4/§2.1）：自动判定（EntityResolver 多发言人）与画像页
     * 手动切换共用一个属性；与 [contactType] 的 group 值保持同义（markGroup 同步写）。
     */
    @ColumnInfo(defaultValue = "0")
    val isGroup: Boolean = false,
    /**
     * 维持度三档（v10，方案 §2.6）：proactive/reactive/mute，默认 reactive——
     * 自动建卡不主动，只有用户手动升级才进入主动维持；engagement 是
     * ProactivePolicy（dailyCap/minGap/quiet）之上的第一手开关。
     */
    @ColumnInfo(defaultValue = "reactive")
    val engagement: String = ENGAGEMENT_REACTIVE,
    /**
     * 联系人级隐私策略（v11，隐私闸门方案 §3.2）：handoff=问到隐私走接管链
     * 交主人回复（默认）；bypass=继续聊天但回避该域并轻通报主人。域判定由
     * 隐私专员（experts/privacy）完成，本列只决定处置档。
     */
    @ColumnInfo(defaultValue = PRIVACY_POLICY_HANDOFF)
    val privacyPolicy: String = PRIVACY_POLICY_HANDOFF,
    /**
     * 聊天手段策略三维档位（v12，T1-a 总方案 §四）：撒谎/许愿/举例，缺键=默认档
     * （D1 拍板「全部自评」）；第四维隐私披露沿用 [privacyPolicy] 单点真源不入本列。
     * 编解码在 experts/strategist/TalkerStrategyCodec，畸形 JSON 回默认档。
     */
    @ColumnInfo(defaultValue = "{}")
    val talkerStrategyJson: String = "{}",
    /** 本阶段意图（v12，T1-a，D5 拍板主人手填）：军师渲染「本阶段你要推进：…」的方针来源；空=无正向方针。 */
    @ColumnInfo(defaultValue = "")
    val stageIntent: String = "",
    /** 对方身份类型（v10，方案 §4.1）：领导/客户/同事/朋友/家人/暧昧对象/其他；空 = 待萃取。 */
    val identity: String? = null,
    /** 对方聊天风格（v10，方案 §4.1）：严肃/随和/幽默/热情/冷淡；空 = 待萃取。 */
    val chatStyle: String? = null,
    /** 亲疏等级（v10，方案 §4.1）：0~3（陌生/熟识/亲近/亲密）；null = 待萃取。 */
    val closeness: Int? = null,
    /** 反馈热度分（v10，方案 §4.3）：0~100，迁移历史行默认 50（中性）；null = 无信号。 */
    @ColumnInfo(defaultValue = "50")
    val heatScore: Int? = null,
    /** 我方在该关系中的侧面人设标签集（v10，方案 §2.4 双方人设）：JSON 数组；建档时从 self_personas 复制为起点。 */
    val selfFacetJson: String? = null,
    /**
     * 我方侧面待确认差异标签（v10，方案 §2.5 合并协议第 2 步）：被并档案的
     * selfFacet 与主档案的差异标签暂存于此（JSON 数组），画像页核阅转正后才
     * 并入 [selfFacetJson]，不静默丢弃、也不直接生效。
     */
    val selfFacetPendingJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 说话风格卡作用域：[STYLE_SCOPE_CONTACT] 按联系人覆盖；[STYLE_SCOPE_MY_DEFAULT] 全局默认（contactId=0）。 */
const val STYLE_SCOPE_MY_DEFAULT = "my_default"
const val STYLE_SCOPE_CONTACT = "contact"

/**
 * 聊天会话行（术语铁律 §1.5，2026-10-21）：IM 里「哪个 APP 的哪个聊天对象」的
 * 记录——**不存聊天内容**，只存 APP 包名 + 对象标识，供 RPA 对口打开与识别
 * 跨 APP 归档（同一联系人在微信/企微/钉钉…各有一条聊天会话行）。
 * 唯一键 (imPackage, channelKey)：一个 APP 内同一聊天对象恒归一个联系人；
 * (imPackage, channelKey) 重复 upsert 时按 REPLACE 更新归属与显示名。
 */
@Entity(
    tableName = "chat_channels",
    indices = [
        Index(value = ["contactId"]),
        Index(value = ["imPackage", "channelKey"], unique = true),
    ],
)
data class ChatChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    /** IM 包名（com.tencent.mm / com.tencent.wework / com.alibaba.android.rimet…）。 */
    val imPackage: String,
    /** 该 APP 内的规范化聊天对象键（ContactNames.normalize(窗口标题/昵称)）。 */
    val channelKey: String,
    /** 该 APP 内的显示名（RPA 搜索导航用）。 */
    val displayName: String,
    val lastSeenAt: Long,
    val createdAt: Long = 0L,
)

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
    /**
     * 会话归属场景（v10，方案 §1.2）：chat（默认）/ non_chat。非聊天屏的孤立
     * 参谋会话在此标记，会话复用只同场景互串（V-B 消费）；联系人绑定行恒 chat。
     */
    @ColumnInfo(defaultValue = "chat")
    val scene: String = SCENE_CHAT,
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

/** goal_decomps.kind：L3→L2 候选 / L2→路线图。 */
const val DECOMP_OUTCOMES = "outcomes"
const val DECOMP_ROADMAP = "roadmap"

/** 策略卡状态：草稿（待审）/ 已确认生效 / 已归档（忽略或被新版本替代）。 */
const val CARD_DRAFT = "draft"
const val CARD_CONFIRMED = "confirmed"
const val CARD_ARCHIVED = "archived"

/** talk_missions.result：待裁 / 推进 / 维持 / 倒退 / 未采纳 / 保温信息回合。 */
const val MISSION_PENDING = "pending"
const val MISSION_ADVANCED = "advanced"
const val MISSION_HELD = "held"
const val MISSION_REGRESSED = "regressed"
const val MISSION_IGNORED = "ignored"

/**
 * M1 保温闲聊回合的任务令终态（方案 D4）：计入台账（话题指纹去重依赖它）
 * 但永不 pending、不参与阶段/目标裁决——Judge 见此标记直接豁免 settle。
 */
const val MISSION_INFORMATIONAL = "informational"

/**
 * 保温任务令文本前缀（M1，方案 D4）：`warm_chat|<话题>|<模型任务令>`。
 * 回放区分与指纹解码据此识别；与 [MISSION_INFORMATIONAL] 成对使用。
 */
const val WARM_CHAT_MISSION_PREFIX = "warm_chat|"

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

// ─────────────────────────────── M0：Director 调度中枢 + 简报 + 行动建议 ───────────────────────────────

/** director_logs.trigger：incoming=对方来信（连续模式/通知）；proactive=主动时机；scheduled=周期检点；manual=用户手动。 */
const val TRIGGER_INCOMING = "incoming"
const val TRIGGER_PROACTIVE = "proactive"
const val TRIGGER_SCHEDULED = "scheduled"
const val TRIGGER_MANUAL = "manual"

/** M3-a 自动档发送事件（ActionDispatcher 落 director_logs 用，追加式常量）。 */
const val TRIGGER_AUTO = "auto"

/**
 * director_logs.route：reply=现有主链路；smalltalk=保温闲聊；replan_hint/action_hint=建议条；
 * brief=简报；noop=不动。guide 已按 v2 方案 D6 移出路由表（推进意图 T2 后才产生，
 * Director 前置拿不到）；历史台账行中的 guide 值仅作历史记录保留。
 */
const val ROUTE_REPLY = "reply"
const val ROUTE_SMALL_TALK = "smalltalk"
const val ROUTE_REPLAN_HINT = "replan_hint"
const val ROUTE_ACTION_HINT = "action_hint"
const val ROUTE_BRIEF = "brief"
const val ROUTE_NOOP = "noop"

/** 2026-10-20 接管通报方案 §5：接管型来信（答案只有用户本人持有），转通报不自动发送。 */
const val ROUTE_HANDOFF = "handoff"

/** action_plays.kind：行动建议类型（方案 §6）：约会 / 礼品 / 拜访 / 关怀 / 自定义。 */
const val ACTION_KIND_MEETUP = "meetup"
const val ACTION_KIND_GIFT = "gift"
const val ACTION_KIND_VISIT = "visit"
const val ACTION_KIND_CARE = "care"
const val ACTION_KIND_CUSTOM = "custom"

/** action_plays.status：草稿（待采纳）/ 已采纳 / 已完成 / 已放弃。 */
const val ACTION_STATUS_DRAFT = "draft"
const val ACTION_STATUS_ADOPTED = "adopted"
const val ACTION_STATUS_DONE = "done"
const val ACTION_STATUS_DROPPED = "dropped"

/** brief_outputs.kind：简报粒度：weekly / monthly / onDemand。 */
const val BRIEF_KIND_WEEKLY = "weekly"
const val BRIEF_KIND_MONTHLY = "monthly"
const val BRIEF_KIND_ON_DEMAND = "onDemand"

/**
 * 调度日志（v7，方案 §6）：Director 每次决策一行，每联系人滚动保留最近
 * 200 条（[DirectorLogDao.trimFor]）。M0 仅 incoming/manual 两路触发；
 * proactive/scheduled 由 M2 起接入。可从本表回放验证路由规则（方案 §12）。
 */
@Entity(tableName = "director_logs", indices = [Index(value = ["contactId", "id"])])
data class DirectorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val conversationId: Long? = null,
    /** TRIGGER_* 常量。 */
    val trigger: String,
    /** ROUTE_* 常量。 */
    val route: String,
    /** 命中的规则键（如 incoming_default），可复盘路由依据。 */
    val reason: String? = null,
    val turnMessageId: Long? = null,
    /**
     * 观察性重规划信号（v8，方案 §5.6 记录面）：failStreak≥3 或连续 retreat×2，
     * 与 P3 rerouteHint 同源——那一轨是分屏按钮（交互面），本列落台账（记录面，
     * 供回放与周期检点消费），不改变任何路由与控制流。历史行迁移后恒 0。
     */
    val replanHint: Boolean = false,
    /**
     * 观察性行动建议信号（v9，方案 §5.6 action_hint 旁路）：阶段驻留超过
     * DirectorRoutes.ACTION_HINT_DWELL_DAYS 时的「行动窗口成熟」标记，只落
     * 台账不占路由；UI 仅在用户打开 GoalTab 时消费（行动建议横幅）。历史行
     * 迁移后恒 0。
     */
    val actionHint: Boolean = false,
    val createdAt: Long = 0L,
)

/**
 * 行动建议（v7，方案 §6，M1 ActionPlanner 产物）：口头聊天外的行动策划
 * （约会/礼品/拜访），用户采纳驱动状态流转；payload 为自包含 JSON。
 */
@Entity(tableName = "action_plays", indices = [Index(value = ["contactId", "status"])])
data class ActionPlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val goalId: Long? = null,
    val stageCode: String? = null,
    /** ACTION_KIND_* 常量。 */
    val kind: String,
    /** 谋略库编号（action_stratagems.json，M1 分发）；null = 库外定制。 */
    val stratagemCode: String? = null,
    /** {title,rationale,steps[],cost,risk,prereq} JSON。 */
    val payloadJson: String,
    val status: String = ACTION_STATUS_DRAFT,
    val resultNote: String? = null,
    val createdAt: Long = 0L,
)

/**
 * 简报产物（v7，方案 §6，BriefCompiler 慢车道）：周报/月报/按需，每联系人
 * 每 kind 滚动保留最近 10 份（[BriefOutputDao.trimFor]）。
 */
@Entity(tableName = "brief_outputs", indices = [Index(value = ["contactId", "createdAt"])])
data class BriefOutputEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    /** BRIEF_KIND_* 常量。 */
    val kind: String,
    /** 统计区间（毫秒，含头不含尾）。 */
    val rangeStart: Long,
    val rangeEnd: Long,
    /** {stagePosition,recentEvents[],goalProgress,risks,nextMoves[]} JSON。 */
    val contentJson: String,
    val createdAt: Long = 0L,
)

/**
 * 主动策略（v7，方案 §6，M2 ProactivePolicy 读写）：每联系人一行的频控配置。
 * 「发不发」由此表确定性裁决，LLM 无权决定（方案 §7.3 频率纪律）；M0 仅建表。
 */
@Entity(tableName = "proactive_policies")
data class ProactivePolicyEntity(
    @PrimaryKey val contactId: Long,
    val enabled: Boolean = false,
    /** 每日主动回合上限。 */
    val dailyCap: Int = 1,
    /** 两次主动的最小间隔（小时）。 */
    val minGapHours: Int = 48,
    /** 静默时段 HH:mm（24h 制）；跨零点区间由读取方判断。 */
    val quietStart: String = "22:30",
    val quietEnd: String = "08:30",
    val preferSmalltalk: Boolean = true,
    val updatedAt: Long = 0L,
)

/**
 * 我的素材库（v9，M1 方案 §7.2 素材三源之②「用户投喂」）：用户录入的趣事/
 * 见闻/资源，作为保温选题的鲜活源（比静态种子鲜活、但相关度弱于对方画像
 * 钩子与共同经历，选题优先级见 [com.verlintas.baic2.floatball.director.TopicSource]）。
 * [fingerprint] 复用 TopicEngine 话题指纹算法（空白规范化），同内容素材以唯一
 * 索引去重（重复录入按 REPLACE 以最新为准）；[lastUsedAt] 在素材被选题后标记，
 * 供选题侧并轨进话题冷却防短期重聊。
 */
@Entity(tableName = "personal_materials", indices = [Index(value = ["fingerprint"], unique = true)])
data class PersonalMaterialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 素材正文（趣事/见闻/资源等，保温回合的话题本体）。 */
    val content: String,
    /** 可选分类标签（用户自由输入，如「趣事/见闻/资源」；仅展示用）。 */
    val tag: String? = null,
    /** TopicEngine.fingerprintOf(content)：同内容去重键。 */
    val fingerprint: String,
    val createdAt: Long = 0L,
    /** 最近一次被保温选题采用的时间；从未被选用为 null。 */
    val lastUsedAt: Long? = null,
)

// ─────────────────────── v10：双方人设 + 场景归属（UltraAdvisor v2.2 方案 §2.4/§5） ───────────────────────

/**
 * 全局我方人设标签库（v10，方案 §2.4）：「我的 → 人设」页维护的模板源。
 * 建档即双建时把全库标签复制为新档案的 selfFacetJson 起点；此后各关系侧面
 * 独立增删改，不随全局变化自动同步。tag 唯一索引去重，重复录入按 REPLACE 以最新为准。
 */
@Entity(tableName = "self_personas", indices = [Index(value = ["tag"], unique = true)])
data class SelfPersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 我方标签本体（如「博学」「风趣」「办事周到」），唯一键。 */
    val tag: String,
    /** 可选备注：标签的语境/边界说明。 */
    val note: String? = null,
    val createdAt: Long = 0L,
)

/**
 * 周期废话方向计划（v10，方案 §4.4，V-C Planner 产出）：每联系人×每周一条，
 * (contactId, cycleStart) 唯一索引支撑 REPLACE upsert；directionsJson 为方向 2~3 条
 * 的 JSON 数组，TopicEngine 选题优先消费、无方向退回四源（本版本只建表+DAO）。
 */
@Entity(
    tableName = "topic_plans",
    indices = [Index(value = ["contactId", "cycleStart"], unique = true)],
)
data class TopicPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    /** 周期起点（毫秒，周检点的当日零点），与周检点节奏对齐。 */
    val cycleStart: Long,
    /** 废话方向 JSON 数组（如 ["行业见闻","工作观察"]）。 */
    val directionsJson: String = "[]",
    val createdAt: Long = 0L,
)
