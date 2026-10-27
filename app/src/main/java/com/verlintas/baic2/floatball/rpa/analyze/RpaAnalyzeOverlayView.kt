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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.verlintas.baic2.R
import com.verlintas.rpa.model.RpaA11ySpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.abs

/**
 * AutoJs6 式「Android 界面布局分析器」覆盖层（方案 §5.2，经典 View 实现）。
 *
 * 所有面板都挂在**同一个** [RpaAnalyzeOverlayView] 窗口内，而不是另开
 * Dialog/PopupWindow：analyzer 本身是 `TYPE_ACCESSIBILITY_OVERLAY`，再开子窗口
 * 会改变目标 App 的无障碍窗口顺序，刷新时还会把自己的面板拍进截图。
 *
 * 交互链：
 * 1. 冻结帧 fitCenter + 绿色控件框；
 * 2. 点控件 → 白色竖排菜单（AutoJs6 同款）；
 * 3. 属性卡 / 层级 / 窗口选择都在覆盖层内完成；
 * 4. 隐藏节点只影响画框，不修改系统节点树。
 */
internal class RpaAnalyzeOverlayView(
    context: Context,
    private val callbacks: Callbacks,
    /** 拾取会话横幅文案；非空时节点菜单多出「应用到当前步骤」入口。 */
    private val pickHint: String? = null,
) : FrameLayout(context) {

    interface Callbacks {
        fun onExit()
        fun onRefresh()

        /** 拾取回填：把当前节点的结构化 a11y 定位写回 RPA 脚本（方案 §7.8）。 */
        fun onApplyPick(spec: RpaA11ySpec) {}

        /** 取消拾取：清空请求，避免横幅在后续会话里继续出现。 */
        fun onCancelPick() {}
    }

    private enum class NodeFilter { ALL, CLICKABLE, EDITABLE, TEXT }

    private val density = resources.displayMetrics.density
    private val displayWidth = resources.displayMetrics.widthPixels
    private val displayHeight = resources.displayMetrics.heightPixels
    private val statusBarHeight = resolveStatusBarHeight()

    private val green = Color.parseColor("#00E676")
    private val yellow = Color.parseColor("#FFD600")
    private val sheetBackgroundColor = Color.WHITE
    private val toolbarColor = Color.parseColor("#E61C1C1E")
    private val primaryText = Color.parseColor("#202124")
    private val secondaryText = Color.parseColor("#5F6368")
    private val dividerColor = Color.parseColor("#14000000")

    private var snapshot: RpaAnalyzeSnapshot? = null
    private var index: RpaNodeIndex? = null
    private var currentWindowIndex = 0
    private var selected: RpaAnalyzeNode? = null
    private var currentFilter = NodeFilter.ALL
    private var showLabels = true
    private var loading = true
    private var hiddenByWindow = mutableMapOf<Int, MutableSet<Int>>()

    private var menuView: View? = null
    private var menuAnchorX = 0f
    private var menuAnchorY = 0f
    private var sheetView: View? = null
    private var detailView: View? = null
    private var hierarchyScroll: ScrollView? = null
    private var hierarchyRows: LinearLayout? = null
    private var hierarchyRowViews = mutableMapOf<Int, TextView>()
    private var filterChips = mutableMapOf<NodeFilter, TextView>()

    private lateinit var chromeColumn: LinearLayout
    private lateinit var subtitleView: TextView
    private lateinit var restoreButton: TextView
    private lateinit var frameNoticeView: TextView
    private lateinit var backgroundNoticeView: TextView
    private lateinit var loadingView: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var errorDetailView: TextView
    private lateinit var canvasView: AnalyzeCanvas

    init {
        setBackgroundColor(Color.TRANSPARENT)
        canvasView = AnalyzeCanvas(context)
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildChrome(context)
        buildLoading(context)
        buildError(context)
        showLoading()
    }

    // ------------------------------------------------------------ public API

    fun showLoading() {
        loading = true
        snapshot = null
        index = null
        selected = null
        hiddenByWindow.clear()
        dismissMenu()
        dismissDetail()
        dismissSheet()
        errorView.visibility = GONE
        loadingView.visibility = VISIBLE
        subtitleView.text = context.getString(R.string.float_rpa_analyze_loading)
        frameNoticeView.visibility = GONE
        backgroundNoticeView.visibility = GONE
        updateChrome()
        canvasView.invalidate()
    }

    fun renderSnapshot(snapshot: RpaAnalyzeSnapshot) {
        this.snapshot = snapshot
        val built = RpaNodeIndex.build(
            windows = snapshot.windows,
            activeWindowIndex = snapshot.activeWindowIndex,
        )
        index = built
        currentWindowIndex = built.activeWindowIndex
            .coerceIn(0, (built.windows.size - 1).coerceAtLeast(0))
        selected = null
        hiddenByWindow.clear()
        loading = false
        errorView.visibility = GONE
        loadingView.visibility = GONE
        updateChrome()
        canvasView.invalidate()
    }

    fun renderError(code: String) {
        loading = false
        snapshot = null
        index = null
        selected = null
        hiddenByWindow.clear()
        dismissMenu()
        dismissDetail()
        dismissSheet()
        loadingView.visibility = GONE
        errorView.visibility = VISIBLE
        errorDetailView.text = context.getString(R.string.float_rpa_analyze_capture_failed, code)
        frameNoticeView.visibility = GONE
        backgroundNoticeView.visibility = GONE
        subtitleView.text = context.getString(R.string.float_rpa_analyze_capture_failed, code)
        canvasView.invalidate()
    }

    /** 窗口已从 WindowManager 摘除；只清引用，不 recycle 帧（帧源可能复用缓冲）。 */
    fun release() {
        dismissMenu()
        dismissDetail()
        dismissSheet()
        snapshot = null
        index = null
        selected = null
        hiddenByWindow.clear()
        removeAllViews()
    }

    // --------------------------------------------------------------- chrome

    private fun buildChrome(context: Context) {
        chromeColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(4))
        }
        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.float_rpa_analyze_title)
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        subtitleView = TextView(context).apply {
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 11f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        titleColumn.addView(title)
        titleColumn.addView(subtitleView)
        titleBox.addView(titleColumn)
        titleBox.addView(chromeButton(context.getString(R.string.float_rpa_analyze_exit)) {
            callbacks.onExit()
        })
        chromeColumn.addView(titleBox)

        val buttonScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(8), 0, dp(8), dp(6))
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(chromeButton(context.getString(R.string.float_rpa_analyze_refresh)) {
            callbacks.onRefresh()
        })
        buttons.addView(chromeButton(context.getString(R.string.float_rpa_analyze_hierarchy)) {
            showHierarchy(selected)
        })
        buttons.addView(chromeButton(context.getString(R.string.float_rpa_analyze_windows)) {
            showWindowPicker()
        })
        restoreButton = chromeButton(context.getString(R.string.float_rpa_analyze_restore_hidden)) {
            restoreHidden()
        }
        buttons.addView(restoreButton)
        if (pickHint != null) {
            buttons.addView(chromeButton(context.getString(R.string.float_rpa_pick_cancel)) {
                callbacks.onCancelPick()
            })
        }
        buttonScroll.addView(buttons)
        chromeColumn.addView(buttonScroll)

        // 拾取横幅与「已隐藏 N 个」等提示同一视觉层级，始终可见，避免用户在
        // 分析过程中忘记自己是为某一步骤拾取定位。
        pickHint?.let { hint ->
            chromeColumn.addView(
                noticeText(hint, Color.parseColor("#E8F0FE"), Color.parseColor("#174EA6")),
                LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

        frameNoticeView = noticeText(
            context.getString(R.string.float_rpa_analyze_no_frame_notice),
            Color.parseColor("#FFF3CD"),
            Color.parseColor("#7A5B00"),
        ).apply { visibility = GONE }
        chromeColumn.addView(frameNoticeView, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        backgroundNoticeView = noticeText(
            context.getString(R.string.float_rpa_analyze_background_window),
            Color.parseColor("#DDEBFF"),
            Color.parseColor("#174EA6"),
        ).apply { visibility = GONE }
        chromeColumn.addView(backgroundNoticeView, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        chromeColumn.background = roundedBackground(toolbarColor, 12)
        addView(
            chromeColumn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = statusBarHeight + dp(8)
                leftMargin = dp(8)
                rightMargin = dp(8)
            },
        )
    }

    private fun buildLoading(context: Context) {
        loadingView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = roundedBackground(Color.parseColor("#E6222222"), 12)
        }
        loadingView.addView(ProgressBar(context).apply { isIndeterminate = true })
        loadingView.addView(TextView(context).apply {
            text = context.getString(R.string.float_rpa_analyze_loading)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })
        addView(
            loadingView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )
    }

    private fun buildError(context: Context) {
        errorView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = roundedBackground(sheetBackgroundColor, 14)
            elevation = dp(10).toFloat()
            visibility = GONE
        }
        errorView.addView(TextView(context).apply {
            text = context.getString(R.string.float_rpa_analyze_capture_failed, "")
            setTextColor(primaryText)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        errorDetailView = TextView(context).apply {
            setTextColor(secondaryText)
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        }
        errorView.addView(errorDetailView)
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(sheetButton(context.getString(R.string.float_rpa_analyze_refresh)) {
            errorView.visibility = GONE
            callbackRefresh()
        })
        actions.addView(sheetButton(context.getString(R.string.float_rpa_analyze_exit)) {
            callbacks.onExit()
        })
        errorView.addView(actions)
        addView(
            errorView,
            LayoutParams(dp(300), LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER },
        )
    }

    private fun callbackRefresh() {
        callbacks.onRefresh()
    }

    private fun updateChrome() {
        val snap = snapshot
        val idx = index
        if (loading) {
            subtitleView.text = context.getString(R.string.float_rpa_analyze_loading)
            frameNoticeView.visibility = GONE
            backgroundNoticeView.visibility = GONE
            restoreButton.visibility = VISIBLE
            return
        }
        val window = idx?.window(currentWindowIndex)
        val count = idx?.nodesOf(currentWindowIndex)?.size ?: 0
        if (window != null && snap != null) {
            val state = if (currentWindowIndex == snap.activeWindowIndex) {
                context.getString(R.string.float_rpa_analyze_window_foreground)
            } else {
                context.getString(R.string.float_rpa_analyze_window_background)
            }
            val label = window.label + state
            subtitleView.text = if (snap.hasFrame) {
                context.getString(R.string.float_rpa_analyze_subtitle, label, count)
            } else {
                context.getString(R.string.float_rpa_analyze_subtitle_no_frame, label, count)
            }
            frameNoticeView.visibility = if (snap.hasFrame) GONE else VISIBLE
            backgroundNoticeView.visibility =
                if (currentWindowIndex == snap.activeWindowIndex) GONE else VISIBLE
        } else {
            subtitleView.text = ""
            frameNoticeView.visibility = GONE
            backgroundNoticeView.visibility = GONE
        }
        val hiddenCount = hiddenByWindow[currentWindowIndex]?.size ?: 0
        restoreButton.text = if (hiddenCount > 0) {
            context.getString(R.string.float_rpa_analyze_restore_hidden_count, hiddenCount)
        } else {
            context.getString(R.string.float_rpa_analyze_restore_hidden)
        }
        restoreButton.alpha = if (hiddenCount > 0) 1f else 0.55f
    }

    private fun chromeButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(Color.parseColor("#33FFFFFF"), 9)
            isClickable = true
            isFocusable = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        }

    private fun sheetButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(primaryText)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(Color.parseColor("#F1F3F4"), 9)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
        }

    private fun noticeText(text: String, background: Int, textColor: Int): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 11f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            this.background = roundedBackground(background, 9)
        }

    // ---------------------------------------------------------------- canvas

    private fun fitFor(frame: android.graphics.Bitmap): RpaImageFit =
        RpaImageFit.fit(frame.width, frame.height, canvasView.width, canvasView.height)

    private fun rectForNode(node: RpaAnalyzeNode): RectF? {
        val snap = snapshot ?: return null
        if (snap.screenWidth <= 0 || snap.screenHeight <= 0) return null
        val frame = snap.frame
        if (frame != null && frame.width > 0 && frame.height > 0) {
            val fit = fitFor(frame)
            if (!fit.valid) return null
            val sx = frame.width.toFloat() / snap.screenWidth
            val sy = frame.height.toFloat() / snap.screenHeight
            val leftTop = fit.imageToView(node.bounds.left * sx, node.bounds.top * sy)
            val rightBottom = fit.imageToView(node.bounds.right * sx, node.bounds.bottom * sy)
            return RectF(
                min(leftTop.x, rightBottom.x),
                min(leftTop.y, rightBottom.y),
                max(leftTop.x, rightBottom.x),
                max(leftTop.y, rightBottom.y),
            )
        }
        val viewWidth = canvasView.width
        val viewHeight = canvasView.height
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val sx = viewWidth.toFloat() / snap.screenWidth
        val sy = viewHeight.toFloat() / snap.screenHeight
        return RectF(
            node.bounds.left * sx,
            node.bounds.top * sy,
            node.bounds.right * sx,
            node.bounds.bottom * sy,
        )
    }

    private fun viewToScreen(x: Float, y: Float): RpaPoint? {
        val snap = snapshot ?: return null
        val frame = snap.frame
        if (frame != null && frame.width > 0 && frame.height > 0) {
            val fit = fitFor(frame)
            if (!fit.valid) return null
            val imagePoint = fit.viewToImage(x, y)
            val sx = snap.screenWidth.toFloat() / frame.width
            val sy = snap.screenHeight.toFloat() / frame.height
            return RpaPoint(imagePoint.x * sx, imagePoint.y * sy)
        }
        if (canvasView.width <= 0 || canvasView.height <= 0) return null
        val sx = snap.screenWidth.toFloat() / canvasView.width
        val sy = snap.screenHeight.toFloat() / canvasView.height
        return RpaPoint(x * sx, y * sy)
    }

    private fun hiddenSet(): Set<Int> =
        hiddenByWindow[currentWindowIndex] ?: emptySet()

    private inner class AnalyzeCanvas(context: Context) : View(context) {

        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
        }
        private val letterboxPaint = Paint().apply { color = Color.BLACK }
        private val scrimPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 2f * density)
            color = green
        }
        private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 3f * density)
            color = yellow
        }
        private val greenFill = Paint().apply { color = Color.argb(26, 0, 230, 118) }
        private val selectedFill = Paint().apply { color = Color.argb(52, 255, 214, 0) }
        private val labelBackground = Paint().apply { color = Color.argb(210, 0, 0, 0) }
        private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10f * density
            typeface = Typeface.DEFAULT_BOLD
        }
        private val labelRects = ArrayList<RectF>(MAX_LABELS)

        override fun onDraw(canvas: Canvas) {
            val snap = snapshot
            val idx = index
            if (snap == null || idx == null) {
                canvas.drawPaint(scrimPaint)
                return
            }
            val frame = snap.frame
            if (frame != null) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), letterboxPaint)
                val fit = fitFor(frame)
                if (fit.valid) {
                    canvas.drawBitmap(
                        frame,
                        null,
                        RectF(fit.left, fit.top, fit.right, fit.bottom),
                        bitmapPaint,
                    )
                }
            } else {
                canvas.drawPaint(scrimPaint)
            }

            labelRects.clear()
            val hidden = hiddenSet()
            var labelsLeft = MAX_LABELS
            for (node in idx.boxNodes(currentWindowIndex, hidden)) {
                val rect = rectForNode(node) ?: continue
                if (rect.width() < 1f || rect.height() < 1f) continue
                val isSelected = selected?.index == node.index
                canvas.drawRect(rect, if (isSelected) selectedFill else greenFill)
                canvas.drawRect(rect, if (isSelected) selectedStroke else boxStroke)
                if (showLabels && labelsLeft > 0) {
                    if (drawLabel(canvas, node, rect)) labelsLeft--
                }
            }
        }

        private fun drawLabel(canvas: Canvas, node: RpaAnalyzeNode, rect: RectF): Boolean {
            if (!node.hasSemanticLabel) return false
            if (rect.height() < dp(12) || rect.width() < dp(28)) return false
            val source = node.label.replace('\n', ' ').replace('\r', ' ').trim()
            if (source.isEmpty()) return false
            val maxTextWidth = min(rect.width() - dp(6), 9f * dp(16).toFloat())
            var display = source
            if (labelText.measureText(display) > maxTextWidth) {
                var end = display.length
                while (end > 1 && labelText.measureText(display.substring(0, end) + "…") > maxTextWidth) {
                    end--
                }
                display = display.substring(0, end) + "…"
            }
            val textWidth = labelText.measureText(display)
            val boxWidth = min(textWidth + dp(6), rect.width() - dp(2))
            val boxHeight = min(labelText.textSize + dp(4), rect.height() - dp(2))
            val labelRect = RectF(
                rect.left + dp(1),
                rect.top + dp(1),
                rect.left + dp(1) + max(1f, boxWidth),
                rect.top + dp(1) + max(1f, boxHeight),
            )
            for (existing in labelRects) {
                if (RectF.intersects(existing, labelRect)) return false
            }
            labelRects.add(labelRect)
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawRoundRect(labelRect, dp(3).toFloat(), dp(3).toFloat(), labelBackground)
            canvas.drawText(
                display,
                labelRect.left + dp(3),
                labelRect.bottom - dp(3),
                labelText,
            )
            canvas.restore()
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    onCanvasTap(event.x, event.y)
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private fun onCanvasTap(x: Float, y: Float) {
        if (menuView != null) {
            dismissMenu()
            return
        }
        if (detailView != null) {
            dismissDetail()
            return
        }
        if (sheetView != null) {
            dismissSheet()
            return
        }
        val idx = index ?: return
        val point = viewToScreen(x, y) ?: return
        val screenX = point.x.roundToInt()
        val screenY = point.y.roundToInt()
        if (screenX < 0 || screenY < 0) return
        val node = idx.hitTest(
            x = screenX,
            y = screenY,
            windowIndex = currentWindowIndex,
            exclude = hiddenSet(),
        ) ?: return
        selected = node
        menuAnchorX = screenX.toFloat()
        menuAnchorY = screenY.toFloat()
        canvasView.invalidate()
        showNodeMenu(node)
    }

    // ------------------------------------------------------------------ menu

    private fun showNodeMenu(node: RpaAnalyzeNode) {
        dismissMenu()
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(sheetBackgroundColor, 12)
            elevation = dp(10).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val entries = ArrayList<Pair<String, () -> Unit>>(8)
        if (pickHint != null) {
            entries.add(
                context.getString(R.string.float_rpa_pick_apply) to {
                    val spec = index?.toRpaA11ySpec(node)
                    if (spec == null) {
                        // 只有坐标/类名的节点无法稳定复现，给复制节点 JSON 的提示。
                        Toast.makeText(
                            context,
                            R.string.float_rpa_pick_no_selector,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        dismissMenu()
                        callbacks.onApplyPick(spec)
                    }
                },
            )
        }
        entries.addAll(
            listOf(
                context.getString(R.string.float_rpa_analyze_menu_info) to {
                    dismissMenu()
                    showDetail(node)
                },
                context.getString(R.string.float_rpa_analyze_menu_hierarchy) to {
                    dismissMenu()
                    showHierarchy(node)
                },
                context.getString(R.string.float_rpa_analyze_menu_copy_a11y) to {
                    index?.let { copyText("rpa_a11y", it.toA11ySpec(node)) }
                    dismissMenu()
                },
                context.getString(R.string.float_rpa_analyze_menu_hide) to {
                    hideNode(node)
                    dismissMenu()
                },
                context.getString(R.string.float_rpa_analyze_menu_hide_same) to {
                    hideSameBounds(node)
                    dismissMenu()
                },
                context.getString(R.string.float_rpa_analyze_menu_windows) to {
                    dismissMenu()
                    showWindowPicker()
                },
                context.getString(R.string.float_rpa_analyze_menu_exit) to {
                    dismissMenu()
                    callbacks.onExit()
                },
            ),
        )
        entries.forEachIndexed { position, entry ->
            if (position > 0) {
                menu.addView(View(context).apply { setBackgroundColor(dividerColor) },
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                        marginStart = dp(10)
                        marginEnd = dp(10)
                    })
            }
            menu.addView(menuItem(entry.first, entry.second))
        }
        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        menuView = menu
        addView(menu, params)
        menu.post { positionMenu(menu) }
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(primaryText)
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(18), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun positionMenu(menu: View) {
        val parentWidth = if (width > 0) width else displayWidth
        val parentHeight = if (height > 0) height else displayHeight
        if (parentWidth <= 0 || parentHeight <= 0) return
        menu.measure(
            MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(parentHeight, MeasureSpec.AT_MOST),
        )
        val menuWidth = menu.measuredWidth
        val menuHeight = menu.measuredHeight
        if (menuWidth <= 0 || menuHeight <= 0) return

        var x = menuAnchorX + dp(8)
        if (x + menuWidth > parentWidth - dp(8)) {
            x = menuAnchorX - menuWidth - dp(8)
        }
        x = x.coerceIn(dp(8).toFloat(), max(dp(8).toFloat(), parentWidth - menuWidth - dp(8).toFloat()))

        val chromeBottom = if (chromeColumn.height > 0) {
            chromeColumn.top + chromeColumn.height
        } else {
            statusBarHeight + dp(64)
        }
        var y = menuAnchorY + dp(8)
        if (y + menuHeight > parentHeight - dp(8)) {
            y = menuAnchorY - menuHeight - dp(8)
        }
        val minY = (chromeBottom + dp(8)).toFloat()
            .coerceAtMost((parentHeight - menuHeight - dp(8)).toFloat().coerceAtLeast(0f))
        y = y.coerceIn(max(dp(8).toFloat(), minY), max(dp(8).toFloat(), parentHeight - menuHeight - dp(8).toFloat()))

        val params = menu.layoutParams as? LayoutParams ?: return
        params.leftMargin = x.roundToInt()
        params.topMargin = y.roundToInt()
        params.gravity = Gravity.TOP or Gravity.START
        menu.layoutParams = params
    }

    private fun dismissMenu() {
        menuView?.let { removeView(it) }
        menuView = null
    }

    // ---------------------------------------------------------------- detail

    private fun showDetail(node: RpaAnalyzeNode) {
        val idx = index ?: return
        dismissDetail()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(sheetBackgroundColor, 14)
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(6))
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.float_rpa_analyze_detail_title)
            setTextColor(primaryText)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(closeButton { dismissDetail() })
        card.addView(header)

        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(10))
        }
        val raw = node.raw
        addDetailField(body, context.getString(R.string.float_rpa_analyze_field_package, raw.packageName))
        addDetailField(body, context.getString(R.string.float_rpa_analyze_field_class, raw.className))
        addDetailField(body, context.getString(R.string.float_rpa_analyze_field_text, raw.text))
        addDetailField(body, context.getString(R.string.float_rpa_analyze_field_desc, raw.description))
        addDetailField(body, context.getString(R.string.float_rpa_analyze_field_view_id, raw.viewId))
        addDetailField(body, context.getString(R.string.float_rpa_analyze_field_hint, raw.hint))
        addDetailField(body, context.getString(R.string.float_rpa_analyze_field_bounds, raw.bounds.toString()))
        addDetailField(body, context.getString(R.string.float_rpa_analyze_field_flags, flagsText(raw)))
        addDetailField(body, context.getString(R.string.float_rpa_analyze_field_path, idx.pathText(node)))
        val hasSelector = idx.hasSelector(node)
        val selector = if (hasSelector) idx.toSelector(node) else {
            context.getString(R.string.float_rpa_analyze_no_selector_value)
        }
        addDetailField(
            body,
            context.getString(R.string.float_rpa_analyze_field_selector, selector),
            mono = true,
        )
        if (!hasSelector) {
            addDetailField(
                body,
                context.getString(R.string.float_rpa_analyze_no_selector),
                secondary = true,
            )
        }
        scroll.addView(body)
        card.addView(scroll, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        actions.addView(sheetButton(context.getString(R.string.float_rpa_analyze_copy_a11y)) {
            copyText("rpa_a11y", idx.toA11ySpec(node))
        })
        actions.addView(sheetButton(context.getString(R.string.float_rpa_analyze_copy_node)) {
            copyText("rpa_node", idx.toNodeJson(node))
        })
        actions.addView(sheetButton(context.getString(R.string.float_rpa_analyze_close)) {
            dismissDetail()
        })
        card.addView(actions)

        detailView = card
        addView(card, sheetParams(heightFraction = 0.52f))
    }

    private fun addDetailField(
        parent: LinearLayout,
        text: String,
        mono: Boolean = false,
        secondary: Boolean = false,
    ) {
        parent.addView(TextView(context).apply {
            this.text = text
            setTextColor(if (secondary) secondaryText else primaryText)
            textSize = if (secondary) 11f else 12f
            if (mono) {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun flagsText(raw: RpaRawNode): String {
        val flags = ArrayList<String>()
        if (raw.clickable) flags.add(context.getString(R.string.float_rpa_analyze_filter_clickable))
        if (raw.editable) flags.add(context.getString(R.string.float_rpa_analyze_filter_editable))
        if (raw.scrollable) flags.add(context.getString(R.string.float_rpa_analyze_tag_scrollable))
        if (raw.focused) flags.add(context.getString(R.string.float_rpa_analyze_tag_focused))
        if (raw.visibleToUser) flags.add(context.getString(R.string.float_rpa_analyze_tag_visible))
        if (!raw.enabled) flags.add(context.getString(R.string.float_rpa_analyze_tag_disabled))
        return if (flags.isEmpty()) {
            context.getString(R.string.float_rpa_analyze_flags_none)
        } else {
            flags.joinToString(" / ")
        }
    }

    private fun dismissDetail() {
        detailView?.let { removeView(it) }
        detailView = null
    }

    // -------------------------------------------------------------- hierarchy

    private fun showHierarchy(focus: RpaAnalyzeNode?) {
        val idx = index ?: return
        dismissSheet()
        dismissDetail()
        dismissMenu()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(sheetBackgroundColor, 14)
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.float_rpa_analyze_hierarchy_title)
            setTextColor(primaryText)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(closeButton { dismissSheet() })
        card.addView(header)

        val filterScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        val filters = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterChips.clear()
        NodeFilter.values().forEach { filter ->
            val chip = filterChip(filter) {
                currentFilter = filter
                updateFilterChips()
                rebuildHierarchyRows()
            }
            filterChips[filter] = chip
            filters.addView(chip)
        }
        filterScroll.addView(filters)
        card.addView(filterScroll)

        val toggle = CheckBox(context).apply {
            text = context.getString(R.string.float_rpa_analyze_show_labels)
            setTextColor(primaryText)
            textSize = 12f
            isChecked = showLabels
            setPadding(dp(4), 0, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                showLabels = checked
                canvasView.invalidate()
            }
        }
        card.addView(toggle, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(12)
            bottomMargin = dp(2)
        })

        val scroll = ScrollView(context)
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        scroll.addView(rows)
        card.addView(scroll, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        hierarchyScroll = scroll
        hierarchyRows = rows
        updateFilterChips()
        sheetView = card
        addView(card, sheetParams(heightFraction = 0.62f))
        rebuildHierarchyRows(focus)
    }

    private fun filterChip(filter: NodeFilter, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = filterLabel(filter)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        }

    private fun filterLabel(filter: NodeFilter): String = when (filter) {
        NodeFilter.ALL -> context.getString(R.string.float_rpa_analyze_filter_all)
        NodeFilter.CLICKABLE -> context.getString(R.string.float_rpa_analyze_filter_clickable)
        NodeFilter.EDITABLE -> context.getString(R.string.float_rpa_analyze_filter_editable)
        NodeFilter.TEXT -> context.getString(R.string.float_rpa_analyze_filter_text)
    }

    private fun updateFilterChips() {
        filterChips.forEach { (filter, chip) ->
            val active = filter == currentFilter
            chip.setTextColor(if (active) Color.WHITE else secondaryText)
            chip.background = roundedBackground(
                if (active) Color.parseColor("#1A73E8") else Color.parseColor("#F1F3F4"),
                9,
            )
        }
    }

    private fun matchesFilter(node: RpaAnalyzeNode): Boolean = when (currentFilter) {
        NodeFilter.ALL -> true
        NodeFilter.CLICKABLE -> node.raw.clickable
        NodeFilter.EDITABLE -> node.raw.editable
        NodeFilter.TEXT -> node.raw.text.isNotBlank() ||
            node.raw.description.isNotBlank() ||
            node.raw.hint.isNotBlank()
    }

    private fun rebuildHierarchyRows(focus: RpaAnalyzeNode? = null) {
        val idx = index ?: return
        val rows = hierarchyRows ?: return
        val scroll = hierarchyScroll ?: return
        rows.removeAllViews()
        hierarchyRowViews.clear()
        val hidden = hiddenSet()
        val source = idx.nodesOf(currentWindowIndex).filter {
            it.index !in hidden && matchesFilter(it)
        }
        if (source.isEmpty()) {
            rows.addView(TextView(context).apply {
                text = context.getString(R.string.float_rpa_analyze_hierarchy_empty)
                setTextColor(secondaryText)
                textSize = 12f
                setPadding(dp(12), dp(16), dp(12), dp(16))
            })
            return
        }
        source.forEach { node ->
            val row = TextView(context).apply {
                text = "  ".repeat(node.depth.coerceAtMost(10)) + node.label
                setTextColor(primaryText)
                textSize = 12f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6) + dp(10) * node.depth.coerceAtMost(10), dp(6), dp(6), dp(6))
                background = if (selected?.index == node.index) {
                    roundedBackground(Color.parseColor("#FFF3CD"), 6)
                } else {
                    null
                }
                isClickable = true
                setOnClickListener {
                    selected = node
                    canvasView.invalidate()
                    refreshRowSelection()
                    scroll.post {
                        smoothScrollToRow(scroll, this)
                    }
                    showDetail(node)
                }
            }
            hierarchyRowViews[node.index] = row
            rows.addView(row, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ))
        }
        val target = focus ?: selected
        if (target != null) {
            rows.post {
                hierarchyRowViews[target.index]?.let { row ->
                    smoothScrollToRow(scroll, row)
                }
            }
        }
    }

    private fun smoothScrollToRow(scroll: ScrollView, row: View) {
        val y = (row.top - dp(8)).coerceAtLeast(0)
        scroll.smoothScrollTo(0, y)
    }

    private fun refreshRowSelection() {
        val idx = selected
        hierarchyRowViews.forEach { (nodeIndex, row) ->
            row.background = if (idx?.index == nodeIndex) {
                roundedBackground(Color.parseColor("#FFF3CD"), 6)
            } else {
                null
            }
        }
    }

    private fun dismissSheet() {
        sheetView?.let { removeView(it) }
        sheetView = null
        hierarchyScroll = null
        hierarchyRows = null
        hierarchyRowViews.clear()
        filterChips.clear()
    }

    // ----------------------------------------------------------------- window

    private fun showWindowPicker() {
        val idx = index ?: return
        if (idx.windows.size <= 1) {
            toast(R.string.float_rpa_analyze_only_one_window)
            return
        }
        dismissMenu()
        dismissDetail()
        dismissSheet()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(sheetBackgroundColor, 14)
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.float_rpa_analyze_windows_title)
            setTextColor(primaryText)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(closeButton { dismissSheet() })
        card.addView(header)

        val scroll = ScrollView(context)
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(8))
        }
        val snap = snapshot
        idx.windows.forEach { window ->
            val state = if (snap != null && window.index == snap.activeWindowIndex) {
                context.getString(R.string.float_rpa_analyze_window_foreground)
            } else {
                context.getString(R.string.float_rpa_analyze_window_background)
            }
            val count = idx.nodesOf(window.index).size
            val summary = context.getString(
                R.string.float_rpa_analyze_subtitle,
                window.label + state,
                count,
            )
            rows.addView(TextView(context).apply {
                text = summary
                setTextColor(primaryText)
                textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = if (window.index == currentWindowIndex) {
                    roundedBackground(Color.parseColor("#E8F0FE"), 8)
                } else {
                    null
                }
                isClickable = true
                setOnClickListener {
                    currentWindowIndex = window.index
                    selected = null
                    dismissSheet()
                    dismissDetail()
                    updateChrome()
                    canvasView.invalidate()
                }
            }, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ))
        }
        scroll.addView(rows)
        card.addView(scroll, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        sheetView = card
        addView(card, sheetParams(heightFraction = 0.45f))
    }

    private fun sheetParams(heightFraction: Float): LayoutParams {
        val parentWidth = if (width > 0) width else displayWidth
        val parentHeight = if (height > 0) height else displayHeight
        val sheetWidth = min(parentWidth - dp(16), dp(520)).coerceAtLeast(dp(240))
        val maxHeight = (parentHeight - statusBarHeight - dp(72)).coerceAtLeast(dp(220))
        val sheetHeight = (parentHeight * heightFraction)
            .roundToInt()
            .coerceIn(dp(220), maxHeight)
        return LayoutParams(sheetWidth, sheetHeight).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        }
    }

    private fun closeButton(onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = context.getString(R.string.float_rpa_analyze_close_symbol)
            setTextColor(secondaryText)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), dp(4))
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------------ misc

    private fun hideNode(node: RpaAnalyzeNode) {
        val set = hiddenByWindow.getOrPut(currentWindowIndex) { mutableSetOf() }
        set.add(node.index)
        if (selected?.index == node.index) selected = null
        updateChrome()
        canvasView.invalidate()
    }

    private fun hideSameBounds(node: RpaAnalyzeNode) {
        val idx = index ?: return
        val set = hiddenByWindow.getOrPut(currentWindowIndex) { mutableSetOf() }
        idx.nodesOf(currentWindowIndex)
            .filter { idx.sameBounds(it, node) }
            .forEach { set.add(it.index) }
        if (selected?.index == node.index) selected = null
        updateChrome()
        canvasView.invalidate()
    }

    private fun restoreHidden() {
        if (hiddenByWindow.remove(currentWindowIndex) != null) {
            updateChrome()
            canvasView.invalidate()
        }
    }

    private fun copyText(label: String, value: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            toast(R.string.float_rpa_analyze_copy_failed)
            return
        }
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(label, value))
        }.onSuccess {
            toast(R.string.float_rpa_analyze_copy_ok)
        }.onFailure {
            toast(R.string.float_rpa_analyze_copy_failed)
        }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun resolveStatusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private companion object {
        const val MAX_LABELS = 80
    }
}
