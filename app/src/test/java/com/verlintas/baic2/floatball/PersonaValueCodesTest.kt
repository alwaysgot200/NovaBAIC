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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import com.verlintas.baic2.floatball.advisor.FieldOption
import com.verlintas.baic2.floatball.advisor.PersonaFieldDef
import com.verlintas.baic2.floatball.advisor.PersonaFieldType
import com.verlintas.baic2.floatball.advisor.PersonaValueCodes
import com.verlintas.baic2.floatball.advisor.PersonaValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaValueCodesTest {

    private val commStyle = PersonaFieldDef(
        key = "commStyle",
        owner = "their",
        group = "g",
        type = PersonaFieldType.ENUM,
        label = "沟通风格",
        options = listOf(
            FieldOption(code = "direct", label = "直接干脆"),
            FieldOption(code = "terse", label = "惜字如金"),
        ),
    )

    private val loveLanguage = PersonaFieldDef(
        key = "loveLanguage",
        owner = "their",
        group = "g",
        type = PersonaFieldType.TAGS,
        label = "好感方式",
        options = listOf(
            FieldOption(code = "listening", label = "被倾听"),
            FieldOption(code = "humor", label = "幽默感"),
        ),
    )

    private val freeTags = PersonaFieldDef(
        key = "interests",
        owner = "their",
        group = "g",
        type = PersonaFieldType.TAGS,
        label = "兴趣爱好",
        options = null,
    )

    private val noCustom = commStyle.copy(key = "genderHint", label = "性别", allowCustom = false)

    @Test
    fun storedOption_mapsLabelAndCode() {
        assertEquals("direct", PersonaValueCodes.storedOption(commStyle, "直接干脆"))
        assertEquals("terse", PersonaValueCodes.storedOption(commStyle, "terse"))
        assertNull(PersonaValueCodes.storedOption(commStyle, "  "))
    }

    @Test
    fun storedOption_unknownBecomesCustomWithoutDoublePrefix() {
        assertEquals("~有点高冷", PersonaValueCodes.storedOption(commStyle, " 有点高冷 "))
        assertEquals("~有点高冷", PersonaValueCodes.storedOption(commStyle, "~有点高冷"))
    }

    @Test
    fun displaySingle_resolvesCodeCustomAndLegacyLabel() {
        assertEquals("惜字如金", PersonaValueCodes.displaySingle(commStyle, "terse"))
        assertEquals("有点高冷", PersonaValueCodes.displaySingle(commStyle, "~有点高冷"))
        assertEquals("直接干脆", PersonaValueCodes.displaySingle(commStyle, "直接干脆"))
        assertEquals("火星语", PersonaValueCodes.displaySingle(commStyle, "火星语"))
        assertEquals("原样文本", PersonaValueCodes.displaySingle(null, "原样文本"))
    }

    @Test
    fun displayValue_tagsMixedRenderWithChineseComma() {
        val stored = PersonaValues.encodeTags(listOf("listening", "~喜欢手写便签", "幽默感"))
        assertEquals("被倾听、喜欢手写便签、幽默感", PersonaValueCodes.displayValue(loveLanguage, stored))
    }

    @Test
    fun normalizeExtracted_respectsAllowCustom() {
        assertEquals("direct", PersonaValueCodes.normalizeExtracted(commStyle, "直接干脆"))
        assertEquals("~自造风格", PersonaValueCodes.normalizeExtracted(commStyle, "自造风格"))
        assertNull(PersonaValueCodes.normalizeExtracted(noCustom, "自造风格"))
        assertNull(PersonaValueCodes.normalizeExtracted(commStyle, "  "))
    }

    @Test
    fun normalizeTags_dropsRejectedValuesAndKeepsOrder() {
        val encoded = PersonaValueCodes.normalizeTags(
            loveLanguage.copy(allowCustom = false),
            listOf("被倾听", "自造", "幽默感", "幽默感"),
        )
        assertEquals(listOf("listening", "humor"), PersonaValues.decodeTags(encoded))
    }

    @Test
    fun migrateSingle_legacyLabelToCode_isIdempotent() {
        assertEquals("direct", PersonaValueCodes.migrateSingle(commStyle, "直接干脆"))
        assertEquals("direct", PersonaValueCodes.migrateSingle(commStyle, "direct"))
        assertEquals("~保留", PersonaValueCodes.migrateSingle(commStyle, "~保留"))
        assertEquals("", PersonaValueCodes.migrateSingle(commStyle, ""))
    }

    @Test
    fun migrateSingle_unknownLegacyValueSurvivesAsCustom() {
        assertEquals("~老派学者风", PersonaValueCodes.migrateSingle(commStyle, "老派学者风"))
        // 二次执行幂等：~ 前缀不再处理
        assertEquals("~老派学者风", PersonaValueCodes.migrateSingle(commStyle, "~老派学者风"))
    }

    @Test
    fun migrateValue_tagsConvertedElementWise() {
        val legacy = PersonaValues.encodeTags(listOf("被倾听", "自造词"))
        val migrated = PersonaValueCodes.migrateValue(loveLanguage, legacy)
        assertEquals(listOf("listening", "~自造词"), PersonaValues.decodeTags(migrated))
    }

    @Test
    fun freeOptionFields_areStoredAndMigratedVerbatim() {
        val raw = PersonaValues.encodeTags(listOf("livehouse", "徒步"))
        assertEquals(raw, PersonaValueCodes.migrateValue(freeTags, raw))
        assertEquals("livehouse、徒步", PersonaValueCodes.displayValue(freeTags, raw))
        assertEquals("徒步", PersonaValueCodes.normalizeExtracted(freeTags, " 徒步 "))
        assertTrue(PersonaValueCodes.isCode("sales_b2b"))
    }
}
