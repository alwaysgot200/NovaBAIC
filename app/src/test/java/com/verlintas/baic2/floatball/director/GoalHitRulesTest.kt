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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 达成型（G）快通道词表（方案 2026-10-20 §3.1）：句首应答词仅对短消息生效、
 * 强敲定短语任意位置命中、进行中/中性话术绝不命中。
 */
class GoalHitRulesTest {

    @Test
    fun shortAck_hits() {
        assertTrue(GoalHitRules.hit("好呀"))
        assertTrue(GoalHitRules.hit("没问题"))
        assertTrue(GoalHitRules.hit("成交"))
        assertTrue(GoalHitRules.hit("可以呀，那就这么办"))
    }

    @Test
    fun shortAck_longMessageIgnored() {
        // >32 字消息里的引语「好的」不可作数（§3.1 误报面控制）。
        val long = "好的，" + "关于上次说的那批货我们内部讨论了一下，物流和仓储都还要再确认，过两天给你答复。"
        assertFalse(GoalHitRules.hit(long))
    }

    @Test
    fun strongPhrase_hitsAnywhere() {
        assertTrue(GoalHitRules.hit("那就这么说定了"))
        assertTrue(GoalHitRules.hit("一言为定！"))
        assertTrue(GoalHitRules.hit("周日下午，明天见"))
        assertTrue(GoalHitRules.hit("价格没问题，就按你说的来"))
        assertTrue(GoalHitRules.hit("可以，下单吧"))
    }

    @Test
    fun inProgress_negatives() {
        assertFalse(GoalHitRules.hit("我再考虑一下"))
        assertFalse(GoalHitRules.hit("下周再说吧"))
        assertFalse(GoalHitRules.hit("今天天气不错"))
        assertFalse(GoalHitRules.hit(""))
    }
}
