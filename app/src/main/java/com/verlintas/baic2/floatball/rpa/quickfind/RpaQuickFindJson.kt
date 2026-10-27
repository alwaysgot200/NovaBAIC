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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.rpa.quickfind

import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import java.util.Locale
import kotlinx.serialization.json.Json

/**
 * 「复制视觉定位 JSON」的序列化口径：与 [com.verlintas.rpa.store.RpaScriptStore]
 * 同配置（默认值全写、pretty print），复制结果可直接粘回脚本或备份。
 *
 * 快捷找图不持久化模板（无 `scriptId`），因此主定位用 RATIO；中心像素与得分
 * 写进 note 供人工核对，不参与运行时定位。
 */
object RpaQuickFindJson {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun visionSpec(
        ratioX: Float,
        ratioY: Float,
        threshold: Double,
        grayscale: Boolean,
        centerX: Int,
        centerY: Int,
        frameWidth: Int,
        frameHeight: Int,
        score: Double,
    ): String {
        val spec = RpaVisionSpec(
            primary = RpaVisionLocator(
                kind = RpaVisionKind.RATIO,
                threshold = threshold,
                grayscale = grayscale,
                ratioX = ratioX,
                ratioY = ratioY,
            ),
            note = "quickfind center=($centerX,$centerY) " +
                "frame=${frameWidth}x$frameHeight " +
                "score=${formatScore(score)}",
        )
        return json.encodeToString(RpaVisionSpec.serializer(), spec)
    }

    /** 比例文案：固定 4 位小数 + 点号，避免逗号小数在不同 locale 下解析歧义。 */
    fun ratioPair(ratioX: Float, ratioY: Float): String =
        String.format(Locale.US, "%.4f, %.4f", ratioX, ratioY)

    fun formatScore(score: Double): String = String.format(Locale.US, "%.3f", score)
}
