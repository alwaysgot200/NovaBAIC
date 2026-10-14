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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS-84 or converted coordinate pair. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Offline geodesy: the public WGS-84 ⇄ GCJ-02 ⇄ BD-09 transforms plus
 * distance/bearing. Pure math, no Play Services and no network — Android's
 * Geocoder is deliberately avoided (GMS-less devices return null or throw).
 */
object GeoCoordinates {

    private const val A = 6_378_245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = Math.PI * 3000.0 / 180.0
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun outOfChina(latitude: Double, longitude: Double): Boolean =
        longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271

    /** The offset mainland map providers expect on top of raw GPS. */
    fun wgs84ToGcj02(point: GeoPoint): GeoPoint {
        if (outOfChina(point.latitude, point.longitude)) return point
        val dLat = transformLat(point.longitude - 105.0, point.latitude - 35.0)
        val dLng = transformLng(point.longitude - 105.0, point.latitude - 35.0)
        val radLat = point.latitude / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val latOffset = dLat * 180.0 / (A * (1 - EE) / (magic * sqrtMagic) * Math.PI)
        val lngOffset = dLng * 180.0 / (A / sqrtMagic * cos(radLat) * Math.PI)
        return GeoPoint(point.latitude + latOffset, point.longitude + lngOffset)
    }

    /** Exact inverse of [wgs84ToGcj02] by fixed-point iteration (<1 m error). */
    fun gcj02ToWgs84(point: GeoPoint): GeoPoint {
        if (outOfChina(point.latitude, point.longitude)) return point
        var latitude = point.latitude
        var longitude = point.longitude
        repeat(4) {
            val forward = wgs84ToGcj02(GeoPoint(latitude, longitude))
            latitude -= forward.latitude - point.latitude
            longitude -= forward.longitude - point.longitude
        }
        return GeoPoint(latitude, longitude)
    }

    fun gcj02ToBd09(point: GeoPoint): GeoPoint {
        val x = point.longitude
        val y = point.latitude
        val z = sqrt(x * x + y * y) + 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) + 0.000003 * cos(x * X_PI)
        return GeoPoint(
            z * sin(theta) + 0.006,
            z * cos(theta) + 0.0065,
        )
    }

    fun bd09ToGcj02(point: GeoPoint): GeoPoint {
        val x = point.longitude - 0.0065
        val y = point.latitude - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        return GeoPoint(
            z * sin(theta),
            z * cos(theta),
        )
    }

    /** Great-circle distance in meters. */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_METERS * kotlin.math.asin(kotlin.math.min(1.0, sqrt(h)))
    }

    /** Initial bearing from [from] to [to], 0..360 clockwise from north. */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
