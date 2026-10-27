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

package com.verlintas.baic2.floatball.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 找图帧源阶梯（2026-10-27「回复慢」归因修复）的分辨率闸纯函数：
 * 投影下采样帧不得顶替原生分辨率帧，否则绿色发送键阈值与 OCR 口径都会走歪。
 */
class FrameSourceResolutionTest {

    @Test
    fun nativeWidth_accepted() {
        assertTrue(FrameSource.resolutionUsable(frameWidth = 1080, screenWidth = 1080))
    }

    @Test
    fun smallVariance_accepted() {
        // 投影与屏宽常见 ±2px 偏差，不应误判。
        assertTrue(FrameSource.resolutionUsable(frameWidth = 1088, screenWidth = 1080))
        assertTrue(FrameSource.resolutionUsable(frameWidth = 1078, screenWidth = 1080))
        assertTrue(FrameSource.resolutionUsable(frameWidth = 972, screenWidth = 1080))
    }

    @Test
    fun downsampledWidth_rejected() {
        // HyperOS 投影约 2× 下采样，实测会让 ML Kit OCR 掉字并算歪发送键。
        assertFalse(FrameSource.resolutionUsable(frameWidth = 540, screenWidth = 1080))
        assertFalse(FrameSource.resolutionUsable(frameWidth = 960, screenWidth = 1080))
    }

    @Test
    fun unknownOrInvalid_degradesByContract() {
        // 拿不到屏宽时放行（由上层证据兜底）；非法帧宽一律拒绝。
        assertTrue(FrameSource.resolutionUsable(frameWidth = 1080, screenWidth = null))
        assertTrue(FrameSource.resolutionUsable(frameWidth = 1080, screenWidth = 0))
        assertFalse(FrameSource.resolutionUsable(frameWidth = 0, screenWidth = 1080))
        assertFalse(FrameSource.resolutionUsable(frameWidth = -1, screenWidth = 1080))
    }
}