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

package com.verlintas.baic2.floatball.contact

import com.verlintas.baic2.floatball.advisor.data.SCENE_NON_CHAT

/**
 * 会话复用强化（V-B，UltraAdvisor v2.2 方案 §3）的纯规则层：池隔离与断崖
 * 路由均为无副作用函数，供 [com.verlintas.baic2.floatball.ScreenAdvisor] 的
 * 会话路由消费，也便于单测覆盖「相似度阈值 / 池隔离 / 断崖」三块判定。
 *
 * 方案 §3 四行的落点对应关系：
 * - 默认行「同联系人已有会话取相似度最高者续用」→ [route] 的 [Route.Reuse]
 *   分支（候选由调用方对该联系人全部绑定会话逐一回看打分，这里只裁决）；
 * - 重大话题转变行「相似度断崖新开并关联同档案」→ [Route.Cliff] 分支
 *   （新建+同档案绑定由调用方完成，旧会话保留）；
 * - NON_CHAT 行「只复用 non_chat 会话池」→ [samePool]/[filterPool]。
 */
object SessionReuse {

    /** 一个候选会话与其归属场景（session_bindings.scene，无行按 chat 口径）。 */
    data class Candidate(val conversationId: Long, val scene: String)

    /** 一个候选会话与当前屏正文的内容相似度（[com.verlintas.baic2.floatball.FrameQuality.textSimilarity] 口径）。 */
    data class Scored(val conversationId: Long, val similarity: Double)

    /** 路由裁决结果。 */
    sealed interface Route {
        /** 续用：同池候选中相似度最高者（复用优先，永不主动新建）。 */
        data class Reuse(val conversationId: Long, val similarity: Double) : Route

        /**
         * 断崖：最高相似度仍低于阈值 → 判定重大话题转变，新开会话（旧会话
         * 保留可回看）。分叉的 Tactician 轻判定留 hook：目前纯规则（相似度
         * < 阈值即断崖），后续可在此分支前置一次 ≤80tok LLM 复核再放行新建。
         */
        data class Cliff(val bestId: Long, val bestSimilarity: Double) : Route

        /** 无从比较：候选都没有可比对的屏幕消息 → 复用优先（宁可续用，由调用方取最近会话）。 */
        data object NoComparable : Route
    }

    /**
     * 池隔离（方案 §1.2/§3 NON_CHAT 行）：当前回合只与同池会话比对复用——
     * non_chat 回合只认 non_chat 会话；chat 回合（含无绑定行按 chat 的历史
     * 共享会话）不串入 non_chat 池。联系人绑定行恒为 chat，天然落 chat 池。
     */
    fun samePool(currentScene: String, candidateScene: String): Boolean = when (currentScene) {
        SCENE_NON_CHAT -> candidateScene == SCENE_NON_CHAT
        else -> candidateScene != SCENE_NON_CHAT
    }

    /** [samePool] 的批量形态：从候选列表里滤出与当前回合同池的会话 id。 */
    fun filterPool(candidates: List<Candidate>, currentScene: String): List<Long> =
        candidates.filter { samePool(currentScene, it.scene) }.map { it.conversationId }

    /**
     * 断崖路由（方案 §3 默认行 + 重大话题转变行）：取相似度最高者，达到
     * [cliffThreshold]（含等于）即续用；低于即断崖。空表 = 无可比候选，按
     * [Route.NoComparable] 交回调用方复用（复用优先）。阈值来自 AdvisorPrefs
     * （默认保守：宁可续用，不轻易新建），运行中可调。
     */
    fun route(scored: List<Scored>, cliffThreshold: Double): Route {
        val best = scored.maxByOrNull { it.similarity } ?: return Route.NoComparable
        return if (best.similarity >= cliffThreshold) {
            Route.Reuse(best.conversationId, best.similarity)
        } else {
            Route.Cliff(best.conversationId, best.similarity)
        }
    }
}
