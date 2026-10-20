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

package com.verlintas.baic2.floatball.experts.privacy

/**
 * 隐私专员的合议内 LLM 结论投影：由合议共享分类器（HandoffGate）一次调用
 * 产出，专家面板映射为本类型后交给 [PrivacyExpert.judge] 合并。独立类型使
 * 隐私专家不依赖 director 包的具体协议类（专家包之间禁止 import）。
 */
data class PrivacyLlmHit(
    val hit: Boolean = false,
    /** 分类器给的域 code（p1~p12/other）；解析失败由 fromCode 归 [PrivacyDomain.OTHER]。 */
    val domain: String = "",
)

/**
 * 隐私专员的裁决（纯数据）：[hit]=命中隐私域；[domain] 命中域（null 仅在
 * 未命中时出现）；[fromRules]=词表直判（一票定案，LLM 不可反向覆盖）。
 * 裁决如何被消费（handoff 档走接管链 / bypass 档绕行+轻通报）是编排器的
 * 事，专家只给结论。
 */
data class PrivacyVerdict(
    val hit: Boolean = false,
    val domain: PrivacyDomain? = null,
    val fromRules: Boolean = false,
)
