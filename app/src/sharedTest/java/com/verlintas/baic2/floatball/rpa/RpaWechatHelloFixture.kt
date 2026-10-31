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

import com.verlintas.rpa.model.RpaA11yLineParams
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaAction
import com.verlintas.rpa.model.RpaIds
import com.verlintas.rpa.model.RpaInputChannel
import com.verlintas.rpa.model.RpaLineParams
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
import com.verlintas.rpa.model.RpaVisionLineParams
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
                // 启动完成判定（spec §2.1）：a11y 等「微信」文本节点出现，
                // 无 a11y 等屏幕出现「微信」（OCR）。2026-10-31 真机取证：微信
                // 8.x 树里带文本的节点均 clickable=false（可点击的是外层容器），
                // 故与拾取回填 toRpaSelector 同语义只设 enabledOnly——点击按
                // 坐标派发，落点天然命中容器。
                lineParams = RpaLineParams(
                    a11y = RpaA11yLineParams(
                        readySelector = RpaSelector(
                            text = "微信",
                            textMatch = RpaMatchMode.CONTAINS,
                            enabledOnly = true,
                        ),
                    ),
                    vision = RpaVisionLineParams(readyText = "微信"),
                ),
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
                // 输入通道（spec §2.1）：a11y 沿用焦点替换写入；无 a11y 剪贴板
                // 粘贴 + 先清空（全选+删除），不预点焦点。
                lineParams = INPUT_LINE_PARAMS,
            ),
            step(
                action = RpaAction.TAP,
                label = "点击联系人",
                target = RpaTarget(
                    a11y = RpaA11ySpec(
                        selectors = listOf(
                            // 2026-10-31 真机取证（微信 8.0.79 FTS 结果页）：搜索
                            // 框 EditText 的 text 也恰为刚输入的完整联系人名，单靠
                            // CONTAINS 会先命中搜索框（点空、不进聊天页）；「包含:」
                            // 聊天记录行的 text 也含全名。结果行名节点是 TextView
                            // （com.tencent.mm:id/odf）且 text 恰为完整显示名，
                            // 故 EXACT + TextView 双条件唯一锁定联系人行。
                            RpaSelector(
                                text = "\${contactName}",
                                textMatch = RpaMatchMode.EXACT,
                                className = "android.widget.TextView",
                                enabledOnly = true,
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
                // 同「输入联系人」的输入通道设计（spec §2.1 步骤6）。
                lineParams = INPUT_LINE_PARAMS,
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

    /**
     * INPUT_TEXT 步骤的 D 类分线通道参数（spec §2.1 步骤3/6 同款）：a11y
     * inputSelector=空（沿用焦点）、appendMode=替换（SET_TEXT）；无 a11y
     * inputChannel=CLIPBOARD_PASTE、clearBeforeInput=是、focusPoint=不预点。
     */
    private val INPUT_LINE_PARAMS = RpaLineParams(
        a11y = RpaA11yLineParams(inputSelector = null, appendMode = false),
        vision = RpaVisionLineParams(
            inputChannel = RpaInputChannel.CLIPBOARD_PASTE,
            clearBeforeInput = true,
            focusPoint = null,
        ),
    )

    private fun step(
        action: RpaAction,
        label: String,
        params: RpaParams = RpaParams(),
        target: RpaTarget? = null,
        timeoutMs: Long = 5_000,
        delayAfterMs: Long = 0,
        lineParams: RpaLineParams = RpaLineParams(),
    ): RpaStep = RpaStep(
        id = RpaIds.newId("step"),
        action = action,
        label = label,
        params = params,
        target = target,
        timeoutMs = timeoutMs,
        delayAfterMs = delayAfterMs,
        lineParams = lineParams,
    )
}
