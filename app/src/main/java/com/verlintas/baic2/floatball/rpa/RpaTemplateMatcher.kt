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

package com.verlintas.baic2.floatball.rpa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.verlintas.baic2.floatball.auto.CvMatch
import com.verlintas.baic2.floatball.auto.CvTemplate
import com.verlintas.baic2.floatball.auto.CvTemplateCache
import com.verlintas.baic2.floatball.auto.ImageScales
import com.verlintas.baic2.floatball.auto.bitmapToMat
import com.verlintas.baic2.floatball.auto.intoFrame
import com.verlintas.baic2.floatball.auto.matchAtScale
import com.verlintas.baic2.floatball.auto.regionOf
import com.verlintas.baic2.floatball.auto.templateFromBitmap
import com.verlintas.rpa.model.RpaRect
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

/**
 * RPA「以图找图」执行器：模板来自 `filesDir/rpa/templates/<scriptId>/` 下的 PNG
 * （用户在配置页现场裁剪生成），不是 assets 预制模板，因此单独入口，避免
 * 影响既有 [com.verlintas.baic2.floatball.auto.ImageMatcher] 的资产口径。
 *
 * 与 ImageMatcher 共用 [com.verlintas.baic2.floatball.auto.CvTemplateCache]：
 * 多尺度、灰度、alpha 掩膜、阈值治理，全 native 计算走 [Dispatchers.Default]。
 * 模板按「文件路径 + mtime」缓存，用户重新裁剪后立即失效。
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
        try {
            val area = regionOf(source, region?.toRect(frame.width, frame.height))
            try {
                val best = ImageScales.FACTORS
                    .mapNotNull { scale -> matchAtScale(area.mat, template, scale) }
                    .maxByOrNull { it.score }
                if (best == null) {
                    Result.failure(IllegalStateException("match_failed: template_larger_than_region"))
                } else {
                    Result.success(best.intoFrame(area.offsetX, area.offsetY))
                }
            } finally {
                area.release()
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

    /** 模板被覆盖/删除后主动失效（mtime 精度不足时兜底）。 */
    fun invalidate(templateFile: File) {
        cache.invalidate(cacheKey(templateFile, grayscale = true))
        cache.invalidate(cacheKey(templateFile, grayscale = false))
    }

    /**
     * 缓存键必须带灰度开关：彩色帧（3 通道）只能匹配彩色模板，
     * 灰度帧（1 通道）只能匹配灰度模板，否则 matchTemplate 通道数不一致直接失败。
     */
    private fun loadTemplate(file: File, grayscale: Boolean): CvTemplate? {
        val key = cacheKey(file, grayscale)
        val stamp = file.lastModified()
        cache.get(key, stamp)?.let { return it }
        val loaded = loadUncached(file, stamp, grayscale) ?: return null
        cache.put(key, loaded)
        return loaded
    }

    private fun loadUncached(
        file: File,
        stamp: Long,
        grayscale: Boolean,
    ): CvTemplate? = runCatching {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        try {
            templateFromBitmap(bitmap, grayscale, stamp)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    private fun cacheKey(file: File, grayscale: Boolean): String =
        file.absolutePath + if (grayscale) "#gray" else "#bgr"

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

    private val cache = CvTemplateCache()

    companion object {
        const val DEFAULT_THRESHOLD = 0.80
    }
}
