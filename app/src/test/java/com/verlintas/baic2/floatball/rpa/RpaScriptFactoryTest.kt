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

import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaCatalog
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaScriptFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 发行版新建脚本 / 拖入动作不得预填任何 App——7 步微信夹具只在 test，
 * 不能把夹具的包名泄漏成产品默认值（方案 §7.12 Demo 审计）。
 */
class RpaScriptFactoryTest {

    @Test
    fun `starter keeps blank package instead of demo app`() {
        val script = RpaScriptFactory.starter(name = "空白脚本", mode = RpaMode.DUAL)
        val launch = script.steps.first { it.action == RpaAction.LAUNCH_APP }

        assertEquals("", script.appPackage)
        assertEquals("", launch.params.packageName)
    }

    @Test
    fun `starter uses provided package`() {
        val script = RpaScriptFactory.starter(
            name = "目标应用",
            mode = RpaMode.A11Y,
            appPackage = "com.example.app",
        )
        val launch = script.steps.first { it.action == RpaAction.LAUNCH_APP }

        assertEquals("com.example.app", script.appPackage)
        assertEquals("com.example.app", launch.params.packageName)
    }

    @Test
    fun `catalog defaults do not prefill demo app`() {
        val launch = RpaCatalog.defaultStep(RpaAction.LAUNCH_APP, fallbackId = "step_launch")
        assertNull(launch.params.packageName)

        val chat = RpaCatalog.defaultStep(RpaAction.OPEN_CHAT, fallbackId = "step_chat")
        assertNull(chat.params.imPackage)
        assertEquals("\${contactName}", chat.params.contactName ?: "")
    }
}
