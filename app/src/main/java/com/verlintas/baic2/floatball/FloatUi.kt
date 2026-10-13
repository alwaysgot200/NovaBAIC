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

package com.verlintas.baic2.floatball

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.verlintas.baic2.R

/**
 * 悬浮窗的全部视觉构建：悬浮球、长按菜单、分屏问答面板。
 *
 * 只造视图不碰窗口：手势/窗口参数由 [FloatBallService] 接线，
 * 这里保证任何 View 都不带业务状态，方便独立调整视觉。
 */
internal object FloatUi {

    const val BALL_SIZE_DP = 56
    const val BALL_LABEL = "AI"
    const val REC_LABEL = "●"
    const val MARGIN_DP = 8

    /** 扇形子球：子球直径 / 环绕半径（均 dp）。 */
    const val FAN_CHILD_DP = 46
    const val FAN_ORBIT_DP = 96
    const val FAN_STAGGER_MS = 30L
    const val FAN_ANIM_MS = 160L

    /** 分屏面板高度占屏幕比例的上下限。 */
    const val SPLIT_MIN_RATIO = 0.30f
    const val SPLIT_MAX_RATIO = 0.88f
    const val SPLIT_DEFAULT_RATIO = 0.55f

    private val BALL_COLOR = 0xFF2E5FD0.toInt()
    private val PANEL_COLOR = 0xF214161C.toInt()
    private val RECORD_COLOR = 0xFFD0342C.toInt()

    /** 悬浮球三态：常态 / 忙碌（旋转环）/ 录制（红球+计数徽章）。 */
    enum class BallMode { NORMAL, BUSY, RECORDING }

    /** 可拖动的悬浮球；触摸手势由服务层绑定。 */
    fun buildBall(context: Context): BallView {
        val label = TextView(context).apply {
            text = BALL_LABEL
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_COLOR)
                setStroke(dp(context, 1), 0x33FFFFFF)
            }
        }
        val spinner = View(context).apply {
            background = android.graphics.drawable.ShapeDrawable(
                android.graphics.drawable.shapes.ArcShape(-45f, 300f),
            ).apply {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = dp(context, 3).toFloat()
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.color = Color.WHITE
            }
            visibility = View.GONE
        }
        val badge = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFC107.toInt())
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(context, 20), dp(context, 20)).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(context, 2), dp(context, 2), 0)
            }
        }
        val size = dp(context, BALL_SIZE_DP)
        val root = FrameLayout(context).apply {
            elevation = dp(context, 6).toFloat()
            addView(label, FrameLayout.LayoutParams(size, size))
            addView(
                spinner,
                FrameLayout.LayoutParams(size + dp(context, 10), size + dp(context, 10)),
            )
            addView(badge)
        }
        spinner.layoutParams = (spinner.layoutParams as FrameLayout.LayoutParams).apply {
            setMargins(-dp(context, 5), -dp(context, 5), 0, 0)
        }
        return BallView(root, label, spinner, badge)
    }

    /** 悬浮球句柄：服务层按状态机切换外观。 */
    class BallView(
        val root: FrameLayout,
        private val label: TextView,
        private val spinner: View,
        private val badge: TextView,
    ) {
        private var spinning = false

        fun setMode(mode: BallMode, context: Context) {
            when (mode) {
                BallMode.NORMAL -> {
                    spinning = false
                    spinner.visibility = View.GONE
                    (label.background as? GradientDrawable)?.setColor(BALL_COLOR)
                    label.text = BALL_LABEL
                    badge.visibility = View.GONE
                }
                BallMode.BUSY -> {
                    spinning = true
                    spinner.visibility = View.VISIBLE
                    (label.background as? GradientDrawable)?.setColor(BALL_COLOR)
                    badge.visibility = View.GONE
                    spinLoop()
                }
                BallMode.RECORDING -> {
                    spinning = false
                    spinner.visibility = View.GONE
                    (label.background as? GradientDrawable)?.setColor(RECORD_COLOR)
                    label.text = REC_LABEL
                    badge.visibility = View.GONE
                }
            }
        }

        /** 连续模式红点计数：0 隐藏，>0 显示次数。 */
        fun setBadge(count: Int) {
            if (count <= 0) {
                badge.visibility = View.GONE
            } else {
                badge.visibility = View.VISIBLE
                badge.text = if (count > 99) "99+" else count.toString()
            }
        }

        private fun spinLoop() {
            if (!spinning) {
                spinner.rotation = 0f
                return
            }
            spinner.animate()
                .rotationBy(120f)
                .setDuration(220L)
                .withEndAction { spinner.post { spinLoop() } }
                .start()
        }
    }

    /** 扇形菜单窗口的边长（px）：服务层据此定位窗口。 */
    fun fanSidePx(context: Context): Int = 2 * (
        dp(context, FAN_ORBIT_DP) + dp(context, FAN_CHILD_DP) / 2
        ) + dp(context, 8)

    /** 扇形菜单的一个子球动作。 */
    class FanItem(val labelRes: Int, val action: () -> Unit)

    /**
     * 长按展开的扇形子球菜单：5 个子球围绕悬浮球（悬浮球窗口本身不动，
     * 本窗口以球心为中心叠加在其上），点击空白处收起。子球从球心错峰
     * 弹出，收起时反向缩回。
     */
    fun buildFanMenu(context: Context, items: List<FanItem>, onDismiss: () -> Unit): FanMenu {
        val child = dp(context, FAN_CHILD_DP)
        val orbit = dp(context, FAN_ORBIT_DP)
        val side = 2 * (orbit + child / 2) + dp(context, 8)
        val center = side / 2f

        // 六个方位：左上、右上、左、右、左下、右下（屏幕坐标，y 向下）。
        val directions = listOf(
            -0.72f to -0.72f,
            0.72f to -0.72f,
            -1f to 0f,
            1f to 0f,
            -0.72f to 0.72f,
            0.72f to 0.72f,
        )

        val root = FrameLayout(context).apply {
            setOnClickListener { onDismiss() }
        }

        val children = items.zip(directions).mapIndexed { index, (item, dir) ->
            val centerX = center + dir.first * orbit
            val centerY = center + dir.second * orbit
            val fromX = center - centerX
            val fromY = center - centerY
            val view = TextView(context).apply {
                text = context.getString(item.labelRes)
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                elevation = dp(context, 8).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PANEL_COLOR)
                    setStroke(dp(context, 1), 0x662E5FD0.toInt())
                }
                layoutParams = FrameLayout.LayoutParams(child, child).apply {
                    leftMargin = (centerX - child / 2).toInt()
                    topMargin = (centerY - child / 2).toInt()
                }
                setOnClickListener { onDismiss(); item.action() }
            }
            root.addView(view)
            // 初始位移 = 球心相对自身的偏移；expand 时动画归零即"弹出"。
            view.translationX = fromX
            view.translationY = fromY
            view.alpha = 0f
            FanChild(view, fromX, fromY, index)
        }

        return FanMenu(root, side) { expand ->
            children.forEach { child ->
                child.view.postDelayed(
                    {
                        child.view.animate()
                            .translationX(if (expand) 0f else child.fromX)
                            .translationY(if (expand) 0f else child.fromY)
                            .alpha(if (expand) 1f else 0f)
                            .setDuration(FAN_ANIM_MS)
                            .start()
                    },
                    if (expand) child.index * FAN_STAGGER_MS else 0L,
                )
            }
        }
    }

    private class FanChild(val view: TextView, val fromX: Float, val fromY: Float, val index: Int)

    /** 扇形菜单句柄：root 进窗口，side 供服务层定位窗口。 */
    class FanMenu(
        val root: FrameLayout,
        val sidePx: Int,
        animateChildren: (expand: Boolean) -> Unit,
    ) {
        private val animate = animateChildren

        /** 子球从球心错峰弹出。 */
        fun expand() = animate(true)

        /** 子球缩回球心（服务层随后移除窗口）。 */
        fun collapse() = animate(false)
    }

    /**
     * 底部锚定的分屏问答面板。上沿是拖动把手（调高度），正文区由服务层
     * 挂载 [TranscriptView] 渲染「老参谋」会话转录。
     */
    fun buildSplitPanel(context: Context, onClose: () -> Unit): SplitPanel {
        val handle = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 26),
            )
            addView(View(context).apply {
                background = rounded(context, 0x66FFFFFF, 2)
                layoutParams = FrameLayout.LayoutParams(dp(context, 56), dp(context, 4), Gravity.CENTER)
            })
        }

        val transcriptContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 8))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(context, 20).toFloat(), dp(context, 20).toFloat(),
                    dp(context, 20).toFloat(), dp(context, 20).toFloat(),
                    0f, 0f, 0f, 0f,
                )
                setColor(PANEL_COLOR)
            }
            elevation = dp(context, 12).toFloat()

            addView(handle)

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 16), dp(context, 4), dp(context, 8), dp(context, 8))
                addView(TextView(context).apply {
                    text = context.getString(R.string.float_ask_title)
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = context.getString(R.string.float_ask_close)
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 16f
                    setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 4))
                    setOnClickListener { onClose() }
                })
            }
            addView(header)
            addView(divider(context))
            addView(transcriptContainer)
        }
        return SplitPanel(root, handle, transcriptContainer)
    }

    /** 服务层操作分屏面板所需的句柄集合。 */
    class SplitPanel(
        val root: LinearLayout,
        val handle: View,
        val transcriptContainer: FrameLayout,
    )

    private fun divider(context: Context): View = View(context).apply {
        background = GradientDrawable().apply { setColor(0x26FFFFFF) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun rounded(context: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(color)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
