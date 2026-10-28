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

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * OpenCV 找图公共实现（[ImageMatcher] 资产模板与 RPA 用户模板共用）。
 *
 * 只做「Bitmap ↔ Mat / 区域裁剪 / 单尺度 matchTemplate / 模板缓存」，
 * 帧源、阈值治理与业务错误码留给调用方，避免两条线路各写一份 native 代码。
 */

/** 预转换模板：灰度或 BGR Mat + 可选 alpha 掩膜；[stamp] 用于文件模板的 mtime 失效。 */
internal class CvTemplate(
    val mat: Mat,
    val mask: Mat?,
    val width: Int,
    val height: Int,
    val stamp: Long = 0L,
) {
    fun release() {
        mat.release()
        mask?.release()
    }
}

/**
 * 帧上的匹配区域。命中坐标必须加 [offsetX]/[offsetY]（clamp 后的真实偏移），
 * 不能再拿原始 ROI 坐标回加——负坐标或越界时 submat 已被裁掉左上部分。
 */
internal class CvRegion(
    val mat: Mat,
    val offsetX: Int,
    val offsetY: Int,
    private val owned: Boolean,
) {
    fun release() {
        if (owned) mat.release()
    }
}

/**
 * 模板 LRU 缓存：上限 [maxEntries]，淘汰时立即 release native Mat，
 * 防止长期运行/大量脚本模板导致 native 内存只增不减。
 */
internal class CvTemplateCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val entries = LinkedHashMap<String, CvTemplate>(maxEntries, 0.75f, true)

    fun get(key: String, stamp: Long? = null): CvTemplate? = synchronized(entries) {
        val cached = entries[key] ?: return null
        if (stamp != null && cached.stamp != stamp) {
            entries.remove(key)
            cached.release()
            return null
        }
        cached
    }

    fun put(key: String, template: CvTemplate) {
        synchronized(entries) {
            entries.put(key, template)?.release()
            while (entries.size > maxEntries) {
                val iterator = entries.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next().value.release()
                iterator.remove()
            }
        }
    }

    /** 文件模板被覆盖/删除后调用；[key] 为 null 时清空全部。 */
    fun invalidate(key: String? = null) {
        synchronized(entries) {
            if (key == null) {
                entries.values.forEach { it.release() }
                entries.clear()
            } else {
                entries.remove(key)?.release()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 16
    }
}

/** 整帧转 Mat：灰度 1 通道，彩色 BGR 3 通道（OpenCV 匹配要求源与模板通道一致）。 */
internal fun bitmapToMat(bitmap: Bitmap, grayscale: Boolean): Mat {
    val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
        bitmap
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
    val rgba = Mat()
    Utils.bitmapToMat(argb, rgba)
    if (argb !== bitmap) argb.recycle()
    if (!grayscale) {
        Imgproc.cvtColor(rgba, rgba, Imgproc.COLOR_RGBA2BGR)
        return rgba
    }
    val gray = Mat()
    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
    rgba.release()
    return gray
}

/** 解码后的模板 Bitmap 转 [CvTemplate]；Bitmap 由调用方回收。 */
internal fun templateFromBitmap(
    bitmap: Bitmap,
    grayscale: Boolean,
    stamp: Long = 0L,
): CvTemplate {
    val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
        bitmap
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
    val rgba = Mat()
    Utils.bitmapToMat(argb, rgba)
    val transparent = hasTransparentPixels(argb)
    val mat = Mat()
    Imgproc.cvtColor(
        rgba,
        mat,
        if (grayscale) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGBA2BGR,
    )
    val mask = if (transparent) {
        Mat().also { Core.extractChannel(rgba, it, 3) }
    } else {
        null
    }
    rgba.release()
    if (argb !== bitmap) argb.recycle()
    return CvTemplate(
        mat = mat,
        mask = mask,
        width = bitmap.width,
        height = bitmap.height,
        stamp = stamp,
    )
}

/** 模板按 RGBA alpha 通道判断是否含透明像素；透明模板需要掩膜匹配。 */
internal fun hasTransparentPixels(bitmap: Bitmap): Boolean {
    if (!bitmap.hasAlpha()) return false
    val width = bitmap.width
    val height = bitmap.height
    val row = IntArray(width)
    for (y in 0 until height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (pixel in row) {
            if (pixel ushr 24 < 0xFA) return true
        }
    }
    return false
}

/**
 * 按归一化 ROI 裁剪整帧；[roi] 为 null 或空时复用整帧（[CvRegion.owned] = false）。
 * 越界 ROI 先 clamp 到帧内，偏移取 clamp 后的真实值。
 */
internal fun regionOf(source: Mat, roi: Rect?): CvRegion {
    if (roi == null || roi.isEmpty) return CvRegion(source, 0, 0, owned = false)
    val left = roi.left.coerceIn(0, source.cols() - 1)
    val top = roi.top.coerceIn(0, source.rows() - 1)
    val right = roi.right.coerceIn(left + 1, source.cols())
    val bottom = roi.bottom.coerceIn(top + 1, source.rows())
    return CvRegion(
        mat = source.submat(top, bottom, left, right),
        offsetX = left,
        offsetY = top,
        owned = true,
    )
}

/** 单尺度匹配；缩放后模板小于 2px 或超出区域时返回 null（该档位不可用）。 */
internal fun matchAtScale(region: Mat, template: CvTemplate, scale: Double): CvMatch? {
    val tw = (template.width * scale).toInt()
    val th = (template.height * scale).toInt()
    if (tw < 2 || th < 2 || tw > region.cols() || th > region.rows()) return null
    val scaled = Mat()
    Imgproc.resize(template.mat, scaled, Size(tw.toDouble(), th.toDouble()))
    val mask = template.mask?.let { m ->
        Mat().also { Imgproc.resize(m, it, Size(tw.toDouble(), th.toDouble())) }
    }
    try {
        val result = Mat(region.rows() - th + 1, region.cols() - tw + 1, CvType.CV_32FC1)
        try {
            if (mask != null) {
                Imgproc.matchTemplate(region, scaled, result, Imgproc.TM_CCORR_NORMED, mask)
            } else {
                Imgproc.matchTemplate(region, scaled, result, Imgproc.TM_CCOEFF_NORMED)
            }
            val mmr = Core.minMaxLoc(result)
            val x = mmr.maxLoc.x.toInt()
            val y = mmr.maxLoc.y.toInt()
            return CvMatch(
                centerX = x + tw / 2,
                centerY = y + th / 2,
                score = mmr.maxVal,
                left = x,
                top = y,
                right = x + tw,
                bottom = y + th,
            )
        } finally {
            result.release()
        }
    } finally {
        scaled.release()
        mask?.release()
    }
}

/** 区域坐标回写整帧坐标：偏移必须来自 [CvRegion] 的 clamp 后值。 */
internal fun CvMatch.intoFrame(offsetX: Int, offsetY: Int): CvMatch =
    if (offsetX == 0 && offsetY == 0) {
        this
    } else {
        copy(
            centerX = centerX + offsetX,
            centerY = centerY + offsetY,
            left = left + offsetX,
            top = top + offsetY,
            right = right + offsetX,
            bottom = bottom + offsetY,
        )
    }
