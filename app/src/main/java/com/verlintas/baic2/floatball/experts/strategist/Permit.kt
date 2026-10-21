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

import kotlinx.serialization.Serializable

/**
 * 军师 Strategist 的结构化交接物（裁量层方案 §3.3 输出协议）：LLM 只产出
 * `<tactics>` JSON 提案，代码执法在 [StrategistRules.enforce]——模型只能提案，
 * 红线死清单任何档位绝对禁止。
 */

/** approach 词表：本回合重点正向手段（D8 拍板，可扩）。 */
const val APPROACH_VALUE_SHOW = "value_show"
const val APPROACH_CHARM = "charm"
const val APPROACH_BENEFIT_LIST = "benefit_list"
const val APPROACH_EMPATHY = "empathy"
const val APPROACH_HUMOR = "humor"
const val APPROACH_STORY = "story"
const val APPROACH_MIXED = "mixed"

/**
 * 单维手段裁决。三维敏感手段（disclosure/lying/promising/exemplifying）共用
 * 一个形态：disclosure 额外带 [domain]（F1 域码），promising 额外带 [within]
 * （许愿是否在授权范围内）。缺字段按保守默认（use=false）。
 */
@Serializable
data class TacticRuling(
    val use: Boolean = false,
    val why: String = "",
    val claim: String = "",
    /** disclosure 维专用：F1 隐私域码（p1~p12/other），其余维空串。 */
    val domain: String = "",
    /** promising 维专用：claim 是否在授权承受范围内（false → 问主人）。 */
    val within: Boolean = true,
)

/**
 * 本回合方针与敏感手段许可单：[approach] 是「本回合重点」不是排他许可
 * （正向手段库段永远全量开放，防军师失误锁死代言者，方案 §四.1）。
 */
@Serializable
data class Permit(
    val approach: String = APPROACH_MIXED,
    val disclosure: TacticRuling = TacticRuling(),
    val lying: TacticRuling = TacticRuling(),
    val promising: TacticRuling = TacticRuling(),
    val exemplifying: TacticRuling = TacticRuling(),
)

/**
 * 档位闸执法后的许可单（模型提案 × 授权档位 × 红线死清单三重过滤的产物）：
 * [permit] 中 never 档维与红线命中的维已被剥除 use；[needsHuman]=许愿超
 * 授权范围（within=false 或 claim 词表命中），转问主人通道该维不发送；
 * [violations] 为红线违规记录（进台账，泄露锁单测消费）。
 */
data class RuledPermit(
    val permit: Permit,
    val needsHuman: Boolean = false,
    val violations: List<String> = emptyList(),
)

/** 军师产出 + 是否缓存命中（台账区分 tactic_permit_ok / tactic_permit_cached）。 */
data class PermitOutcome(
    val ruled: RuledPermit?,
    val fromCache: Boolean,
)
