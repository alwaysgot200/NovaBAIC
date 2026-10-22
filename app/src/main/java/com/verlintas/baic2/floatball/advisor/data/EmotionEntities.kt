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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.verlintas.baic2.floatball.advisor.emotion.EmotionBaseline
import com.verlintas.baic2.floatball.advisor.emotion.EmotionJudge
import com.verlintas.baic2.floatball.advisor.emotion.EmotionLexiconEntry
import com.verlintas.baic2.floatball.advisor.emotion.EmotionTrendPoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// ───────────────────────────── 情感分析师三表（DB v14，方案 §4.3/§4.7） ─────────────────────────────

/** emotion_events.state 三档（EmotionJudge.EmotionState 落库口径）。 */
const val EMOTION_STATE_CONFIRMED = "confirmed"
const val EMOTION_STATE_WATCH = "watch"
const val EMOTION_STATE_CALM = "calm"

/** emotion_events.confidence 置信三档。 */
const val EMOTION_CONFIDENCE_HIGH = "high"
const val EMOTION_CONFIDENCE_MEDIUM = "medium"
const val EMOTION_CONFIDENCE_LOW = "low"

/**
 * emotion_events.reason 台账键（`emotion_*` 家族，方案 §七 E-d）：
 * 词库判定落 [EMOTION_REASON_JUDGE]；LLM 精判复核落 [EMOTION_REASON_REFINE]；
 * 审查官情绪红线降级（E-f）落 `emotion_veto`——同族键可按前缀检索回放。
 */
const val EMOTION_REASON_JUDGE = "emotion_judge"
const val EMOTION_REASON_REFINE = "emotion_refine"

/** 审查官情绪红线降级（E-f，方案 §4.5 行 11）：盛怒稿被拦下换安抚稿时落此键。 */
const val EMOTION_REASON_VETO = "emotion_veto"

/**
 * 情绪信号词条（方案 §4.3.2）：**可运营数据资产**——用户可查看/增补/调权重/停用/
 * 按联系人限定；AI 挖词与判错反馈只产生人审建议，入库以 source 区分。
 * (term, library, scopeContactId) 为身份，播种与人工增补都按它判重。
 */
@Entity(
    tableName = "emotion_lexicon",
    indices = [Index(value = ["term", "library", "scopeContactId"], unique = true)],
)
data class EmotionLexiconEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val term: String,
    /** 子库 1-8（EmotionLibraries）。 */
    val library: Int,
    /** 情绪标签 JSON 数组（EmotionLabels 口径，可多标签）。 */
    val labelsJson: String = "[]",
    val polarity: Int = 0,
    val intensityDelta: Int = 0,
    val negatable: Boolean = false,
    /** 子库 7 场景包 code 限定；null = 全场景。 */
    val scenario: String? = null,
    /** 限定联系人生效；null = 全局。 */
    val scopeContactId: Long? = null,
    /** 判错反馈落点（0..2），内置 1.0、AI 挖掘初始 0.8。 */
    val weight: Float = 1f,
    val enabled: Boolean = true,
    /** builtin / user / ai_mined。 */
    val source: String = "builtin",
    val note: String = "",
)

/**
 * 情绪判定事件（方案 §4.7）：每回合判定结果落台账，双索引供联系人时间线与
 * 90 天保留清理（D4，deleteOlderThan）。证据链 JSON 供分屏"点开证据链"回放。
 */
@Entity(
    tableName = "emotion_events",
    indices = [
        Index(value = ["contactId", "createdAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class EmotionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val conversationId: Long? = null,
    val turnMessageId: Long? = null,
    /** EMOTION_STATE_*。 */
    val state: String,
    /** 标签得分 JSON 数组 [{label,score}]。 */
    val labelsJson: String = "[]",
    /** 判定强度 1..5。 */
    val intensity: Int = 1,
    /** EMOTION_CONFIDENCE_*。 */
    val confidence: String,
    /** 证据链 JSON 数组（EmotionJudge.Evidence 口径）。 */
    val evidenceJson: String = "[]",
    val reason: String = "",
    val createdAt: Long = 0L,
)

// ───────────────────────── 词库挖掘待审（E-g，方案 §4.7 运营闭环） ─────────────────────────

/** emotion_mining_candidates.status 三档：待审 / 采纳 / 拒绝（拒绝行保留=拒绝记忆）。 */
const val EMOTION_MINING_PENDING = "pending"
const val EMOTION_MINING_ADOPTED = "adopted"
const val EMOTION_MINING_REJECTED = "rejected"

/**
 * AI 挖词候选（方案 §4.7 词库运营闭环）：模型**只能提案**——挖出的词永远先进
 * 本表待审，主人在词库管理页点采纳才入 emotion_lexicon（source=ai_mined、
 * weight 初始 0.8）；拒绝的行以 [EMOTION_MINING_REJECTED] 保留，供同类建议
 * 不再提（拒绝记忆）。term 全局唯一去重。
 */
@Entity(
    tableName = "emotion_mining_candidates",
    indices = [Index(value = ["term"], unique = true)],
)
data class EmotionMiningCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val term: String,
    /** 建议标签 JSON 数组（EmotionLabels 口径）。 */
    val labelsJson: String = "[]",
    /** 挖掘理由（给主人审核看）。 */
    val reason: String = "",
    /** 来源联系人（人群差异线索）；null = 全局候选。 */
    val contactId: Long? = null,
    /** EMOTION_MINING_*。 */
    val status: String = EMOTION_MINING_PENDING,
    val createdAt: Long = 0L,
)

/**
 * 联系人 30 天滚动基线（方案 §4.4 原则 2）：一联系人一行，慢车道滚动重算
 * （EmotionBaselineCalculator.calculate）后 upsert。
 */
@Entity(tableName = "emotion_baseline")
data class EmotionBaselineEntity(
    @PrimaryKey val contactId: Long,
    val avgLen: Double = 0.0,
    val avgIntervalMs: Long = 0L,
    val shortReplyRatio: Double = 0.0,
    val activeFollowRatio: Double = 0.0,
    val sampleCount: Int = 0,
    val updatedAt: Long = 0L,
)

// ───────────────────────────── 领域 ↔ 落库 映射（纯函数） ─────────────────────────────

private val labelJson = Json { ignoreUnknownKeys = true }

/** 标签列表 → JSON 数组文本。 */
fun encodeEmotionLabels(labels: List<String>): String =
    labelJson.encodeToString(ListSerializer(String.serializer()), labels)

/** JSON 数组文本 → 标签列表（畸形文本回空表，不炸库）。 */
fun decodeEmotionLabels(json: String): List<String> =
    runCatching { labelJson.decodeFromString(ListSerializer(String.serializer()), json) }
        .getOrDefault(emptyList())

/** 词条领域模型 → 落库行（source/weight 等运营字段原样保留）。 */
fun EmotionLexiconEntry.toEntity(): EmotionLexiconEntity = EmotionLexiconEntity(
    term = term,
    library = library,
    labelsJson = encodeEmotionLabels(labels),
    polarity = polarity,
    intensityDelta = intensityDelta,
    negatable = negatable,
    scenario = scenario,
    scopeContactId = scopeContactId,
    weight = weight,
    enabled = enabled,
    source = source,
    note = note,
)

/** 落库行 → 词条领域模型（判定引擎与词库管理页共用）。 */
fun EmotionLexiconEntity.toEntry(): EmotionLexiconEntry = EmotionLexiconEntry(
    term = term,
    library = library,
    labels = decodeEmotionLabels(labelsJson),
    polarity = polarity,
    intensityDelta = intensityDelta,
    negatable = negatable,
    scenario = scenario,
    scopeContactId = scopeContactId,
    weight = weight,
    enabled = enabled,
    source = source,
    note = note,
)

/** 基线领域记录 → 落库行。 */
fun EmotionBaseline.toEntity(contactId: Long): EmotionBaselineEntity = EmotionBaselineEntity(
    contactId = contactId,
    avgLen = avgLen,
    avgIntervalMs = avgIntervalMs,
    shortReplyRatio = shortReplyRatio,
    activeFollowRatio = activeFollowRatio,
    sampleCount = sampleCount,
    updatedAt = updatedAt,
)

/** 落库行 → 基线领域记录。 */
fun EmotionBaselineEntity.toDomain(): EmotionBaseline = EmotionBaseline(
    avgLen = avgLen,
    avgIntervalMs = avgIntervalMs,
    shortReplyRatio = shortReplyRatio,
    activeFollowRatio = activeFollowRatio,
    sampleCount = sampleCount,
    updatedAt = updatedAt,
)

// ───────────────────── 判定结论 → emotion_events 落库（E-d 台账） ─────────────────────

/** 标签得分数组 → JSON（emotion_events.labelsJson，[{label,score}]）。 */
fun encodeEmotionLabelScores(labels: List<EmotionJudge.LabelScore>): String =
    labelJson.encodeToString(ListSerializer(EmotionJudge.LabelScore.serializer()), labels)

/** JSON → 标签得分数组（畸形回空表）。 */
fun decodeEmotionLabelScores(json: String): List<EmotionJudge.LabelScore> =
    runCatching { labelJson.decodeFromString(ListSerializer(EmotionJudge.LabelScore.serializer()), json) }
        .getOrDefault(emptyList())

/** 证据链数组 → JSON（emotion_events.evidenceJson，分屏"点开证据链"回放）。 */
fun encodeEmotionEvidence(evidence: List<EmotionJudge.Evidence>): String =
    labelJson.encodeToString(ListSerializer(EmotionJudge.Evidence.serializer()), evidence)

/** JSON → 证据链数组（畸形回空表）。 */
fun decodeEmotionEvidence(json: String): List<EmotionJudge.Evidence> =
    runCatching { labelJson.decodeFromString(ListSerializer(EmotionJudge.Evidence.serializer()), json) }
        .getOrDefault(emptyList())

/** 三档输出 → 落库码。 */
fun EmotionJudge.EmotionState.code(): String = when (this) {
    EmotionJudge.EmotionState.CONFIRMED -> EMOTION_STATE_CONFIRMED
    EmotionJudge.EmotionState.WATCH -> EMOTION_STATE_WATCH
    EmotionJudge.EmotionState.CALM -> EMOTION_STATE_CALM
}

/** 置信三档 → 落库码。 */
fun EmotionJudge.EmotionConfidence.code(): String = when (this) {
    EmotionJudge.EmotionConfidence.HIGH -> EMOTION_CONFIDENCE_HIGH
    EmotionJudge.EmotionConfidence.MEDIUM -> EMOTION_CONFIDENCE_MEDIUM
    EmotionJudge.EmotionConfidence.LOW -> EMOTION_CONFIDENCE_LOW
}

/** 判定结论 → 台账行（reason 传 `emotion_*` 键，见 [EMOTION_REASON_JUDGE]）。 */
fun EmotionJudge.EmotionVerdict.toEventEntity(
    contactId: Long,
    conversationId: Long?,
    turnMessageId: Long?,
    reason: String,
    createdAt: Long,
): EmotionEventEntity = EmotionEventEntity(
    contactId = contactId,
    conversationId = conversationId,
    turnMessageId = turnMessageId,
    state = state.code(),
    labelsJson = encodeEmotionLabelScores(labels),
    intensity = intensity,
    confidence = confidence.code(),
    evidenceJson = encodeEmotionEvidence(evidence),
    reason = reason,
    createdAt = createdAt,
)

/** 台账行 → 情绪轨迹点（E-g 趋势计算输入，[EmotionTrendCalculator] 纯函数消费）。 */
fun EmotionEventEntity.toTrendPoint(): EmotionTrendPoint = EmotionTrendPoint(
    createdAtMs = createdAt,
    labels = decodeEmotionLabelScores(labelsJson),
    intensity = intensity,
    state = state,
)
