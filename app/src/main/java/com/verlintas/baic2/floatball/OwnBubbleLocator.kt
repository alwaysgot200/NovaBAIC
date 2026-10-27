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

package com.verlintas.baic2.floatball

import android.graphics.Bitmap
import com.verlintas.baic2.device.api.OcrLine

/**
 * 屏幕像素坐标系里的一个「我方气泡」外接盒（右下开区间）。
 */
internal data class BubbleBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

/**
 * 我方气泡归属判定（2026-10-27 真机加固）：差分引擎只拿到「屏幕上的文字」，
 * 无法区分气泡归属；我方绿色气泡上屏后，下一回合的来信候选就是自己的话，
 * 会被当成对方来信再回复一次（自问自答）。更隐蔽的是 IncomingCue 会把
 * 末尾两行拼成候选——我方旧气泡末行 + 对方新短句「很好」——整段覆盖率
 * 判定误吞真来信。
 *
 * 判定口径：微信我方气泡为品牌绿（#95EC69，g-r=87、g-b=131），对方为白/
 * 浅色。本对象在掩膜后的帧上按粗网格找绿色连通块，得到气泡外接盒；正文
 * OCR 行的中心落在盒内即判我方行。绿色区域必须具备气泡尺度（最小宽高按
 * 帧比例过滤），排除发送键圆点、键盘功能键、头像与绿色图片的干扰。
 *
 * 纯网格计算（[boxesFromGrid]）与像素读取分离，前者 JVM 可单测。
 */
internal object OwnBubbleLocator {

    /** 绿色判定：绿通道下限 + 对红/蓝的显著优势（#EDEDED 灰底差值为 0）。 */
    private const val MIN_GREEN = 170
    private const val GREEN_DOMINANCE = 40

    /** 粗网格边长（像素）：2×2 采样把 260 万像素降到 65 万次判定。 */
    private const val BLOCK_STEP = 2

    /** 一个网格块内绿色像素占比达到该值才算绿色块（抗文字/抗锯齿）。 */
    private const val BLOCK_GREEN_RATIO = 0.5f

    /** 气泡最小尺寸（帧比例）：发送键 ≈90px、头像 ≈108px，均小于 0.15W。 */
    private const val MIN_WIDTH_RATIO = 0.15f
    private const val MIN_HEIGHT_RATIO = 0.012f

    /** 输入栏/键盘带：起点在此之下的绿色块不算正文气泡。 */
    private const val BOTTOM_GUARD_RATIO = 0.90f

    /**
     * 行盒被气泡盒覆盖的判定比例（2026-10-27 真机回声漏判修复）：只判「行中心
     * 在气泡内」时，OCR 把我方长气泡的一行与旁边元素拼成一行（行中心落到气泡
     * 外）就会漏判，实测「先不动,稳住休验」被我方旧气泡漏出、白跑一次 LLM。
     * 行盒与气泡盒的相交面积达行盒面积该比例即判我方——气泡背景完全包住自己
     * 的文字，正常命中接近 1.0；相邻对方白气泡横向不重叠，不会误杀。
     */
    private const val OWN_OVERLAP_RATIO = 0.6f

    /** 读取一帧的我方气泡盒；任何失败按「无我方气泡」处理（不阻断回合）。 */
    fun boxes(bitmap: Bitmap): List<BubbleBox> {
        val width = bitmap.width
        val height = bitmap.height
        if (width < BLOCK_STEP * 2 || height < BLOCK_STEP * 2) return emptyList()
        return runCatching {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val gridWidth = width / BLOCK_STEP
            val gridHeight = height / BLOCK_STEP
            val green = BooleanArray(gridWidth * gridHeight)
            val minGreenPixels = (BLOCK_STEP * BLOCK_STEP * BLOCK_GREEN_RATIO).toInt()
            for (gy in 0 until gridHeight) {
                for (gx in 0 until gridWidth) {
                    var count = 0
                    for (dy in 0 until BLOCK_STEP) {
                        val row = (gy * BLOCK_STEP + dy) * width
                        for (dx in 0 until BLOCK_STEP) {
                            val color = pixels[row + gx * BLOCK_STEP + dx]
                            val red = (color shr 16) and 0xFF
                            val g = (color shr 8) and 0xFF
                            val blue = color and 0xFF
                            if (g >= MIN_GREEN &&
                                g - red >= GREEN_DOMINANCE &&
                                g - blue >= GREEN_DOMINANCE
                            ) {
                                count++
                            }
                        }
                    }
                    green[gy * gridWidth + gx] = count >= minGreenPixels
                }
            }
            boxesFromGrid(green, gridWidth, gridHeight, BLOCK_STEP, width, height)
        }.getOrDefault(emptyList())
    }

    /**
     * OCR 行是否属于我方气泡：行中心在气泡内（既有口径）**或**行盒面积的
     * [OWN_OVERLAP_RATIO] 以上被气泡盒覆盖（OCR 拼接导致中心漂出气泡的兜底）。
     */
    fun isOwn(boxes: List<BubbleBox>, line: OcrLine): Boolean =
        boxes.any { box ->
            box.contains(line.centerX, line.centerY) || box.covers(line, OWN_OVERLAP_RATIO)
        }

    /** 行盒与气泡盒相交面积占行盒面积的比例是否达到 [ratio]。 */
    internal fun BubbleBox.covers(line: OcrLine, ratio: Float): Boolean {
        val lineWidth = line.right - line.left
        val lineHeight = line.bottom - line.top
        if (lineWidth <= 0 || lineHeight <= 0) return false
        val overlapW = (minOf(right, line.right) - maxOf(left, line.left)).coerceAtLeast(0)
        val overlapH = (minOf(bottom, line.bottom) - maxOf(top, line.top)).coerceAtLeast(0)
        return overlapW.toLong() * overlapH >= (lineWidth.toLong() * lineHeight * ratio).toLong()
    }

    /**
     * 绿色网格 → 气泡外接盒（4 邻域连通块 + 尺度过滤）。[step] 为网格块对应
     * 的像素边长，产出坐标已换算回帧像素空间。
     */
    internal fun boxesFromGrid(
        green: BooleanArray,
        gridWidth: Int,
        gridHeight: Int,
        step: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): List<BubbleBox> {
        if (gridWidth <= 0 || gridHeight <= 0 || green.size < gridWidth * gridHeight) {
            return emptyList()
        }
        val minWidth = (frameWidth * MIN_WIDTH_RATIO).toInt()
        val minHeight = (frameHeight * MIN_HEIGHT_RATIO).toInt()
        val bottomGuard = (frameHeight * BOTTOM_GUARD_RATIO).toInt()
        val seen = BooleanArray(gridWidth * gridHeight)
        val queue = IntArray(gridWidth * gridHeight)
        val boxes = ArrayList<BubbleBox>()
        for (start in 0 until gridWidth * gridHeight) {
            if (!green[start] || seen[start]) continue
            var head = 0
            var tail = 0
            fun push(index: Int) {
                if (!green[index] || seen[index]) return
                seen[index] = true
                queue[tail++] = index
            }
            push(start)
            var minX = gridWidth
            var maxX = -1
            var minY = gridHeight
            var maxY = -1
            while (head < tail) {
                val index = queue[head++]
                val x = index % gridWidth
                val y = index / gridWidth
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) push(index - 1)
                if (x + 1 < gridWidth) push(index + 1)
                if (y > 0) push(index - gridWidth)
                if (y + 1 < gridHeight) push(index + gridWidth)
            }
            val box = BubbleBox(
                left = minX * step,
                top = minY * step,
                right = (maxX + 1) * step,
                bottom = (maxY + 1) * step,
            )
            if (box.width >= minWidth && box.height >= minHeight && box.top < bottomGuard) {
                boxes += box
            }
        }
        return boxes
    }
}
