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

package com.verlintas.baic2.floatball

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/**
 * 像素帧的纯函数工具集：低分辨率灰度签名（帧差分）、空白帧检测
 * （黑屏/白屏/FLAG_SECURE）、自身浮窗矩形掩膜、OCR 文本相似度。
 * 全部在调用方选定的后台线程执行，无状态。
 */
internal object FrameQuality {

    private const val SAMPLE = 32
    private const val BLOCKS = 8

    /** 32×32 灰度帧：像素级亮度 + 8×8 分块均值，供质检与差分共用一次解码。 */
    class GrayFrame(val pixels: FloatArray, val blocks: FloatArray)

    /**
     * 解码并降采样到 [SAMPLE]×[SAMPLE] 灰度。非图片字节返回 null。
     * 成本约 2-5ms（720p PNG），调用方须在 Dispatchers.Default 上。
     */
    fun grayFrame(bytes: ByteArray): GrayFrame? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SAMPLE && bounds.outHeight / (sample * 2) >= SAMPLE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val small = Bitmap.createScaledBitmap(decoded, SAMPLE, SAMPLE, true)
        if (small !== decoded) decoded.recycle()

        val pixels = FloatArray(SAMPLE * SAMPLE)
        val buffer = IntArray(SAMPLE * SAMPLE)
        small.getPixels(buffer, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        for (i in buffer.indices) {
            val c = buffer[i]
            pixels[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000f
        }
        small.recycle()

        val blocks = FloatArray(BLOCKS * BLOCKS)
        val perBlock = SAMPLE / BLOCKS
        for (by in 0 until BLOCKS) {
            for (bx in 0 until BLOCKS) {
                var sum = 0f
                for (y in by * perBlock until (by + 1) * perBlock) {
                    for (x in bx * perBlock until (bx + 1) * perBlock) {
                        sum += pixels[y * SAMPLE + x]
                    }
                }
                blocks[by * BLOCKS + bx] = sum / (perBlock * perBlock)
            }
        }
        return GrayFrame(pixels, blocks)
    }

    /** 8×8 分块平均亮度差的归一化值（0=完全一致，1=最大差异）。 */
    fun blockDiff(a: FloatArray, b: FloatArray): Float {
        var total = 0f
        for (i in a.indices) total += kotlin.math.abs(a[i] - b[i])
        return total / a.size / 255f
    }

    /**
     * 空白帧检测：全黑（息屏/过渡黑场/FLAG_SECURE 输出）或近乎纯色
     * （纯白页加载中）。判据：亮度均值极端且方差极小。
     */
    fun isBlank(frame: GrayFrame): Boolean {
        var mean = 0f
        for (p in frame.pixels) mean += p
        mean /= frame.pixels.size
        var variance = 0f
        for (p in frame.pixels) {
            val d = p - mean
            variance += d * d
        }
        val std = kotlin.math.sqrt(variance / frame.pixels.size)
        return (mean < 10f || mean > 245f) && std < 5f
    }

    /**
     * 把已知属于自身浮窗的矩形涂黑（连续模式下无法撤窗，几何掩膜零误判）。
     * 输出 JPEG（比 PNG 快数倍，OCR 不在乎压缩格式）；失败返回原图。
     */
    fun maskRects(bytes: ByteArray, rects: List<Rect>): ByteArray {
        if (rects.isEmpty()) return bytes
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        // PNG 解码产物是 immutable，Canvas 需要可变位图。
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        Canvas(bitmap).apply {
            val paint = android.graphics.Paint().apply { color = Color.BLACK }
            rects.forEach { drawRect(it, paint) }
        }
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
            bitmap.recycle()
            out.toByteArray()
        } else {
            bitmap.recycle()
            bytes
        }
    }

    /**
     * 只解析头块拿像素尺寸（不分载整图），供几何分区在掩膜前确定坐标系。
     * 非图片字节返回 null。调用方须在 Dispatchers.Default 上。
     */
    fun bitmapSize(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    /**
     * 底部系统带（手势条/三键导航/IME 候选栏）黑色掩膜（方案 §12.2 第 1 层）：
     * 该区域是纯系统像素，物理切除最干净；顶部不掩膜——状态栏行 OCR 后按
     * 包围盒丢弃，标题带还要留给联系人识别。输出 JPEG；无带可裁/失败返回原图。
     * 调用方须在 Dispatchers.Default 上。
     */
    fun cropBands(bytes: ByteArray, width: Int, height: Int, bottomPx: Int): ByteArray {
        if (bottomPx <= 0) return bytes
        val bottom = bottomPx.coerceAtMost(height - 1)
        if (bottom <= 0) return bytes
        return maskRects(bytes, listOf(Rect(0, height - bottom, width, height)))
    }

    /**
     * OCR 文本相似度（字符二元组 Jaccard），用于连续模式丢弃重复内容。
     * 中文按字、英文按词均可被 2-gram 覆盖。
     */
    fun textSimilarity(a: String, b: String): Double {
        val left = shingles(a)
        val right = shingles(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.count { right.contains(it) }
        return intersection.toDouble() / (left.size + right.size - intersection).coerceAtLeast(1)
    }

    private fun shingles(text: String): Set<String> {
        val compact = text.filterNot { it.isWhitespace() }.take(4_000)
        if (compact.length < 2) return emptySet()
        return buildSet {
            for (i in 0 until compact.length - 1) add(compact.substring(i, i + 2))
        }
    }
}
