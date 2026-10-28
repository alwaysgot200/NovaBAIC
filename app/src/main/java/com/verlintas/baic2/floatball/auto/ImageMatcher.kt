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
        val template = loadTemplate(assetDir, templateName, grayscale)
            ?: return@withContext Result.failure(
                IllegalStateException("template_missing: assets/rpa_templates/${assetDir.orEmpty()}/$templateName.png"),
            )
        runCatching { OpenCVLoader.initLocal() }

        val source = bitmapToMat(frame, grayscale)
        try {
            val region = regionOf(source, roi)
            try {
                val best = ImageScales.forScreen(frame.width)
                    .mapNotNull { scale -> matchAtScale(region.mat, template, scale) }
                    .maxByOrNull { it.score }
                if (best == null) {
                    Result.failure(IllegalStateException("match_failed: template larger than region"))
                } else {
                    val framed = best.intoFrame(region.offsetX, region.offsetY)
                    if (framed.score >= threshold) {
                        Result.success(framed)
                    } else {
                        Result.failure(
                            IllegalStateException("match_below_threshold: best=${framed.score}"),
                        )
                    }
                }
            } finally {
                region.release()
            }
        } finally {
            source.release()
        }
    }

    private fun loadTemplate(assetDir: String?, name: String, grayscale: Boolean): CvTemplate? =
        synchronized(loadLock) {
            val key = "${assetDir.orEmpty()}/$name" + if (grayscale) "#gray" else "#bgr"
            templateCache.get(key)?.let { return it }
            val loaded = loadTemplateUncached(assetDir, name, grayscale)
            if (loaded != null) templateCache.put(key, loaded)
            loaded
        }

    private fun loadTemplateUncached(
        assetDir: String?,
        name: String,
        grayscale: Boolean,
    ): CvTemplate? = runCatching {
        val path = buildString {
            append("rpa_templates/")
            assetDir?.takeIf { it.isNotBlank() }?.let { append("$it/") }
            append("$name.png")
        }
        context.assets.open(path).use { input ->
            val bitmap = BitmapFactory.decodeStream(input) ?: return null
            try {
                templateFromBitmap(bitmap, grayscale)
            } finally {
                bitmap.recycle()
            }
        }
    }.getOrNull()

    private val loadLock = Any()

    private val templateCache = CvTemplateCache()

    companion object {
        const val DEFAULT_THRESHOLD = 0.80
    }
}
