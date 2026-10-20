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

import com.verlintas.baic2.floatball.director.GoalHitRules
import com.verlintas.baic2.floatball.director.HandoffCategory
import com.verlintas.baic2.floatball.director.HandoffGate
import com.verlintas.baic2.floatball.director.HandoffJudgment
import com.verlintas.baic2.floatball.director.HandoffRules
import com.verlintas.baic2.floatball.director.IncomingLlmVerdict
import com.verlintas.baic2.floatball.experts.privacy.PrivacyExpert
import com.verlintas.baic2.floatball.experts.privacy.PrivacyLlmHit
import com.verlintas.baic2.floatball.experts.privacy.PrivacyVerdict
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次来信合议的完整结论：全部专家的裁决各占一字段（每个新专家在此加一项），
 * 编排器（ScreenAdvisor）只读裁决、只做消费决策——分发/合并/降级都在本团内。
 *
 * @param handoff 交接官裁决（E1 行为等价迁入：白名单/强模式/LLM 合并）
 * @param privacy 隐私专员裁决（F1）
 * @param goalHit 达成快通道命中（E1 G 类；只在非接管且非 uncertain 时评估，
 *   与 E1 judgeEscalation 的短路顺序逐字一致）
 */
data class IncomingReview(
    val handoff: HandoffJudgment = HandoffJudgment(handoff = false),
    val privacy: PrivacyVerdict = PrivacyVerdict(),
    val goalHit: Boolean = false,
) {
    companion object {

        /** 白名单快筛命中：纯应答无需任何专家表态（skipLlm 语义随 handoff 携带）。 */
        fun skip() = IncomingReview(handoff = HandoffJudgment(handoff = false, skipLlm = true))
    }
}

/**
 * 专家合议团（子代理化组织模式 §3）：唯一对编排器暴露的专家门面。编排器
 * （ScreenAdvisor）只问合议一次，合议内完成词表直判、共享分类器调用与多专家
 * 结论合并——专家互不调用，编排器不逐个点名。
 *
 * 合议纪律：
 * - 成本：N 个专家 ≠ N 次 LLM——handoff 强模式直判即短路（E1 口径，省调用），
 *   否则共享分类器一次产出 handoff+privacy 两域结论；
 * - 降级：分类器故障只把自己的结论降为词表备胎/uncertain，不拖垮其他专家
 *   （runCatching 包裹调用点，故障=无 LLM 结论）；
 * - 执行面不进合议团：通知（Liaison）、发送（ActionDispatcher）、发送前硬审
 *   （Gatekeeper）都是「手」，保持在编排器/发送层——专家只动嘴（裁决）。
 */
@Singleton
class ExpertPanel @Inject constructor(
    private val handoffGate: HandoffGate,
) {

    /**
     * 后台来信合议：[text] 为对方来信段（IncomingCue.latestIncoming 抽取）。
     * 任何意外异常向上抛出由编排器按「静默降级为不接管」处置（旁路纪律）。
     */
    suspend fun reviewIncoming(text: String): IncomingReview {
        val strongHandoff = runCatching { HandoffRules.strongHit(text) }.getOrNull()
        // E1 成本口径：强模式直判即省 LLM（llmHandoff 已定 true，分类结果无增量）。
        val llm = if (strongHandoff != null) {
            null
        } else {
            runCatching { handoffGate.classify(text) }.getOrNull()
        }
        return merge(text, strongHandoff, llm)
    }

    companion object {

        /**
         * 纯合并逻辑（JVM 可单测）：词表先行、LLM 只补空隙。E1 行为等价的关键
         * 三态——[llm]=null 时 llmHandoff 必须是 null（uncertain）而非 false
         * （放行）；强模式命中时 llmHandoff 恒 true 且 LLM 结论不参与。
         */
        internal fun merge(
            text: String,
            strongHandoff: Pair<HandoffCategory, String>?,
            llm: IncomingLlmVerdict?,
        ): IncomingReview {
            if (HandoffRules.whitelistHit(text)) return IncomingReview.skip()
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
            return IncomingReview(handoff = handoff, privacy = privacy, goalHit = goalHit)
        }
    }
}
