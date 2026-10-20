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

import android.content.Context
import com.verlintas.baic2.device.api.OcrLine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OCR 噪声治理的几何分区（方案 §12.2 第 1 层后半 + 第 2 层）：
 * 底部系统带已由 [FrameQuality.cropBands] 位图掩膜；本文件负责一次 OCR 后
 * 按包围盒把行分流为「状态栏丢弃 / 标题带（联系人识别）/ 正文」，再对正文
 * 跑可配置的行级正则库。全部函数无状态、纯计算，规则由 [NoiseRules] 注入。
 */
internal object OcrNoiseFilter {

    /** 一帧 OCR 的几何分区结果。正文行已按 top（再 left）排序。 */
    data class Partition(
        val titleLines: List<OcrLine>,
        val bodyLines: List<OcrLine>,
    )

    /**
     * 按包围盒分流：
     * - top < 状态栏高度：整行丢弃（含连续模式录屏计时红胶囊）；
     * - 状态栏下、标题带下沿之上：标题带，交联系人识别（L2），不进正文；
     * - top ≥ 内容区底沿（底部带）：丢弃（位图掩膜的双保险）；
     * - 其余为正文。
     */
    fun partition(lines: List<OcrLine>, geometry: ScreenGeometry): Partition {
        val title = ArrayList<OcrLine>()
        val body = ArrayList<OcrLine>()
        val bodyBottom = geometry.heightPx - geometry.bottomBandPx
        for (line in lines) {
            when {
                line.top < geometry.statusBarPx -> Unit
                line.top < geometry.titleBandBottomPx -> title += line
                line.top >= bodyBottom -> Unit
                else -> body += line
            }
        }
        body.sortWith(compareBy({ it.top }, { it.left }))
        title.sortWith(compareBy({ it.top }, { it.left }))
        return Partition(titleLines = title, bodyLines = body)
    }

    /**
     * 行级正则过滤（第 2 层）。命中行规则/禁词即丢；日期分割行白名单优先；
     * [whitelistNames] 中任一名称出现在行内则整行保留（绑定联系人名免疫）。
     */
    fun clean(
        lines: List<OcrLine>,
        rules: CompiledNoiseRules,
        whitelistNames: Collection<String> = emptyList(),
    ): List<OcrLine> {
        val names = whitelistNames.map { it.trim() }.filter { it.length >= 2 }
        return lines.filter { line -> keepLine(line.text, rules, names) }
    }

    private fun keepLine(raw: String, rules: CompiledNoiseRules, whitelistNames: List<String>): Boolean {
        val text = normalize(raw)
        if (text.isEmpty()) return false
        if (whitelistNames.any { it in raw }) return true
        if (rules.keep.any { it.matches(text) }) return true
        if (rules.linePatterns.any { it.matches(text) }) return false
        if (rules.blockedWords.any { it in text }) return false
        return true
    }

    /** 行规范化：trim + 连续空白压成单空格（全角空格同样处理）。 */
    fun normalize(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")
}

/**
 * 帧坐标系分区尺寸（像素，OCR 截图空间）。
 *
 * @param statusBarPx 状态栏高度；其上行整行丢弃。
 * @param titleBandBottomPx 标题带下沿（状态栏 + 约 48dp）；带内行交联系人识别。
 * @param bottomBandPx 底部系统带高度（手势条/导航/IME 候选栏），位图已掩膜、
 *   包围盒再次分流做双保险。
 */
data class ScreenGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val statusBarPx: Int,
    val titleBandBottomPx: Int,
    val bottomBandPx: Int,
)

/**
 * 几何尺寸来源：优先系统 dimen（status_bar_height / navigation_bar_height），
 * 缺失回退屏幕比例（top 5% / bottom 4%；IME 弹起期间底部带按 14%）。
 * v1 不新增上游接口取精确 IME 高度，只用现成的 editableFocused 布尔加宽。
 */
@Singleton
class ScreenGeometryProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun compute(width: Int, height: Int, imeOpen: Boolean): ScreenGeometry {
        val statusBar = systemDimen("status_bar_height") ?: (height * STATUS_FALLBACK).toInt()
        val titleBand = statusBar + dp(TITLE_BAND_DP)
        val bottom = if (imeOpen) {
            (height * IME_BOTTOM_RATIO).toInt()
        } else {
            systemDimen("navigation_bar_height")?.takeIf { it > 0 }
                ?: (height * NAV_FALLBACK).toInt()
        }
        return ScreenGeometry(
            widthPx = width,
            heightPx = height,
            statusBarPx = statusBar.coerceIn(0, height / 4),
            titleBandBottomPx = titleBand.coerceIn(statusBar, height / 3),
            bottomBandPx = bottom.coerceIn(0, height / 3),
        )
    }

    private fun systemDimen(name: String): Int? {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else null
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TITLE_BAND_DP = 48
        const val STATUS_FALLBACK = 0.05
        const val NAV_FALLBACK = 0.04
        const val IME_BOTTOM_RATIO = 0.14
    }
}

/** 已编译的噪声规则（[NoiseRules] 加载后常驻，避免每帧编译正则）。 */
class CompiledNoiseRules(
    val linePatterns: List<Regex>,
    val blockedWords: List<String>,
    val keep: List<Regex>,
)

/** assets/advisor/noise_rules.json 加载器；资产缺失时回退内置规则。 */
@Singleton
class NoiseRules @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cached: CompiledNoiseRules? = null

    fun get(): CompiledNoiseRules = cached ?: load().also { cached = it }

    private fun load(): CompiledNoiseRules {
        val asset = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        val parsed = asset?.let {
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(NoiseRulesFile.serializer(), it) }.getOrNull()
        }
        val lines = parsed?.linePatterns?.takeIf { it.isNotEmpty() } ?: DEFAULT_LINE_PATTERNS
        val words = parsed?.blockedWords?.takeIf { it.isNotEmpty() } ?: DEFAULT_BLOCKED_WORDS
        val keeps = parsed?.keepPatterns?.takeIf { it.isNotEmpty() } ?: DEFAULT_KEEP_PATTERNS
        return CompiledNoiseRules(
            linePatterns = lines.map { runCatching { Regex(it) }.getOrNull() }.filterNotNull(),
            blockedWords = words,
            keep = keeps.map { runCatching { Regex(it) }.getOrNull() }.filterNotNull(),
        )
    }

    @Serializable
    private data class NoiseRulesFile(
        val linePatterns: List<String> = emptyList(),
        val blockedWords: List<String> = emptyList(),
        val keepPatterns: List<String> = emptyList(),
    )

    private companion object {
        const val ASSET_PATH = "advisor/noise_rules.json"

        val DEFAULT_LINE_PATTERNS = listOf(
            """^\d{1,2}:\d{2}$""",
            """^\d{1,2}:\d{2}:\d{2}$""",
            """^\d+(?:\.\d+)?%$""",
            """^[2-9][0-9]?$""",
            """^[\p{P}\p{S}\s]+$""",
            """^.{0,1}$""",
        )
        val DEFAULT_BLOCKED_WORDS =
            listOf("对方正在输入", "正在输入", "已读", "未读", "VoLTE", "LTE", "4G", "5G", "HD")
        val DEFAULT_KEEP_PATTERNS = listOf(
            """^(今天|昨天|前天|星期[一二三四五六日天])$""",
            """^\d{1,2}月\d{1,2}日(?:\s+星期[一二三四五六日天])?$""",
            """^\d{4}年\d{1,2}月\d{1,2}日$""",
        )
    }
}
