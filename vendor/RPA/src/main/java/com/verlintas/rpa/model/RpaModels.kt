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

/**
 * RPA 线路策略（动作设计之前必须先定的三选一）：
 * - [A11Y] 开启无障碍：只允许无障碍节点定位 + 无障碍手势 / SET_TEXT；
 * - [VISION] 无无障碍：只允许 Shizuku 注入 + 抽帧 + OCR / OpenCV 找图；
 * - [DUAL] 双模式：每一步必须同时给出 a11y 与 vision 两套定位，运行时优先
 *   a11y，失败后显式切换到 vision（切换会写入运行事件，禁止静默切换）。
 */
@Serializable
enum class RpaMode {
    A11Y,
    VISION,
    DUAL,
}

/** 单次执行的线路。 */
@Serializable
enum class RpaLine {
    A11Y,
    VISION,
    ;

    fun other(): RpaLine = if (this == A11Y) VISION else A11Y
}

/** 动作目录（参考自动精灵 / UiPath 的步骤原语，保持最小可用集合）。 */
@Serializable
enum class RpaAction {
    /** 拉起 App：参数 packageName。 */
    LAUNCH_APP,

    /** 打开 IM 与联系人的聊天页：参数 imPackage + contactName。 */
    OPEN_CHAT,

    /** 固定等待：参数 waitMs。 */
    WAIT,

    /** 轮询等待目标出现（只定位不点击），超时失败。 */
    WAIT_FOR_TARGET,

    /** 点击目标。 */
    TAP,

    /** 长按目标。 */
    LONG_PRESS,

    /** 向当前焦点输入框写入文本：参数 text。 */
    INPUT_TEXT,

    /** 系统按键：参数 key = back|home|recents|notifications|enter。 */
    PRESS_KEY,

    /** 定向滑动：参数 direction + distanceRatio + durationMs。 */
    SWIPE,

    /** 滚动（默认向上滑，露出下方内容）。 */
    SCROLL,

    /** 断言屏幕文本：参数 expect。 */
    ASSERT_TEXT,

    /** 注释（仅编辑器可读，运行时空操作）。 */
    COMMENT,
}

@Serializable
enum class RpaMatchMode {
    EXACT,
    CONTAINS,
    PREFIX,
    REGEX,
}

/** 多命中时的选取策略（与 UiPath / AutoJs 的 selector index 语义对齐）。 */
@Serializable
enum class RpaPick {
    FIRST,
    TOP_MOST,
    BOTTOM_MOST,
    LARGEST,
    NEAREST,
}

@Serializable
enum class RpaNodeAction {
    CLICK,
    LONG_CLICK,
    READ,
}

@Serializable
enum class RpaVisionKind {
    /** 以图找图（OpenCV matchTemplate，模板为 filesDir 下的 PNG）。 */
    TEMPLATE,

    /** 抽帧 OCR 文本定位。 */
    OCR_TEXT,

    /** 屏幕比例坐标（兜底；按当前帧尺寸换算）。 */
    RATIO,
}

@Serializable
enum class RpaDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

@Serializable
enum class RpaFailurePolicy {
    STOP,
    CONTINUE,
}

/** 归一化矩形（0..1，相对当前帧），用于圈定 OCR / 找图 ROI。 */
@Serializable
data class RpaRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val widthRatio: Float get() = (right - left).coerceAtLeast(0f)
    val heightRatio: Float get() = (bottom - top).coerceAtLeast(0f)

    fun normalized(): RpaRect = RpaRect(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f),
    )

    fun isValid(): Boolean = widthRatio > 0.005f && heightRatio > 0.005f
}

/** 绝对像素边界（节点快照用，展示 / 快照兜底点击）。 */
@Serializable
data class RpaBounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * 单条 a11y 选择器。同一条内所有非空条件按 AND 组合；多条选择器之间 OR，
 * 全部命中后由 [RpaA11ySpec.pick] 决定取哪一个。空条件的选择器会被校验器
 * 拒绝——避免「随便点一个节点」的隐性误操作。
 */
@Serializable
data class RpaSelector(
    val text: String? = null,
    val textMatch: RpaMatchMode = RpaMatchMode.CONTAINS,
    val viewId: String? = null,
    val description: String? = null,
    val className: String? = null,
    val clickableOnly: Boolean = false,
    val editableOnly: Boolean = false,
    val enabledOnly: Boolean = true,
) {
    fun isEmpty(): Boolean =
        text.isNullOrBlank() && viewId.isNullOrBlank() && description.isNullOrBlank() &&
            className.isNullOrBlank() && !clickableOnly && !editableOnly
}

/** 布局分析器留下的节点快照（编辑器展示 + 选择器全 miss 时的坐标兜底）。 */
@Serializable
data class RpaNodeSnapshot(
    val packageName: String = "",
    val className: String = "",
    val text: String = "",
    val description: String = "",
    val viewId: String = "",
    val hintText: String = "",
    val bounds: RpaBounds = RpaBounds(),
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
)

/** a11y 定位表达式。 */
@Serializable
data class RpaA11ySpec(
    val selectors: List<RpaSelector> = emptyList(),
    val pick: RpaPick = RpaPick.FIRST,
    val action: RpaNodeAction = RpaNodeAction.CLICK,
    val snapshot: RpaNodeSnapshot? = null,
    val note: String = "",
) {
    fun isConfigured(): Boolean = selectors.any { !it.isEmpty() } || snapshot != null
}

/**
 * 视觉定位表达式（TEMPLATE / OCR_TEXT / RATIO 三选一，另带一个兜底）。
 * 模板 / 阈值 / 灰度 / 区域等属性均可在「以图找图」配置页交互生成。
 */
@Serializable
data class RpaVisionLocator(
    val kind: RpaVisionKind = RpaVisionKind.RATIO,
    val templateId: String? = null,
    val ocrText: String? = null,
    val ocrMatch: RpaMatchMode = RpaMatchMode.CONTAINS,
    val threshold: Double = 0.80,
    val grayscale: Boolean = true,
    val region: RpaRect? = null,
    val ratioX: Float = 0.5f,
    val ratioY: Float = 0.5f,
    val pick: RpaPick = RpaPick.BOTTOM_MOST,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
) {
    fun isConfigured(): Boolean = when (kind) {
        RpaVisionKind.TEMPLATE -> !templateId.isNullOrBlank()
        RpaVisionKind.OCR_TEXT -> !ocrText.isNullOrBlank()
        RpaVisionKind.RATIO -> ratioX in 0f..1f && ratioY in 0f..1f
    }
}

@Serializable
data class RpaVisionSpec(
    val primary: RpaVisionLocator = RpaVisionLocator(),
    val fallback: RpaVisionLocator? = null,
    val note: String = "",
) {
    fun isConfigured(): Boolean = primary.isConfigured()
}

/** 一步的双线路定位。模式决定哪一侧可用，校验器负责强制约束。 */
@Serializable
data class RpaTarget(
    val a11y: RpaA11ySpec? = null,
    val vision: RpaVisionSpec? = null,
) {
    fun specFor(line: RpaLine): Any? = when (line) {
        RpaLine.A11Y -> a11y
        RpaLine.VISION -> vision
    }
}

/** 动作参数（按动作取用其中一部分；未使用字段保持 null）。 */
@Serializable
data class RpaParams(
    val packageName: String? = null,
    val imPackage: String? = null,
    val contactName: String? = null,
    val text: String? = null,
    val key: String? = null,
    val waitMs: Long? = null,
    val durationMs: Int? = null,
    val direction: RpaDirection? = null,
    val distanceRatio: Float? = null,
    val clearFirst: Boolean = false,
)

@Serializable
data class RpaExpect(
    val text: String = "",
    val match: RpaMatchMode = RpaMatchMode.CONTAINS,
    val timeoutMs: Long = 5_000,
)

@Serializable
data class RpaStep(
    val id: String,
    val action: RpaAction,
    val label: String = "",
    val enabled: Boolean = true,
    val params: RpaParams = RpaParams(),
    val target: RpaTarget? = null,
    val expect: RpaExpect? = null,
    val timeoutMs: Long = 5_000,
    val retries: Int = 0,
    val delayBeforeMs: Long = 0,

    /** 动作最终成功后等待（毫秒）；失败 / 重试期间不等待。 */
    val delayAfterMs: Long = 0,
    val onFailure: RpaFailurePolicy = RpaFailurePolicy.STOP,
    val note: String = "",
) {
    fun needsTarget(): Boolean = RpaCatalog.needsTarget(action)
}

/**
 * RPA 脚本（JSON 文档式存储，见 [com.verlintas.rpa.store.RpaScriptStore]）。
 * [variables] 只放可替换的轻量参数（如 contactName），运行时以 `${key}` 形式
 * 注入到动作参数里——脚本因此可以跨联系人复用。
 */
@Serializable
data class RpaScript(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val mode: RpaMode = RpaMode.DUAL,
    val appPackage: String = "",
    val description: String = "",
    val variables: Map<String, String> = emptyMap(),
    val steps: List<RpaStep> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun enabledSteps(): List<RpaStep> = steps.filter { it.enabled }

    fun variableKeys(): Set<String> = steps.flatMap { it.referencedVariables() }.toSet()

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** 从动作参数、断言文本与定位表达式里提取 `${name}` 变量名。 */
fun RpaStep.referencedVariables(): Set<String> = buildSet {
    val texts = buildList {
        addAll(
            listOfNotNull(
                params.packageName,
                params.imPackage,
                params.contactName,
                params.text,
                params.key,
                expect?.text,
            ),
        )
        // 定位表达式同样支持变量：联系人名既可能出现在动作参数里，也可能
        // 出现在 a11y 选择器文本 / 视觉 OCR 文本里，两边都要计入声明检查。
        target?.a11y?.selectors?.forEach { selector ->
            addAll(
                listOfNotNull(
                    selector.text,
                    selector.viewId,
                    selector.description,
                    selector.className,
                ),
            )
        }
        listOfNotNull(target?.vision?.primary, target?.vision?.fallback).forEach { locator ->
            locator.ocrText?.let(::add)
        }
    }
    // Android 的 ICU 正则不接受转义花括号 `\{`（桌面 JVM 可以），用 `Regex`
    // 会在设备上抛 PatternSyntaxException，直接击穿编辑器首帧。这里改为纯字符串
    // 扫描，两端行为一致，也避免把用户输入交给正则编译。
    texts.forEach { value -> addAll(value.extractVariableNames()) }
}

/** 扫描 `${name}`，名称字符集与校验器约定一致：`A-Z a-z 0-9 _ . -`。 */
private fun String.extractVariableNames(): List<String> {
    // buildList 的隐式接收者是 MutableList，不能在其作用域内裸调 indexOf，
    // 否则会按「列表元素查找」解析而永远返回 -1；先绑定源字符串。
    val value = this
    if (!value.contains("\${")) return emptyList()
    return buildList {
        var index = value.indexOf("\${")
        while (index >= 0) {
            val start = index + 2
            val end = value.indexOf('}', start)
            if (end < 0) break
            val name = value.substring(start, end)
            val nested = name.indexOf("\${")
            if (nested >= 0) {
                // 外层未闭合的 `${` 里又出现了新占位符：从内层重新扫描，
                // 不能把内层一起当成非法名扔掉。
                index = start + nested
                continue
            }
            if (name.isNotEmpty() && name.all { it.isVariableNameChar() }) add(name)
            index = value.indexOf("\${", end + 1)
        }
    }
}

private fun Char.isVariableNameChar(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
        this == '_' || this == '.' || this == '-'

/** 变量替换；未定义的变量保留原样（校验器会先给出警告）。 */
fun resolveVariables(value: String?, variables: Map<String, String>): String? {
    if (value == null) return null
    var out: String = value
    variables.forEach { (key, v) -> out = out.replace("\${$key}", v) }
    return out
}

/** 选择器文本的运行时变量展开（布局分析器采集的是字面文本）。 */
fun RpaSelector.resolve(variables: Map<String, String>): RpaSelector = copy(
    text = resolveVariables(text, variables),
    viewId = resolveVariables(viewId, variables),
    description = resolveVariables(description, variables),
    className = resolveVariables(className, variables),
)

/** 视觉定位只展开 OCR 文本；模板 id 是存储键，不参与变量替换。 */
fun RpaVisionLocator.resolve(variables: Map<String, String>): RpaVisionLocator = copy(
    ocrText = resolveVariables(ocrText, variables),
)

fun RpaVisionSpec.resolve(variables: Map<String, String>): RpaVisionSpec = copy(
    primary = primary.resolve(variables),
    fallback = fallback?.resolve(variables),
)

/** 执行前展开双线路定位表达式里的变量（模式与就绪校验仍看原始结构）。 */
fun RpaTarget.resolve(variables: Map<String, String>): RpaTarget = copy(
    a11y = a11y?.copy(selectors = a11y.selectors.map { it.resolve(variables) }),
    vision = vision?.resolve(variables),
)

/** 一次定位命中。 */
data class RpaHit(
    val x: Int,
    val y: Int,
    val score: Double = 1.0,
    val detail: String = "",
    val line: RpaLine = RpaLine.A11Y,
)

enum class RpaIssueSeverity {
    ERROR,
    WARNING,
}

/** 校验 / 就绪门问题（code 稳定，UI 负责本地化）。 */
data class RpaIssue(
    val code: String,
    val stepId: String? = null,
    val severity: RpaIssueSeverity = RpaIssueSeverity.ERROR,
    val detail: String = "",
)

/** 运行前的能力快照（由 app 侧采集）。 */
data class RpaCapabilities(
    val a11yReady: Boolean = false,
    val shizukuReady: Boolean = false,
    val visionReady: Boolean = false,
    val ocrReady: Boolean = false,
    val projectionReady: Boolean = false,
    val screenshotReady: Boolean = false,
)

/** 运行事件流（UI 日志 / 失败报因统一口径）。 */
sealed interface RpaRunEvent {
    data class ScriptStarted(val script: RpaScript, val line: RpaLine?) : RpaRunEvent

    data class StepStarted(
        val index: Int,
        val step: RpaStep,
        val line: RpaLine?,
    ) : RpaRunEvent

    data class StepFinished(
        val index: Int,
        val step: RpaStep,
        val ok: Boolean,
        val detail: String = "",
    ) : RpaRunEvent

    /** 双模式下从一条线路切到另一条（必须显式记录，禁止静默）。 */
    data class LineFallback(
        val index: Int,
        val step: RpaStep,
        val from: RpaLine,
        val to: RpaLine,
        val reason: String,
    ) : RpaRunEvent

    data class Log(val message: String) : RpaRunEvent

    data class ScriptFinished(
        val ok: Boolean,
        val failedStepIndex: Int? = null,
        val detail: String = "",
    ) : RpaRunEvent
}

data class RpaRunResult(
    val ok: Boolean,
    val failedStepIndex: Int? = null,
    val detail: String = "",
)
