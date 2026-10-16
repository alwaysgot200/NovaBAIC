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

package com.verlintas.baic2.floatball.strategy

import com.verlintas.baic2.floatball.advisor.strategy.PlanStreamFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanStreamFilterTest {

    @Test
    fun standardBlock_isStrippedAndBodyVisible() {
        val f = PlanStreamFilter()
        val visible = f.feed("<plan>{\"mission\":\"问好\"}</plan>你好呀")
        assertEquals("你好呀", visible)
        assertEquals("{\"mission\":\"问好\"}", f.plan())
        assertEquals("", f.endStream())
    }

    @Test
    fun charByCharChunks_stillStripped() {
        val f = PlanStreamFilter()
        val out = StringBuilder()
        "<plan>{\"a\":1}</plan>正文".forEach { out.append(f.feed(it.toString())) }
        out.append(f.endStream())
        assertEquals("正文", out.toString())
        assertEquals("{\"a\":1}", f.plan())
    }

    @Test
    fun textBeforeOpenTag_passesThrough() {
        val f = PlanStreamFilter()
        val out = f.feed("前言<plan>x</plan>后语") + f.endStream()
        assertEquals("前言后语", out)
        assertEquals("x", f.plan())
    }

    @Test
    fun noTagAtAll_passthrough() {
        val f = PlanStreamFilter()
        assertEquals("普通回复，没有任何标记", f.feed("普通回复，没有任何标记"))
        assertNull(f.plan())
        assertEquals("", f.endStream())
    }

    @Test
    fun unterminatedOversizedBlock_fallsBackToVisible() {
        val f = PlanStreamFilter()
        val huge = "很".repeat(PlanStreamFilter.MAX_PLAN_CHARS + 50)
        val out = f.feed(PlanStreamFilter.PLAN_OPEN + huge)
        assertEquals(PlanStreamFilter.PLAN_OPEN + huge, out)
        assertNull(f.plan())
        // 兜底后已转 BODY，后续直通。
        assertEquals("尾", f.feed("尾"))
    }

    @Test
    fun unterminatedAtEndStream_isReturnedForPersistence() {
        val f = PlanStreamFilter()
        assertEquals("", f.feed(PlanStreamFilter.PLAN_OPEN + "半截战术"))
        assertNull(f.plan())
        assertEquals(PlanStreamFilter.PLAN_OPEN + "半截战术", f.endStream())
    }

    @Test
    fun cjkSplitAcrossChunks_keepsBodyWhole() {
        val f = PlanStreamFilter()
        val out = StringBuilder()
        // 闭标记跨 chunk 切分（</pl | an>），CJK 正文在其后。
        listOf("<plan>战术", "</pl", "an>周末", "一起吃饭吗").forEach {
            out.append(f.feed(it))
        }
        out.append(f.endStream())
        assertEquals("周末一起吃饭吗", out.toString())
        assertEquals("战术", f.plan())
    }

    @Test
    fun emptyStream_andFencedPlan_isHandled() {
        val empty = PlanStreamFilter()
        assertEquals("", empty.feed(""))
        assertEquals("", empty.endStream())

        val fenced = PlanStreamFilter()
        val raw = "<plan>```json\n{\"mission\":\"约咖啡\"}\n```</plan>那周六见"
        assertEquals("那周六见", fenced.feed(raw))
        assertEquals("```json\n{\"mission\":\"约咖啡\"}\n```", fenced.plan())
    }

    @Test
    fun splitFullText_returnsBodyAndPlan() {
        val (body, plan) = PlanStreamFilter.split(
            "<plan>{\"mission\":\"先问预算\"}</plan>\n\n建议这样回：先问预算区间。",
        )
        assertEquals("{\"mission\":\"先问预算\"}", plan)
        assertEquals("建议这样回：先问预算区间。", body)
    }

    @Test
    fun splitFullText_unclosedOrMissing_keepsWholeBody() {
        PlanStreamFilter.split("<plan>{\"mission\":\"半截").let { (body, plan) ->
            assertNull(plan)
            assertEquals("<plan>{\"mission\":\"半截", body)
        }
        PlanStreamFilter.split("纯正文无标记").let { (body, plan) ->
            assertNull(plan)
            assertEquals("纯正文无标记", body)
        }
    }
}
