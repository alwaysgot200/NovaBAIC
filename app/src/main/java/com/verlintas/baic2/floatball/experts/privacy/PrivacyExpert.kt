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
 * 隐私专员的合议裁决入口：词表直判 ∪ 共享分类器结论。纯函数无状态——
 * 泛化判断由合议共享分类器（HandoffGate）承担，本对象只负责按纪律合并：
 * 词表一票定案，LLM 只补词表未直判的空隙（[PrivacyRules.judge]）。
 *
 * 独立成件（而非让合议团直调 Rules）是专家门面纪律：合议团只认专家，
 * 未来隐私专家若增自持 LLM 件或新判据，只改此处不动合议团。
 */
object PrivacyExpert {

    /**
     * 裁决 [text]（对方来信段）是否触碰隐私域。
     * @param llm 共享分类器的隐私结论；null = 分类器不可用（降级走词表备胎）。
     */
    fun judge(text: String, llm: PrivacyLlmHit?): PrivacyVerdict = PrivacyRules.judge(text, llm)
}
