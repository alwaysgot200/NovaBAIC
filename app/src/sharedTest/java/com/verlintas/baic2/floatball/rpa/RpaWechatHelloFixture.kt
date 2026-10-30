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

package com.verlintas.baic2.floatball.rpa

import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaMode
import com.verlintas.rpa.model.RpaParams
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaRect
import com.verlintas.rpa.model.RpaScript
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaStep
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec

/**
 * 用户原始需求里的微信 7 步例子：**只用于测试**，不进入发行版。
 *
 * 发行版只保留 [com.verlintas.rpa.model.RpaScriptFactory] 的空白脚本骨架；
 * 本夹具只存在于 `app/src/sharedTest`（JVM 单测与 androidTest 共用），
 * 由 JVM 测试验证结构，并由真机 androidTest 实际跑通。断言不占第 8 步：
 * 测试代码在脚本结束后自行检查「你好」是否在屏。
 */
object RpaWechatHelloFixture {

    const val DEFAULT_IM_PACKAGE = "com.tencent.mm"
    const val DEFAULT_CONTACT_NAME = "吴瑞曦topwayricky"

    fun build(
        imPackage: String = DEFAULT_IM_PACKAGE,
        contactName: String = DEFAULT_CONTACT_NAME,
        launchDelayMs: Long = 5_000,
        now: Long = System.currentTimeMillis(),
    ): RpaScript = RpaScript(
        id = RpaIds.newId("script"),
        name = "微信 7 步测试夹具",
        mode = RpaMode.DUAL,
        appPackage = imPackage,
        description = "测试夹具：启动微信 → 搜索图标 → 输入联系人 → 选中联系人 → " +
            "点击输入框 → 输入「你好」→ 点击发送。发行版不包含此脚本。",
        variables = mapOf(
            "imPackage" to imPackage,
            "contactName" to contactName,
        ),
        steps = listOf(
            step(
                action = RpaAction.LAUNCH_APP,
                label = "启动微信",
                params = RpaParams(packageName = imPackage),
                timeoutMs = 15_000,
                delayAfterMs = launchDelayMs,
            ),
            step(
                action = RpaAction.TAP,
                label = "点击搜索图标",
                target = RpaTarget(
                    a11y = RpaA11ySpec(
                        selectors = listOf(
                            RpaSelector(
                                description = "搜索",
                                textMatch = RpaMatchMode.CONTAINS,
                            ),
                            RpaSelector(viewId = "search"),
                        ),
                        pick = RpaPick.FIRST,
                        note = "微信右上角搜索入口",
                    ),
                    vision = RpaVisionSpec(
                        primary = RpaVisionLocator(
                            kind = RpaVisionKind.RATIO,
                            // 2026-10-28 真机（1080×2460）实测：微信 8.0.79 主页
                            // 右上角是「+」在 x≈971，搜索放大镜在 x≈870；旧值
                            // 0.88,0.075 → (950,184) 点到「+」展开菜单，后续步骤
                            // 全部跑偏（真机日志 ok=true 但落到「发现/附近的人」）。
                            ratioX = 0.806f,
                            ratioY = 0.058f,
                            pick = RpaPick.FIRST,
                        ),
                        note = "右上角搜索图标；微信版本变化时用布局分析 / 以图找图校准",
                    ),
                ),
                timeoutMs = 5_000,
            ),
            step(
                action = RpaAction.INPUT_TEXT,
                label = "输入联系人",
                params = RpaParams(
                    text = "\${contactName}",
                    clearFirst = true,
                ),
                timeoutMs = 5_000,
            ),
            step(
                action = RpaAction.TAP,
                label = "点击联系人",
                target = RpaTarget(
                    a11y = RpaA11ySpec(
                        selectors = listOf(
                            RpaSelector(
                                text = "\${contactName}",
                                textMatch = RpaMatchMode.CONTAINS,
                                clickableOnly = true,
                            ),
                        ),
                        pick = RpaPick.FIRST,
                        note = "搜索结果里的联系人行",
                    ),
                    vision = RpaVisionSpec(
                        primary = RpaVisionLocator(
                            kind = RpaVisionKind.OCR_TEXT,
                            ocrText = "\${contactName}",
                            ocrMatch = RpaMatchMode.CONTAINS,
                            region = RpaRect(0f, 0.12f, 1f, 0.75f),
                            pick = RpaPick.TOP_MOST,
                        ),
                        fallback = RpaVisionLocator(
                            kind = RpaVisionKind.RATIO,
                            ratioX = 0.5f,
                            ratioY = 0.28f,
                            pick = RpaPick.FIRST,
                        ),
                        note = "搜索结果首行；OCR 失败时用比例兜底",
                    ),
                ),
                timeoutMs = 6_000,
            ),
            step(
                action = RpaAction.TAP,
                label = "点击聊天输入框",
                target = RpaTarget(
                    a11y = RpaA11ySpec(
                        selectors = listOf(RpaSelector(editableOnly = true)),
                        pick = RpaPick.BOTTOM_MOST,
                        note = "底部可编辑节点即聊天输入框",
                    ),
                    vision = RpaVisionSpec(
                        primary = RpaVisionLocator(
                            kind = RpaVisionKind.RATIO,
                            ratioX = 0.5f,
                            // 2026-10-28 真机（1080×2460）实测：微信 8.0.79 聊天页
                            // 输入栏在 y≈2280–2400，点击 y=2320 才弹出键盘；
                            // 旧值 0.88（y≈2165）落在消息区，点了没有任何反应。
                            ratioY = 0.94f,
                            pick = RpaPick.BOTTOM_MOST,
                        ),
                        note = "聊天输入栏固定在底部中央（真机 ratio≈0.94）",
                    ),
                ),
                timeoutMs = 5_000,
            ),
            step(
                action = RpaAction.INPUT_TEXT,
                label = "输入你好",
                params = RpaParams(text = "你好", clearFirst = true),
                timeoutMs = 5_000,
            ),
            step(
                action = RpaAction.TAP,
                label = "点击发送",
                target = RpaTarget(
                    a11y = RpaA11ySpec(
                        selectors = listOf(
                            RpaSelector(text = "发送", textMatch = RpaMatchMode.CONTAINS),
                            RpaSelector(
                                description = "发送",
                                textMatch = RpaMatchMode.CONTAINS,
                            ),
                        ),
                        pick = RpaPick.BOTTOM_MOST,
                        note = "发送键文本 / 描述",
                    ),
                    vision = RpaVisionSpec(
                        primary = RpaVisionLocator(
                            kind = RpaVisionKind.OCR_TEXT,
                            ocrText = "发送",
                            ocrMatch = RpaMatchMode.CONTAINS,
                            // 键盘弹起后「发送」文本中心 y≈1358；区域上界取 0.5
                            // （y=1230）才能完整框住字，旧值 0.55（y=1353）把字
                            // 上缘切掉，OCR 恒 miss 只剩比例兜底。
                            region = RpaRect(0.5f, 0.5f, 1f, 1f),
                            pick = RpaPick.BOTTOM_MOST,
                        ),
                        fallback = RpaVisionLocator(
                            kind = RpaVisionKind.RATIO,
                            ratioX = 0.87f,
                            // 键盘弹出后输入栏上移到 y≈1340–1440，绿色「发送」按钮
                            // 中心≈(941,1358)，真机 ratio≈(0.871,0.552)；
                            // 旧的 0.88 落在键盘键位区，会点空。
                            ratioY = 0.552f,
                            pick = RpaPick.BOTTOM_MOST,
                        ),
                        note = "底部右侧 OCR「发送」，失败用比例兜底（键盘弹出后 ratio≈0.552）",
                    ),
                ),
                timeoutMs = 6_000,
            ),
        ),
        createdAt = now,
        updatedAt = now,
    )

    private fun step(
        action: RpaAction,
        label: String,
        params: RpaParams = RpaParams(),
        target: RpaTarget? = null,
        timeoutMs: Long = 5_000,
        delayAfterMs: Long = 0,
    ): RpaStep = RpaStep(
        id = RpaIds.newId("step"),
        action = action,
        label = label,
        params = params,
        target = target,
        timeoutMs = timeoutMs,
        delayAfterMs = delayAfterMs,
    )
}
