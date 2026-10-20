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

package com.verlintas.baic2.floatball.auto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ReadinessGate.missingTemplates] 纯函数：必备模板集差集计算（目录前缀剥离 +
 * .png 后缀归一 + 排序输出）。八项探针中的 Android 边界（shell/OCR/剪贴板）
 * 不在此测，R1-7 真机验收覆盖。
 */
class ReadinessGateTest {

    @Test
    fun allPresent_noMissing() {
        val present = listOf("send.png", "search.png", "back.png", "more.png")
        assertEquals(emptyList<String>(), ReadinessGate.missingTemplates(present))
    }

    @Test
    fun emptyPresent_listsAllSorted() {
        assertEquals(listOf("back", "more", "search", "send"), ReadinessGate.missingTemplates(emptyList()))
    }

    @Test
    fun partialPresent_listsOnlyMissing() {
        val present = listOf("send.png", "back.png", "extra.png")
        assertEquals(listOf("more", "search"), ReadinessGate.missingTemplates(present))
    }

    @Test
    fun subdirectoryPrefix_stripped() {
        val present = listOf("rpa_templates/send.png", "search.png", "back.png", "more.png")
        assertEquals(emptyList<String>(), ReadinessGate.missingTemplates(present))
    }

    @Test
    fun noSuffix_alsoAccepted() {
        val present = listOf("send", "search", "back", "more")
        assertEquals(emptyList<String>(), ReadinessGate.missingTemplates(present))
    }
}
