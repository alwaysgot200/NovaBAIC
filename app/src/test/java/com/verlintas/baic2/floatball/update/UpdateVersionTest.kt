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

package com.verlintas.baic2.floatball.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 升级判定矩阵：从左到右逐段比较，第三段变小不提示。 */
class UpdateVersionTest {

    private fun manifest(version: String, code: Long) =
        UpdateManifest(version = version, versionCode = code)

    @Test
    fun parseStripsPrereleaseSuffix() {
        assertEquals(SemVer(0, 3, 8), SemVer.parse("0.3.8-dev.gabc1234"))
        assertEquals(SemVer(1, 0, 0), SemVer.parse("1.0.0"))
    }

    @Test
    fun parseRejectsInvalidVersion() {
        assertNull(SemVer.parse("0.3"))
        assertNull(SemVer.parse("v0.3.8"))
        assertNull(SemVer.parse("0.3.x"))
        assertNull(SemVer.parse(""))
    }

    @Test
    fun majorLowerIsForced() {
        assertEquals(
            UpdateDecision.FORCED,
            decideUpdate(SemVer(0, 2, 9), 209L, manifest("1.0.0", 10000L)),
        )
    }

    @Test
    fun minorLowerIsOptional() {
        assertEquals(
            UpdateDecision.OPTIONAL,
            decideUpdate(SemVer(0, 3, 7), 307L, manifest("0.4.0", 400L)),
        )
    }

    @Test
    fun patchLowerIsSilent() {
        assertEquals(
            UpdateDecision.NONE,
            decideUpdate(SemVer(0, 3, 7), 307L, manifest("0.3.8", 308L)),
        )
    }

    @Test
    fun installedHigherIsIgnored() {
        assertEquals(
            UpdateDecision.NONE,
            decideUpdate(SemVer(1, 0, 0), 10000L, manifest("0.9.9", 909L)),
        )
        assertEquals(
            UpdateDecision.NONE,
            decideUpdate(SemVer(0, 3, 7), 307L, manifest("0.3.0", 300L)),
        )
    }

    @Test
    fun sameVersionIsIgnored() {
        assertEquals(
            UpdateDecision.NONE,
            decideUpdate(SemVer(0, 3, 7), 307L, manifest("0.3.7", 307L)),
        )
    }

    @Test
    fun versionCodeGuardPreventsDowngrade() {
        // 服务端 semver 更高但 versionCode 不大于本机 -> 防降级优先，不提示。
        assertEquals(
            UpdateDecision.NONE,
            decideUpdate(SemVer(0, 3, 7), 400L, manifest("0.4.0", 400L)),
        )
    }

    @Test
    fun invalidServerVersionIsIgnored() {
        assertEquals(
            UpdateDecision.NONE,
            decideUpdate(SemVer(0, 3, 7), 307L, manifest("0.3", 308L)),
        )
    }
}
