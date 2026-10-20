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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import dagger.hilt.android.qualifiers.ApplicationContext
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

/** 一次找图命中：模板在源图上的矩形、中心点与匹配置信度（0..1）。 */
data class CvMatch(
    val centerX: Int,
    val centerY: Int,
    val score: Double,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** 多尺度档位（RPA §6.2.3）：以 720p 为模板制作基准，运行时按屏宽比缩放。 */
object ImageScales {
    val FACTORS = listOf(0.9, 1.0, 1.1)

    fun forScreen(screenWidth: Int, baseWidth: Int = 720): List<Double> {
        if (screenWidth <= 0) return listOf(1.0)
        val ratio = screenWidth.toDouble() / baseWidth
        return FACTORS.map { it * ratio }.distinct()
    }
}

/**
 * OpenCV 找图（RPA §6，方案 §17.3）：单目标 matchTemplate + minMaxLoc（全 native），
 * 多尺度取最高分；alpha 模板自动转掩膜；阈值治理——低于阈值不返回命中，
 * 失败结果携带最佳分数供上层决策（禁止「差一点也硬点」）。
 *
 * 匹配方法选择：模板含透明像素 → [Imgproc.TM_CCORR_NORMED] + 掩膜
 * （OpenCV matchTemplate 的掩膜仅支持 SQDIFF/CCORR_NORMED）；
 * 否则 → [Imgproc.TM_CCOEFF_NORMED]（去均值，深色模式/亮度变化不敏感）。
 * 模板 Mat 预转换并缓存（加载一次），全帧计算走 [Dispatchers.Default]。
 * 不联网传图、不落盘（本地优先红线）。
 */
@Singleton
class ImageMatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val frameSource: FrameSource,
) {

    /** 在给定帧（null 则走 [FrameSource] 阶梯取帧）中找模板 [templateName]。 */
    suspend fun find(
        templateName: String,
        image: Bitmap? = null,
        threshold: Double = DEFAULT_THRESHOLD,
        roi: Rect? = null,
        grayscale: Boolean = true,
        assetDir: String? = null,
    ): Result<CvMatch> = withContext(Dispatchers.Default) {
        val frame = image
            ?: frameSource.grabFrame().getOrNull()
            ?: return@withContext Result.failure(
                IllegalStateException("frame_unavailable: no frame for matching"),
            )
        val template = loadTemplate(assetDir, templateName)
            ?: return@withContext Result.failure(
                IllegalStateException("template_missing: assets/rpa_templates/${assetDir.orEmpty()}/$templateName.png"),
            )
        runCatching { OpenCVLoader.initLocal() }

        val source = bitmapToMat(frame, grayscale)
        try {
            val region = if (roi != null) {
                val l = roi.left.coerceIn(0, source.cols() - 1)
                val t = roi.top.coerceIn(0, source.rows() - 1)
                val w = roi.width().coerceAtMost(source.cols() - l)
                val h = roi.height().coerceAtMost(source.rows() - t)
                source.submat(t, t + h, l, l + w)
            } else {
                source
            }
            try {
                val best = ImageScales.forScreen(frame.width)
                    .mapNotNull { scale -> matchAtScale(region, template, scale) }
                    .maxByOrNull { it.score }
                if (best == null) {
                    Result.failure(IllegalStateException("match_failed: template larger than region"))
                } else {
                    best.intoFrame(roi).takeIf { it.score >= threshold }
                        ?.let { Result.success(it) }
                        ?: Result.failure(
                            IllegalStateException(
                                "match_below_threshold: best=${best.intoFrame(roi).score}",
                            ),
                        )
                }
            } finally {
                if (roi != null) region.release()
            }
        } finally {
            source.release()
        }
    }

    /** 单尺度匹配；模板缩放后超出区域时返回 null。 */
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

    private fun loadTemplate(assetDir: String?, name: String): CachedTemplate? =
        synchronized(templateCache) {
            val key = "${assetDir.orEmpty()}/$name"
            templateCache[key]?.let { return it }
            val loaded = loadTemplateUncached(assetDir, name)
            if (loaded != null) templateCache[key] = loaded
            loaded
        }

    private fun loadTemplateUncached(assetDir: String?, name: String): CachedTemplate? =
        runCatching {
            val path = buildString {
                append("rpa_templates/")
                assetDir?.takeIf { it.isNotBlank() }?.let { append("$it/") }
                append("$name.png")
            }
            context.assets.open(path).use { input ->
                val raw = BitmapFactory.decodeStream(input) ?: return null
                val argb = if (raw.config == Bitmap.Config.ARGB_8888) {
                    raw
                } else {
                    raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
                }
                val rgba = Mat()
                Utils.bitmapToMat(argb, rgba)
                val hasTransparency = hasTransparentPixels(argb)
                val gray = Mat()
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                val mask = if (hasTransparency) {
                    val m = Mat()
                    Core.extractChannel(rgba, m, 3)
                    m
                } else {
                    null
                }
                rgba.release()
                CachedTemplate(
                    mat = gray,
                    mask = mask,
                    width = argb.width,
                    height = argb.height,
                )
            }
        }.getOrNull()

    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (pixel in row) {
                if (pixel ushr 24 < 0xFA) return true
            }
        }
        return false
    }

    private class CachedTemplate(
        val mat: Mat,
        val mask: Mat?,
        val width: Int,
        val height: Int,
    )

    private val templateCache = HashMap<String, CachedTemplate>()

    companion object {
        const val DEFAULT_THRESHOLD = 0.80
    }
}
