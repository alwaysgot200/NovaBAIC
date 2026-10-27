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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

/**
 * 悬浮球靠边自动隐藏的纯几何与时间参数。
 *
 * 这里只回答四件事：球是否贴边、贴哪一边、收起后窗口 x 放哪、计时多久；
 * 动画、窗口更新与手势唤醒由 [FloatBallService] 负责。保持无 Android 依赖，
 * 便于在 JVM 单测里覆盖四角/贴边/横向分辨率。
 */
internal object FloatEdgeTuck {

    /** 收起后留在屏幕内的可点边条宽度。 */
    const val SLIVER_DP = 10

    /** 距左右屏缘多少 dp 内视为“贴边”。 */
    const val EDGE_PROXIMITY_DP = 24

    /** 空闲后多久自动收起。 */
    const val IDLE_DELAY_MS = 2_500L

    /** 收起 / 滑出的动画时长。 */
    const val ANIM_MS = 180L

    enum class Side { LEFT, RIGHT }

    /**
     * 判定贴边方向；屏幕中间或参数非法返回 null。
     *
     * 用**逻辑坐标**（未收起时的窗口 x）判断，左右都贴时取离屏缘更近的一侧。
     */
    fun sideFor(
        logicalX: Int,
        screenWidth: Int,
        ballSize: Int,
        proximity: Int,
    ): Side? {
        if (screenWidth <= 0 || ballSize <= 0 || proximity < 0) return null
        val leftGap = logicalX
        val rightGap = screenWidth - (logicalX + ballSize)
        val leftNear = leftGap <= proximity
        val rightNear = rightGap <= proximity
        return when {
            leftNear && (!rightNear || leftGap <= rightGap) -> Side.LEFT
            rightNear -> Side.RIGHT
            else -> null
        }
    }

    /**
     * 收起后的窗口 x：左侧藏到 `sliver - ballSize`，右侧放到 `screenWidth - sliver`。
     * 两种情况都只把 [sliver] 宽的边条留在屏内。
     */
    fun tuckedX(
        side: Side,
        screenWidth: Int,
        ballSize: Int,
        sliver: Int,
    ): Int {
        val visible = sliver.coerceIn(0, ballSize)
        return when (side) {
            Side.LEFT -> visible - ballSize
            Side.RIGHT -> screenWidth - visible
        }
    }
}
