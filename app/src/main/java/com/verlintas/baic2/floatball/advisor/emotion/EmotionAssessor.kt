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

package com.verlintas.baic2.floatball.advisor.emotion

import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.EMOTION_REASON_JUDGE
import com.verlintas.baic2.floatball.advisor.data.toEntry
import com.verlintas.baic2.floatball.advisor.data.toEntity
import com.verlintas.baic2.floatball.advisor.data.toEventEntity
import com.verlintas.baic2.floatball.advisor.data.toTrendPoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 情绪判定的装配面（方案 §七 E-d 哨官挂载）：把词库（emotion_lexicon）、
 * 个人基线（emotion_baseline）与来信行为线索装配成 [EmotionJudge.Input]，
 * 跑纯函数判定并把结论落 emotion_events 台账。**零 LLM**——精判是合议团
 * （ExpertPanel）的事，本类只管数据进出。
 *
 * 旁路纪律：任何持久化失败只静默降级（词库读不到=纯行为判定；基线写不进=
 * 下回合重算），绝不阻断参谋回合。
 */
@Singleton
class EmotionAssessor @Inject constructor(
    private val db: AdvisorDatabase,
) {

    /**
     * 判定一条来信的情绪。[samples] 为旧→新的来信序列（末条 = [text] 对应的
     * 当前来信），线索/窗口/基线样本由 [EmotionCueExtractor] 统一提取。
     */
    suspend fun assess(
        contactId: Long?,
        text: String,
        samples: List<EmotionCueExtractor.IncomingSample>,
    ): EmotionJudge.EmotionVerdict {
        val extraction = EmotionCueExtractor.extract(samples)
        val lexicon = runCatching { db.emotionLexiconDao().all().map { it.toEntry() } }
            .getOrDefault(emptyList())
        val scenario = contactId?.let {
            runCatching { db.contactProfileDao().findById(it)?.scenarioCode }.getOrNull()
        }
        val baseline = baselineFor(contactId, extraction.samples)
        return EmotionJudge.judge(
            EmotionJudge.Input(
                text = text,
                cue = extraction.cue,
                window = extraction.window,
                baseline = baseline,
                lexicon = lexicon,
                scenario = scenario,
                contactId = contactId,
            ),
        )
    }

    /**
     * 判定结论落台账（旁路：失败只静默，绝不阻断回合）。[reason] 用 `emotion_*`
     * 台账键（[EMOTION_REASON_JUDGE] / `emotion_refine` / E-f 的 `emotion_veto`）。
     * 落库后顺手执行保留期清理（默认 [RETENTION_DAYS] 天，D4 口径）。
     */
    suspend fun record(
        contactId: Long,
        conversationId: Long?,
        turnMessageId: Long?,
        verdict: EmotionJudge.EmotionVerdict,
        reason: String = EMOTION_REASON_JUDGE,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            db.emotionEventDao().insert(
                verdict.toEventEntity(
                    contactId = contactId,
                    conversationId = conversationId,
                    turnMessageId = turnMessageId,
                    reason = reason,
                    createdAt = nowMs,
                ),
            )
            db.emotionEventDao().deleteOlderThan(nowMs - RETENTION_DAYS * DAY_MS)
        }
    }

    /**
     * 基线快照：本次来信窗口样本全量重算（幂等）后 upsert 一行（一联系人一行，
     * 慢车道 E-g 的跨会话全量滚动重算落地前的过渡口径），供原则 2 偏离对照。
     * 无联系人/无样本 → null（判定退化为不判基线类信号，绝不臆测）。
     */
    private suspend fun baselineFor(
        contactId: Long?,
        samples: List<EmotionMessageSample>,
    ): EmotionJudge.Baseline? {
        if (contactId == null || samples.isEmpty()) return null
        val computed = EmotionBaselineCalculator.calculate(samples, System.currentTimeMillis())
        runCatching { db.emotionBaselineDao().save(computed.toEntity(contactId)) }
        return computed.toJudgeBaseline()
    }

    /**
     * 跨会话基线全量滚动重算（E-g 慢车道，方案 §4.7）：[samples] 由调用方跨
     * 该联系人全部参谋会话收集（旧→新），距上次重算不足 [BASELINE_ROLL_INTERVAL_MS]
     * 直接跳过；失败静默，绝不阻断回合。判定回合内的 [baselineFor] 小窗口快照
     * 照旧，本方法是慢车道的全量校准。
     */
    suspend fun maybeRecalcBaseline(
        contactId: Long,
        samples: List<EmotionMessageSample>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (samples.isEmpty()) return
        val last = runCatching { db.emotionBaselineDao().get(contactId) }.getOrNull()?.updatedAt ?: 0L
        if (nowMs - last < BASELINE_ROLL_INTERVAL_MS) return
        runCatching {
            db.emotionBaselineDao().save(EmotionBaselineCalculator.calculate(samples, nowMs).toEntity(contactId))
        }
    }

    /**
     * 联系人情绪轨迹（E-g，方案 §4.7）：近 [TREND_WINDOW_DAYS] 天 emotion_events
     * 纯函数聚合。无事件返回 null（消费面空段不渲染，绝不臆测）。
     */
    suspend fun trend(
        contactId: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): EmotionTrend? {
        val rows = runCatching {
            db.emotionEventDao().sinceFor(contactId, nowMs - TREND_WINDOW_DAYS * DAY_MS)
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) return null
        return EmotionTrendCalculator.calculate(rows.map { it.toTrendPoint() }, nowMs)
    }

    companion object {
        /** emotion_events 保留天数（方案 D4：默认 90 天，AdvisorPrefs 可调留 E-h）。 */
        const val RETENTION_DAYS = 90

        /** 趋势窗口天数（§4.7：7/30 日曲线）。 */
        const val TREND_WINDOW_DAYS = 30

        /** 跨会话基线滚动重算最小间隔（慢车道每日一次足够）。 */
        val BASELINE_ROLL_INTERVAL_MS = 24L * 60 * 60 * 1000

        private const val DAY_MS = 86_400_000L
    }
}
