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

package com.verlintas.baic2.floatball.auto

/**
 * 执行通道选择与降级链（方案 §17.2：**用户配置 > A 可用 > B 可用 > C 降级**）。
 * 纯函数判定，JVM 可测；C（起草档）不算「托管」——选到 C 时自动派发直接降级
 * 起草通知。显式配置的通道不可用时**不静默换道**（用户指定即强制），直接降 C。
 */
object InjectorSelector {

    /** 自动降级（默认）：A 可用走 A，其次 B，都不可用降 C。 */
    const val CONFIG_AUTO = "auto"

    /** 强制模式 A（a11y）；不可用直接降 C，不借道 B。 */
    const val CONFIG_A11Y = "a11y"

    /** 强制模式 B（Shizuku）；不可用直接降 C，不借道 A。 */
    const val CONFIG_SHIZUKU = "shizuku"

    val CONFIG_OPTIONS = setOf(CONFIG_AUTO, CONFIG_A11Y, CONFIG_SHIZUKU)

    /** [configured] 为 AdvisorPrefs 持久化的通道配置（非法值按 auto 处理）。 */
    fun select(configured: String?, a11yReady: Boolean, shizukuReady: Boolean): InjectorChannel =
        plan(configured, a11yReady, shizukuReady).firstOrNull() ?: InjectorChannel.DraftFallback

    /**
     * 运行期降级链（2026-10-27 真机修复）：返回**按序尝试**的通道列表，末位
     * 兜底不是 C（本地通道全不可用时列表为空，调用方按 [InjectorChannel.DraftFallback]
     * 处理）。与 [select] 的差别只在 auto 档多一位候选：
     *
     * - `a11y`/`shizuku` 强制配置：只含该通道（可用时），不可用即空——用户指定
     *   即强制，绝不静默换道（与 [select] 同口径）。
     * - `auto`：A 可用先 A；A 失败且「确定未发出」时回落 B 再试一遍。真机
     *   2026-10-27 事故正是 A 选得中但微信节点树为空（`input_inject_failed`）
     *   导致整轮静默——B（Shizuku + OCR 定位）本来是通的，只是没人去试。
     *
     * 是否允许换道由 `SendSequence.safeToRetryElsewhere` 判定（证据不足绝不重发）。
     */
    fun plan(configured: String?, a11yReady: Boolean, shizukuReady: Boolean): List<InjectorChannel> =
        when (configured) {
            CONFIG_A11Y -> if (a11yReady) listOf(InjectorChannel.A11y) else emptyList()
            CONFIG_SHIZUKU -> if (shizukuReady) listOf(InjectorChannel.Shizuku) else emptyList()
            else -> buildList {
                if (a11yReady) add(InjectorChannel.A11y)
                if (shizukuReady) add(InjectorChannel.Shizuku)
            }
        }
}
