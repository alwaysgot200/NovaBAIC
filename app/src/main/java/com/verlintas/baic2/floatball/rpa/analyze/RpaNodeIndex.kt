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

package com.verlintas.baic2.floatball.rpa.analyze

import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaBounds
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaNodeAction
import com.verlintas.rpa.model.RpaNodeSnapshot
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaSelector
import kotlin.math.min

/**
 * 布局分析器的**纯逻辑层**（方案 §5.2）：节点镜像、扁平索引、命中测试与
 * 「定位条件 / 节点快照」JSON。
 *
 * 设计约束：
 * - 不引用任何 Android 类，JVM 单测可直接覆盖坐标映射、命中优先级与 JSON
 *   转义；采集层只负责把 [android.view.accessibility.AccessibilityNodeInfo]
 *   读成 [RpaRawNode]。
 * - 节点索引是**全局唯一**的：多窗口采集时按窗口顺序先序遍历打号，
 *   [RpaAnalyzeNode.windowIndex] 用于切窗口过滤，[RpaAnalyzeNode.index]
 *   用于隐藏集合与层级面板选中。
 */
data class RpaBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean =
        x >= left && x < right && y >= top && y < bottom

    override fun toString(): String = "[$left,$top][$right,$bottom]"

    companion object {
        val EMPTY = RpaBox(0, 0, 0, 0)
    }
}

/** 与 Android 解耦的节点镜像；[children] 保留原始层级供层级面板缩进。 */
data class RpaRawNode(
    val packageName: String = "",
    val className: String = "",
    val text: String = "",
    val description: String = "",
    val viewId: String = "",
    val hint: String = "",
    val bounds: RpaBox = RpaBox.EMPTY,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val visibleToUser: Boolean = true,
    val children: List<RpaRawNode> = emptyList(),
)

/** 扁平化后的节点：全局 [index]、父节点、深度和所属窗口。 */
data class RpaAnalyzeNode(
    val index: Int,
    val parentIndex: Int,
    val depth: Int,
    val windowIndex: Int,
    val raw: RpaRawNode,
) {
    val bounds: RpaBox get() = raw.bounds

    val simpleClassName: String
        get() = raw.className.substringAfterLast('.').ifBlank { raw.className }

    /** 层级面板/标签用的一行摘要：文本 → 描述 → hint → viewId 尾段 → 类名。 */
    val label: String
        get() = when {
            raw.text.isNotBlank() -> raw.text
            raw.description.isNotBlank() -> raw.description
            raw.hint.isNotBlank() -> raw.hint
            raw.viewId.isNotBlank() -> raw.viewId.substringAfterLast('/')
            else -> simpleClassName
        }

    val hasSemanticLabel: Boolean
        get() = raw.text.isNotBlank() || raw.description.isNotBlank() ||
            raw.viewId.isNotBlank()
}

data class RpaAnalyzeWindow(
    val index: Int,
    val packageName: String,
    val label: String,
    val root: RpaRawNode,
)

/**
 * 一次采集结果的只读索引。
 *
 * 命中测试按「包含点击点 → 面积小优先 → 深度大优先」排序：父子同框时优先
 * 最深的可点控件；[hitTestAll] 保留全部候选供重叠区域巡检（分析器点一下先
 * 选中最小框，菜单里循环切换，长按列出整摞候选）。
 * `visibleToUser=false` 或空矩形不参与命中。
 */
class RpaNodeIndex private constructor(
    val windows: List<RpaAnalyzeWindow>,
    val nodes: List<RpaAnalyzeNode>,
    val activeWindowIndex: Int,
) {

    fun window(windowIndex: Int): RpaAnalyzeWindow? = windows.getOrNull(windowIndex)

    fun nodesOf(windowIndex: Int): List<RpaAnalyzeNode> =
        nodes.filter { it.windowIndex == windowIndex }

    /** 画布上需要画框的节点：可见、非空矩形、未在 [exclude] 中。 */
    fun boxNodes(windowIndex: Int, exclude: Set<Int> = emptySet()): List<RpaAnalyzeNode> =
        nodesOf(windowIndex).filter {
            it.raw.visibleToUser && !it.bounds.isEmpty && it.index !in exclude
        }

    fun hitTest(
        x: Int,
        y: Int,
        windowIndex: Int,
        exclude: Set<Int> = emptySet(),
    ): RpaAnalyzeNode? = hitTestAll(x, y, windowIndex, exclude).firstOrNull()

    /**
     * 点击点下的**全部**候选，按「面积小 → 深度大 → 全局索引」排序。
     *
     * 重叠区域巡检依赖这个顺序：第一个就是 [hitTest] 的返回值（最内层控件），
     * 后续依次是祖先容器/背景层。分析器据此实现「点按循环 / 长按列表」，
     * 纯逻辑层只保证顺序稳定，不替 UI 决定展示方式。
     */
    fun hitTestAll(
        x: Int,
        y: Int,
        windowIndex: Int,
        exclude: Set<Int> = emptySet(),
    ): List<RpaAnalyzeNode> {
        val hits = ArrayList<RpaAnalyzeNode>()
        for (node in nodes) {
            if (node.windowIndex != windowIndex || node.index in exclude) continue
            if (!node.raw.visibleToUser || !node.raw.enabled) continue
            val box = node.bounds
            if (box.isEmpty || !box.contains(x, y)) continue
            hits.add(node)
        }
        hits.sortWith(HIT_ORDER)
        return hits
    }

    fun sameBounds(a: RpaAnalyzeNode, b: RpaAnalyzeNode): Boolean =
        a.windowIndex == b.windowIndex && a.bounds == b.bounds

    /** 根 → 当前节点的祖先链（含自身），父索引缺失时截断，防御脏数据成环。 */
    fun path(node: RpaAnalyzeNode): List<RpaAnalyzeNode> {
        val result = ArrayList<RpaAnalyzeNode>()
        var cursor: RpaAnalyzeNode? = node
        var guard = 0
        while (cursor != null && guard++ <= MAX_PATH_DEPTH) {
            result.add(cursor)
            val parentIndex = cursor.parentIndex
            cursor = if (parentIndex >= 0) nodes.getOrNull(parentIndex) else null
        }
        result.reverse()
        return result
    }

    fun pathText(node: RpaAnalyzeNode, separator: String = " > "): String =
        path(node).joinToString(separator) { it.label }

    /**
     * 语义定位条件是否可用。只有 bounds/类名不算「可复现定位」——同一界面的
     * 同类控件坐标可能漂移，此时 UI 应提示改复制节点快照。
     */
    fun hasSelector(node: RpaAnalyzeNode): Boolean = node.raw.run {
        text.isNotBlank() || description.isNotBlank() ||
            viewId.isNotBlank() || hint.isNotBlank()
    }

    /** 供「复制定位 JSON」使用：a11y 线路的稳定条件集合。 */
    fun toSelector(node: RpaAnalyzeNode): String {
        val raw = node.raw
        val pairs = ArrayList<Pair<String, String>>(10)
        pairs.add("packageName" to jsonString(raw.packageName))
        if (raw.text.isNotBlank()) pairs.add("text" to jsonString(raw.text))
        if (raw.description.isNotBlank()) pairs.add("description" to jsonString(raw.description))
        if (raw.viewId.isNotBlank()) pairs.add("viewId" to jsonString(raw.viewId))
        if (raw.hint.isNotBlank()) pairs.add("hint" to jsonString(raw.hint))
        if (raw.className.isNotBlank()) pairs.add("className" to jsonString(raw.className))
        pairs.add("bounds" to jsonString(raw.bounds.toString()))
        pairs.add("clickable" to raw.clickable.toString())
        pairs.add("editable" to raw.editable.toString())
        pairs.add("scrollable" to raw.scrollable.toString())
        return jsonObject(pairs)
    }

    /** 单节点的 a11y 动作规格（供 RPA 动作编辑器拾取回填的中间格式）。 */
    fun toA11ySpec(node: RpaAnalyzeNode): String = jsonObject(
        listOf(
            "mode" to jsonString("A11Y"),
            "windowIndex" to node.windowIndex.toString(),
            "nodeIndex" to node.index.toString(),
            "selector" to toSelector(node),
            "node" to toNodeJson(node),
            "path" to jsonString(pathText(node)),
        ),
    )

    /** 节点快照：无稳定选择器时的兜底（按包名 + 快照文字 + 相对路径校验）。 */
    fun toSnapshot(node: RpaAnalyzeNode): String = jsonObject(
        listOf(
            "mode" to jsonString("A11Y"),
            "reason" to jsonString("node_snapshot"),
            "windowIndex" to node.windowIndex.toString(),
            "nodeIndex" to node.index.toString(),
            "snapshot" to toNodeJson(node),
            "path" to jsonString(pathText(node)),
        ),
    )

    fun toNodeJson(node: RpaAnalyzeNode): String {
        val raw = node.raw
        val pairs = ArrayList<Pair<String, String>>(20)
        pairs.add("index" to node.index.toString())
        pairs.add("parentIndex" to node.parentIndex.toString())
        pairs.add("depth" to node.depth.toString())
        pairs.add("windowIndex" to node.windowIndex.toString())
        pairs.add("packageName" to jsonString(raw.packageName))
        pairs.add("className" to jsonString(raw.className))
        if (raw.text.isNotBlank()) pairs.add("text" to jsonString(raw.text))
        if (raw.description.isNotBlank()) pairs.add("description" to jsonString(raw.description))
        if (raw.viewId.isNotBlank()) pairs.add("viewId" to jsonString(raw.viewId))
        if (raw.hint.isNotBlank()) pairs.add("hint" to jsonString(raw.hint))
        pairs.add(
            "bounds" to jsonObject(
                listOf(
                    "left" to raw.bounds.left.toString(),
                    "top" to raw.bounds.top.toString(),
                    "right" to raw.bounds.right.toString(),
                    "bottom" to raw.bounds.bottom.toString(),
                ),
            ),
        )
        pairs.add("clickable" to raw.clickable.toString())
        pairs.add("editable" to raw.editable.toString())
        pairs.add("scrollable" to raw.scrollable.toString())
        pairs.add("focused" to raw.focused.toString())
        pairs.add("enabled" to raw.enabled.toString())
        pairs.add("visibleToUser" to raw.visibleToUser.toString())
        return jsonObject(pairs)
    }

    /**
     * 拾取回填的结构化 a11y 定位（方案 §7.8）：viewId（最稳）→ description →
     * text → className + 属性过滤。四类都没有时返回 null——与「复制定位 JSON」
     * 同口径，不伪造一个「随便点」的空选择器。
     */
    fun toRpaSelector(node: RpaAnalyzeNode): RpaSelector? {
        val raw = node.raw
        val viewId = raw.viewId.trim()
        if (viewId.isNotEmpty()) {
            return RpaSelector(viewId = viewId, enabledOnly = true)
        }
        val description = raw.description.trim()
        if (description.isNotEmpty()) {
            return RpaSelector(
                description = description,
                textMatch = RpaMatchMode.CONTAINS,
                enabledOnly = true,
            )
        }
        val text = raw.text.trim()
        if (text.isNotEmpty()) {
            return RpaSelector(
                text = text,
                textMatch = RpaMatchMode.CONTAINS,
                enabledOnly = true,
            )
        }
        val className = raw.className.trim()
        if (className.isNotEmpty()) {
            return RpaSelector(
                className = className,
                clickableOnly = raw.clickable,
                editableOnly = raw.editable,
                enabledOnly = true,
            )
        }
        return null
    }

    /** 拾取回填用的节点快照（选择器全 miss 时的坐标兜底）。 */
    fun toRpaSnapshot(node: RpaAnalyzeNode): RpaNodeSnapshot {
        val raw = node.raw
        return RpaNodeSnapshot(
            packageName = raw.packageName,
            className = raw.className,
            text = raw.text,
            description = raw.description,
            viewId = raw.viewId,
            hintText = raw.hint,
            bounds = RpaBounds(
                left = raw.bounds.left,
                top = raw.bounds.top,
                right = raw.bounds.right,
                bottom = raw.bounds.bottom,
            ),
            clickable = raw.clickable,
            editable = raw.editable,
            scrollable = raw.scrollable,
            focused = raw.focused,
        )
    }

    /** null 表示该节点没有可复用条件，调用方应提示改「复制节点 JSON」。 */
    fun toRpaA11ySpec(node: RpaAnalyzeNode): RpaA11ySpec? {
        val selector = toRpaSelector(node) ?: return null
        return RpaA11ySpec(
            selectors = listOf(selector),
            pick = RpaPick.FIRST,
            action = RpaNodeAction.CLICK,
            snapshot = toRpaSnapshot(node),
            note = "layout inspector: ${node.simpleClassName}",
        )
    }

    /** 整次采集的 a11y 视图快照（调试导出/后续动作拾取共用）。 */
    fun toA11yJson(): String {
        val windowArray = windows.joinToString(",", "[", "]") { window ->
            jsonObject(
                listOf(
                    "index" to window.index.toString(),
                    "packageName" to jsonString(window.packageName),
                    "label" to jsonString(window.label),
                    "root" to jsonObject(
                        listOf(
                            "className" to jsonString(window.root.className),
                            "bounds" to jsonString(window.root.bounds.toString()),
                        ),
                    ),
                ),
            )
        }
        return jsonObject(
            listOf(
                "mode" to jsonString("A11Y"),
                "activeWindowIndex" to activeWindowIndex.toString(),
                "nodeCount" to nodes.size.toString(),
                "windows" to windowArray,
            ),
        )
    }

    companion object {
        const val DEFAULT_MAX_NODES = 800
        private const val MAX_PATH_DEPTH = 64

        /**
         * 重叠候选的稳定顺序：面积小（更贴手的控件）→ 深度大（同框时选子节点）
         * → 全局索引（完全同框且同深度时先出现者优先）。排序是纯函数，单测
         * 直接断言数组顺序。
         */
        private val HIT_ORDER: Comparator<RpaAnalyzeNode> = compareBy(
            { it.bounds.area },
            { -it.depth },
            { it.index },
        )

        fun build(
            windows: List<RpaAnalyzeWindow>,
            activeWindowIndex: Int = 0,
            maxNodes: Int = DEFAULT_MAX_NODES,
        ): RpaNodeIndex {
            val limit = maxNodes.coerceAtLeast(0)
            val flat = ArrayList<RpaAnalyzeNode>(min(limit, 256))
            windows.forEachIndexed { windowIndex, window ->
                flatten(
                    raw = window.root,
                    windowIndex = windowIndex,
                    parentIndex = -1,
                    depth = 0,
                    out = flat,
                    maxNodes = limit,
                )
            }
            val normalized = windows.mapIndexed { index, window ->
                if (window.index == index) window else window.copy(index = index)
            }
            val active = activeWindowIndex.coerceIn(0, (windows.size - 1).coerceAtLeast(0))
            return RpaNodeIndex(normalized, flat, active)
        }

        private fun flatten(
            raw: RpaRawNode,
            windowIndex: Int,
            parentIndex: Int,
            depth: Int,
            out: MutableList<RpaAnalyzeNode>,
            maxNodes: Int,
        ) {
            if (out.size >= maxNodes) return
            val index = out.size
            out.add(RpaAnalyzeNode(index, parentIndex, depth, windowIndex, raw))
            if (depth >= MAX_PATH_DEPTH) return
            raw.children.forEach { child ->
                if (out.size >= maxNodes) return@forEach
                flatten(child, windowIndex, index, depth + 1, out, maxNodes)
            }
        }

        private fun jsonString(value: String): String = buildString {
            append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch < ' ') append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    else append(ch)
                }
            }
            append('"')
        }

        private fun jsonObject(pairs: List<Pair<String, String>>): String =
            pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                jsonString(key) + ":" + value
            }
    }
}

data class RpaPoint(val x: Float, val y: Float)

/**
 * `fitCenter` 映射（纯函数）：截图按比例完整放进画布，留黑边。分析器点击
 * 需要「画布 → 截图 → 屏幕」两级反算，任何一级都不做隐式取整。
 */
data class RpaImageFit(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    val left: Float,
    val top: Float,
    val scale: Float,
) {
    val valid: Boolean
        get() = imageWidth > 0 && imageHeight > 0 && viewWidth > 0 &&
            viewHeight > 0 && scale > 0f

    val right: Float get() = left + imageWidth * scale
    val bottom: Float get() = top + imageHeight * scale

    fun imageToView(x: Float, y: Float): RpaPoint =
        RpaPoint(left + x * scale, top + y * scale)

    fun viewToImage(x: Float, y: Float): RpaPoint =
        RpaPoint((x - left) / scale, (y - top) / scale)

    companion object {
        val Empty = RpaImageFit(0, 0, 0, 0, 0f, 0f, 1f)

        fun fit(
            imageWidth: Int,
            imageHeight: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): RpaImageFit {
            if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return Empty
            }
            val scale = min(
                viewWidth.toFloat() / imageWidth,
                viewHeight.toFloat() / imageHeight,
            )
            val drawnWidth = imageWidth * scale
            val drawnHeight = imageHeight * scale
            return RpaImageFit(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                left = (viewWidth - drawnWidth) / 2f,
                top = (viewHeight - drawnHeight) / 2f,
                scale = scale,
            )
        }
    }
}
