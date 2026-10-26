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

package com.verlintas.baic2.core.model

/**
 * 积分计价（与服务端 PointsLedger 同口径）。
 *
 * - 基础积分 B（毫积分）= (输入×1 + 缓存×0.1 + 输出×3) ÷ 1000；
 * - 实际扣点 D（毫积分）= max(100, 向上取整到 0.1 积分的 B × M)，
 *   M = coefficientMilli（模型倍率 × 1000）。
 *
 * App 端用于每条回复即时展示「积分消耗」；服务端实际扣点仍以 spend log 结算为准，
 * 两端口径一致，正常只会在缓存 token 上报差异时出现极小误差。
 */
object PointsPricing {

    /** 默认倍率（1.0x，未命中模型目录时兜底）。 */
    const val DEFAULT_COEFFICIENT_MILLI = 1000

    /** 最低扣点 0.1 积分 = 100 毫积分。 */
    private const val MIN_DEDUCT_MILLI = 100L

    /** 计算基础积分（毫积分）。 */
    fun basePointsMilli(promptTokens: Long, cachedTokens: Long = 0L, completionTokens: Long): Long {
        val weighted = promptTokens.coerceAtLeast(0L) * 10L +
            cachedTokens.coerceAtLeast(0L) +
            completionTokens.coerceAtLeast(0L) * 30L
        return weighted / 10L
    }

    /** 计算实际扣点（毫积分）。 */
    fun deductPointsMilli(
        promptTokens: Long,
        completionTokens: Long,
        coefficientMilli: Int,
        cachedTokens: Long = 0L,
    ): Long {
        val base = basePointsMilli(promptTokens, cachedTokens, completionTokens)
        val grid = ceilDiv(base * coefficientMilli.coerceAtLeast(0).toLong(), 100_000L) * 100L
        return grid.coerceAtLeast(MIN_DEDUCT_MILLI)
    }

    /** 毫积分 → 展示积分（1.2 表示 1.2 积分）。 */
    fun toPoints(pointsMilli: Long): Double = pointsMilli / 1000.0

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1L) / b
}

/**
 * 模型倍率缓存（app 在拉取服务端模型目录后写入；AgentLoop/界面读取）。
 *
 * 进程内单例、无持久化：冷启动未拉目录前按 1.0x 兜底，进入设置页后即刷新。
 */
object ModelPricing {

    @Volatile
    private var coefficients: Map<String, Int> = emptyMap()

    /** 当前默认 Agent 的模型（消息未带 model 时的兜底）。 */
    @Volatile
    var activeModelKey: String? = null

    fun update(models: List<Pair<String, Int>>) {
        coefficients = models.associate { (key, milli) -> key to milli }
    }

    fun coefficientMilli(modelKey: String?): Int {
        val key = modelKey?.takeIf { it.isNotBlank() } ?: activeModelKey
        if (key.isNullOrBlank()) return PointsPricing.DEFAULT_COEFFICIENT_MILLI
        return coefficients[key] ?: PointsPricing.DEFAULT_COEFFICIENT_MILLI
    }
}
