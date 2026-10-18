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

package com.verlintas.baic2.floatball.advisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 反馈热度增量纯函数（V-C 方案 §4.3）：只消费可得信号、增量夹逼、0~100 落分。
 */
class HeatRulesTest {

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 24L * HOUR
    }

    // ── 字数档 ─────────────────────────────────────────────────────────

    @Test
    fun textLengthTiers() {
        assertEquals(-2, HeatRules.delta(HeatRules.Signal("好"))) // <15 字
        assertEquals(
            1,
            HeatRules.delta(HeatRules.Signal("今天下午三点开会，记得带上季度报表的材料一起过一遍")),
        ) // 15~59
        assertEquals(
            3,
            HeatRules.delta(
                HeatRules.Signal(
                    "这周末我们几个人打算去郊区爬山，早上七点出发，中午在山脚下吃农家菜，" +
                        "下午再去附近的水库边钓鱼吹吹风，晚上一起吃个饭聊聊天，你要不要一起就差你一个了",
                ),
            ),
        ) // ≥60 且无问号
    }

    @Test
    fun questionMarkAddsBonus() {
        // "在吗？" 3 字：-2（短）+2（反问）= 0。
        assertEquals(0, HeatRules.delta(HeatRules.Signal("在吗？")))
        assertEquals(-2, HeatRules.delta(HeatRules.Signal("在吗"))) // 无问号
    }

    // ── 时延档 ─────────────────────────────────────────────────────────

    @Test
    fun gapTiers() {
        assertEquals(2, HeatRules.delta(HeatRules.Signal(null, 4 * MIN))) // 秒回
        assertEquals(1, HeatRules.delta(HeatRules.Signal(null, HOUR)))
        assertEquals(0, HeatRules.delta(HeatRules.Signal(null, 10 * HOUR))) // 日常节奏
        assertEquals(-2, HeatRules.delta(HeatRules.Signal(null, DAY + MIN))) // 久疏
    }

    // ── 信号不可得跳过不猜（方案 §4.3 红线） ─────────────────────────────

    @Test
    fun noSignal_returnsNull() {
        assertNull(HeatRules.delta(HeatRules.Signal(null)))
        assertNull(HeatRules.delta(HeatRules.Signal("   ")))
        assertNull(HeatRules.delta(HeatRules.Signal(null, null)))
    }

    // ── F6：时钟回拨负 gap 按信号不可得跳过 ─────────────────────────────

    @Test
    fun negativeGap_isTreatedAsMissingSignal() {
        // 负 gap（时钟回拨）绝不落进「秒回」档：文本不可得时整体无信号 → null。
        assertNull(HeatRules.delta(HeatRules.Signal(null, -MIN)))
        assertNull(HeatRules.delta(HeatRules.Signal(null, -DAY)))
        // 文本可得时只按文本档计分，时延档不参与（与 gap=null 同口径）。
        assertEquals(
            HeatRules.delta(HeatRules.Signal("在吗？")),
            HeatRules.delta(HeatRules.Signal("在吗？", -MIN)),
        )
        assertEquals(
            HeatRules.delta(HeatRules.Signal("好")),
            HeatRules.delta(HeatRules.Signal("好", -DAY)),
        )
        // 对照：正值 gap 正常计分。
        assertEquals(2, HeatRules.delta(HeatRules.Signal(null, 4 * MIN)))
    }

    // ── 合计与夹逼 ─────────────────────────────────────────────────────

    @Test
    fun combinedDeltaClamped() {
        // 长文 +3 反问 +2 秒回 +2 = 7 → 夹到上限 5。
        assertEquals(
            5,
            HeatRules.delta(HeatRules.Signal("这周末我们几个人打算去郊区爬山，早上七点出发，中午在山脚下吃农家菜，你要不要一起？", 4 * MIN)),
        )
        // 短文 -2 久疏 -2 = -4（下限）。
        assertEquals(-4, HeatRules.delta(HeatRules.Signal("好", DAY + MIN)))
    }

    // ── 落分 ───────────────────────────────────────────────────────────

    @Test
    fun clamp_nullStartsNeutralAndBounds() {
        assertEquals(53, HeatRules.clamp(null, 3))
        assertEquals(48, HeatRules.clamp(null, -2))
        assertEquals(0, HeatRules.clamp(1, -4))
        assertEquals(100, HeatRules.clamp(98, 5))
        assertEquals(50, HeatRules.clamp(48, 2))
    }
}
