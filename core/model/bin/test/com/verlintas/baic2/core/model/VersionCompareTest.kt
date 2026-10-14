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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionCompareTest {

    @Test
    fun newerPatchMinorAndMajorAreDetected() {
        assertTrue(isVersionNewer("v0.1.5", "0.1.4"))
        assertTrue(isVersionNewer("0.2.0", "0.1.9"))
        assertTrue(isVersionNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun equalOrOlderTagsAreNotNewer() {
        assertFalse(isVersionNewer("v0.1.4", "0.1.4"))
        assertFalse(isVersionNewer("0.1.3", "0.1.4"))
    }

    @Test
    fun prereleaseSuffixIsIgnoredAndMissingPartsDefaultToZero() {
        assertTrue(isVersionNewer("v0.2-rc1", "0.1.4"))
        assertFalse(isVersionNewer("0.2", "0.2.0"))
    }

    @Test
    fun malformedTagsAreNotNewer() {
        assertFalse(isVersionNewer("nightly", "0.1.4"))
        assertFalse(isVersionNewer("", "0.1.4"))
    }
}
