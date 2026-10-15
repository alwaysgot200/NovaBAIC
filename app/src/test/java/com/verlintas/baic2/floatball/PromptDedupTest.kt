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

import com.verlintas.baic2.core.model.Note
import com.verlintas.baic2.core.model.NoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDedupTest {

    private fun note(
        id: Long,
        content: String,
        kind: NoteKind = NoteKind.FACT,
        updatedAt: Long = 0L,
    ) = Note(id = id, kind = kind, content = content, updatedAt = updatedAt)

    // ---- isDuplicate -------------------------------------------------------

    @Test
    fun personaValueSubstringOfNoteIsDuplicate() {
        assertTrue(PromptDedup.isDuplicate("惜字如金", "对方沟通风格惜字如金，话很少"))
    }

    @Test
    fun unrelatedSentencesAreNotDuplicate() {
        assertFalse(PromptDedup.isDuplicate("我们明天可以发货", "我们现在可以吃饭了吗"))
    }

    @Test
    fun samePriceWithDifferentWordingIsDuplicate() {
        assertTrue(PromptDedup.isDuplicate("报价5000元一台", "上次报价是5000元每台"))
    }

    @Test
    fun differentPriceNumbersAreNeverDuplicate() {
        // 数字锚点不一致（5000 vs 3000）= 两次报价是两条事实，禁止误杀。
        assertFalse(PromptDedup.isDuplicate("报价5000元一台", "报价3000元一台"))
    }

    @Test
    fun shorterThanThreeCharsNeverDuplicates() {
        assertFalse(PromptDedup.isDuplicate("直接", "她说话直接得很"))
    }

    @Test
    fun stopWordOnlyTextNeverDuplicates() {
        assertFalse(PromptDedup.isDuplicate("我们可以", "你们现在觉得怎么样"))
    }

    // ---- filterNotes -------------------------------------------------------

    @Test
    fun filterNotesDropsById() {
        val notes = listOf(note(1, "对方在杭州工作"), note(2, "对方喜欢民谣"))
        val kept = PromptDedup.filterNotes(notes, keepIds = setOf(1L))
        assertEquals(listOf(2L), kept.map { it.id })
    }

    @Test
    fun filterNotesDropsByCuratedText() {
        val notes = listOf(
            note(1, "对方沟通风格惜字如金，话很少"),
            note(2, "对方住在城西，开车过来要半小时"),
        )
        val kept = PromptDedup.filterNotes(notes, keepTexts = listOf("惜字如金"))
        assertEquals(listOf(2L), kept.map { it.id })
    }

    @Test
    fun protectedKindsOnlyDedupById() {
        val promise = note(9, "答应周末请对方吃饭", kind = NoteKind.AGREEMENT)
        val kept = PromptDedup.filterNotes(
            notes = listOf(promise),
            keepTexts = listOf("答应周末请对方吃饭"),
            protectedKinds = setOf(NoteKind.AGREEMENT),
        )
        assertEquals(listOf(9L), kept.map { it.id })
    }

    @Test
    fun filterNotesEmptyInput() {
        assertTrue(PromptDedup.filterNotes(emptyList()).isEmpty())
    }

    // ---- dedupeNotes -------------------------------------------------------

    @Test
    fun dedupeNotesKeepsFirstAndDropsLaterDuplicate() {
        val newer = note(10, "对方预算5000元", updatedAt = 200L)
        val older = note(11, "预算5000元左右", updatedAt = 100L)
        val kept = PromptDedup.dedupeNotes(listOf(newer, older))
        assertEquals(listOf(10L), kept.map { it.id })
    }

    @Test
    fun protectedNotesSurviveButStillSuppressLaterFacts() {
        val promise1 = note(20, "下周三见面时把合同带上", kind = NoteKind.AGREEMENT)
        val promise2 = note(21, "下周三见面时把合同带上，别忘了", kind = NoteKind.AGREEMENT)
        val echoFact = note(22, "下周三见面带合同", kind = NoteKind.FACT)
        val kept = PromptDedup.dedupeNotes(
            listOf(promise1, promise2, echoFact),
            protectedKinds = setOf(NoteKind.AGREEMENT, NoteKind.PLAN, NoteKind.EVENT, NoteKind.SUMMARY),
        )
        // 两条承诺各自保留；重复承诺措辞的普通事实仍被挤掉。
        assertEquals(listOf(20L, 21L), kept.map { it.id })
    }

    @Test
    fun dedupeNotesKeepsDistinctFacts() {
        val notes = listOf(
            note(1, "对方在滨江上班"),
            note(2, "下周五出差回来再联系"),
        )
        assertEquals(2, PromptDedup.dedupeNotes(notes).size)
    }

    // ---- filterTexts (DealFact 摘录) ---------------------------------------

    @Test
    fun filterTextsDropsAgainstReferencesAndDedupsWithin() {
        val candidates = listOf(
            "报价5000元一台",
            "报价3000元一台",
        )
        val kept = PromptDedup.filterTexts(
            candidates,
            against = listOf("上次报价是5000元每台"),
        )
        // 与脉络同价的一条剔除；数字不同的一条保留。
        assertEquals(listOf("报价3000元一台"), kept)
    }
}
