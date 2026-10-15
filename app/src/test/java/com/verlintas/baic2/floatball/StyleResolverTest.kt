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

import com.verlintas.baic2.floatball.advisor.PersonaValues
import com.verlintas.baic2.floatball.advisor.StyleIntent
import com.verlintas.baic2.floatball.advisor.StylePlaybook
import com.verlintas.baic2.floatball.advisor.StyleResolver
import com.verlintas.baic2.floatball.advisor.StyleTone
import com.verlintas.baic2.floatball.advisor.data.STYLE_SCOPE_CONTACT
import com.verlintas.baic2.floatball.advisor.data.StyleCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleResolverTest {

    private val playbook = StylePlaybook(
        tones = listOf(
            StyleTone("buddy", "朋友式轻松", "口语短句，正事一句话说清"),
        ),
        intents = listOf(
            StyleIntent(
                code = "spark_topic",
                label = "制造话题",
                `do` = listOf("结尾留开放式话钩"),
                dont = listOf("不查户口"),
                good = "好例子",
                bad = "坏例子",
                fitStages = listOf("weak", "attract"),
            ),
            StyleIntent(
                code = "push_close",
                label = "推进邀约",
                `do` = listOf("给二选一"),
                dont = listOf("不连逼两次"),
                fitStages = listOf("probe"),
            ),
            StyleIntent(
                code = "hold_warmth",
                label = "维持热度",
                `do` = listOf("先共情再延展"),
                dont = listOf("不只回哈哈"),
                fitStages = null,
            ),
        ),
    )

    private fun card(
        tone: String? = null,
        intents: List<String> = emptyList(),
        note: String? = null,
    ) = StyleCardEntity(
        scope = STYLE_SCOPE_CONTACT,
        contactId = 7L,
        toneCode = tone,
        intentCodes = PersonaValues.encodeTags(intents),
        customNote = note,
    )

    @Test
    fun render_fullCard_containsTitleToneIntentAndNote() {
        val out = StyleResolver.render(
            card(tone = "buddy", intents = listOf("spark_topic"), note = "先问吃饭没"),
            playbook,
        )!!
        assertTrue(out.startsWith("【说话方式】\n"))
        assertTrue(out.contains("基调：朋友式轻松。口语短句，正事一句话说清"))
        assertTrue(out.contains("意图「制造话题」：要结尾留开放式话钩。不要不查户口。"))
        assertTrue(out.contains("其他要求：先问吃饭没"))
    }

    @Test
    fun render_emptyCard_returnsNull() {
        assertNull(StyleResolver.render(card(), playbook))
        assertNull(StyleResolver.render(null, playbook))
    }

    @Test
    fun render_unknownCodes_areSkipped() {
        val out = StyleResolver.render(
            card(tone = "ghost_tone", intents = listOf("ghost_intent", "spark_topic")),
            playbook,
        )!!
        assertFalse(out.contains("ghost"))
        assertTrue(out.contains("制造话题"))
    }

    @Test
    fun render_onlyCustomNote_rendersNote() {
        val out = StyleResolver.render(card(note = " 别讲大道理 "), playbook)!!
        assertTrue(out.contains("其他要求：别讲大道理"))
        assertFalse(out.contains("基调"))
    }

    @Test
    fun render_stageCodeFiltersByFitStages() {
        val out = StyleResolver.render(
            card(intents = listOf("spark_topic", "push_close", "hold_warmth")),
            playbook,
            stageCode = "probe",
        )!!
        assertFalse(out.contains("制造话题"))
        assertTrue(out.contains("推进邀约"))
        assertTrue(out.contains("维持热度")) // fitStages=null 全阶段适用
    }

    @Test
    fun render_nullStageCode_rendersAllIntents() {
        val out = StyleResolver.render(
            card(intents = listOf("spark_topic", "push_close")),
            playbook,
            stageCode = null,
        )!!
        assertTrue(out.contains("制造话题"))
        assertTrue(out.contains("推进邀约"))
    }

    @Test
    fun renderLegacy_freeTextFallback() {
        assertNull(StyleResolver.renderLegacy("  "))
        assertEquals("【说话方式】\n老派幽默", StyleResolver.renderLegacy(" 老派幽默 "))
    }

    @Test
    fun summary_joinToneAndIntentLabels() {
        assertEquals(
            "朋友式轻松 · 制造话题、推进邀约",
            StyleResolver.summary(card(tone = "buddy", intents = listOf("spark_topic", "push_close")), playbook),
        )
        assertNull(StyleResolver.summary(card(), playbook))
    }
}
