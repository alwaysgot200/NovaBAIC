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

package com.verlintas.baic2.floatball.experts

import com.verlintas.baic2.floatball.advisor.data.EMOTION_REASON_JUDGE
import com.verlintas.baic2.floatball.advisor.data.EMOTION_REASON_REFINE
import com.verlintas.baic2.floatball.advisor.emotion.EmotionAssessor
import com.verlintas.baic2.floatball.advisor.emotion.EmotionCueExtractor
import com.verlintas.baic2.floatball.advisor.emotion.EmotionJudge
import com.verlintas.baic2.floatball.advisor.emotion.EmotionLabels
import com.verlintas.baic2.floatball.director.GoalHitRules
import com.verlintas.baic2.floatball.director.HandoffCategory
import com.verlintas.baic2.floatball.director.HandoffGate
import com.verlintas.baic2.floatball.director.HandoffJudgment
import com.verlintas.baic2.floatball.director.HandoffRules
import com.verlintas.baic2.floatball.director.IncomingEmotionRefine
import com.verlintas.baic2.floatball.director.IncomingLlmVerdict
import com.verlintas.baic2.floatball.experts.privacy.PrivacyExpert
import com.verlintas.baic2.floatball.experts.privacy.PrivacyLlmHit
import com.verlintas.baic2.floatball.experts.privacy.PrivacyVerdict
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 一次来信合议的完整结论：全部专家的裁决各占一字段（每个新专家在此加一项），
 * 编排器（ScreenAdvisor）只读裁决、只做消费决策——分发/合并/降级都在本团内。
 *
 * @param handoff 交接官裁决（E1 行为等价迁入：白名单/强模式/LLM 合并）
 * @param privacy 隐私专员裁决（F1）
 * @param goalHit 达成快通道命中（E1 G 类；只在非接管且非 uncertain 时评估，
 *   与 E1 judgeEscalation 的短路顺序逐字一致）
 * @param emotion 情感分析师裁决（E-d）：三档 + 标签 + 强度 + 置信 + 证据链；
 *   词库判定为主、LLM 精判只在低置信 + 高价值回合复核（预算零增加）
 */
data class IncomingReview(
    val handoff: HandoffJudgment = HandoffJudgment(handoff = false),
    val privacy: PrivacyVerdict = PrivacyVerdict(),
    val goalHit: Boolean = false,
    val emotion: EmotionJudge.EmotionVerdict = EmotionJudge.CALM_VERDICT,
) {
    companion object {

        /** 白名单快筛命中：纯应答无需任何专家表态（skipLlm 语义随 handoff 携带）。 */
        fun skip(emotion: EmotionJudge.EmotionVerdict = EmotionJudge.CALM_VERDICT) =
            IncomingReview(handoff = HandoffJudgment(handoff = false, skipLlm = true), emotion = emotion)
    }
}

/**
 * 情绪判定入参（E-d）：编排器组装的来信序列——[samples] 旧→新、末条=当前来信；
 * [assessDim] 为高价值回合门之一（任一敏感维 assess 档 = 本回合可能自评裁量）。
 */
data class EmotionTurn(
    val contactId: Long? = null,
    val conversationId: Long? = null,
    val turnMessageId: Long? = null,
    val samples: List<EmotionCueExtractor.IncomingSample> = emptyList(),
    val assessDim: Boolean = false,
)

/**
 * 专家合议团（子代理化组织模式 §3）：唯一对编排器暴露的专家门面。编排器
 * （ScreenAdvisor）只问合议一次，合议内完成词表直判、共享分类器调用与多专家
 * 结论合并——专家互不调用，编排器不逐个点名。
 *
 * 合议纪律：
 * - 成本：N 个专家 ≠ N 次 LLM——handoff 强模式直判即短路（E1 口径，省调用），
 *   白名单快筛绝不烧分类调用，否则共享分类器一次产出 handoff+privacy 两域结论；
 *   情绪精判（E-d）**并入同一次分类调用**（refineEmotion 追加复核段），不新增
 *   调用——预算锁（方案 §八.3）由结构保证；
 * - 降级：分类器故障只把自己的结论降为词表备胎/uncertain，不拖垮其他专家
 *   （runCatching 包裹调用点，故障=无 LLM 结论）；情绪判定故障降平稳档；
 * - 台账：判定结论旁路落 emotion_events（`emotion_*` 键），失败静默不阻断回合；
 * - 执行面不进合议团：通知（Liaison）、发送（ActionDispatcher）、发送前硬审
 *   （Gatekeeper）都是「手」，保持在编排器/发送层——专家只动嘴（裁决）。
 */
@Singleton
class ExpertPanel @Inject constructor(
    private val handoffGate: HandoffGate,
    private val emotionAssessor: EmotionAssessor,
) {

    /**
     * 后台来信合议：[text] 为对方来信段（IncomingCue.latestIncoming 抽取）。
     * 任何意外异常向上抛出由编排器按「静默降级为不接管」处置（旁路纪律）。
     */
    suspend fun reviewIncoming(text: String, turn: EmotionTurn = EmotionTurn()): IncomingReview {
        val strongHandoff = runCatching { HandoffRules.strongHit(text) }.getOrNull()
        // 情绪判定先行（纯函数零 LLM）：连白名单应答也要判——「好的」的连续累积
        // （原则 3）正是靠这些短确认喂出来的，故障降平稳档。
        val base = runCatching { emotionAssessor.assess(turn.contactId, text, turn.samples) }
            .getOrDefault(EmotionJudge.CALM_VERDICT)
        val refine = refineGate(base, turn.assessDim, takeoverRisk = strongHandoff != null)
        // E1 成本口径：强模式直判即省 LLM（llmHandoff 已定 true，分类结果无增量）；
        // 白名单快筛 skipLlm 契约同理（此前白名单也烧分类调用，属浪费，一并堵上）。
        val llm = when {
            HandoffRules.whitelistHit(text) -> null
            strongHandoff != null -> null
            else -> runCatching { handoffGate.classify(text, refineEmotion = refine) }.getOrNull()
        }
        val refined = if (refine) llm?.emotion?.let { applyEmotionRefine(base, it) } ?: base else base
        if (turn.contactId != null) {
            // 台账键：复核真正采纳（产出新裁决）记 emotion_refine，否则记 emotion_judge。
            val reason = if (refined === base) EMOTION_REASON_JUDGE else EMOTION_REASON_REFINE
            runCatching {
                emotionAssessor.record(
                    contactId = turn.contactId,
                    conversationId = turn.conversationId,
                    turnMessageId = turn.turnMessageId,
                    verdict = refined,
                    reason = reason,
                )
            }
        }
        return merge(text, strongHandoff, llm, refined)
    }

    companion object {

        /** 高价值回合门：疑似强度达此值即算高价值（方案 §4.5「疑似强度≥4」）。 */
        const val HIGH_VALUE_INTENSITY = 4

        /**
         * 精判触发门（方案 §4.5）：**置信非高 + 非平稳**（[EmotionJudge.needsLlmRefine]）
         * 且高价值回合（assess 裁量维 / 接管风险 / 疑似强度≥4）才请求 LLM 复核——
         * 典型回合不触发，且复核并入既有分类调用，LLM 次数零增加。
         */
        internal fun refineGate(
            verdict: EmotionJudge.EmotionVerdict,
            assessDim: Boolean,
            takeoverRisk: Boolean,
        ): Boolean = with(EmotionJudge) {
            verdict.needsLlmRefine &&
                (assessDim || takeoverRisk || verdict.intensity >= HIGH_VALUE_INTENSITY)
        }

        /**
         * 精判复核采纳（纯函数）：字段合法才整体采纳（置信升 HIGH，证据链保留库判
         * 原件）；畸形/标签全非法/非平稳档却给不出标签 → 弃用回库判——LLM 只能
         * 复核，不能把坏输出塞进裁决。
         */
        internal fun applyEmotionRefine(
            base: EmotionJudge.EmotionVerdict,
            refine: IncomingEmotionRefine,
        ): EmotionJudge.EmotionVerdict {
            val state = when (refine.state.trim().lowercase()) {
                "confirmed" -> EmotionJudge.EmotionState.CONFIRMED
                "watch" -> EmotionJudge.EmotionState.WATCH
                "calm" -> EmotionJudge.EmotionState.CALM
                else -> return base
            }
            val labels = refine.labels.map { it.trim() }.filter { it in EmotionLabels.ALL }
            if (state != EmotionJudge.EmotionState.CALM && labels.isEmpty()) return base
            val direction = if ((base.labels.firstOrNull()?.score ?: 0.0) < 0) -1.0 else 1.0
            val scored = labels
                .map { label ->
                    EmotionJudge.LabelScore(
                        label = label,
                        score = base.labels.firstOrNull { it.label == label }?.score ?: direction,
                    )
                }
                .sortedByDescending { abs(it.score) }
            return base.copy(
                state = state,
                labels = scored,
                intensity = if (refine.intensity in 1..5) refine.intensity else base.intensity,
                confidence = EmotionJudge.EmotionConfidence.HIGH,
                reason = "经 LLM 精判复核（原判：${base.reason}）",
            )
        }

        /**
         * 纯合并逻辑（JVM 可单测）：词表先行、LLM 只补空隙。E1 行为等价的关键
         * 三态——[llm]=null 时 llmHandoff 必须是 null（uncertain）而非 false
         * （放行）；强模式命中时 llmHandoff 恒 true 且 LLM 结论不参与。
         * [emotion] 是情感分析师裁决（E-d）：白名单短路不吞情绪——连续短确认
         * 累积（原则 3）恰恰靠纯应答喂出来。
         */
        internal fun merge(
            text: String,
            strongHandoff: Pair<HandoffCategory, String>?,
            llm: IncomingLlmVerdict?,
            emotion: EmotionJudge.EmotionVerdict = EmotionJudge.CALM_VERDICT,
        ): IncomingReview {
            if (HandoffRules.whitelistHit(text)) return IncomingReview.skip(emotion)
            val llmHandoff: Boolean? = when {
                strongHandoff != null -> true
                llm == null -> null
                else -> llm.isHandoff
            }
            val handoff = HandoffRules.judge(text, llmHandoff)
            val privacyLlm = llm?.let { PrivacyLlmHit(hit = it.privacy, domain = it.privacyDomain) }
            val privacy = PrivacyExpert.judge(text, privacyLlm)
            // E1 短路顺序：接管/uncertain 命中时不评达成快通道（逐字等价）。
            val goalHit = if (!handoff.handoff && !handoff.uncertain) GoalHitRules.hit(text) else false
            return IncomingReview(handoff = handoff, privacy = privacy, goalHit = goalHit, emotion = emotion)
        }
    }
}
