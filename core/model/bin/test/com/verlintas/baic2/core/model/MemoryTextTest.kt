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
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.core.model

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryTextTest {

    @Test
    fun normalizeIgnoresCaseAndPunctuation() {
        assertEquals("用户喜欢咖啡", MemoryText.normalize("用户喜欢咖啡。"))
        assertEquals("likesdarkthemes", MemoryText.normalize("Likes dark themes!"))
    }

    @Test
    fun termsBecomeBigramsForCjkAndWordsForLatin() {
        assertEquals(listOf("咖啡"), MemoryText.terms("咖啡"))
        assertEquals(listOf("燕麦", "麦拿", "拿铁"), MemoryText.terms("燕麦拿铁"))
        assertTrue(MemoryText.terms("the coffee please").containsAll(listOf("coffee", "please")))
        assertTrue(MemoryText.terms("a to of").isEmpty())
    }

    @Test
    fun stopwordsAreDroppedFromCues() {
        val terms = MemoryText.terms("我的生日是什么时候")
        assertTrue(terms.contains("生日"), "content words survive")
        assertTrue(
            terms.none { it in setOf("我的", "什么", "时候", "是") },
            "filler bigrams and particles are filtered",
        )
        assertTrue(MemoryText.terms("the and you").isEmpty())
    }

    @Test
    fun snippetCentresOnTheCue() {
        val content = "x".repeat(200) + "咖啡" + "y".repeat(200)
        val snippet = MemoryText.snippet(content, listOf("咖啡"), window = 60)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("咖啡"))
        assertTrue(snippet.length <= 64)
    }

    @Test
    fun snippetKeepsShortContentWhole() {
        assertEquals("hello world", MemoryText.snippet("hello world", listOf("world")))
        assertEquals("", MemoryText.snippet("   ", emptyList()))
    }

    @Test
    fun relativeTimeWalksTheForgettingCurve() {
        val now = 1_000_000_000_000L
        assertEquals("just now", MemoryText.relativeTime(now, now - 5_000))
        assertEquals("3 min ago", MemoryText.relativeTime(now, now - 3 * 60_000))
        assertEquals("2 h ago", MemoryText.relativeTime(now, now - 2 * 3_600_000))
        assertEquals("yesterday", MemoryText.relativeTime(now, now - 30 * 3_600_000))
        assertEquals("3 days ago", MemoryText.relativeTime(now, now - 3 * 86_400_000L))
        assertTrue(MemoryText.relativeTime(now, now - 60 * 86_400_000L).startsWith("on "))
    }

    @Test
    fun parseWhenAcceptsDatesAndInstants() {
        val zone = ZoneId.of("Asia/Shanghai")
        val date = MemoryText.parseWhen("2026-09-12", zone)
        assertEquals(MemoryText.formatDateTime(date!!, zone), "2026-09-12 00:00")
        assertEquals(
            MemoryText.formatDateTime(MemoryText.parseWhen("2026-09-12 14:30", zone)!!, zone),
            "2026-09-12 14:30",
        )
        assertEquals("2026-09-12", MemoryText.dateOnly(MemoryText.parseWhen("2026-09-12T10:00:00Z", zone)!!, zone))
        assertNull(MemoryText.parseWhen("sometime next week", zone))
        assertNull(MemoryText.parseWhen(null, zone))
    }

    @Test
    fun similarityFindsNearDuplicatesAndSeparatesStrangers() {
        assertEquals(1.0, MemoryText.similarity("用户喜欢深色主题。", "用户喜欢深色主题"))
        assertEquals(1.0, MemoryText.similarity("Likes dark themes!", "likes dark themes"))
        assertTrue(MemoryText.similarity("用户喜欢深色主题", "用户喜欢深色主题界面") >= 0.8)
        assertTrue(MemoryText.similarity("用户住在杭州", "用户住在上海") in 0.55..0.84)
        assertTrue(MemoryText.similarity("用户喜欢咖啡", "会议定在周三下午") < 0.55)
        assertEquals(0.0, MemoryText.similarity("", "anything"))
    }

    @Test
    fun entitiesRoundTripAndQueryPattern() {
        val encoded = MemoryText.encodeEntities(listOf("张伟", "BAIC2", "", "张伟"))
        assertEquals(listOf("张伟", "BAIC2"), MemoryText.decodeEntities(encoded))
        assertEquals("", MemoryText.encodeEntities(emptyList()))
        assertTrue(MemoryText.entityLikePattern("张伟").contains("\u0001张伟\u0001"))
    }

    @Test
    fun parseExpiryAcceptsDurationsAndDates() {
        val now = 1_700_000_000_000L
        assertEquals(now + 30 * 60_000L, MemoryText.parseExpiry("30m", now))
        assertEquals(now + 12 * 3_600_000L, MemoryText.parseExpiry("in 12h", now))
        assertEquals(now + 3 * 86_400_000L, MemoryText.parseExpiry("3 天", now))
        assertEquals(now + 2 * 604_800_000L, MemoryText.parseExpiry("2周", now))
        assertEquals(now + 45 * 60_000L, MemoryText.parseExpiry("45分钟后", now))
        val absolute = MemoryText.parseWhen("2026-09-12")!!
        assertEquals(absolute, MemoryText.parseExpiry("2026-09-12", now))
        assertNull(MemoryText.parseExpiry("", now))
        assertNull(MemoryText.parseExpiry("soon", now))
        assertNull(MemoryText.parseExpiry("0d", now))
    }
}
