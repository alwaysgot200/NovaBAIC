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

package com.verlintas.baic2.floatball.director

/**
 * 达成型（G）快通道词表（方案 2026-10-20 §3.1，纯函数 JVM 可单测）：对方
 * 来信**明示同意/敲定**时当回合立即通报主人，不等下一回合 MissionJudge。
 *
 * 误报面控制：只收「同意/敲定」类肯定表态——「考虑一下/再看看/下周再说」
 * 是进行中不是达成，绝不入表；成交/答应语义必须由对方主语发出（「行」「好呀」
 * 「可以」类应答词开头或独立成句），避免把对方的提问误当表态。
 */
object GoalHitRules {

    /**
     * 句首应答词（好呀/行/可以呀…）。只对短消息生效——应答+敲定不会写成
     * 小作文，长消息里的引语「好的」不可作数。
     */
    private val SHORT_ACK_REGEX =
        Regex("^(好呀|好啊|好的|行呀|行啊|可以呀|可以啊|嗯好|要得|成|中|没问题|成交)")

    /** 强敲定短语：任何位置命中都算（独立短语本身语义完备）。 */
    private val STRONG_HIT_REGEXES: List<Regex> = listOf(
        Regex("就这么(说)?定了|一言为定|一拍即合"),
        Regex("明天见|后天见|周[末一二三四五六日天]见|到时候见|老地方见"),
        Regex("价格没问题|价格可以|这个价可以"),
        Regex("(地址|收货地址|电话)[^。！？!?\\n]{0,4}发(给)?我"),
        Regex("下单吧|拍了吧|买了吧|订了吧|先付定金"),
    )

    /** 快通道命中：[text] 为对方来信段（IncomingCue 抽取）。 */
    fun hit(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length <= 32 && SHORT_ACK_REGEX.containsMatchIn(trimmed)) return true
        return STRONG_HIT_REGEXES.any { it.containsMatchIn(trimmed) }
    }
}
