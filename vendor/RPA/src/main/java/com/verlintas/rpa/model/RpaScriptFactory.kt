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

/**
 * 发行版可用的脚本模板工厂（纯 Kotlin，无 Android 依赖）。
 *
 * 这里只放“新建脚本”的通用骨架；微信 7 步等测试夹具放在 test source set，
 * 不进入 release APK（见 docs 的 §7.9）。
 */
object RpaScriptFactory {

    /** 新建空白脚本的初始四步，帮助用户理解执行顺序。 */
    fun starter(
        name: String,
        mode: RpaMode,
        appPackage: String = "",
    ): RpaScript {
        val now = System.currentTimeMillis()
        return RpaScript(
            id = RpaIds.newId("script"),
            name = name,
            mode = mode,
            appPackage = appPackage,
            steps = listOf(
                step(
                    action = RpaAction.COMMENT,
                    label = "从动作库拖入动作",
                    note = "长按动作库按钮拖到此处，或直接点击追加",
                ),
                step(
                    action = RpaAction.LAUNCH_APP,
                    label = "打开目标 App",
                    params = RpaParams(packageName = appPackage),
                    timeoutMs = 15_000,
                ),
                step(
                    action = RpaAction.WAIT,
                    label = "等待界面就绪",
                    params = RpaParams(waitMs = 1_000),
                    timeoutMs = 2_000,
                ),
                step(
                    action = RpaAction.TAP,
                    label = "点击目标元素",
                    // 新建脚本还没有真实定位：默认禁用，避免首帧校验报 target_missing，
                    // 也避免 DUAL 预填的比例点误点屏幕中心。
                    enabled = false,
                    target = if (mode == RpaMode.DUAL) {
                        RpaTarget(
                            a11y = RpaA11ySpec(
                                selectors = listOf(RpaSelector(clickableOnly = true)),
                                pick = RpaPick.FIRST,
                            ),
                            vision = RpaVisionSpec(
                                primary = RpaVisionLocator(
                                    kind = RpaVisionKind.RATIO,
                                    ratioX = 0.5f,
                                    ratioY = 0.5f,
                                ),
                            ),
                        )
                    } else {
                        null
                    },
                    timeoutMs = 5_000,
                    note = "用属性面板的「在目标应用拾取 / 以图找图」生成定位后再启用",
                ),
            ),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun step(
        action: RpaAction,
        label: String,
        params: RpaParams = RpaParams(),
        target: RpaTarget? = null,
        expect: RpaExpect? = null,
        timeoutMs: Long = 5_000,
        delayBeforeMs: Long = 0,
        enabled: Boolean = true,
        note: String = "",
    ): RpaStep = RpaStep(
        id = RpaIds.newId("step"),
        action = action,
        label = label,
        params = params,
        target = target,
        expect = expect,
        timeoutMs = timeoutMs,
        delayBeforeMs = delayBeforeMs,
        enabled = enabled,
        note = note,
    )
}
