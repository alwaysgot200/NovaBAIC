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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa.quickfind

import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionSpec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「复制视觉定位 JSON / 比例」的序列化与格式化口径（方案 §6.5 / §6.6）。 */
class RpaQuickFindJsonTest {

    private val decode = Json { ignoreUnknownKeys = true }

    @Test
    fun `vision spec is a ratio locator that round-trips`() {
        val text = RpaQuickFindJson.visionSpec(
            ratioX = 0.25f,
            ratioY = 0.5f,
            threshold = 0.86,
            grayscale = false,
            centerX = 250,
            centerY = 1000,
            frameWidth = 1000,
            frameHeight = 2000,
            score = 0.912,
        )

        val spec = decode.decodeFromString(RpaVisionSpec.serializer(), text)
        assertEquals(RpaVisionKind.RATIO, spec.primary.kind)
        assertEquals(0.25f, spec.primary.ratioX, 0.0001f)
        assertEquals(0.5f, spec.primary.ratioY, 0.0001f)
        assertEquals(0.86, spec.primary.threshold, 0.0001)
        assertFalse(spec.primary.grayscale)
        assertEquals(null, spec.fallback)
        assertTrue(spec.primary.isConfigured())
    }

    @Test
    fun `note keeps capture facts for manual verification`() {
        val text = RpaQuickFindJson.visionSpec(
            ratioX = 0.25f,
            ratioY = 0.5f,
            threshold = 0.8,
            grayscale = true,
            centerX = 250,
            centerY = 1000,
            frameWidth = 1000,
            frameHeight = 2000,
            score = 0.9116,
        )

        val spec = decode.decodeFromString(RpaVisionSpec.serializer(), text)
        assertEquals(
            "quickfind center=(250,1000) frame=1000x2000 score=0.912",
            spec.note,
        )
        assertTrue(text.contains("\n"))
    }

    @Test
    fun `ratio pair uses dot decimals regardless of default locale`() {
        assertEquals("0.2500, 0.5000", RpaQuickFindJson.ratioPair(0.25f, 0.5f))
        assertEquals("0.0000, 1.0000", RpaQuickFindJson.ratioPair(0f, 1f))
    }

    @Test
    fun `score formatting keeps three decimals`() {
        assertEquals("0.912", RpaQuickFindJson.formatScore(0.9116))
        assertEquals("1.000", RpaQuickFindJson.formatScore(1.0))
        assertEquals("0.500", RpaQuickFindJson.formatScore(0.5))
    }
}
