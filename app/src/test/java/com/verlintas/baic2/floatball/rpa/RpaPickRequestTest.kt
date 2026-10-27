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

import com.verlintas.rpa.model.RpaPickKind
import com.verlintas.rpa.model.RpaPickRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拾取请求的 TTL、步骤展示与 cache 持久化字段（方案 §7.8）。 */
class RpaPickRequestTest {

    private fun request(
        createdAt: Long = 1_000L,
        stepLabel: String = "发送消息",
    ) = RpaPickRequest(
        requestId = "pick_1",
        scriptId = "script_1",
        stepId = "step_2",
        kind = RpaPickKind.VISION,
        scriptName = "微信问候",
        stepLabel = stepLabel,
        createdAt = createdAt,
    )

    @Test
    fun `fresh request inside ttl is accepted`() {
        val fresh = request(createdAt = 1_000L)
        assertFalse(fresh.isExpired(now = 1_000L + 60_000L))
        assertFalse(fresh.isExpired(now = 1_000L + RpaPickRequest.TTL_MS))
    }

    @Test
    fun `request beyond ttl expires`() {
        val stale = request(createdAt = 1_000L)
        assertTrue(stale.isExpired(now = 1_000L + RpaPickRequest.TTL_MS + 1))
    }

    @Test
    fun `missing created timestamp is treated as expired`() {
        assertTrue(request(createdAt = 0L).isExpired(now = 1_000L))
    }

    @Test
    fun `display step prefers label then index then fallback`() {
        assertEquals("发送消息", request().displayStep(3))
        assertEquals("第 4 步", request(stepLabel = " ").displayStep(3))
        assertEquals("当前步骤", request(stepLabel = "").displayStep(null))
    }

    @Test
    fun `serialization round trip keeps pick fields`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(RpaPickRequest.serializer(), request())
        val decoded = json.decodeFromString(RpaPickRequest.serializer(), encoded)

        assertEquals("script_1", decoded.scriptId)
        assertEquals("step_2", decoded.stepId)
        assertEquals(RpaPickKind.VISION, decoded.kind)
        assertEquals("微信问候", decoded.scriptName)
        assertEquals("发送消息", decoded.stepLabel)
        assertEquals(1_000L, decoded.createdAt)
    }
}
