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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对方风格三标签中文词表的归一规则（V-C 方案 §4.1）：萃取输出只认词表枚举，
 * 词表外一律丢弃（绝不自造枚举值）。
 */
class StyleVocabTest {

    @Test
    fun normalizeIdentity_exactAndContainMatches() {
        assertEquals("领导", StyleVocab.normalizeIdentity("领导"))
        assertEquals("领导", StyleVocab.normalizeIdentity("他是领导"))
        assertEquals("客户", StyleVocab.normalizeIdentity("大客户经理"))
        assertEquals("暧昧对象", StyleVocab.normalizeIdentity("  暧昧对象  "))
        assertNull(StyleVocab.normalizeIdentity("投资人"))
        assertNull(StyleVocab.normalizeIdentity(null))
        assertNull(StyleVocab.normalizeIdentity("  "))
    }

    @Test
    fun normalizeChatStyle_exactAndContainMatches() {
        assertEquals("严肃", StyleVocab.normalizeChatStyle("严肃"))
        assertEquals("热情", StyleVocab.normalizeChatStyle("比较热情的人"))
        assertNull(StyleVocab.normalizeChatStyle("毒舌"))
        assertNull(StyleVocab.normalizeChatStyle(""))
    }

    @Test
    fun closenessOf_acceptsChineseWordOrInteger() {
        assertEquals(0, StyleVocab.closenessOf("陌生"))
        assertEquals(2, StyleVocab.closenessOf("亲近"))
        assertEquals(3, StyleVocab.closenessOf("亲密"))
        assertEquals(1, StyleVocab.closenessOf("1"))
        assertNull(StyleVocab.closenessOf("4"))
        assertNull(StyleVocab.closenessOf("-1"))
        assertNull(StyleVocab.closenessOf("点头之交"))
        assertNull(StyleVocab.closenessOf(null))
    }

    @Test
    fun closenessLabel_mapsLevelToWord() {
        assertEquals("陌生", StyleVocab.closenessLabel(0))
        assertEquals("熟识", StyleVocab.closenessLabel(1))
        assertEquals("亲近", StyleVocab.closenessLabel(2))
        assertEquals("亲密", StyleVocab.closenessLabel(3))
        // 越界夹逼到最近档（仅展示用，不抛）。
        assertEquals("亲密", StyleVocab.closenessLabel(9))
        assertEquals("陌生", StyleVocab.closenessLabel(-2))
    }

    // ── F8：词表锁（UI 引用本对象常量后，成员清单即写库值全集，锁死防漂移） ──

    @Test
    fun vocabLists_exactMembershipAndOrder() {
        assertEquals(
            listOf("领导", "客户", "同事", "朋友", "家人", "暧昧对象", "其他"),
            StyleVocab.IDENTITIES,
        )
        assertEquals(
            listOf("严肃", "随和", "幽默", "热情", "冷淡"),
            StyleVocab.CHAT_STYLES,
        )
        assertEquals(
            listOf("陌生", "熟识", "亲近", "亲密"),
            StyleVocab.CLOSENESS_LEVELS,
        )
    }

    @Test
    fun vocabWords_roundTripThroughNormalizers() {
        // 每个词表词都能经归一通道回查到自己：UI 写下的值必可被萃取侧认回。
        StyleVocab.IDENTITIES.forEach { word ->
            assertEquals(word, StyleVocab.normalizeIdentity(word))
        }
        StyleVocab.CHAT_STYLES.forEach { word ->
            assertEquals(word, StyleVocab.normalizeChatStyle(word))
        }
        StyleVocab.CLOSENESS_LEVELS.forEachIndexed { level, word ->
            assertEquals(level, StyleVocab.closenessOf(word))
            assertEquals(word, StyleVocab.closenessLabel(level))
        }
    }

    @Test
    fun vocabLists_noDuplicateEntries() {
        assertEquals(StyleVocab.IDENTITIES.size, StyleVocab.IDENTITIES.distinct().size)
        assertEquals(StyleVocab.CHAT_STYLES.size, StyleVocab.CHAT_STYLES.distinct().size)
        assertEquals(StyleVocab.CLOSENESS_LEVELS.size, StyleVocab.CLOSENESS_LEVELS.distinct().size)
    }

    // ── 身份多选（2026-10-23 方案 §2.1）：编码多值 + 旧单值兼容 + 命中任一 ──

    @Test
    fun encodeIdentities_dedupesKeepsOrderAndNullForEmpty() {
        assertEquals("""["领导","家人"]""", StyleVocab.encodeIdentities(listOf("领导", "家人", "领导", " ")))
        assertNull(StyleVocab.encodeIdentities(emptyList()))
        assertNull(StyleVocab.encodeIdentities(listOf(" ", "")))
    }

    @Test
    fun decodeIdentities_acceptsEncodedMultiValue() {
        assertEquals(listOf("领导", "家人"), StyleVocab.decodeIdentities("""["领导","家人"]"""))
    }

    @Test
    fun decodeIdentities_backCompatLegacySingleValue() {
        // 迁移前的旧单值裸字符串仍认回（词表外丢弃不抛）。
        assertEquals(listOf("领导"), StyleVocab.decodeIdentities("领导"))
        assertEquals(emptyList<String>(), StyleVocab.decodeIdentities("投资人"))
        assertEquals(emptyList<String>(), StyleVocab.decodeIdentities(null))
        assertEquals(emptyList<String>(), StyleVocab.decodeIdentities("  "))
    }

    @Test
    fun decodeIdentities_splitsJoinedSegments() {
        assertEquals(listOf("领导", "家人"), StyleVocab.decodeIdentities("领导+家人"))
        assertEquals(listOf("同事", "暧昧对象"), StyleVocab.decodeIdentities("同事、暧昧对象"))
        assertEquals(listOf("客户", "朋友"), StyleVocab.decodeIdentities("客户，朋友"))
    }

    @Test
    fun normalizeIdentities_collectsAllVocabHitsInDeclareOrder() {
        // 「他是我的领导，也是我家人」→ 领导、家人（词表声明序，封顶 3）。
        assertEquals(
            listOf("领导", "家人"),
            StyleVocab.normalizeIdentities("他是我的领导，也是我家人"),
        )
        assertEquals(emptyList<String>(), StyleVocab.normalizeIdentities("投资人"))
        assertEquals(emptyList<String>(), StyleVocab.normalizeIdentities(null))
        assertEquals(3, StyleVocab.normalizeIdentities("领导、客户、同事、朋友").size)
    }

    @Test
    fun hasIdentity_hitsAnyInMultiValue() {
        assertTrue(StyleVocab.hasIdentity("""["领导","家人"]""", StyleVocab.IDENTITY_LEADER))
        assertTrue(StyleVocab.hasIdentity("""["领导","家人"]""", StyleVocab.IDENTITY_FAMILY))
        assertFalse(StyleVocab.hasIdentity("""["同事","暧昧对象"]""", StyleVocab.IDENTITY_LEADER))
        // 旧单值兼容。
        assertTrue(StyleVocab.hasIdentity("领导", StyleVocab.IDENTITY_LEADER))
        assertFalse(StyleVocab.hasIdentity(null, StyleVocab.IDENTITY_LEADER))
    }
}
