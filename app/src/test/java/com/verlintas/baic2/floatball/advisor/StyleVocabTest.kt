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
}
