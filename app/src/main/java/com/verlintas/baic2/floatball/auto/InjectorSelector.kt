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
    fun select(configured: String?, a11yReady: Boolean, shizukuReady: Boolean): InjectorChannel = when {
        configured == CONFIG_A11Y -> if (a11yReady) InjectorChannel.A11y else InjectorChannel.DraftFallback
        configured == CONFIG_SHIZUKU -> if (shizukuReady) InjectorChannel.Shizuku else InjectorChannel.DraftFallback
        a11yReady -> InjectorChannel.A11y
        shizukuReady -> InjectorChannel.Shizuku
        else -> InjectorChannel.DraftFallback
    }
}
