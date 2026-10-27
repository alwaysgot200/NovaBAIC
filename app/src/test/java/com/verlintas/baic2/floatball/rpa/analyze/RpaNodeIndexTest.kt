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

import com.verlintas.rpa.model.RpaBounds
import com.verlintas.rpa.model.RpaPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 布局分析纯逻辑：扁平索引、命中优先级、路径与定位 JSON。 */
class RpaNodeIndexTest {

    private fun node(
        text: String = "",
        description: String = "",
        viewId: String = "",
        className: String = "android.widget.TextView",
        bounds: RpaBox = RpaBox.EMPTY,
        clickable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        children: List<RpaRawNode> = emptyList(),
    ) = RpaRawNode(
        packageName = "com.tencent.mm",
        className = className,
        text = text,
        description = description,
        viewId = viewId,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
        enabled = enabled,
        visibleToUser = visible,
        children = children,
    )

    private fun window(root: RpaRawNode, index: Int = 0) = RpaAnalyzeWindow(
        index = index,
        packageName = "com.tencent.mm",
        label = "com.tencent.mm · 应用",
        root = root,
    )

    @Test
    fun `build flattens hierarchy with parent depth and window index`() {
        val root = node(
            bounds = RpaBox(0, 0, 1080, 1920),
            children = listOf(
                node(
                    text = "微信",
                    bounds = RpaBox(0, 0, 1080, 200),
                    children = listOf(
                        node(text = "搜索", bounds = RpaBox(800, 40, 1040, 160), clickable = true),
                    ),
                ),
                node(text = "通讯录", bounds = RpaBox(0, 1800, 360, 1920), clickable = true),
            ),
        )
        val index = RpaNodeIndex.build(listOf(window(root)))

        assertEquals(4, index.nodes.size)
        assertEquals(0, index.nodes[0].index)
        assertEquals(-1, index.nodes[0].parentIndex)
        assertEquals(1, index.nodes[1].depth)
        assertEquals(0, index.nodes[1].parentIndex)
        assertEquals(2, index.nodes[2].depth)
        assertEquals(1, index.nodes[2].parentIndex)
        assertEquals(0, index.nodes[3].parentIndex)
        assertEquals(1, index.nodes[3].depth)
        assertEquals(0, index.nodes[3].windowIndex)
    }

    @Test
    fun `build caps nodes and keeps indexes contiguous`() {
        val root = node(
            bounds = RpaBox(0, 0, 1080, 1920),
            children = (0 until 10).map {
                node(text = "item$it", bounds = RpaBox(0, it * 100, 1080, it * 100 + 100))
            },
        )
        val index = RpaNodeIndex.build(listOf(window(root)), maxNodes = 4)

        assertEquals(4, index.nodes.size)
        assertEquals(listOf(0, 1, 2, 3), index.nodes.map { it.index })
    }

    @Test
    fun `hit test prefers smallest containing node`() {
        val root = node(
            bounds = RpaBox(0, 0, 1080, 1920),
            children = listOf(
                node(text = "列表", bounds = RpaBox(0, 0, 1080, 600), clickable = true),
                node(text = "行", bounds = RpaBox(0, 100, 1080, 200), clickable = true),
                node(text = "按钮", bounds = RpaBox(900, 120, 1040, 180), clickable = true),
            ),
        )
        val index = RpaNodeIndex.build(listOf(window(root)))

        val hit = index.hitTest(950, 150, windowIndex = 0)
        assertEquals("按钮", hit?.raw?.text)
        assertNull(index.hitTest(2000, 2000, windowIndex = 0))
    }

    @Test
    fun `hit test skips hidden invisible and disabled nodes`() {
        val root = node(
            bounds = RpaBox(0, 0, 1080, 1920),
            children = listOf(
                node(text = "外层", bounds = RpaBox(0, 0, 1080, 600), clickable = true),
                node(
                    text = "禁用",
                    bounds = RpaBox(10, 10, 200, 200),
                    clickable = true,
                    enabled = false,
                ),
                node(
                    text = "不可见",
                    bounds = RpaBox(10, 10, 190, 190),
                    visible = false,
                ),
                node(text = "隐藏", bounds = RpaBox(10, 10, 180, 180), clickable = true),
            ),
        )
        val index = RpaNodeIndex.build(listOf(window(root)))
        val hidden = index.nodes.first { it.raw.text == "隐藏" }.index

        val hit = index.hitTest(100, 100, windowIndex = 0, exclude = setOf(hidden))
        assertEquals("外层", hit?.raw?.text)
    }

    @Test
    fun `box nodes exclude empty invisible and hidden`() {
        val root = node(
            bounds = RpaBox(0, 0, 1080, 1920),
            children = listOf(
                node(text = "空框", bounds = RpaBox(0, 0, 0, 0)),
                node(text = "不可见", bounds = RpaBox(0, 0, 100, 100), visible = false),
                node(text = "正常", bounds = RpaBox(0, 0, 100, 100)),
            ),
        )
        val index = RpaNodeIndex.build(listOf(window(root)))
        val nodes = index.boxNodes(windowIndex = 0)
        assertEquals(listOf("", "正常"), nodes.map { it.raw.text })
    }

    @Test
    fun `same bounds requires same window`() {
        val shared = RpaBox(0, 0, 100, 100)
        val w0 = window(node(bounds = shared), index = 0)
        val w1 = window(node(bounds = shared), index = 1)
        val index = RpaNodeIndex.build(listOf(w0, w1))
        val a = index.nodes.first { it.windowIndex == 0 }
        val b = index.nodes.first { it.windowIndex == 1 }

        assertFalse(index.sameBounds(a, b))
        assertEquals(shared, a.bounds)
        assertEquals(shared, b.bounds)
    }

    @Test
    fun `path walks from root to node`() {
        val root = node(
            bounds = RpaBox(0, 0, 1080, 1920),
            children = listOf(
                node(
                    text = "会话",
                    bounds = RpaBox(0, 0, 1080, 600),
                    children = listOf(
                        node(text = "发送", bounds = RpaBox(900, 500, 1040, 580)),
                    ),
                ),
            ),
        )
        val index = RpaNodeIndex.build(listOf(window(root)))
        val target = index.nodes.first { it.raw.text == "发送" }

        // 根节点没有文本，label 回退为类名简写。
        assertEquals(listOf("TextView", "会话", "发送"), index.path(target).map { it.label })
        assertEquals("TextView > 会话 > 发送", index.pathText(target))
    }

    @Test
    fun `selector json escapes quotes and newlines`() {
        val root = node(
            text = "He said \"hi\"\nnext",
            viewId = "com.tencent.mm:id/send",
            bounds = RpaBox(10, 20, 30, 40),
        )
        val index = RpaNodeIndex.build(listOf(window(root)))
        val selector = index.toSelector(index.nodes[0])

        assertTrue(selector.contains("\\\"hi\\\""))
        assertTrue(selector.contains("\\n"))
        assertTrue(selector.contains("\"viewId\":\"com.tencent.mm:id/send\""))
        assertTrue(selector.contains("\"bounds\":\"[10,20][30,40]\""))
    }

    @Test
    fun `bounds only node has no semantic selector`() {
        val root = node(bounds = RpaBox(10, 20, 30, 40))
        val index = RpaNodeIndex.build(listOf(window(root)))
        val target = index.nodes[0]

        assertFalse(index.hasSelector(target))
        assertFalse(target.hasSemanticLabel)
        assertTrue(index.toSelector(target).contains("\"bounds\""))
    }

    @Test
    fun `a11y json carries active window and node count`() {
        val root = node(
            text = "首页",
            bounds = RpaBox(0, 0, 1080, 100),
        )
        val index = RpaNodeIndex.build(
            windows = listOf(window(root)),
            activeWindowIndex = 0,
        )
        val json = index.toA11yJson()

        assertTrue(json.contains("\"mode\":\"A11Y\""))
        assertTrue(json.contains("\"activeWindowIndex\":0"))
        assertTrue(json.contains("\"nodeCount\":1"))
        assertTrue(json.contains("\"packageName\":\"com.tencent.mm\""))
    }

    @Test
    fun `node json includes flags bounds and hierarchy metadata`() {
        val root = node(
            text = "发送",
            viewId = "com.tencent.mm:id/send",
            bounds = RpaBox(1, 2, 3, 4),
            clickable = true,
            editable = true,
        )
        val index = RpaNodeIndex.build(listOf(window(root)))
        val json = index.toNodeJson(index.nodes[0])

        assertTrue(json.contains("\"clickable\":true"))
        assertTrue(json.contains("\"editable\":true"))
        assertTrue(json.contains("\"left\":1"))
        assertTrue(json.contains("\"right\":3"))
        assertTrue(json.contains("\"depth\":0"))
    }

    @Test
    fun `active window index is clamped to available windows`() {
        val index = RpaNodeIndex.build(
            windows = listOf(window(node(bounds = RpaBox(0, 0, 10, 10)))),
            activeWindowIndex = 7,
        )
        assertEquals(0, index.activeWindowIndex)
        assertNull(index.window(2))
    }

    @Test
    fun `pick selector prefers view id and carries snapshot`() {
        val root = node(
            text = "发送",
            viewId = "com.tencent.mm:id/send",
            bounds = RpaBox(1, 2, 3, 4),
            clickable = true,
        )
        val index = RpaNodeIndex.build(listOf(window(root)))
        val spec = index.toRpaA11ySpec(index.nodes[0])

        assertEquals(1, spec?.selectors?.size)
        assertEquals("com.tencent.mm:id/send", spec?.selectors?.get(0)?.viewId)
        assertNull(spec?.selectors?.get(0)?.text)
        assertEquals(RpaPick.FIRST, spec?.pick)
        assertEquals(RpaBounds(1, 2, 3, 4), spec?.snapshot?.bounds)
        assertEquals("发送", spec?.snapshot?.text)
        assertEquals("com.tencent.mm", spec?.snapshot?.packageName)
    }

    @Test
    fun `pick selector falls back to description then text`() {
        val described = node(
            text = "发送",
            description = "发送消息",
            bounds = RpaBox(0, 0, 10, 10),
        )
        val index = RpaNodeIndex.build(listOf(window(described)))
        val byDescription = index.toRpaSelector(index.nodes[0])
        assertEquals("发送消息", byDescription?.description)
        assertNull(byDescription?.text)

        val textOnly = RpaNodeIndex.build(
            listOf(window(node(text = "搜索", bounds = RpaBox(0, 0, 10, 10)))),
        )
        val byText = textOnly.toRpaSelector(textOnly.nodes[0])
        assertEquals("搜索", byText?.text)
        assertTrue(byText?.enabledOnly == true)
    }

    @Test
    fun `pick selector uses class flags and rejects bounds only nodes`() {
        val classOnly = node(
            className = "android.widget.Button",
            bounds = RpaBox(0, 0, 10, 10),
            clickable = true,
        )
        val index = RpaNodeIndex.build(listOf(window(classOnly)))
        val byClass = index.toRpaSelector(index.nodes[0])
        assertEquals("android.widget.Button", byClass?.className)
        assertTrue(byClass?.clickableOnly == true)

        val boundsOnly = RpaNodeIndex.build(
            listOf(window(node(className = "", bounds = RpaBox(0, 0, 10, 10)))),
        )
        val target = boundsOnly.nodes[0]
        assertNull(boundsOnly.toRpaSelector(target))
        assertNull(boundsOnly.toRpaA11ySpec(target))
    }
}
