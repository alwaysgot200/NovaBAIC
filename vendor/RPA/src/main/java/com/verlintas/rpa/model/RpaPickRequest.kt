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

package com.verlintas.rpa.model

import kotlinx.serialization.Serializable

/** 拾取类型：与线路一一对应，DUAL 脚本两种都可拾取。 */
@Serializable
enum class RpaPickKind {
    A11Y,
    VISION,
}

/**
 * 拾取用途（v5 §7.9 排矛盾）：同一 VISION 拾取，回填的目标种类不同。
 *
 * - [TEMPLATE]：图片拾取——屏幕选区裁剪成模板，回填 `RpaVisionLocator(kind=TEMPLATE)`；
 * - [POINT]：屏幕取点——回填 `RpaVisionLocator(kind=RATIO, ratioX/ratioY)`，
 *   保持「点击(坐标)」的概念不被翻转成「点击图片」。
 */
@Serializable
enum class RpaPickPurpose {
    TEMPLATE,
    POINT,
}

/**
 * 编辑器武装的拾取请求（方案 §7.8）。
 *
 * 编辑器先落盘脚本，再 arm 一份请求；工具（布局分析覆盖层 / 以图找图浮层）
 * 读到请求后显示「为某脚本第 N 步拾取」横幅，并把结果直接写回 store。
 * 请求 15 分钟过期，避免过夜后仍误回填。
 */
@Serializable
data class RpaPickRequest(
    val requestId: String,
    val scriptId: String,
    val stepId: String,
    val kind: RpaPickKind,
    val scriptName: String = "",
    val stepLabel: String = "",
    val createdAt: Long = 0L,

    /** 拾取用途（v5）：区分图片拾取与屏幕取点的回填目标种类，默认图片拾取。 */
    val purpose: RpaPickPurpose = RpaPickPurpose.TEMPLATE,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        createdAt <= 0L || now - createdAt > TTL_MS

    fun displayStep(stepIndex: Int?): String = when {
        stepLabel.isNotBlank() -> stepLabel
        stepIndex != null && stepIndex >= 0 -> "第 ${stepIndex + 1} 步"
        else -> "当前步骤"
    }

    companion object {
        /** 请求有效期：15 分钟。 */
        const val TTL_MS = 15 * 60 * 1000L
    }
}
