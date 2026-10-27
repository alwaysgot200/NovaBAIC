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

package com.verlintas.baic2.floatball.rpa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.verlintas.baic2.floatball.auto.CvMatch
import com.verlintas.baic2.floatball.auto.ImageScales
import com.verlintas.rpa.model.RpaRect
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * RPA「以图找图」执行器：模板来自 `filesDir/rpa/templates/<scriptId>/` 下的 PNG
 * （用户在配置页现场裁剪生成），不是 assets 预制模板，因此单独实现，避免
 * 影响既有 [com.verlintas.baic2.floatball.auto.ImageMatcher] 的资产口径。
 *
 * 与 ImageMatcher 相同的工程约束：多尺度、灰度、alpha 掩膜、阈值治理、
 * 全 native 计算走 [Dispatchers.Default]。模板按「文件路径 + mtime」缓存，
 * 用户重新裁剪后立即失效。
 */
@Singleton
class RpaTemplateMatcher @Inject constructor() {

    /** 最佳匹配（不设阈值，供配置页显示得分）。 */
    suspend fun bestMatch(
        frame: Bitmap,
        templateFile: File,
        region: RpaRect? = null,
        grayscale: Boolean = true,
    ): Result<CvMatch> = withContext(Dispatchers.Default) {
        if (!templateFile.isFile) {
            return@withContext Result.failure(
                IllegalStateException("template_missing: ${templateFile.absolutePath}"),
            )
        }
        runCatching { OpenCVLoader.initLocal() }
        val template = loadTemplate(templateFile, grayscale)
            ?: return@withContext Result.failure(
                IllegalStateException("template_decode_failed: ${templateFile.name}"),
            )
        val source = bitmapToMat(frame, grayscale)
        val roi = region?.toRect(frame.width, frame.height)
        try {
            val sub = if (roi != null && !roi.isEmpty) {
                val left = roi.left.coerceIn(0, source.cols() - 1)
                val top = roi.top.coerceIn(0, source.rows() - 1)
                val width = roi.width().coerceAtMost(source.cols() - left)
                val height = roi.height().coerceAtMost(source.rows() - top)
                source.submat(top, top + height, left, left + width)
            } else {
                source
            }
            try {
                val best = ImageScales.FACTORS
                    .mapNotNull { scale -> matchAtScale(sub, template, scale) }
                    .maxByOrNull { it.score }
                if (best == null) {
                    Result.failure(IllegalStateException("match_failed: template_larger_than_region"))
                } else {
                    Result.success(best.intoFrame(if (sub === source) null else roi))
                }
            } finally {
                if (sub !== source) sub.release()
            }
        } finally {
            source.release()
        }
    }

    /** 阈值治理：低于阈值不返回命中，失败消息带最佳分数（禁止差一点硬点）。 */
    suspend fun find(
        frame: Bitmap,
        templateFile: File,
        threshold: Double = DEFAULT_THRESHOLD,
        region: RpaRect? = null,
        grayscale: Boolean = true,
    ): Result<CvMatch> = bestMatch(frame, templateFile, region, grayscale).mapCatching { match ->
        if (match.score < threshold) {
            error("match_below_threshold: best=${"%.3f".format(match.score)}")
        }
        match
    }

    private fun matchAtScale(region: Mat, template: CachedTemplate, scale: Double): CvMatch? {
        val tw = (template.width * scale).toInt()
        val th = (template.height * scale).toInt()
        if (tw < 2 || th < 2 || tw > region.cols() || th > region.rows()) return null
        val scaled = Mat()
        Imgproc.resize(template.mat, scaled, Size(tw.toDouble(), th.toDouble()))
        val mask = template.mask?.let { m ->
            val sm = Mat()
            Imgproc.resize(m, sm, Size(tw.toDouble(), th.toDouble()))
            sm
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

    private fun CvMatch.intoFrame(roi: Rect?): CvMatch =
        if (roi == null) this else copy(
            centerX = centerX + roi.left,
            centerY = centerY + roi.top,
            left = left + roi.left,
            top = top + roi.top,
            right = right + roi.left,
            bottom = bottom + roi.top,
        )

    private fun bitmapToMat(bitmap: Bitmap, grayscale: Boolean): Mat {
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

    /**
     * 模板缓存键必须带上灰度开关：彩色帧（3 通道）只能匹配彩色模板，
     * 灰度帧（1 通道）只能匹配灰度模板，否则 OpenCV matchTemplate 会因通道数
     * 不一致直接失败。用户重新裁剪模板后 mtime 变化，缓存立即失效。
     */
    private fun loadTemplate(file: File, grayscale: Boolean): CachedTemplate? =
        synchronized(cache) {
            val key = file.absolutePath + if (grayscale) "#gray" else "#bgr"
            val stamp = file.lastModified()
            cache[key]?.takeIf { it.stamp == stamp }?.let { return it }
            cache.remove(key)?.release()
            val loaded = loadUncached(file, stamp, grayscale)
            if (loaded != null) cache[key] = loaded
            loaded
        }

    private fun loadUncached(
        file: File,
        stamp: Long,
        grayscale: Boolean,
    ): CachedTemplate? = runCatching {
        val bytes = file.readBytes()
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val argb = if (raw.config == Bitmap.Config.ARGB_8888) {
            raw
        } else {
            raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
        }
        val rgba = Mat()
        Utils.bitmapToMat(argb, rgba)
        val hasTransparency = hasTransparentPixels(argb)
        val mat = Mat()
        Imgproc.cvtColor(
            rgba,
            mat,
            if (grayscale) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGBA2BGR,
        )
        val mask = if (hasTransparency) {
            val m = Mat()
            Core.extractChannel(rgba, m, 3)
            m
        } else {
            null
        }
        rgba.release()
        CachedTemplate(
            mat = mat,
            mask = mask,
            width = argb.width,
            height = argb.height,
            stamp = stamp,
        )
    }.getOrNull()

    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
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

    private fun RpaRect.toRect(frameWidth: Int, frameHeight: Int): Rect? {
        val r = normalized()
        if (!r.isValid()) return null
        return Rect(
            (r.left * frameWidth).toInt().coerceIn(0, frameWidth - 1),
            (r.top * frameHeight).toInt().coerceIn(0, frameHeight - 1),
            (r.right * frameWidth).toInt().coerceIn(1, frameWidth),
            (r.bottom * frameHeight).toInt().coerceIn(1, frameHeight),
        )
    }

    private class CachedTemplate(
        val mat: Mat,
        val mask: Mat?,
        val width: Int,
        val height: Int,
        val stamp: Long,
    ) {
        fun release() {
            mat.release()
            mask?.release()
        }
    }

    private val cache = HashMap<String, CachedTemplate>()

    companion object {
        const val DEFAULT_THRESHOLD = 0.80
    }
}
