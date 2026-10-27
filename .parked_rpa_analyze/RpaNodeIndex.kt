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

package com.verlintas.baic2.floatball.rpa.analyze

import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaBounds
import com.verlintas.rpa.model.RpaMatchMode
import com.verlintas.rpa.model.RpaNodeAction
import com.verlintas.rpa.model.RpaNodeSnapshot
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaSelector
import kotlinx.serialization.json.Json

/**
 * 与 Android 无关的原始节点镜像：布局分析采集层把 `AssistsCore.NodeTree`
 * 转成这棵树，之后的扁平化 / 命中 / 选择器生成 / JSON 全部可在 JVM 单测。
 */
internal data class RpaRawNode(
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
    val enabled: Boolean = true,
    val visibleToUser: Boolean = true,
    val children: List<RpaRawNode> = emptyList(),
)

/** 扁平化后的节点；`index` 是当前窗口内的稳定序号，`parentIndex` 为 -1 表示根。 */
internal data class RpaAnalyzeNode(
    val index: Int,
    val parentIndex: Int,
    val depth: Int,
    val windowIndex: Int,
    val packageName: String,
    val className: String,
    val text: String,
    val description: String,
    val viewId: String,
    val hintText: String,
    val bounds: RpaBounds,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
) {
    val simpleClassName: String
        get() = className.substringAfterLast('.').ifBlank { className }

    val label: String
        get() = text.trim().ifBlank {
            description.trim().ifBlank {
                hintText.trim().ifBlank { simpleClassName }
            }
        }

    /** 标签是否值得画在框上；纯类名节点太多，只在层级表里展示。 */
    val hasSemanticLabel: Boolean
        get() = text.isNotBlank() || description.isNotBlank() || hintText.isNotBlank()
}

internal data class RpaAnalyzeWindow(
    val index: Int,
    val packageName: String,
    val label: String,
    val root: RpaRawNode,
)

/** 扁平化、命中、生成选择器与 JSON 的纯逻辑面。 */
internal object RpaNodeIndex {

    const val DEFAULT_MAX_NODES = 800

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun build(
        windowIndex: Int,
        root: RpaRawNode,
        maxNodes: Int = DEFAULT_MAX_NODES,
    ): List<RpaAnalyzeNode> {
        if (maxNodes <= 0) return emptyList()
        val out = ArrayList<RpaAnalyzeNode>(minOf(maxNodes, 256))
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, depth = 0, parentIndex = -1))
        while (stack.isNotEmpty() && out.size < maxNodes) {
            val frame = stack.removeLast()
            val index = out.size
            val node = frame.node
            out += RpaAnalyzeNode(
                index = index,
                parentIndex = frame.parentIndex,
                depth = frame.depth,
                windowIndex = windowIndex,
                packageName = node.packageName,
                className = node.className,
                text = node.text,
                description = node.description,
                viewId = node.viewId,
                hintText = node.hintText,
                bounds = node.bounds,
                clickable = node.clickable,
                editable = node.editable,
                scrollable = node.scrollable,
                focused = node.focused,
                enabled = node.enabled,
                visibleToUser = node.visibleToUser,
            )
            // 逆序压栈，保证弹出顺序仍是父 → 子、且兄弟保持原始顺序。
            for (i in frame.node.children.indices.reversed()) {
                stack.addLast(
                    Frame(frame.node.children[i], frame.depth + 1, index),
                )
            }
        }
        return out
    }

    /** 画布可绘制 / 可命中的节点：可见、有面积、未隐藏。 */
    fun boxNodes(
        nodes: List<RpaAnalyzeNode>,
        hidden: Set<Int>,
    ): List<RpaAnalyzeNode> = nodes.filter { node ->
        node.index !in hidden &&
            node.visibleToUser &&
            !node.bounds.isEmpty
    }

    /** 命中包含该点的最小面积节点；面积相同取更深（更具体）的节点。 */
    fun hitTest(
        nodes: List<RpaAnalyzeNode>,
        hidden: Set<Int>,
        x: Int,
        y: Int,
    ): RpaAnalyzeNode? = boxNodes(nodes, hidden)
        .asSequence()
        .filter { node ->
            val b = node.bounds
            x in b.left..b.right && y in b.top..b.bottom
        }
        .minWithOrNull(
            compareBy<RpaAnalyzeNode>(
                { it.bounds.width.toLong() * it.bounds.height },
                { -it.depth },
            ),
        )

    fun sameBounds(a: RpaAnalyzeNode, b: RpaAnalyzeNode): Boolean =
        a.bounds.left == b.bounds.left &&
            a.bounds.top == b.bounds.top &&
            a.bounds.right == b.bounds.right &&
            a.bounds.bottom == b.bounds.bottom

    /**
     * 生成 a11y 选择器：viewId（最稳）→ description → text → className + 属性过滤。
     * 四类都没有时返回 null：只能复制节点 JSON，不能伪造一个「随便点」的选择器。
     */
    fun toSelector(node: RpaAnalyzeNode): RpaSelector? {
        val viewId = node.viewId.trim()
        if (viewId.isNotEmpty()) {
            return RpaSelector(viewId = viewId, enabledOnly = true)
        }
        val description = node.description.trim()
        if (description.isNotEmpty()) {
            return RpaSelector(
                description = description,
                textMatch = RpaMatchMode.CONTAINS,
                enabledOnly = true,
            )
        }
        val text = node.text.trim()
        if (text.isNotEmpty()) {
            return RpaSelector(
                text = text,
                textMatch = RpaMatchMode.CONTAINS,
                enabledOnly = true,
            )
        }
        val className = node.className.trim()
        if (className.isNotEmpty()) {
            return RpaSelector(
                className = className,
                clickableOnly = node.clickable,
                editableOnly = node.editable,
                enabledOnly = true,
            )
        }
        return null
    }

    fun toA11ySpec(node: RpaAnalyzeNode): RpaA11ySpec? {
        val selector = toSelector(node) ?: return null
        return RpaA11ySpec(
            selectors = listOf(selector),
            pick = RpaPick.FIRST,
            action = RpaNodeAction.CLICK,
            snapshot = toSnapshot(node),
            note = "layout inspector: ${node.simpleClassName}",
        )
    }

    fun toSnapshot(node: RpaAnalyzeNode): RpaNodeSnapshot = RpaNodeSnapshot(
        packageName = node.packageName,
        className = node.className,
        text = node.text,
        description = node.description,
        viewId = node.viewId,
        hintText = node.hintText,
        bounds = node.bounds,
        clickable = node.clickable,
        editable = node.editable,
        scrollable = node.scrollable,
        focused = node.focused,
    )

    fun toA11yJson(node: RpaAnalyzeNode): String? {
        val spec = toA11ySpec(node) ?: return null
        return json.encodeToString(RpaA11ySpec.serializer(), spec)
    }

    fun toNodeJson(node: RpaAnalyzeNode): String =
        json.encodeToString(RpaNodeSnapshot.serializer(), toSnapshot(node))

    /** 从根到当前节点的类名路径，用于属性卡展示。 */
    fun path(nodes: List<RpaAnalyzeNode>, node: RpaAnalyzeNode): String {
        val parts = ArrayDeque<String>()
        var cursor: RpaAnalyzeNode? = node
        var guard = 0
        while (cursor != null && guard++ < 64) {
            parts.addFirst(cursor.simpleClassName.ifBlank { "?" })
            cursor = nodes.getOrNull(cursor.parentIndex)
        }
        return parts.joinToString(" > ")
    }

    private data class Frame(
        val node: RpaRawNode,
        val depth: Int,
        val parentIndex: Int,
    )
}

/**
 * 冻结帧按 `fitCenter` 铺到全屏画布时的映射；把「画布点击」反算成帧像素，
 * 也把「节点屏幕坐标」换算成画布坐标。纯 Kotlin，可单测。
 */
internal data class RpaImageFit(
    val viewWidth: Int,
    val viewHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    val valid: Boolean
        get() = scale > 0f && imageWidth > 0 && imageHeight > 0

    fun imageToView(x: Float, y: Float): Pair<Float, Float> =
        (offsetX + x * scale) to (offsetY + y * scale)

    fun viewToImage(x: Float, y: Float): Pair<Float, Float>? {
        if (!valid) return null
        val imageX = (x - offsetX) / scale
        val imageY = (y - offsetY) / scale
        if (imageX < 0f || imageY < 0f ||
            imageX > imageWidth.toFloat() || imageY > imageHeight.toFloat()
        ) {
            return null
        }
        return imageX to imageY
    }

    companion object {
        fun fit(
            viewWidth: Int,
            viewHeight: Int,
            imageWidth: Int,
            imageHeight: Int,
        ): RpaImageFit {
            if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
                return RpaImageFit(
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    scale = 0f,
                    offsetX = 0f,
                    offsetY = 0f,
                )
            }
            val scale = minOf(
                viewWidth.toFloat() / imageWidth,
                viewHeight.toFloat() / imageHeight,
            )
            return RpaImageFit(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                scale = scale,
                offsetX = (viewWidth - imageWidth * scale) / 2f,
                offsetY = (viewHeight - imageHeight * scale) / 2f,
            )
        }
    }
}
