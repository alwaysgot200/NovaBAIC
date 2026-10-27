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

package com.verlintas.baic2.floatball.rpa

import com.verlintas.rpa.model.RpaScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脚本库搜索：名称 / 描述 / 包名，大小写不敏感，空查询原样返回。 */
class RpaScriptFilterTest {

    private val scripts = listOf(
        RpaScript(
            id = "1",
            name = "每日问候",
            description = "给客户发消息",
            appPackage = "com.tencent.mm",
        ),
        RpaScript(
            id = "2",
            name = "Weekly Report",
            description = "导出任务列表",
            appPackage = "com.example.tasks",
        ),
        RpaScript(
            id = "3",
            name = "清理通知",
            appPackage = "com.android.settings",
        ),
    )

    @Test
    fun `blank query returns original list`() {
        assertSame(scripts, filterRpaScripts(scripts, ""))
        assertSame(scripts, filterRpaScripts(scripts, "   "))
    }

    @Test
    fun `matches name case insensitive`() {
        assertEquals(listOf("1"), filterRpaScripts(scripts, "问候").map { it.id })
        assertEquals(listOf("2"), filterRpaScripts(scripts, "weekly").map { it.id })
    }

    @Test
    fun `matches description`() {
        assertEquals(listOf("2"), filterRpaScripts(scripts, "导出").map { it.id })
    }

    @Test
    fun `matches package`() {
        assertEquals(listOf("3"), filterRpaScripts(scripts, "settings").map { it.id })
        assertEquals(
            listOf("1"),
            filterRpaScripts(scripts, "TENCENT").map { it.id },
        )
    }

    @Test
    fun `trims query and returns empty when nothing matches`() {
        assertTrue(filterRpaScripts(scripts, " 不存在 ").isEmpty())
    }
}
