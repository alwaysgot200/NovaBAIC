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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GazetteerIndexTest {

    private val places = listOf(
        place("北京市", 39.9042, 116.4074, GazetteerPlace.KIND_CITY),
        place("天津市", 39.3434, 117.3616, GazetteerPlace.KIND_CITY),
        place("廊坊市", 39.5379, 116.6835, GazetteerPlace.KIND_COUNTY),
        place("安次区", 39.5206, 116.6945, GazetteerPlace.KIND_TOWN),
        place("某村", 39.6000, 116.7000, GazetteerPlace.KIND_VILLAGE),
    )
    private val index = GazetteerIndex(places)

    @Test
    fun returnsNearestFirst() {
        val nearest = index.nearest(39.5210, 116.6940, limit = 3)
        assertEquals("安次区", nearest.first().name)
        assertTrue(nearest.first().distanceMeters < 100.0)
        assertTrue(nearest.all { it.distanceMeters >= 0 })
    }

    @Test
    fun kindFilterApplies() {
        val citiesOnly = index.nearest(
            39.5210,
            116.6940,
            limit = 2,
            kinds = GazetteerPlace.KIND_CITY..GazetteerPlace.KIND_CITY,
        )
        assertEquals(listOf("北京市", "天津市"), citiesOnly.map { it.name })
    }

    @Test
    fun farAwayOriginStillFindsPlacesThroughFallback() {
        val nearest = index.nearest(0.0, 0.0, limit = 1)
        assertEquals(1, nearest.size)
        assertEquals("北京市", nearest.first().name)
    }

    @Test
    fun emptyIndexIsSafe() {
        assertTrue(GazetteerIndex(emptyList()).nearest(39.9, 116.4).isEmpty())
    }

    private fun place(name: String, latitude: Double, longitude: Double, kind: Int) =
        GazetteerPlace(
            name = name,
            pinyin = name,
            latitude = latitude,
            longitude = longitude,
            kind = kind,
            population = 0,
        )
}
