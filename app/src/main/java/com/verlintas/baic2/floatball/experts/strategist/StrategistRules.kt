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

package com.verlintas.baic2.floatball.experts.strategist

import com.verlintas.baic2.floatball.advisor.data.PRIVACY_POLICY_ASSESS
import com.verlintas.baic2.floatball.advisor.strategy.strategyJson

/**
 * 军师确定性校验（裁量层方案 §3.4，纯函数 JVM 可单测）：模型提案永远不可信，
 * 四道闸全部代码执法——
 * 1. 档位闸：never 档维度 use=true → 剥除（宪法优先于军令）；
 * 2. claim 词表闸：promising.claim 命中 D4 词表且档位=assess → needsHuman；
 * 3. 红线闸：claim 命中 D3 死清单 → 整单作废该维并记违规（free 档也拦）；
 * 4. approach 归一：未知值归 mixed。
 */
internal object StrategistRules {

    /**
     * `<tactics>` JSON 解析：剥围栏/取花括号/容错解码（方案 §3.3）。
     * 解析失败/畸形 → null（走 §3.5 降级，绝不抛）。
     */
    fun parse(raw: String?): Permit? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            strategyJson.decodeFromString(Permit.serializer(), text.substring(start, end + 1))
        }.getOrNull()?.let(::normalizeApproach)
    }

    /**
     * 四道闸执法：输入模型提案 + 四维授权档位（disclosure 用 F1 privacyPolicy
     * 单点真源）→ 档位闸过滤后的许可单。恒不返回 null（降级口径在门面层）。
     * [emotion] 为 E-e 情绪约束（方案 §4.6，active 时追加执法）：禁用敏感维剥
     * use、approach 命中禁用词表则覆写为映射建议重点。
     */
    fun enforce(
        permit: Permit,
        strategy: TalkerStrategy,
        privacyPolicy: String?,
        emotion: EmotionRoute = EmotionRoute.INACTIVE,
    ): RuledPermit {
        val violations = mutableListOf<String>()
        var needsHuman = false

        val disclosureLevel = if (privacyPolicy == PRIVACY_POLICY_ASSESS) TALKER_ASSESS else TALKER_NEVER
        val dims = listOf(
            Triple(DIM_DISCLOSURE, permit.disclosure, disclosureLevel),
            Triple(DIM_LYING, permit.lying, normalizeTalkerLevel(strategy.lying)),
            Triple(DIM_PROMISING, permit.promising, normalizeTalkerLevel(strategy.promising)),
            Triple(DIM_EXEMPLIFYING, permit.exemplifying, normalizeTalkerLevel(strategy.exemplifying)),
        )

        var disclosure = permit.disclosure
        var lying = permit.lying
        var promising = permit.promising
        var exemplifying = permit.exemplifying

        for ((dim, ruling, level) in dims) {
            if (!ruling.use) continue
            // 红线闸（最高优先）：死清单任何档位恒拦。
            val claimText = ruling.claim
            val redline = claimText.takeIf { it.isNotBlank() }?.let { StrategistAssets.redlineHit(it) }
            val stripped = level == TALKER_NEVER
            if (redline != null) {
                violations += "$dim:redline:$redline"
            }
            when (dim) {
                DIM_DISCLOSURE -> if (stripped || redline != null) disclosure = ruling.copy(use = false)
                DIM_LYING -> if (stripped || redline != null) lying = ruling.copy(use = false)
                DIM_EXEMPLIFYING -> if (stripped || redline != null) exemplifying = ruling.copy(use = false)
                DIM_PROMISING -> {
                    // claim 词表闸：assess 档下硬承诺（金额/期限/身份类）→ 问主人。
                    val overreach = ruling.claim.isNotBlank() &&
                        level == TALKER_ASSESS &&
                        StrategistAssets.PROMISE_WORDS.any { ruling.claim.contains(it) }
                    if (stripped || redline != null) {
                        promising = ruling.copy(use = false)
                    } else if (!ruling.within || overreach) {
                        needsHuman = true
                    }
                }
            }
        }

        // E-e 情绪约束（方案 §4.6 映射表硬执法）：禁用敏感维剥 use（盛怒禁
        // exemplifying 类比论证）；approach 命中禁用词表则覆写为映射建议重点。
        // 非红线违规（不进 violations），提示词端已同口径告知，这里只兜底。
        if (emotion.active && emotion.bannedDims.isNotEmpty()) {
            if (DIM_DISCLOSURE in emotion.bannedDims) disclosure = disclosure.copy(use = false)
            if (DIM_LYING in emotion.bannedDims) lying = lying.copy(use = false)
            if (DIM_PROMISING in emotion.bannedDims) promising = promising.copy(use = false)
            if (DIM_EXEMPLIFYING in emotion.bannedDims) exemplifying = exemplifying.copy(use = false)
        }
        val approach = if (emotion.active && permit.approach in emotion.bannedApproaches) {
            emotion.approaches.firstOrNull() ?: APPROACH_MIXED
        } else {
            permit.approach
        }

        return RuledPermit(
            permit = Permit(
                approach = approach,
                disclosure = disclosure,
                lying = lying,
                promising = promising,
                exemplifying = exemplifying,
            ),
            needsHuman = needsHuman,
            violations = violations,
        )
    }

    /** approach 归一：未知/空值归 mixed（D8），解析后立即执行。 */
    private fun normalizeApproach(permit: Permit): Permit =
        if (permit.approach.trim() in StrategistAssets.APPROACH_LABELS.keys) {
            permit
        } else {
            permit.copy(approach = APPROACH_MIXED)
        }

    /** 校验维度键（台账/日志用，与 TalkerStrategy 三维键 + disclosure 对齐）。 */
    const val DIM_DISCLOSURE = "disclosure"
    const val DIM_LYING = "lying"
    const val DIM_PROMISING = "promising"
    const val DIM_EXEMPLIFYING = "exemplifying"
}
