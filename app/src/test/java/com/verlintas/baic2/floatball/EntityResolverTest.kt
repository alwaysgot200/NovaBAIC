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

package com.verlintas.baic2.floatball

import org.junit.Assert.assertEquals
import org.junit.Test

class EntityResolverTest {

    @Test
    fun soleCounterpartWithSelfLineJudgesPerson() {
        val screen = listOf(
            "李总：我们这边基础版28万能做吗？",
            "我：李总，基础版给您28万，不含运维",
            "李总：行，那这两天把28万的合同发我",
        ).joinToString("\n")

        assertEquals(
            EntityResolver.Entity.Person("李总"),
            EntityResolver.judge(screen),
        )
    }

    @Test
    fun multipleCounterpartsJudgeGroupEvenWithSelfLine() {
        val screen = listOf(
            "我：大家好",
            "李总：你好",
            "王总：你好",
        ).joinToString("\n")

        assertEquals(EntityResolver.Entity.Group, EntityResolver.judge(screen))
    }

    @Test
    fun groupJudgedEvenWithoutSelfLine() {
        // 群里「我」一直潜水也算群：多发言人即群形态，不要求「我」现身。
        val screen = listOf(
            "李总：方案今天能定吗",
            "王总：还得等财务那边",
            "李总：行，明天再说",
        ).joinToString("\n")

        assertEquals(EntityResolver.Entity.Group, EntityResolver.judge(screen))
    }

    @Test
    fun counterpartAppearingOnceIsNone() {
        // 单行冒名不足以建档（与 speakerOf ≥2 行门槛同口径）。
        val screen = listOf(
            "李总：在吗",
            "我：在的",
        ).joinToString("\n")

        assertEquals(EntityResolver.Entity.None, EntityResolver.judge(screen))
    }

    @Test
    fun soleCounterpartWithoutSelfLineIsNone() {
        val screen = listOf(
            "李总：基础版多少钱",
            "李总：在吗",
        ).joinToString("\n")

        assertEquals(EntityResolver.Entity.None, EntityResolver.judge(screen))
    }

    @Test
    fun unstructuredAndBlankBodiesAreNone() {
        val news = listOf(
            "网页视图",
            "未连接到互联网",
            "news.baidu.com",
        ).joinToString("\n")
        assertEquals(EntityResolver.Entity.None, EntityResolver.judge(news))
        assertEquals(EntityResolver.Entity.None, EntityResolver.judge(null))
        assertEquals(EntityResolver.Entity.None, EntityResolver.judge(""))
    }
}
