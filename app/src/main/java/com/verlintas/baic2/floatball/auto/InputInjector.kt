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
 * 执行通道三模（方案 §17.2）：A=a11y（节点/手势/SET_TEXT）、B=Shizuku（shell
 * `input tap`/剪贴板+粘贴）、C=纯感知起草档（无注入能力，自动挂起草通知）。
 */
enum class InjectorChannel { A11y, Shizuku, DraftFallback }

/**
 * 执行通道抽象（方案 §17.2「定位/执行解耦」）：[SendSequence] 负责三步状态机
 * 编排，一切触点坐标来自 [SendLocator] 定位结果，注入/点击由本接口实现执行。
 * 模式 A（[A11yInjector]）与模式 B（[ShizukuInjector]）共用同一状态机底座。
 */
interface InputInjector {

    val channel: InjectorChannel

    /** 通道当前是否可执行（仅作通道选择依据，不担保后续注入成功）。 */
    val available: Boolean

    /** 不可用时的台账原因码（[SendSequence] 短路返回用，REASON_* 口径）。 */
    val unavailableReason: String

    suspend fun tap(x: Int, y: Int): Result<Unit>

    /** 注入文本到焦点输入框（实现方自决 SET_TEXT / input text / 剪贴板+粘贴）。 */
    suspend fun type(text: String): Result<Unit>

    /** 将当前系统剪贴板内容粘贴进焦点输入框（模式 B 中文注入路径的执行原语）。 */
    suspend fun paste(): Result<Unit>

    /** best effort 清空焦点输入框（降级起草前清残留，失败可忽略）。 */
    suspend fun clearInput(): Result<Unit>

    /** 前台包名探测；null = 未知（状态机按「拒绝注入」处理，防错发到别的 App）。 */
    suspend fun foregroundPackage(): String?
}

/**
 * 定位结果：发送按钮在屏幕坐标系的点击中心；[width]/[height] 为按钮外接盒
 * （像素定位器产出，供点击后「按钮是否仍在」的发送证据判定；OCR 定位器
 * 无盒子信息时保持 0，证据判定退化为坐标比较）。
 */
data class SendTarget(
    val centerX: Int,
    val centerY: Int,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * 发送按钮定位器（与执行通道解耦）：模式 A 由 a11y 节点树定位
 * （[A11ySendLocator]），模式 B 由录屏抽帧+OCR/找图产出坐标
 * （[OcrSendLocator]）。定位失败返回 null，重试节奏由 [SendSequence] 统一编排。
 */
fun interface SendLocator {

    /** 本帧定位发送按钮中心；null = 未找到（状态机负责一次重试）。 */
    suspend fun locate(): SendTarget?
}

/** 发送按钮候选词（包含匹配、大小写不敏感）：中文 IM 主用「发送」，拉丁 UI 兜底 "send"。 */
internal val SEND_BUTTON_QUERIES = listOf("发送", "send")

/**
 * 定位候选的尺寸过滤（启发式：IM 发送按钮是小控件）：过滤掉高度/宽度异常的
 * 整行容器节点或 OCR 误合并的长句，模式 A/B 定位器共用同一口径。
 */
internal const val MAX_SEND_NODE_HEIGHT = 200
internal const val MAX_SEND_NODE_WIDTH = 400
