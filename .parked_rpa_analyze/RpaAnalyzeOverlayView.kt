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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.verlintas.baic2.R
import com.verlintas.rpa.model.RpaSelector
import kotlin.math.max
import kotlin.math.min

/**
 * AutoJs6 风格布局分析覆盖层（经典 View，不依赖 Compose / Dialog）。
 *
 * 结构：冻结帧画布（绿色控件框 / 选中高亮）之上叠白色竖排菜单、属性卡、
 * 层级面板与窗口选择器；顶部工具条只做刷新 / 层级 / 窗口 / 恢复隐藏 / 退出。
 * 所有面板都在同一个 `TYPE_ACCESSIBILITY_OVERLAY` 窗口内，不启动 Activity，
 * 因此分析的始终是底下的目标 App。
 */
internal class RpaAnalyzeOverlayView(
    context: Context,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    internal interface Callbacks {
        fun onExit()
        fun onRefresh()
    }

    private enum class Filter {
        ALL,
        CLICKABLE,
        EDITABLE,
        TEXT,
    }

    private data class DrawBox(
        val index: Int,
        val rect: RectF,
        val label: String?,
    )

    private val density: Float = resources.displayMetrics.density
    private val screenWidth: Int = resources.displayMetrics.widthPixels
    private val screenHeight: Int = resources.displayMetrics.heightPixels
    private val statusBarHeight: Int = dimension("status_bar_height", dp(24))
    private val navigationBarHeight: Int = dimension("navigation_bar_height", dp(0))

    private var snapshot: RpaAnalyzeSnapshot? = null
    private var currentWindowIndex = 0
    private var selected: RpaAnalyzeNode? = null
    private var currentFilter = Filter.ALL
    private var showLabels = true
    private val nodesByWindow = mutableMapOf<Int, List<RpaAnalyzeNode>>()
    private val hiddenByWindow = mutableMapOf<Int, MutableSet<Int>>()
    private var selectedRow: TextView? = null

    private val canvasView = AnalyzeCanvas(context)
    private val emptyView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = roundedBackground(0xE6202124.toInt(), 12)
        visibility = GONE
    }
    private val backgroundNotice = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = roundedBackground(0xF2B26A00.toInt(), 10)
        elevation = dp(6).toFloat()
        visibility = GONE
    }

    private val subtitleView = TextView(context).apply {
        setTextColor(0xB3FFFFFF.toInt())
        textSize = 11f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val refreshButton = topBarButton(R.string.float_rpa_analyze_refresh) {
        callbacks.onRefresh()
    }
    private val hierarchyButton = topBarButton(R.string.float_rpa_analyze_hierarchy) {
        toggleHierarchy()
    }
    private val windowsButton = topBarButton(R.string.float_rpa_analyze_windows) {
        toggleWindowPanel()
    }
    private val restoreButton = topBarButton(R.string.float_rpa_analyze_restore_hidden) {
        restoreHiddenNodes()
    }.apply { visibility = GONE }
    private val exitButton = topBarButton(R.string.float_rpa_analyze_exit) {
        callbacks.onExit()
    }.apply { setTextColor(0xFFFF8A80.toInt()) }

    private val topBar: LinearLayout = buildTopBar()

    private val menuCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_PANEL, 12, COLOR_PANEL_STROKE)
        elevation = dp(18).toFloat()
        visibility = GONE
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private val detailCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_PANEL, 16, COLOR_PANEL_STROKE)
        elevation = dp(14).toFloat()
        visibility = GONE
    }
    private val detailContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), dp(8))
    }
    private val detailScroll = ScrollView(context).apply {
        isFillViewport = true
        addView(detailContent, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    private val detailActions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), dp(10))
    }

    private val hierarchyCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_PANEL, 14, COLOR_PANEL_STROKE)
        elevation = dp(10).toFloat()
        visibility = GONE
    }
    private val filterRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), dp(6))
    }
    private val hierarchyList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(2), 0, dp(6))
    }
    private val hierarchyScroll = ScrollView(context).apply {
        isFillViewport = true
        addView(hierarchyList, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    private val filterChips = mutableMapOf<Filter, TextView>()
    private val labelToggle = CheckBox(context).apply {
        text = context.getString(R.string.float_rpa_analyze_show_labels)
        textSize = 12f
        setTextColor(COLOR_TEXT)
        isChecked = true
        setPadding(dp(10), 0, dp(10), dp(2))
    }

    private val windowCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_PANEL, 14, COLOR_PANEL_STROKE)
        elevation = dp(16).toFloat()
        visibility = GONE
    }
    private val windowList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(6))
    }

    init {
        setBackgroundColor(COLOR_SCRIM)
        isClickable = true

        addView(
            canvasView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        canvasView.onTap = { x, y -> handleCanvasTap(x, y) }
        canvasView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                rebuildCanvas()
            }
        }

        addView(
            emptyView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(32)
                rightMargin = dp(32)
            },
        )
        addView(
            backgroundNotice,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = navigationBarHeight + dp(10)
            },
        )

        setupHierarchyCard()
        val hierarchyHeight = min((screenHeight * 0.70f).toInt(), dp(560))
            .coerceAtLeast(dp(280))
        addView(
            hierarchyCard,
            LayoutParams(
                min(dp(340), (screenWidth - dp(24)).coerceAtLeast(dp(220))),
                hierarchyHeight,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(12)
                topMargin = statusBarHeight + dp(64)
            },
        )

        setupDetailCard()
        val detailHeight = min((screenHeight * 0.58f).toInt(), dp(460))
            .coerceAtLeast(dp(260))
        addView(
            detailCard,
            LayoutParams(LayoutParams.MATCH_PARENT, detailHeight).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = navigationBarHeight + dp(10)
            },
        )

        setupWindowCard()
        addView(
            windowCard,
            LayoutParams(
                min(dp(280), (screenWidth - dp(24)).coerceAtLeast(dp(220))),
                LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(12)
                topMargin = statusBarHeight + dp(64)
            },
        )

        addView(
            menuCard,
            LayoutParams(dp(230), LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )

        addView(
            topBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                leftMargin = dp(8)
                rightMargin = dp(8)
                topMargin = statusBarHeight + dp(4)
            },
        )

        labelToggle.setOnCheckedChangeListener { _, checked ->
            showLabels = checked
            rebuildCanvas()
        }
        updateRestoreButton()
    }

    // ------------------------------------------------------------ public API

    fun showLoading() {
        snapshot = null
        selected = null
        currentWindowIndex = 0
        nodesByWindow.clear()
        hiddenByWindow.clear()
        hideAllPanels()
        canvasView.clear()
        subtitleView.text = getString(R.string.float_rpa_analyze_loading)
        emptyView.text = getString(R.string.float_rpa_analyze_loading)
        emptyView.visibility = VISIBLE
        backgroundNotice.visibility = GONE
        refreshButton.isEnabled = false
        refreshButton.alpha = 0.5f
        updateRestoreButton()
    }

    fun renderSnapshot(value: RpaAnalyzeSnapshot) {
        snapshot = value
        selected = null
        nodesByWindow.clear()
        hiddenByWindow.clear()
        currentWindowIndex = value.windows.getOrNull(value.activeWindowIndex)?.index
            ?: value.windows.firstOrNull()?.index
            ?: 0
        hideAllPanels()
        refreshButton.isEnabled = true
        refreshButton.alpha = 1f
        updateRestoreButton()
        updateSubtitle()
        updateBackgroundNotice()

        if (value.windows.isEmpty()) {
            canvasView.clear()
            emptyView.text = getString(R.string.float_rpa_analyze_no_nodes)
            emptyView.visibility = VISIBLE
        } else {
            emptyView.visibility = GONE
            rebuildCanvas()
        }
    }

    /** 采集失败时保留工具条：用户仍可刷新 / 退出。 */
    fun renderError(code: String?) {
        snapshot = null
        selected = null
        nodesByWindow.clear()
        hiddenByWindow.clear()
        hideAllPanels()
        canvasView.clear()
        updateRestoreButton()
        backgroundNotice.visibility = GONE
        refreshButton.isEnabled = true
        refreshButton.alpha = 1f
        emptyView.text = when {
            code?.startsWith(RpaAnalyzeCapture.ERROR_A11Y_UNAVAILABLE) == true ->
                getString(R.string.float_rpa_analyze_need_a11y)

            else -> getString(
                R.string.float_rpa_analyze_capture_failed,
                code ?: RpaAnalyzeCapture.ERROR_NODE_TREE,
            )
        }
        emptyView.visibility = VISIBLE
        subtitleView.text = ""
    }

    fun release() {
        hideAllPanels()
        canvasView.clear()
        emptyView.visibility = GONE
        backgroundNotice.visibility = GONE
        snapshot = null
        selected = null
        nodesByWindow.clear()
        hiddenByWindow.clear()
        detailContent.removeAllViews()
        hierarchyList.removeAllViews()
        windowList.removeAllViews()
    }

    // -------------------------------------------------------------- top bar

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_TOP_BAR, 14)
            elevation = dp(20).toFloat()
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        val firstRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.float_rpa_analyze_title)
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        firstRow.addView(title)
        firstRow.addView(
            subtitleView,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            },
        )
        firstRow.addView(exitButton)

        val secondScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val secondRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(refreshButton, hierarchyButton, windowsButton, restoreButton).forEach { button ->
            secondRow.addView(
                button,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) },
            )
        }
        secondScroll.addView(secondRow)

        bar.addView(firstRow)
        bar.addView(
            secondScroll,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) },
        )
        return bar
    }

    private fun topBarButton(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = getString(labelRes)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(0x33FFFFFF, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------- hierarchy

    private fun setupHierarchyCard() {
        hierarchyCard.addView(
            panelHeader(getString(R.string.float_rpa_analyze_hierarchy_title)) {
                hideHierarchy()
            },
        )
        hierarchyCard.addView(
            filterRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        hierarchyCard.addView(
            hierarchyScroll,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        hierarchyCard.addView(
            labelToggle,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        Filter.values().forEach { filter ->
            val chip = TextView(context).apply {
                text = getString(filterLabel(filter))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                isClickable = true
                setOnClickListener {
                    currentFilter = filter
                    updateFilterChipStyles()
                    rebuildHierarchyList()
                }
            }
            filterChips[filter] = chip
            filterRow.addView(
                chip,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) },
            )
        }
        updateFilterChipStyles()
    }

    private fun filterLabel(filter: Filter): Int = when (filter) {
        Filter.ALL -> R.string.float_rpa_analyze_filter_all
        Filter.CLICKABLE -> R.string.float_rpa_analyze_filter_clickable
        Filter.EDITABLE -> R.string.float_rpa_analyze_filter_editable
        Filter.TEXT -> R.string.float_rpa_analyze_filter_text
    }

    private fun updateFilterChipStyles() {
        filterChips.forEach { (filter, chip) ->
            val active = filter == currentFilter
            chip.setTextColor(if (active) Color.WHITE else COLOR_MUTED)
            chip.background = roundedBackground(
                if (active) 0xFF2E7D32.toInt() else 0xFFEDEDED.toInt(),
                999,
            )
        }
    }

    private fun toggleHierarchy() {
        if (hierarchyCard.visibility == VISIBLE) {
            hideHierarchy()
        } else {
            showHierarchy()
        }
    }

    private fun showHierarchy() {
        menuCard.visibility = GONE
        detailCard.visibility = GONE
        windowCard.visibility = GONE
        rebuildHierarchyList()
        updateFilterChipStyles()
        positionBelowTopBar(hierarchyCard)
        hierarchyCard.visibility = VISIBLE
        hierarchyScroll.post {
            selectedRow?.let { row ->
                hierarchyScroll.smoothScrollTo(0, max(0, row.top - dp(8)))
            }
        }
    }

    private fun hideHierarchy() {
        hierarchyCard.visibility = GONE
    }

    private fun rebuildHierarchyList() {
        hierarchyList.removeAllViews()
        selectedRow = null
        val nodes = currentNodes()
        if (nodes.isEmpty()) {
            hierarchyList.addView(hintLine(getString(R.string.float_rpa_analyze_no_nodes)))
            return
        }
        val filtered = nodes.filter { node ->
            when (currentFilter) {
                Filter.ALL -> true
                Filter.CLICKABLE -> node.clickable
                Filter.EDITABLE -> node.editable
                Filter.TEXT -> node.hasSemanticLabel
            }
        }
        if (filtered.isEmpty()) {
            hierarchyList.addView(hintLine(getString(R.string.float_rpa_analyze_hierarchy_empty)))
            return
        }
        filtered.take(MAX_HIERARCHY_ROWS).forEach { node ->
            val text = buildString {
                append('#').append(node.index).append(' ').append(node.simpleClassName)
                if (node.hasSemanticLabel) {
                    append(" · ").append(node.label.take(MAX_ROW_LABEL_CHARS))
                }
            }
            val row = TextView(context).apply {
                this.text = text
                textSize = 12f
                setTextColor(COLOR_TEXT)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10) + node.depth * dp(10), dp(6), dp(8), dp(6))
                if (selected?.index == node.index) {
                    setBackgroundColor(0x3300C853)
                    selectedRow = this
                }
                setOnClickListener {
                    selectNode(node)
                    showDetail(node)
                    rebuildHierarchyList()
                }
            }
            hierarchyList.addView(
                row,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    // -------------------------------------------------------------- detail

    private fun setupDetailCard() {
        detailCard.addView(
            panelHeader(getString(R.string.float_rpa_analyze_detail_title)) {
                detailCard.visibility = GONE
            },
        )
        detailCard.addView(
            detailScroll,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        detailCard.addView(
            detailActions,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        detailActions.addView(
            panelActionButton(R.string.float_rpa_analyze_copy_a11y) {
                selected?.let { node ->
                    val json = RpaNodeIndex.toA11yJson(node)
                    if (json == null) {
                        toast(R.string.float_rpa_analyze_no_selector)
                    } else {
                        copyToClipboard(json)
                    }
                }
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            },
        )
        detailActions.addView(
            panelActionButton(R.string.float_rpa_analyze_copy_node) {
                selected?.let { copyToClipboard(RpaNodeIndex.toNodeJson(it)) }
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            },
        )
        detailActions.addView(
            panelActionButton(R.string.float_rpa_analyze_close) {
                detailCard.visibility = GONE
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private fun showDetail(node: RpaAnalyzeNode) {
        selected = node
        rebuildCanvas()
        menuCard.visibility = GONE
        windowCard.visibility = GONE
        detailContent.removeAllViews()
        addDetailField(R.string.float_rpa_analyze_field_package, node.packageName)
        addDetailField(R.string.float_rpa_analyze_field_class, node.className)
        addDetailField(R.string.float_rpa_analyze_field_text, node.text)
        addDetailField(R.string.float_rpa_analyze_field_desc, node.description)
        addDetailField(R.string.float_rpa_analyze_field_view_id, node.viewId)
        addDetailField(R.string.float_rpa_analyze_field_hint, node.hintText)
        addDetailField(
            R.string.float_rpa_analyze_field_bounds,
            node.bounds.let { b ->
                "[${b.left},${b.top}][${b.right},${b.bottom}] ${b.width}×${b.height}"
            },
        )
        addDetailField(R.string.float_rpa_analyze_field_flags, nodeFlags(node))
        addDetailField(
            R.string.float_rpa_analyze_field_path,
            RpaNodeIndex.path(currentNodes(), node),
        )
        val selector = RpaNodeIndex.toSelector(node)
        addDetailField(
            R.string.float_rpa_analyze_field_selector,
            selector?.let { describeSelector(it) }
                ?: getString(R.string.float_rpa_analyze_no_selector_value),
        )
        detailCard.visibility = VISIBLE
    }

    private fun addDetailField(labelRes: Int, value: String) {
        val line = TextView(context).apply {
            text = getString(labelRes, value.ifBlank { "—" })
            textSize = 12f
            setTextColor(COLOR_TEXT)
            setTextIsSelectable(true)
            setPadding(0, dp(3), 0, dp(3))
        }
        detailContent.addView(
            line,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private fun describeSelector(selector: RpaSelector): String = buildList {
        selector.viewId?.takeIf { it.isNotBlank() }?.let { add("viewId=$it") }
        selector.description?.takeIf { it.isNotBlank() }?.let { add("description=$it") }
        selector.text?.takeIf { it.isNotBlank() }?.let {
            add("text(${selector.textMatch.name.lowercase()})=$it")
        }
        selector.className?.takeIf { it.isNotBlank() }?.let { add("class=$it") }
        if (selector.clickableOnly) add("clickable=true")
        if (selector.editableOnly) add("editable=true")
        if (selector.enabledOnly) add("enabled=true")
    }.joinToString(" & ")

    private fun nodeFlags(node: RpaAnalyzeNode): String = buildList {
        if (node.clickable) add(getString(R.string.float_rpa_inspector_tag_clickable))
        if (node.editable) add(getString(R.string.float_rpa_inspector_tag_editable))
        if (node.scrollable) add(getString(R.string.float_rpa_analyze_tag_scrollable))
        if (node.focused) add(getString(R.string.float_rpa_analyze_tag_focused))
        if (node.visibleToUser) add(getString(R.string.float_rpa_analyze_tag_visible))
        if (!node.enabled) add(getString(R.string.float_rpa_analyze_tag_disabled))
    }.joinToString(" · ").ifBlank { getString(R.string.float_rpa_analyze_flags_none) }

    // ------------------------------------------------------- vertical menu

    private fun showNodeMenu(node: RpaAnalyzeNode, x: Float, y: Float) {
        menuCard.removeAllViews()
        val header = TextView(context).apply {
            text = "#${node.index} ${node.simpleClassName}"
            textSize = 11f
            setTextColor(COLOR_MUTED)
            setPadding(dp(10), dp(4), dp(10), dp(6))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        menuCard.addView(header)

        addMenuItem(R.string.float_rpa_analyze_menu_info) {
            hideMenu()
            showDetail(node)
        }
        addMenuItem(R.string.float_rpa_analyze_menu_hierarchy) {
            hideMenu()
            selectNode(node)
            showHierarchy()
        }
        addMenuItem(R.string.float_rpa_analyze_menu_copy_a11y) {
            val json = RpaNodeIndex.toA11yJson(node)
            if (json == null) {
                toast(R.string.float_rpa_analyze_no_selector)
            } else {
                copyToClipboard(json)
                hideMenu()
            }
        }
        addMenuItem(R.string.float_rpa_analyze_menu_hide) {
            hiddenForCurrent().add(node.index)
            selected = null
            hideMenu()
            rebuildCanvas()
            updateRestoreButton()
        }
        addMenuItem(R.string.float_rpa_analyze_menu_hide_same) {
            val same = hiddenForCurrent()
            currentNodes()
                .filter { RpaNodeIndex.sameBounds(it, node) }
                .forEach { same.add(it.index) }
            selected = null
            hideMenu()
            rebuildCanvas()
            updateRestoreButton()
        }
        addMenuItem(R.string.float_rpa_analyze_menu_windows) {
            hideMenu()
            showWindowPanel()
        }
        addMenuItem(R.string.float_rpa_analyze_menu_exit) {
            callbacks.onExit()
        }

        menuCard.visibility = VISIBLE
        positionMenu(x, y)
    }

    private fun addMenuItem(labelRes: Int, onClick: () -> Unit) {
        if (menuCard.childCount > 1) {
            val divider = View(context).apply {
                setBackgroundColor(0x14000000)
            }
            menuCard.addView(
                divider,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, max(1, dp(1) / 2)),
            )
        }
        val item = TextView(context).apply {
            text = getString(labelRes)
            textSize = 13f
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        menuCard.addView(
            item,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private fun positionMenu(anchorX: Float, anchorY: Float) {
        val overlayWidth = if (width > 0) width else screenWidth
        val overlayHeight = if (height > 0) height else screenHeight
        val widthSpec = MeasureSpec.makeMeasureSpec(dp(230), MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        menuCard.measure(widthSpec, heightSpec)
        val menuWidth = menuCard.measuredWidth
        val menuHeight = menuCard.measuredHeight
        val edge = dp(8)
        val minTop = statusBarHeight + dp(4) +
            (if (topBar.height > 0) topBar.height else dp(76)) + dp(8)

        var left = anchorX.toInt() + dp(10)
        if (left + menuWidth > overlayWidth - edge) {
            left = anchorX.toInt() - menuWidth - dp(10)
        }
        left = left.coerceIn(edge, max(edge, overlayWidth - menuWidth - edge))

        var top = anchorY.toInt() + dp(10)
        if (top + menuHeight > overlayHeight - edge) {
            top = anchorY.toInt() - menuHeight - dp(10)
        }
        top = top.coerceIn(minTop, max(minTop, overlayHeight - menuHeight - edge))

        val params = menuCard.layoutParams as LayoutParams
        params.leftMargin = left
        params.topMargin = top
        menuCard.layoutParams = params
    }

    private fun hideMenu() {
        menuCard.visibility = GONE
    }

    // -------------------------------------------------------------- windows

    private fun setupWindowCard() {
        windowCard.addView(
            panelHeader(getString(R.string.float_rpa_analyze_windows_title)) {
                hideWindowPanel()
            },
        )
        windowCard.addView(
            windowList,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private fun toggleWindowPanel() {
        if (windowCard.visibility == VISIBLE) {
            hideWindowPanel()
        } else {
            showWindowPanel()
        }
    }

    private fun showWindowPanel() {
        val value = snapshot
        if (value == null) return
        if (value.windows.size <= 1) {
            toast(R.string.float_rpa_analyze_only_one_window)
            return
        }
        hideMenu()
        detailCard.visibility = GONE
        hideHierarchy()
        rebuildWindowRows()
        positionBelowTopBar(windowCard)
        windowCard.visibility = VISIBLE
    }

    private fun hideWindowPanel() {
        windowCard.visibility = GONE
    }

    private fun rebuildWindowRows() {
        windowList.removeAllViews()
        val value = snapshot ?: return
        val duplicatePackages = value.windows
            .groupBy { it.packageName }
            .filterValues { it.size > 1 }
            .keys
        value.windows.forEach { window ->
            val label = if (window.packageName in duplicatePackages) {
                "${window.label} #${window.index + 1}"
            } else {
                window.label
            }
            val role = if (window.index == value.activeWindowIndex) {
                getString(R.string.float_rpa_analyze_window_foreground)
            } else {
                getString(R.string.float_rpa_analyze_window_background)
            }
            val checked = window.index == currentWindowIndex
            val row = TextView(context).apply {
                text = "$label $role" + if (checked) "  ✓" else ""
                textSize = 12f
                setTextColor(COLOR_TEXT)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(9), dp(12), dp(9))
                if (checked) setBackgroundColor(0x2200C853)
                isClickable = true
                setOnClickListener { switchWindow(window.index) }
            }
            windowList.addView(
                row,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun switchWindow(index: Int) {
        if (index == currentWindowIndex) {
            hideWindowPanel()
            return
        }
        currentWindowIndex = index
        selected = null
        hideMenu()
        detailCard.visibility = GONE
        hideHierarchy()
        hideWindowPanel()
        updateSubtitle()
        updateBackgroundNotice()
        rebuildCanvas()
    }

    // -------------------------------------------------------------- canvas

    private fun handleCanvasTap(x: Float, y: Float) {
        val value = snapshot ?: return
        val point = screenPoint(value, x, y)
        hideMenu()
        detailCard.visibility = GONE
        hideWindowPanel()
        if (point == null) {
            selected = null
            rebuildCanvas()
            return
        }
        val node = RpaNodeIndex.hitTest(
            nodes = currentNodes(),
            hidden = hiddenForCurrent(),
            x = point.first.toInt(),
            y = point.second.toInt(),
        )
        if (node == null) {
            selected = null
            rebuildCanvas()
            return
        }
        selected = node
        rebuildCanvas()
        showNodeMenu(node, x, y)
    }

    private fun selectNode(node: RpaAnalyzeNode) {
        selected = node
        rebuildCanvas()
    }

    private fun rebuildCanvas() {
        val value = snapshot
        if (value == null) {
            canvasView.clear()
            return
        }
        if (canvasView.width <= 0 || canvasView.height <= 0) return
        val fit = RpaImageFit.fit(
            canvasView.width,
            canvasView.height,
            imageWidth(value),
            imageHeight(value),
        )
        val boxes = RpaNodeIndex.boxNodes(currentNodes(), hiddenForCurrent())
            .mapNotNull { node ->
                rectFor(node, value, fit)?.let { rect ->
                    DrawBox(
                        index = node.index,
                        rect = rect,
                        label = node.label.takeIf { node.hasSemanticLabel },
                    )
                }
            }
        canvasView.render(
            frame = value.frame,
            fit = fit,
            boxes = boxes,
            selectedIndex = selected?.index,
            showLabels = showLabels,
        )
    }

    private fun rectFor(
        node: RpaAnalyzeNode,
        value: RpaAnalyzeSnapshot,
        fit: RpaImageFit,
    ): RectF? {
        val bounds = node.bounds
        if (bounds.isEmpty) return null
        val scaleX = imageWidth(value).toFloat() / value.screenWidth.coerceAtLeast(1)
        val scaleY = imageHeight(value).toFloat() / value.screenHeight.coerceAtLeast(1)
        val (left, top) = fit.imageToView(bounds.left * scaleX, bounds.top * scaleY)
        val (right, bottom) = fit.imageToView(bounds.right * scaleX, bounds.bottom * scaleY)
        return RectF(left, top, right, bottom)
    }

    private fun screenPoint(
        value: RpaAnalyzeSnapshot,
        viewX: Float,
        viewY: Float,
    ): Pair<Float, Float>? {
        if (canvasView.width <= 0 || canvasView.height <= 0) return null
        val fit = RpaImageFit.fit(
            canvasView.width,
            canvasView.height,
            imageWidth(value),
            imageHeight(value),
        )
        val (imageX, imageY) = fit.viewToImage(viewX, viewY) ?: return null
        return (imageX * value.screenWidth / imageWidth(value)) to
            (imageY * value.screenHeight / imageHeight(value))
    }

    private fun imageWidth(value: RpaAnalyzeSnapshot): Int =
        value.frame?.width?.takeIf { it > 0 } ?: value.screenWidth

    private fun imageHeight(value: RpaAnalyzeSnapshot): Int =
        value.frame?.height?.takeIf { it > 0 } ?: value.screenHeight

    // ---------------------------------------------------------------- state

    private fun currentWindow(): RpaAnalyzeWindow? {
        val value = snapshot ?: return null
        return value.windows.firstOrNull { it.index == currentWindowIndex }
            ?: value.windows.firstOrNull()
    }

    private fun currentNodes(): List<RpaAnalyzeNode> {
        val window = currentWindow() ?: return emptyList()
        return nodesByWindow.getOrPut(window.index) {
            RpaNodeIndex.build(window.index, window.root)
        }
    }

    private fun hiddenForCurrent(): MutableSet<Int> {
        val window = currentWindow()
        return hiddenByWindow.getOrPut(window?.index ?: 0) { mutableSetOf() }
    }

    private fun restoreHiddenNodes() {
        hiddenByWindow.values.forEach { it.clear() }
        selected = null
        hideMenu()
        rebuildCanvas()
        updateRestoreButton()
    }

    private fun updateRestoreButton() {
        val count = hiddenByWindow.values.sumOf { it.size }
        if (count > 0) {
            restoreButton.text = getString(R.string.float_rpa_analyze_restore_hidden_count, count)
            restoreButton.visibility = VISIBLE
        } else {
            restoreButton.text = getString(R.string.float_rpa_analyze_restore_hidden)
            restoreButton.visibility = GONE
        }
    }

    private fun updateSubtitle() {
        val value = snapshot ?: return
        val window = currentWindow() ?: return
        val nodes = nodesByWindow[window.index]?.size ?: currentNodes().size
        subtitleView.text = if (value.frame == null) {
            getString(R.string.float_rpa_analyze_subtitle_no_frame, window.label, nodes)
        } else {
            getString(R.string.float_rpa_analyze_subtitle, window.label, nodes)
        }
    }

    private fun updateBackgroundNotice() {
        val value = snapshot
        if (value == null) {
            backgroundNotice.visibility = GONE
            return
        }
        val window = currentWindow()
        when {
            window != null && window.index != value.activeWindowIndex -> {
                backgroundNotice.text = getString(R.string.float_rpa_analyze_background_window)
                backgroundNotice.visibility = VISIBLE
            }

            value.frame == null -> {
                backgroundNotice.text = getString(R.string.float_rpa_analyze_no_frame_notice)
                backgroundNotice.visibility = VISIBLE
            }

            else -> backgroundNotice.visibility = GONE
        }
    }

    private fun hideAllPanels() {
        hideMenu()
        detailCard.visibility = GONE
        hideHierarchy()
        hideWindowPanel()
    }

    // -------------------------------------------------------------- helpers

    private fun positionBelowTopBar(card: View) {
        val params = card.layoutParams as LayoutParams
        params.topMargin = statusBarHeight + dp(4) +
            (if (topBar.height > 0) topBar.height else dp(76)) + dp(8)
        card.layoutParams = params
    }

    private fun panelHeader(title: String, onClose: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(6), dp(4))
        }
        val titleView = TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(
            titleView,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        val close = TextView(context).apply {
            text = getString(R.string.float_rpa_analyze_close_symbol)
            textSize = 20f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), dp(2))
            isClickable = true
            setOnClickListener { onClose() }
        }
        row.addView(close)
        return row
    }

    private fun panelActionButton(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = getString(labelRes)
            textSize = 12f
            setTextColor(0xFF1B5E20.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(0xFFE8F5E9.toInt(), 18)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun hintLine(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_MUTED)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun copyToClipboard(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            toast(R.string.float_rpa_analyze_copy_failed)
            return
        }
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText("RPA", text))
        }.onSuccess {
            toast(R.string.float_rpa_analyze_copy_ok)
        }.onFailure {
            toast(R.string.float_rpa_analyze_copy_failed)
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun dimension(name: String, fallback: Int): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id <= 0) return fallback
        return runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(fallback)
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int = 12,
        strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    // ---------------------------------------------------------------- canvas

    private inner class AnalyzeCanvas(context: Context) : View(context) {
        var onTap: ((Float, Float) -> Unit)? = null
        private var frame: Bitmap? = null
        private var fit: RpaImageFit? = null
        private var boxes: List<DrawBox> = emptyList()
        private var selectedIndex: Int? = null
        private var labelsVisible = true

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = 0xCC00C853.toInt()
        }
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = 0xFFFFC400.toInt()
        }
        private val selectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x33FFC400
        }
        private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xD9000000.toInt()
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(10).toFloat()
        }
        private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF
            textSize = dp(14).toFloat()
            textAlign = Paint.Align.CENTER
        }
        private val destination = RectF()

        init {
            isClickable = true
        }

        fun render(
            frame: Bitmap?,
            fit: RpaImageFit?,
            boxes: List<DrawBox>,
            selectedIndex: Int?,
            showLabels: Boolean,
        ) {
            this.frame = frame
            this.fit = fit
            this.boxes = boxes
            this.selectedIndex = selectedIndex
            this.labelsVisible = showLabels
            invalidate()
        }

        fun clear() {
            frame = null
            fit = null
            boxes = emptyList()
            selectedIndex = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(COLOR_BG)
            val bitmap = frame
            val imageFit = fit
            if (bitmap != null && imageFit != null && imageFit.valid) {
                destination.set(
                    imageFit.offsetX,
                    imageFit.offsetY,
                    imageFit.offsetX + bitmap.width * imageFit.scale,
                    imageFit.offsetY + bitmap.height * imageFit.scale,
                )
                canvas.drawBitmap(bitmap, null, destination, null)
            }

            boxes.forEach { box ->
                canvas.drawRect(box.rect, boxPaint)
            }
            selectedIndex?.let { index ->
                boxes.firstOrNull { it.index == index }?.let { selectedBox ->
                    canvas.drawRect(selectedBox.rect, selectedFill)
                    canvas.drawRect(selectedBox.rect, selectedPaint)
                }
            }

            if (bitmap == null) {
                canvas.drawText(
                    context.getString(R.string.float_rpa_analyze_no_frame_short),
                    width / 2f,
                    height / 2f,
                    watermarkPaint,
                )
            }

            if (labelsVisible) drawLabels(canvas)
        }

        private fun drawLabels(canvas: Canvas) {
            var drawn = 0
            boxes.forEach { box ->
                if (drawn >= MAX_LABELS) return
                val label = box.label?.takeIf { it.isNotBlank() } ?: return@forEach
                if (box.rect.width() < dp(30) || box.rect.height() < dp(10)) return@forEach
                val text = label.take(MAX_LABEL_CHARS)
                val textWidth = labelPaint.measureText(text)
                val x = box.rect.left.coerceIn(0f, max(0f, width - textWidth - dp(6)))
                val y = (box.rect.top - dp(16)).coerceIn(0f, max(0f, height - dp(18)))
                canvas.drawRoundRect(
                    RectF(x, y, x + textWidth + dp(6), y + dp(15)),
                    dp(2).toFloat(),
                    dp(2).toFloat(),
                    labelBackground,
                )
                canvas.drawText(text, x + dp(3), y + dp(11), labelPaint)
                drawn++
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                onTap?.invoke(event.x, event.y)
                performClick()
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    companion object {
        private val COLOR_PANEL = 0xFFFDFDFD.toInt()
        private val COLOR_PANEL_STROKE = 0x26000000
        private val COLOR_TEXT = 0xFF202124.toInt()
        private val COLOR_MUTED = 0xFF5F6368.toInt()
        private val COLOR_TOP_BAR = 0xE614171A.toInt()
        private val COLOR_SCRIM = 0xFF101214.toInt()

        private const val MAX_HIERARCHY_ROWS = 400
        private const val MAX_ROW_LABEL_CHARS = 56
        private const val MAX_LABELS = 80
        private const val MAX_LABEL_CHARS = 28
    }
}
