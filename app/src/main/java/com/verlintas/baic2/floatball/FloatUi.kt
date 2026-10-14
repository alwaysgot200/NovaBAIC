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
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.verlintas.baic2.R

/**
 * 悬浮窗的全部视觉构建：悬浮球、单击扇形菜单、分屏问答面板。
 *
 * 只造视图不碰窗口：手势/窗口参数由 [FloatBallService] 接线，
 * 这里保证任何 View 都不带业务状态，方便独立调整视觉。
 */
internal object FloatUi {

    const val BALL_SIZE_DP = 56
    const val MARGIN_DP = 8

    /** 扇形子球：子球直径 / 轨道半径（子球中心距主球中心 = 2 倍主球直径）。 */
    const val FAN_CHILD_DP = 46
    const val FAN_ORBIT_DP = 2 * BALL_SIZE_DP // 112dp
    const val FAN_EDGE_MARGIN_DP = 8
    const val FAN_STAGGER_MS = 30L
    const val FAN_ANIM_MS = 160L

    /** 分屏面板高度占屏幕比例的上下限。 */
    const val SPLIT_MIN_RATIO = 0.30f
    const val SPLIT_MAX_RATIO = 0.88f
    const val SPLIT_DEFAULT_RATIO = 0.55f

    private val BALL_COLOR = 0xFF2E5FD0.toInt()
    private val PANEL_COLOR = 0xF214161C.toInt()
    private val RECORD_COLOR = 0xFFD0342C.toInt()

    /** 单次参谋中：主球去饱和变色 + 灰白蒙层。 */
    private val BUSY_COLOR = 0xFF7C8696.toInt()
    private const val BUSY_SCRIM = 0x55FFFFFF

    /** 悬浮球三态：常态 / 忙碌（旋转环）/ 录制（红球+计数徽章）。 */
    enum class BallMode { NORMAL, BUSY, RECORDING }

    /** 可拖动的悬浮球；触摸手势由服务层绑定。窗口尺寸=视觉尺寸（中心即可点击）。 */
    fun buildBall(context: Context): BallView {
        val size = dp(context, BALL_SIZE_DP)
        // 与应用图标共用同一矢量 logo；矢量内容占视口约 52%，故 logo 视图
        // 放大到 72dp 居中（父容器裁剪），使羽扇在 56dp 球内视觉占比约 2/3。
        // 圆形底色单独由 56dp 的圆盘承担，避免底色随 logo 放大溢出球体。
        val logoSize = dp(context, 72)
        val disc = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_COLOR)
                setStroke(dp(context, 1), 0x33FFFFFF)
            }
        }
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.ic_logo_advisor)
            scaleType = ImageView.ScaleType.FIT_CENTER
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
        // 单次参谋态的灰白蒙层（盖在变色球体上、旋转环之下）。
        val scrim = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BUSY_SCRIM)
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
        }
        val root = FrameLayout(context).apply {
            // 不设 elevation: overlay 的 surfaceInsets 会让触摸坐标与视觉
            // 整体偏移 (阴影像素被计入窗口), 立体感由描边承担。
            addView(disc, FrameLayout.LayoutParams(size, size))
            addView(logo, FrameLayout.LayoutParams(logoSize, logoSize).apply {
                gravity = Gravity.CENTER
            })
            addView(scrim, FrameLayout.LayoutParams(size, size))
            addView(spinner, FrameLayout.LayoutParams(size, size))
            addView(badge)
        }
        badge.layoutParams = (badge.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.END
            width = dp(context, 20)
            height = dp(context, 20)
            setMargins(0, dp(context, 1), dp(context, 1), 0)
        }
        return BallView(root, disc, scrim, spinner, badge)
    }

    /** 悬浮球句柄：服务层按状态机切换外观。 */
    class BallView(
        val root: FrameLayout,
        private val disc: View,
        private val scrim: View,
        private val spinner: View,
        private val badge: TextView,
    ) {
        private var spinning = false

        fun setMode(mode: BallMode, context: Context) {
            when (mode) {
                BallMode.NORMAL -> {
                    spinning = false
                    spinner.visibility = View.GONE
                    scrim.visibility = View.GONE
                    (disc.background as? GradientDrawable)?.setColor(BALL_COLOR)
                    badge.visibility = View.GONE
                }
                BallMode.BUSY -> {
                    spinning = true
                    (disc.background as? GradientDrawable)?.setColor(BUSY_COLOR)
                    scrim.visibility = View.VISIBLE
                    spinner.visibility = View.VISIBLE
                    badge.visibility = View.GONE
                    spinLoop()
                }
                BallMode.RECORDING -> {
                    spinning = false
                    spinner.visibility = View.GONE
                    scrim.visibility = View.GONE
                    (disc.background as? GradientDrawable)?.setColor(RECORD_COLOR)
                    // 徽章可见性只由 setBadge() 按未读数管理，切换录制态不清零。
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

    /** 扇形菜单的一个子球动作。 */
    class FanItem(val labelRes: Int, val action: () -> Unit)

    /** 扇形朝向：主球贴屏幕左缘向右展开、贴右缘向左展开（开口恒朝屏幕中央）。 */
    enum class FanFacing { RIGHT, LEFT }

    /**
     * 单击展开的扇形子球菜单：6 个子球围绕球心、在水平中轴 ±75°（共 150°）
     * 内均匀分度（相邻 30°）；只向屏幕中央一侧（[facing]）展开，轨道半径
     * = 2 倍主球直径。球贴上下边缘、纯水平会导致子球出屏时，中轴向屏内
     * 压最小倾角（[FanMenu.place] 重算），常态视觉始终是纯粹的「或左或右」。
     *
     * 窗口高度包络整个上下分度，纵向位置钳制在屏内；子球与主球相对固定，
     * 拖动时服务层调 [FanMenu.place] 同步。点击窗口空白处收起；子球从球心
     * 错峰回弹弹出，收起时反向缩回。
     */
    fun buildFanMenu(
        context: Context,
        items: List<FanItem>,
        facing: FanFacing,
        ballCenterX: Int,
        ballCenterY: Int,
        screenWidth: Int,
        screenHeight: Int,
        onDismiss: () -> Unit,
    ): FanMenu {
        val child = dp(context, FAN_CHILD_DP)
        val orbit = dp(context, FAN_ORBIT_DP).toFloat()
        val margin = dp(context, FAN_EDGE_MARGIN_DP).toFloat()
        val halfChild = child / 2f

        // 只向朝向一侧伸展：宽 = 轨道 + 子球半径 + 两侧边距。
        val width = (orbit + halfChild + margin * 2f).toInt()
        // 高 = 2 ×（轨道在 75° 的上下投影 + 子球半径）+ 边距。
        val halfSpan = orbit * kotlin.math.sin(Math.toRadians(75.0)).toFloat() + halfChild
        val height = (halfSpan * 2f + margin * 2f).toInt()

        // 6 球在中轴 ±75° 内均匀分度（相邻 30°）。
        val offsets = listOf(-75.0, -45.0, -15.0, 15.0, 45.0, 75.0)

        val root = FrameLayout(context).apply {
            setOnClickListener { onDismiss() }
        }

        val children = items.zip(offsets).mapIndexed { index, (item, offset) ->
            val view = TextView(context).apply {
                text = context.getString(item.labelRes)
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setShadowLayer(dp(context, 3).toFloat(), 0f, dp(context, 1).toFloat(), 0xCC000000.toInt())
                elevation = dp(context, 8).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PANEL_COLOR)
                    setStroke(dp(context, 1), 0x885B86E5.toInt())
                }
                layoutParams = FrameLayout.LayoutParams(child, child)
                setOnClickListener { onDismiss(); item.action() }
            }
            root.addView(view)
            view.scaleX = 0.6f
            view.scaleY = 0.6f
            FanChild(view, offset, index)
        }

        return FanMenu(
            root = root,
            widthPx = width,
            heightPx = height,
            facing = facing,
            children = children,
            orbit = orbit,
            halfChild = halfChild,
            margin = margin,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        ).also { it.place(ballCenterX, ballCenterY) }
    }

    internal class FanChild(
        val view: TextView,
        val offsetDeg: Double,
        val index: Int,
        var dirX: Float = 0f,
        var dirY: Float = 0f,
    )

    /** 扇形菜单句柄：root 进窗口；[place] 按球心位置重算子球布局与窗口坐标。 */
    class FanMenu internal constructor(
        val root: FrameLayout,
        val widthPx: Int,
        val heightPx: Int,
        private val facing: FanFacing,
        private val children: List<FanChild>,
        private val orbit: Float,
        private val halfChild: Float,
        private val margin: Float,
        private val screenWidth: Int,
        private val screenHeight: Int,
    ) {
        private var expanded = false

        /** 窗口左上角屏幕坐标（[place] 后有效）。 */
        var windowX = 0
            private set
        var windowY = 0
            private set

        /**
         * 按主球当前屏坐标重算：倾角（纯水平优先、贴边保护）、窗口位置
         * （钳制在屏内）与各子球 margin。展开前把子球摆在球心（等弹出）。
         */
        fun place(ballCenterX: Int, ballCenterY: Int) {
            val tilt = axisTiltDeg(ballCenterY.toFloat())
            // 顶点（球心）在窗口内的位置：朝向侧在窗口对侧边缘。
            val anchorX = if (facing == FanFacing.RIGHT) margin else widthPx - margin
            windowX = (ballCenterX - anchorX).toInt()
                .coerceIn(0, (screenWidth - widthPx).coerceAtLeast(0))
            windowY = (ballCenterY - heightPx / 2f).toInt()
                .coerceIn(0, (screenHeight - heightPx).coerceAtLeast(0))
            val anchorY = ballCenterY - windowY

            children.forEach { child ->
                val angle = Math.toRadians(tilt + child.offsetDeg)
                val radialX = kotlin.math.cos(angle).toFloat()
                child.dirX = if (facing == FanFacing.RIGHT) radialX else -radialX
                child.dirY = kotlin.math.sin(angle).toFloat() // 屏幕坐标 y 向下
                (child.view.layoutParams as FrameLayout.LayoutParams).apply {
                    leftMargin = (anchorX + child.dirX * orbit - halfChild).toInt()
                    topMargin = (anchorY + child.dirY * orbit - halfChild).toInt()
                }
                if (!expanded) {
                    // 初始缩在球心；expand 动画归零即「弹出」。
                    child.view.translationX = -child.dirX * orbit
                    child.view.translationY = -child.dirY * orbit
                }
            }
        }

        /**
         * 中轴倾角（度）：0 = 纯水平。仅当水平扇形的上下包子球超出屏幕
         * 时，以 1° 步进向屏幕内侧压，取能完整容纳的最小倾角。
         */
        private fun axisTiltDeg(ballCenterY: Float): Double {
            var tilt = 0.0
            while (tilt > -90.0 && tilt < 90.0) {
                var top = Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE
                children.forEach {
                    val sy = orbit * kotlin.math.sin(Math.toRadians(tilt + it.offsetDeg)).toFloat()
                    if (sy < top) top = sy
                    if (sy > bottom) bottom = sy
                }
                val topBad = ballCenterY + top - halfChild < margin
                val bottomBad = ballCenterY + bottom + halfChild > screenHeight - margin
                if (!topBad && !bottomBad) break
                tilt += if (topBad) 1.0 else -1.0
            }
            return tilt
        }

        /** 子球从球心错峰回弹弹出。 */
        fun expand() {
            expanded = true
            val overshoot = android.view.animation.OvershootInterpolator(0.9f)
            children.forEach { child ->
                child.view.postDelayed(
                    {
                        child.view.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setInterpolator(overshoot)
                            .setDuration(FAN_ANIM_MS)
                            .start()
                    },
                    child.index * FAN_STAGGER_MS,
                )
            }
        }

        /** 子球缩回球心（服务层随后移除窗口）。 */
        fun collapse() {
            expanded = false
            children.forEach { child ->
                child.view.animate()
                    .translationX(-child.dirX * orbit)
                    .translationY(-child.dirY * orbit)
                    .scaleX(0.6f)
                    .scaleY(0.6f)
                    .alpha(0f)
                    .setDuration(FAN_ANIM_MS)
                    .start()
            }
        }
    }

    /**
     * 底部锚定的分屏问答面板。上沿是拖动把手（调高度），正文区由服务层
     * 挂载 [TranscriptView]，底部是「新对话」同款输入行（输入框/语音/
     * ＋号/发送-停止），由服务层接线行为。
     */
    fun buildSplitPanel(
        context: Context,
        onClose: () -> Unit,
        inputCallbacks: InputBarCallbacks,
    ): SplitPanel {
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

        val inputBar = buildInputBar(context, inputCallbacks)

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
            addView(inputBar)
        }
        return SplitPanel(root, handle, transcriptContainer, inputBar)
    }

    /** 分屏面板句柄：服务层操作所需的句柄集合。 */
    class SplitPanel(
        val root: LinearLayout,
        val handle: View,
        val transcriptContainer: FrameLayout,
        val inputBar: LinearLayout,
    )

    /** 「新对话」同款输入行的行为接线（由服务层实现）。 */
    interface InputBarCallbacks {
        /** 发送文本（空文本不回调）。 */
        fun onSend(text: String)

        /** 开始语音输入（结果由实现方追加到输入框）。 */
        fun onVoice()

        /** ＋号：引导去主界面完整会话。 */
        fun onPlus()

        /** 停止当前正在进行的回答（思考中发送键变为停止）。 */
        fun onStop()

        /** 输入框被触摸：服务层需先把窗口切为可聚焦并呼出键盘。 */
        fun onInputTouch()

        /** 输入框焦点变化：true=获得（记录避让基准），false=失去（还原）。 */
        fun onInputFocus(focused: Boolean)

        /** 思考态查询：true=LLM 正在回答（发送键显示为停止）。 */
        fun isBusy(): Boolean
    }

    /**
     * 「新对话」同款输入行：输入框（最多 6 行自动增高）+ 语音 + ＋号 +
     * 发送/停止。发送键在思考中变为停止键（符合"思考中的输入默认排除、
     * 可打断"的交互约定）；文字始终保留在输入框，不会被静默丢弃。
     */
    fun buildInputBar(context: Context, callbacks: InputBarCallbacks): LinearLayout {
        fun actionButton(iconRes: Int, descRes: Int, onClick: () -> Unit) = ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = context.getString(descRes)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener { onClick() }
        }

        val field = EditText(context).apply {
            hint = context.getString(R.string.float_input_hint)
            setHintTextColor(0x66FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(0x22FFFFFF.toInt())
            }
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            maxLines = 4
            isVerticalScrollBarEnabled = true
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnFocusChangeListener { _, hasFocus -> callbacks.onInputFocus(hasFocus) }
            // overlay 窗口默认不可聚焦: 触摸输入框瞬间先请服务层切窗口
            // flag + 呼出键盘, 再放行让 EditText 正常拿到焦点。
            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    callbacks.onInputTouch()
                }
                false
            }
        }

        val sendStop = ImageButton(context).apply {
            contentDescription = context.getString(R.string.float_input_send)
            setImageResource(R.drawable.fb_ic_send)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_COLOR)
            }
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        fun refreshSendStop() {
            val busy = callbacks.isBusy()
            sendStop.setImageResource(if (busy) R.drawable.fb_ic_stop else R.drawable.fb_ic_send)
            sendStop.contentDescription = context.getString(
                if (busy) R.string.float_input_stop else R.string.float_input_send,
            )
        }
        refreshSendStop()

        sendStop.setOnClickListener {
            if (callbacks.isBusy()) {
                callbacks.onStop()
            } else {
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) {
                    callbacks.onSend(text)
                    field.setText("")
                }
            }
            refreshSendStop()
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 8))
            addView(field)
            addView(
                actionButton(R.drawable.fb_ic_mic, R.string.float_input_voice) { callbacks.onVoice() },
            )
            addView(
                actionButton(R.drawable.fb_ic_plus, R.string.float_input_plus) { callbacks.onPlus() },
            )
            addView(sendStop)
            tag = InputBarHandle(field, ::refreshSendStop)
        }
    }

    /** 输入行句柄：服务层刷新思考态/追加语音文本用。 */
    class InputBarHandle(
        val field: EditText,
        val refresh: () -> Unit,
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
