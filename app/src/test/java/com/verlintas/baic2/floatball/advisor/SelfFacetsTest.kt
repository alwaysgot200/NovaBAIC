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
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfFacetsTest {

    @Test
    fun encodeDedupesKeepsOrderAndReturnsNullForEmpty() {
        assertEquals("""["硬件","模具"]""", SelfFacets.encode(listOf("硬件", "模具", "硬件", " ")))
        assertNull(SelfFacets.encode(emptyList()))
        assertNull(SelfFacets.encode(listOf(" ", "")))
    }

    @Test
    fun decodeIsDefensiveAgainstNullAndMalformed() {
        assertEquals(listOf("硬件"), SelfFacets.decode("""["硬件"]"""))
        assertEquals(emptyList<String>(), SelfFacets.decode(null))
        assertEquals(emptyList<String>(), SelfFacets.decode("not-json"))
        assertEquals(emptyList<String>(), SelfFacets.decode("""{"a":1}"""))
    }

    @Test
    fun mergePendingKeepsMainFacetAndCollectsOtherDifference() {
        // 合并协议第 2 步：主档案补丁为准（extra/masked 都不覆写）；other 有而
        // main 没有的专属差异连同主档案原有 pending 一起进待确认区（V-C 对象
        // 形态），不静默丢弃；style 待定值原样保留。
        val (facet, pending) = SelfFacets.mergePending(
            mainJson = """["硬件","报价"]""",
            otherJson = """["报价","模具"]""",
            mainPendingJson = """{"facet":["电商"],"identity":"领导"}""",
        )
        // 旧数组收进补丁 extra（一个不丢）。
        assertEquals(listOf("硬件", "报价"), SelfFacets.decodePatch(facet).extra)
        val decoded = SelfFacets.decodePending(pending)!!
        assertEquals(listOf("电商", "模具"), decoded.facet)
        assertEquals("领导", decoded.identity)
    }

    @Test
    fun mergePendingReturnsNullPendingWhenNothingToAdd() {
        val (facet, pending) = SelfFacets.mergePending(
            mainJson = """["硬件"]""",
            otherJson = """["硬件"]""",
            mainPendingJson = null,
        )
        assertEquals(listOf("硬件"), SelfFacets.decodePatch(facet).extra)
        assertNull(pending)
    }

    // ── 2026-10-23 §3.1 继承+补丁：FacetPatch 编解码与 resolve 生效集 ──

    @Test
    fun decodePatch_legacyArrayGoesToExtraNothingLost() {
        val patch = SelfFacets.decodePatch("""["硬件","报价"]""")
        assertEquals(listOf("硬件", "报价"), patch.extra)
        assertTrue(patch.masked.isEmpty())
    }

    @Test
    fun decodePatch_objectShapeAndDefensive() {
        val patch = SelfFacets.decodePatch("""{"extra":["会来事"],"masked":["冷淡"]}""")
        assertEquals(listOf("会来事"), patch.extra)
        assertEquals(listOf("冷淡"), patch.masked)
        // null/畸形/空 = 纯继承（空补丁），绝不抛。
        assertTrue(SelfFacets.decodePatch(null).isEmpty)
        assertTrue(SelfFacets.decodePatch("").isEmpty)
        assertTrue(SelfFacets.decodePatch("not-json").isEmpty)
        assertTrue(SelfFacets.decodePatch("""{"extra":[" "]}""").isEmpty)
    }

    @Test
    fun encodePatch_emptyYieldsNullForPureInheritance() {
        assertNull(SelfFacets.encodePatch(SelfFacets.FacetPatch()))
        assertNull(SelfFacets.encodePatch(SelfFacets.FacetPatch(extra = listOf(" "))))
        val roundTrip = SelfFacets.decodePatch(
            SelfFacets.encodePatch(SelfFacets.FacetPatch(extra = listOf("会来事"), masked = listOf("冷淡"))),
        )
        assertEquals(listOf("会来事"), roundTrip.extra)
        assertEquals(listOf("冷淡"), roundTrip.masked)
    }

    @Test
    fun resolve_globalMinusMaskedUnionExtra() {
        val global = listOf("博学", "风趣", "会吃喝")
        // 默认全量继承：无补丁 = 全局库原样生效。
        assertEquals(global, SelfFacets.resolve(null, global))
        // 屏蔽生效：此关系不用「会吃喝」。
        assertEquals(
            listOf("博学", "风趣"),
            SelfFacets.resolve("""{"masked":["会吃喝"]}""", global),
        )
        // 专属补充在全局序之后追加，全局序在前、去重保序。
        assertEquals(
            listOf("博学", "风趣", "会吃喝", "会来事"),
            SelfFacets.resolve("""{"extra":["会来事"]}""", global),
        )
        // 旧快照数组数据：一个不丢收进 extra（= 曾经的快照语义近似）。
        assertEquals(
            listOf("博学", "风趣", "会吃喝", "模具"),
            SelfFacets.resolve("""["模具"]""", global),
        )
    }

    // ── V-C 通用待确认通道（StylePending 对象形态） ───────────────────────

    @Test
    fun stylePending_isEmptyCoversAllDimensions() {
        assertTrue(SelfFacets.StylePending().isEmpty)
        assertTrue(SelfFacets.StylePending(identity = " ").isEmpty)
        // 任一维度非空即非空。
        assertEquals(
            false,
            SelfFacets.StylePending(identity = "领导").isEmpty,
        )
        assertEquals(false, SelfFacets.StylePending(closeness = 2).isEmpty)
    }

    @Test
    fun decodePending_acceptsObjectShape() {
        val pending = SelfFacets.decodePending(
            """{"facet":["模具"],"identity":"客户","chatStyle":"严肃","closeness":1}""",
        )!!
        assertEquals(listOf("模具"), pending.facet)
        assertEquals("客户", pending.identity)
        assertEquals("严肃", pending.chatStyle)
        assertEquals(1, pending.closeness)
    }

    @Test
    fun decodePending_backCompatWithLegacyArrayShape() {
        // VA-2 合并协议历史数据是纯 facet 数组：向后兼容为只有 facet 的对象。
        val pending = SelfFacets.decodePending("""["电商","模具"]""")!!
        assertEquals(listOf("电商", "模具"), pending.facet)
        assertNull(pending.identity)
        assertNull(pending.chatStyle)
        assertNull(pending.closeness)
    }

    @Test
    fun decodePending_nullMalformedOrEmptyAllYieldNull() {
        assertNull(SelfFacets.decodePending(null))
        assertNull(SelfFacets.decodePending(""))
        assertNull(SelfFacets.decodePending("not-json"))
        assertNull(SelfFacets.decodePending("{}"))
        assertNull(SelfFacets.decodePending("[]"))
    }

    @Test
    fun encodePending_roundtripAndClearColumnSemantics() {
        val json = SelfFacets.encodePending(
            SelfFacets.StylePending(identity = "领导", closeness = 1),
        )!!
        val decoded = SelfFacets.decodePending(json)!!
        assertEquals("领导", decoded.identity)
        assertEquals(1, decoded.closeness)
        // 全空 = 无待确认内容 → null 清列。
        assertNull(SelfFacets.encodePending(SelfFacets.StylePending()))
    }

    @Test
    fun promoteAll_mergesFacetAndCarriesStyleOnlyForCaller() {
        val promoted = SelfFacets.promoteAll(
            facetJson = """{"extra":["硬件"],"masked":["模具"]}""",
            pendingJson = """{"facet":["硬件","模具"],"identity":"领导","chatStyle":"严肃","closeness":2}""",
        )
        // 提案标签并入 extra，且同名屏蔽被解除（§3.2 转正语义）。
        val patch = SelfFacets.decodePatch(promoted.facetJson)
        assertEquals(listOf("硬件", "模具"), patch.extra)
        assertTrue(patch.masked.isEmpty())
        assertEquals("领导", promoted.identity)
        assertEquals("严肃", promoted.chatStyle)
        assertEquals(2, promoted.closeness)
    }

    @Test
    fun promoteAll_noPending_yieldsNullStyleValues() {
        val promoted = SelfFacets.promoteAll(facetJson = """["硬件"]""", pendingJson = null)
        assertEquals(listOf("硬件"), SelfFacets.decodePatch(promoted.facetJson).extra)
        assertNull(promoted.identity)
        assertNull(promoted.chatStyle)
        assertNull(promoted.closeness)
    }

    @Test
    fun promoteAll_blankPendingValues_dropped() {
        val promoted = SelfFacets.promoteAll(
            facetJson = null,
            pendingJson = """{"identity":"  ","chatStyle":"","closeness":2}""",
        )
        // 空白 style 值视为无待定（调用方保持原列）；closeness 数值透传。
        assertNull(promoted.facetJson)
        assertNull(promoted.identity)
        assertNull(promoted.chatStyle)
        assertEquals(2, promoted.closeness)
    }
}
