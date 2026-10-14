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

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoCoordinatesTest {

    /** Widely cross-checked Shanghai vector: WGS-84 -> GCJ-02. */
    @Test
    fun wgs84ToGcj02MatchesKnownOffset() {
        val converted = GeoCoordinates.wgs84ToGcj02(GeoPoint(31.1774276, 121.5272106))
        assertEquals(31.17530398364597, converted.latitude, 1e-6)
        assertEquals(121.531541859215, converted.longitude, 1e-6)
    }

    @Test
    fun gcj02ToBd09MatchesKnownValue() {
        val converted = GeoCoordinates.gcj02ToBd09(GeoPoint(31.17530398364597, 121.531541859215))
        assertEquals(31.181328606874466, converted.latitude, 1e-6)
        assertEquals(121.53801568643858, converted.longitude, 1e-6)
    }

    @Test
    fun roundTripIsAccurate() {
        val gcj = GeoCoordinates.wgs84ToGcj02(GeoPoint(39.9088, 116.3975))
        val back = GeoCoordinates.gcj02ToWgs84(gcj)
        assertEquals(39.9088, back.latitude, 1e-5)
        assertEquals(116.3975, back.longitude, 1e-5)

        val bd = GeoCoordinates.gcj02ToBd09(gcj)
        val gcjBack = GeoCoordinates.bd09ToGcj02(bd)
        assertEquals(gcj.latitude, gcjBack.latitude, 1e-6)
        assertEquals(gcj.longitude, gcjBack.longitude, 1e-6)
    }

    @Test
    fun outsideChinaPassesThrough() {
        val tokyo = GeoPoint(35.6762, 139.6503)
        assertEquals(tokyo, GeoCoordinates.wgs84ToGcj02(tokyo))
        assertEquals(tokyo, GeoCoordinates.gcj02ToWgs84(tokyo))
        assertTrue(GeoCoordinates.outOfChina(tokyo.latitude, tokyo.longitude))
    }

    @Test
    fun distanceAndBearing() {
        val oneDegreeNorth = GeoCoordinates.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        assertTrue(abs(oneDegreeNorth - 111_195.0) < 100.0)
        assertEquals(0.0, GeoCoordinates.bearingDegrees(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 1e-6)
        assertEquals(90.0, GeoCoordinates.bearingDegrees(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 1e-6)
        assertEquals(180.0, GeoCoordinates.bearingDegrees(GeoPoint(1.0, 0.0), GeoPoint(0.0, 0.0)), 1e-6)
        assertEquals(270.0, GeoCoordinates.bearingDegrees(GeoPoint(0.0, 1.0), GeoPoint(0.0, 0.0)), 1e-6)
    }
}
