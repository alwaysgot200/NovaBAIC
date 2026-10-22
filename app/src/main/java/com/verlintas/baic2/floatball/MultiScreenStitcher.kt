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
 * WARRANTY; WITHOUT even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import android.util.Log
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.floatball.auto.ScreenCapture
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 单次参谋的多屏上滑拼接（方案 §6.6）：截当前视图 → 上滑 N 屏 → 逐屏
 * OCR（三层噪声治理后的正文行）→ 重叠带锚点对齐拼接 → 下滑回底。
 *
 * 前置条件由调用方（ScreenAdvisor）校验：已绑定联系人、无障碍已连接
 * （滚动依赖 [AccessibilityBridge.swipe]）、IME 已收拢、包名不在禁滑名单。
 * 采集通道优先无障碍 [AccessibilityBridge.captureScreen]（序列内可连续截、
 * 无授权弹窗），失败且常驻投影存活时整序列改用 [ScreenCapture] 像素阶梯
 * （投影 acquire 主源 + screencap 兜底，方案 2026-10-22 模块 C）；投影
 * 常驻复用，本类不触碰投影生命周期。
 */
@Singleton
class MultiScreenStitcher @Inject constructor(
    private val accessibility: AccessibilityBridge,
    private val screenCapture: ScreenCapture,
    private val ocrProvider: OcrProvider,
    private val geometryProvider: ScreenGeometryProvider,
    private val noiseRules: NoiseRules,
) {
    /**
     * @param extraScreens 额外上滑屏数 N（1..3）。
     * @param settleMs 每次滑动后渲染等待。
     * @param swipeRatio 滑动距离占内容区高度比例（<1 留重叠带）。
     * @param scrollBack 完成后下滑同数回底。
     */
    data class Config(
        val extraScreens: Int,
        val settleMs: Long,
        val swipeRatio: Float,
        val scrollBack: Boolean,
    )

    /** 拼接产物。 */
    data class Stitched(
        val text: String,
        val screens: Int,
    )

    /**
     * 执行采集序列。第 0 屏都拿不到时返回 null（调用方静默回退单屏现行流程，
     * 不触发授权弹窗）。任何中途失败都尽量回底后返回已有内容。
     */
    suspend fun run(config: Config, whitelistNames: Collection<String>): Stitched? {
        // 采集通道在序列开始前一次性选定：a11y 优先；其截图不可用时常驻投影
        // 存活才整序列改走像素阶梯（且不触发授权弹窗）。中途不静默切换。
        var useA11y = accessibility.connected.value
        var seed: ByteArray? = null
        if (useA11y) {
            seed = accessibility.captureScreen().getOrNull()?.takeIf { looksUsable(it) }
            if (seed == null) useA11y = false
        }
        if (!useA11y && !screenCapture.projectionAlive) return null

        val first = grabScreen(useA11y = useA11y, preset = seed) ?: return null
        var geometry: ScreenGeometry? = first.geometry
        var merged: List<OcrLine> = first.lines
        var mergedScreens = 1
        var swipes = 0

        try {
            val geo = geometry!!

            var previous: List<OcrLine> = merged
            for (i in 1..config.extraScreens.coerceIn(1, MAX_EXTRA)) {
                val swipeOk = accessibility.swipe(
                    geo.widthPx / 2,
                    swipeStartY(geo, config.swipeRatio),
                    geo.widthPx / 2,
                    swipeEndY(geo),
                    SWIPE_DURATION_MS,
                ).isSuccess
                if (!swipeOk) break
                swipes++
                delay(config.settleMs)

                val frame = grabScreen(useA11y = useA11y, preset = null)
                if (frame == null) break
                val current = frame.lines
                if (current.isEmpty()) break

                if (jaccard(previous, current) >= NO_MOVE_JACCARD) {
                    Log.i(TAG, "screen $i looks identical (top reached), stop early")
                    break
                }
                merged = align(older = current, mergedNewer = merged, ratio = config.swipeRatio)
                previous = current
                mergedScreens++
            }
        } finally {
            if (config.scrollBack && swipes > 0) {
                geoScrollBack(geometry, swipes)
            }
        }

        val text = merged.joinToString("\n") { it.text.trim() }.trim()
        if (text.isEmpty()) return null
        return Stitched(text = text, screens = mergedScreens)
    }

    private suspend fun geoScrollBack(geometry: ScreenGeometry?, swipes: Int) {
        val geo = geometry ?: return
        repeat(swipes) {
            // 反向：手指下滑，6%Y → (6%+ratio)Y；到底后继续下滑无副作用。
            runCatching {
                accessibility.swipe(
                    geo.widthPx / 2,
                    swipeEndY(geo),
                    geo.widthPx / 2,
                    swipeStartY(geo, SWIPE_BACK_RATIO),
                    SWIPE_DURATION_MS,
                )
            }
            delay(BACK_SETTLE_MS)
        }
    }

    private fun swipeStartY(geo: ScreenGeometry, ratio: Float): Int {
        val contentH = geo.heightPx - geo.bottomBandPx - geo.titleBandBottomPx
        return (geo.titleBandBottomPx + contentH * (SWIPE_END_FRACTION + ratio)).toInt()
            .coerceAtMost(geo.heightPx - geo.bottomBandPx)
    }

    private fun swipeEndY(geo: ScreenGeometry): Int {
        val contentH = geo.heightPx - geo.bottomBandPx - geo.titleBandBottomPx
        return (geo.titleBandBottomPx + contentH * SWIPE_END_FRACTION).toInt()
    }

    /**
     * 抓一屏：底部带位图掩膜 → OCR → 几何分流 → 行级正则；过渡黑帧重采一次。
     * [preset] 为序列开始前已探测抓到的第 0 屏字节，避免重复截图。
     */
    private suspend fun grabScreen(useA11y: Boolean, preset: ByteArray?): Frame? {
        repeat(2) { attempt ->
            if (attempt == 1) delay(RETRY_DELAY_MS) // 过渡帧重采前等一拍
            val raw = preset ?: captureOnce(useA11y)
            if (raw == null) return@repeat
            val (width, height) = FrameQuality.bitmapSize(raw) ?: return@repeat
            val geometry = geometryProvider.compute(width, height, imeOpen = false)
            val cropped = withContext(Dispatchers.Default) {
                FrameQuality.cropBands(raw, width, height, geometry.bottomBandPx)
            }
            val ocrLines = withContext(Dispatchers.Default) {
                ocrProvider.recognize(cropped).getOrNull()
            } ?: return@repeat
            val partition = OcrNoiseFilter.partition(ocrLines, geometry)
            val body = OcrNoiseFilter.clean(partition.bodyLines, noiseRules.get(), emptyList())
            if (body.isEmpty() && attempt == 0) {
                // 可能是滑动过渡帧，等一拍重采。
                return@repeat
            }
            return Frame(lines = body, geometry = geometry)
        }
        return null
    }

    private suspend fun captureOnce(useA11y: Boolean): ByteArray? {
        if (useA11y) {
            val bytes = accessibility.captureScreen().getOrNull()
            if (bytes != null && looksUsable(bytes)) return bytes
            return null // 通道已在序列前选定，不中途降级投影
        }
        // qualityCheck=false：拼接器自带 looksUsable 质检，跳过重复检查。
        return screenCapture.captureBytes(qualityCheck = false).getOrNull()
            ?.takeIf { looksUsable(it) }
    }

    private suspend fun looksUsable(bytes: ByteArray): Boolean {
        val gray = withContext(Dispatchers.Default) { FrameQuality.grayFrame(bytes) } ?: return false
        return !FrameQuality.isBlank(gray)
    }

    /**
     * 重叠带对齐（方案 §6.6 步骤 2–3）：[older] 是更老一屏（尾部与
     * [mergedNewer] 头部在理论重叠窗口内重叠）。在「older 尾 ↔ merged 头」
     * 候选窗口内统计保序匹配对，按同斜线（b-a=常数）计锚点；要求 ≥3 锚点
     * 或候选区匹配率 ≥60%。命中则丢弃 older 的重叠尾段后前置；未达阈值
     * 保守按理论重叠行数截尾——宁可少一行，不可重复一行。
     */
    internal fun align(
        older: List<OcrLine>,
        mergedNewer: List<OcrLine>,
        ratio: Float,
    ): List<OcrLine> {
        if (older.isEmpty()) return mergedNewer
        val bandFraction = (1f - ratio).coerceIn(0.1f, 0.5f) + OVERLAP_MARGIN
        val tailCount = (older.size * bandFraction).toInt().coerceIn(1, older.size)
        val headCount = (mergedNewer.size * bandFraction).toInt()
            .coerceIn(1, mergedNewer.size.coerceAtLeast(1))

        data class PairHit(val a: Int, val b: Int)
        val pairs = ArrayList<PairHit>()
        for (a in older.size - tailCount until older.size) {
            val tailText = OcrNoiseFilter.normalize(older[a].text)
            for (b in 0 until headCount) {
                val headText = OcrNoiseFilter.normalize(mergedNewer[b].text)
                if (tailText == headText ||
                    FrameQuality.textSimilarity(tailText, headText) >= ANCHOR_SIMILARITY
                ) {
                    pairs += PairHit(a, b)
                }
            }
        }

        // 同一 a 只取最靠前的 b，保证锚点保序；按斜线投票。
        val best = pairs.groupBy { it.a }
            .mapValues { (_, hits) -> hits.minBy { it.b } }
            .values
            .groupBy { it.b - it.a }
            .maxByOrNull { (_, hits) -> hits.size }
        val anchors = best?.value.orEmpty()
        val matched = anchors.size
        val matchRate = matched.toDouble() / minOf(tailCount, headCount).coerceAtLeast(1)

        if (matched >= ANCHOR_MIN || matchRate >= ANCHOR_MATCH_RATE) {
            val cutAt = anchors.minOf { it.a }
            if (cutAt <= 0) return mergedNewer
            return older.subList(0, cutAt) + mergedNewer
        }

        // 未达阈值：保守丢掉 older 底部理论重叠长度的行再前置。
        val dropCount = kotlin.math.round(older.size * (1f - ratio)).toInt()
            .coerceIn(0, older.size - 1)
        val keep = older.size - dropCount
        if (keep <= 0) return mergedNewer
        Log.i(TAG, "alignment weak ($matched anchors), conservative drop=$dropCount")
        return older.subList(0, keep) + mergedNewer
    }

    /** 相邻屏行集合 Jaccard（规范化文本），≥0.95 判「没动」早停。 */
    internal fun jaccard(previous: List<OcrLine>, current: List<OcrLine>): Double {
        val a = previous.map { OcrNoiseFilter.normalize(it.text) }.filter { it.isNotEmpty() }.toSet()
        val b = current.map { OcrNoiseFilter.normalize(it.text) }.filter { it.isNotEmpty() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return intersection.toDouble() / (a + b).size
    }

    private data class Frame(val lines: List<OcrLine>, val geometry: ScreenGeometry)

    private companion object {
        const val TAG = "MultiScreenStitcher"
        const val MAX_EXTRA = 3
        const val SWIPE_DURATION_MS = 300
        const val BACK_SETTLE_MS = 500L
        const val RETRY_DELAY_MS = 300L

        /** 滑动起止点相对内容区的位置：6% → 78%（ratio=0.72 时）。 */
        const val SWIPE_END_FRACTION = 0.06f
        const val SWIPE_BACK_RATIO = 0.72f

        /** 理论重叠 28% 外加的对齐窗口余量。 */
        const val OVERLAP_MARGIN = 0.08f

        /** 锚点 OCR 抖动兜底相似度与「对齐成立」阈值（方案 §6.6）。 */
        const val ANCHOR_SIMILARITY = 0.8
        const val ANCHOR_MIN = 3
        const val ANCHOR_MATCH_RATE = 0.6

        /** 相邻屏行集 Jaccard 达到该值判为到顶/手势失效。 */
        const val NO_MOVE_JACCARD = 0.95
    }
}
