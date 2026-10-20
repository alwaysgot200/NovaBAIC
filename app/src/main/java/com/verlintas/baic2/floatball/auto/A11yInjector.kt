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

import com.verlintas.baic2.device.api.AccessibilityBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模式 A 执行通道（方案 §17.2）：委托 [AccessibilityBridge]（assists 换源实现）。
 * 文本注入走 typeText（SET_TEXT 失败自动落 F2 剪贴板+ACTION_PASTE 降级链），
 * 点击走 a11y 手势；通道可用性 = 无障碍服务连接状态。
 */
@Singleton
class A11yInjector @Inject constructor(
    private val bridge: AccessibilityBridge,
) : InputInjector {

    override val channel = InjectorChannel.A11y

    override val available: Boolean
        get() = bridge.connected.value

    override val unavailableReason = SendSequence.REASON_A11Y_DISCONNECTED

    override suspend fun tap(x: Int, y: Int): Result<Unit> = bridge.tap(x, y)

    override suspend fun type(text: String): Result<Unit> = bridge.typeText(text)

    /**
     * 模式 A 不走独立 paste 原语：typeText 内部已内嵌 SET_TEXT→剪贴板+ACTION_PASTE
     * 降级链（F2），状态机对模式 A 永远走 type 路径。
     */
    override suspend fun paste(): Result<Unit> = Result.failure(
        IllegalStateException("a11y_paste_not_exposed: use type() (F2 chain built-in)"),
    )

    override suspend fun clearInput(): Result<Unit> = bridge.typeText("")

    override suspend fun foregroundPackage(): String? = bridge.foregroundPackage()
}

/**
 * 模式 A 定位器：从 a11y 节点树定位发送按钮（候选词包含匹配 + 小控件尺寸过滤，
 * 取**最靠下、其次最靠右**——IM 发送按钮恒在底部输入栏右端）。
 */
class A11ySendLocator(
    private val bridge: AccessibilityBridge,
) : SendLocator {

    override suspend fun locate(): SendTarget? =
        SEND_BUTTON_QUERIES.asSequence()
            .flatMap { bridge.findText(it).asSequence() }
            .filter { it.bottom - it.top <= MAX_SEND_NODE_HEIGHT }
            .filter { it.right - it.left <= MAX_SEND_NODE_WIDTH }
            .maxWithOrNull(compareBy({ it.bottom }, { it.right }))
            ?.let { SendTarget(it.centerX, it.centerY) }
}
