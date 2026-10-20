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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.verlintas.baic2.R
import kotlin.math.abs

/**
 * 悬浮窗的全部视觉构建：悬浮球、单击扇形菜单、分屏问答面板。
 *
 * 只造视图不碰窗口：手势/窗口参数由 [FloatBallService] 接线，
 * 这里保证任何 View 都不带业务状态，方便独立调整视觉。
 */
internal object FloatUi {

    // 主球取桌面启动器图标（实测约 55dp）的 80%，视觉上不与 app 图标争大小。
    const val BALL_SIZE_DP = 44
    const val MARGIN_DP = 8

    /**
     * 扇形子球：子球直径 / 轨道半径（子球中心距主球中心 = 1.5 倍主球直径）。
     * 几何约束：6 球在 ±75° 内 30° 分度，轨道 66dp 时相邻球心间距
     * 2×66×sin15°≈34dp，故子球取 32dp（相邻净距约 2dp，再大即重叠）。
     */
    const val FAN_CHILD_DP = 32
    const val FAN_ORBIT_DP = 3 * BALL_SIZE_DP / 2 // 66dp
    const val FAN_EDGE_MARGIN_DP = 8
    const val FAN_STAGGER_MS = 30L
    const val FAN_ANIM_MS = 160L

    /** 分屏面板高度占屏幕比例的上下限。 */
    const val SPLIT_MIN_RATIO = 0.30f
    const val SPLIT_MAX_RATIO = 0.88f
    const val SPLIT_DEFAULT_RATIO = 0.55f

    /** 紧凑顶栏（拖动热区/标题/方向/自动/关闭单行）高度。 */
    const val HEADER_HEIGHT_DP = 38

    /** 方向 chip 未选/已选底色；下拉菜单强调色。 */
    private val DIRECTION_CHIP_OFF = 0x22FFFFFF.toInt()
    private val DIRECTION_CHIP_ON = 0x552E5FD0.toInt()
    private val MENU_ACCENT = 0xFF9DB8F0.toInt()
    private val MENU_DANGER = 0xFFFF8A80.toInt()

    /** AI 建议方向的最多展示条数；自定义方向的字符上限。 */
    const val DIRECTION_MENU_MAX = 5
    const val DIRECTION_MAX_CHARS = 24

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
        // 球内专用彩色扇标（48 视口、全实色）：与应用图标 ic_logo_advisor
        // 同形（五羽尖白扇面/金柄/柔褶/青星），两外羽尖逼近圆盘描边
        // （扇幅约占球径 95%）。42dp 居中，四边各留 1dp。
        val logoSize = dp(context, 42)
        val disc = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_COLOR)
                setStroke(dp(context, 1), 0x33FFFFFF)
            }
        }
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.fb_ic_logo_ball)
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

    /**
     * 扇形菜单整体拖动回调（服务层接线）：菜单窗口展开后与主球一起移动，
     * [onDragBy] 的位移是相对手指按下点的累计量，服务层据此平移主球窗口
     * 并让扇形跟随（[FloatUi.FanMenu.place]）。
     */
    interface FanDragHandler {
        fun onDragStart()
        fun onDragBy(totalDx: Float, totalDy: Float)
        fun onDragEnd()
    }

    /**
     * 扇形菜单根容器：菜单窗口 z-order 在主球之上且矩形覆盖主球朝向侧，
     * 若空白区域只靠 click 消费会把「按住主球拖动」的手势整个吃掉（时灵
     * 时不灵的根源）。此处统一接管：子球上的移动超 slop 后拦截为整体拖动
     * （子球收 CANCEL 不误触），空白处按下即进拖动判定；未发生拖动的
     * 点按走正常 click（=点空白收起），拖过的 up 直接吞掉防止拖完收起。
     */
    private class FanDragLayout(context: Context) : FrameLayout(context) {
        var handler: FanDragHandler? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_MOVE && !dragging) {
                if (abs(ev.rawX - downRawX) > slop || abs(ev.rawY - downRawY) > slop) {
                    dragging = true
                    handler?.onDragStart()
                }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (abs(ev.rawX - downRawX) > slop || abs(ev.rawY - downRawY) > slop)
                    ) {
                        dragging = true
                        handler?.onDragStart()
                    }
                    if (dragging) {
                        handler?.onDragBy(ev.rawX - downRawX, ev.rawY - downRawY)
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        handler?.onDragEnd()
                        return true
                    }
                }
            }
            return super.onTouchEvent(ev)
        }
    }

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

        val root = FanDragLayout(context).apply {
            setOnClickListener { onDismiss() }
        }

        val children = items.zip(offsets).mapIndexed { index, (item, offset) ->
            val view = TextView(context).apply {
                text = context.getString(item.labelRes)
                setTextColor(Color.WHITE)
                textSize = 10f
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

        /** 接线整体拖动回调（服务层平移主球窗口+本菜单跟随）。 */
        fun setDragHandler(handler: FanDragHandler?) {
            (root as? FanDragLayout)?.handler = handler
        }

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
     * 底部锚定的分屏问答面板。顶部是 **38dp 单行 headerRow**（同时作为拖动
     * 调高热区）：握把 │「老参谋」│「方向 ▾」chip │ 弹性空白 │「自动」
     * （本期置灰占位）│「✕」；其下是压缩到 24dp 的联系人状态条；正文区由
     * 服务层挂载 [TranscriptView]，底部是「新对话」同款输入行。
     */
    fun buildSplitPanel(
        context: Context,
        onClose: () -> Unit,
        onDirection: () -> Unit,
        onAuto: () -> Unit,
        onSmalltalk: () -> Unit,
        inputCallbacks: InputBarCallbacks,
        draftCallbacks: DraftStripCallbacks,
    ): SplitPanel {
        val transcriptContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 8))
        }

        // 联系人状态条（§7）：显示当前绑定联系人，点击打开画像管理页。
        // 顶栏紧凑化后压到单行 24dp；文案/点击由服务层随 activeContact 更新。
        val statusBar = TextView(context).apply {
            text = context.getString(R.string.float_profile_status_unbound)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 4))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

        // 方向 chip 在 header 外声明（apply 块外的 SplitPanel 需要引用）。
        val chip = TextView(context).apply {
            text = context.getString(R.string.float_dir_chip)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12.5f
            gravity = Gravity.CENTER
            maxLines = 1
            maxWidth = dp(context, 150)
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14).toFloat()
                setColor(DIRECTION_CHIP_OFF)
            }
            setPadding(dp(context, 10), dp(context, 3), dp(context, 10), dp(context, 3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(context, 4) }
            setOnClickListener { onDirection() }
        }

        // 整行即拖动把手：非按钮子 View（握把/标题/空白）不消费触摸，
        // 服务层把 resize listener 挂在 headerRow 上即可；chip/自动/✕ 各自
        // clickable，点击不会误触发拖动。
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, HEADER_HEIGHT_DP),
            )
            setPadding(dp(context, 12), 0, dp(context, 6), 0)

            addView(ImageView(context).apply {
                setImageResource(R.drawable.fb_ic_drag)
                alpha = 0.55f
                contentDescription = context.getString(R.string.float_split_drag_desc)
                layoutParams = LinearLayout.LayoutParams(dp(context, 16), dp(context, 16)).apply {
                    marginEnd = dp(context, 6)
                }
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.float_ask_title)
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(context, 8) }
            })

            addView(chip)

            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            // M1 保温闲聊手动入口：出一条开场草稿到分屏，绝不自动发送。
            addView(TextView(context).apply {
                text = context.getString(R.string.float_split_smalltalk)
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 12.5f
                setPadding(dp(context, 8), dp(context, 4), dp(context, 4), dp(context, 4))
                setOnClickListener { onSmalltalk() }
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.float_split_auto)
                setTextColor(0x80FFFFFF.toInt())
                textSize = 12.5f
                alpha = 0.45f
                setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
                setOnClickListener { onAuto() }
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.float_ask_close_x)
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 18f
                contentDescription = context.getString(R.string.float_ask_close)
                setPadding(dp(context, 10), dp(context, 2), dp(context, 10), dp(context, 4))
                setOnClickListener { onClose() }
            })
        }

        val inputBar = buildInputBar(context, inputCallbacks)
        val strategyStrip = buildStrategyStrip(context)
        val draftStrip = buildDraftStrip(context, draftCallbacks)

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

            addView(header)
            addView(divider(context))
            addView(statusBar)
            addView(strategyStrip.root)
            addView(transcriptContainer)
            addView(draftStrip.root)
            addView(inputBar)
        }
        return SplitPanel(
            root, header, transcriptContainer, inputBar, statusBar, chip, strategyStrip, draftStrip,
        )
    }

    /** 分屏面板句柄：服务层操作所需的句柄集合。 */
    class SplitPanel(
        val root: LinearLayout,
        val handle: View,
        val transcriptContainer: FrameLayout,
        val inputBar: LinearLayout,
        val statusBar: TextView,
        val directionChip: TextView,
        val strategyStrip: StrategyStrip,
        val draftStrip: DraftStrip,
    )

    /** 策略条回调（P2）：轻确认三键与任务令展开；P3：分叉提议两键 + reroute 三件。服务层接线，视图不带业务。 */
    interface StrategyStripCallbacks {
        fun onProvisionalYes(goalId: Long)
        fun onProvisionalEdit(goalId: Long)
        fun onProvisionalSkip(goalId: Long)
        fun onMissionToggle()
        fun onBranchAccept()
        fun onBranchReject()
        /** 点「路线不对？重规划」（只发起，绝不静默迁移）。 */
        fun onReroute()
        fun onRerouteAccept()
        fun onRerouteReject()
    }

    /** 策略条视图句柄：planning / provisional / branch / reroute / mission 态，默认整条 GONE。 */
    class StrategyStrip(
        val root: LinearLayout,
        val planningRow: TextView,
        val provisionalBox: LinearLayout,
        val provisionalQuestion: TextView,
        val branchBox: LinearLayout,
        val branchQuestion: TextView,
        val rerouteBox: LinearLayout,
        val rerouteQuestion: TextView,
        val rerouteHintRow: TextView,
        val missionBox: LinearLayout,
        val missionHeader: LinearLayout,
        val missionLine: TextView,
        val missionChevron: TextView,
        val missionDetail: LinearLayout,
    ) {
        /** 渲染时随轻确认问句写入的目标 id；非问句态置 null。 */
        internal var pendingGoalId: Long? = null

        /** 服务层接线的回调，每次渲染刷新。 */
        internal var callbacksRef: StrategyStripCallbacks? = null
    }

    private fun buildStrategyStrip(context: Context): StrategyStrip {
        fun stripText(size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(context).apply {
                setTextColor(color)
                textSize = size
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(context, 16), dp(context, 3), dp(context, 16), dp(context, 3))
            }

        fun stripButton(text: String, filled: Boolean): TextView =
            TextView(context).apply {
                this.text = text
                setTextColor(if (filled) Color.WHITE else 0xCCFFFFFF.toInt())
                textSize = 12.5f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(if (filled) DIRECTION_CHIP_ON else 0x22FFFFFF)
                }
                setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5))
                isClickable = true
                selectableBackground(this)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(context, 8) }
            }

        val planningRow = stripText(12.5f, 0x99FFFFFF.toInt())
        val provisionalQuestion = stripText(13f, 0xEEFFFFFF.toInt())
        val yesButton = stripButton(context.getString(R.string.float_mission_yes), filled = true)
        val editButton = stripButton(context.getString(R.string.float_mission_edit), filled = false)
        val skipButton = stripButton(context.getString(R.string.float_mission_skip), filled = false)
        val provisionalButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 6))
            addView(yesButton)
            addView(editButton)
            addView(skipButton)
        }
        val provisionalBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(provisionalQuestion)
            addView(provisionalButtons)
        }

        // P3 分叉提议（branch 边永不自动移动，必须用户两键确认）。
        val branchQuestion = stripText(13f, 0xEEFFFFFF.toInt())
        val branchAccept = stripButton(context.getString(R.string.float_branch_accept), filled = true)
        val branchReject = stripButton(context.getString(R.string.float_branch_reject), filled = false)
        val branchButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 6))
            addView(branchAccept)
            addView(branchReject)
        }
        val branchBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(branchQuestion)
            addView(branchButtons)
        }

        // P3 reroute：待确认条（采用新路线/先不）。
        val rerouteQuestion = stripText(13f, 0xEEFFFFFF.toInt())
        val rerouteAccept = stripButton(context.getString(R.string.float_reroute_accept), filled = true)
        val rerouteReject = stripButton(context.getString(R.string.float_reroute_reject), filled = false)
        val rerouteButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 6))
            addView(rerouteAccept)
            addView(rerouteReject)
        }
        val rerouteBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(rerouteQuestion)
            addView(rerouteButtons)
        }
        // P3 reroute 入口行：连续受挫时出现，可与任务令并存。
        val rerouteHintRow = stripText(12.5f, 0xFFFFD27D.toInt()).apply {
            text = context.getString(R.string.float_reroute_hint)
            setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 6))
            visibility = View.GONE
            isClickable = true
            selectableBackground(this)
        }

        val missionLine = stripText(13f, 0xEEFFFFFF.toInt(), bold = true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val missionChevron = stripText(12f, 0x80FFFFFF.toInt()).apply {
            text = "▸"
        }
        val missionHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            addView(missionLine)
            addView(missionChevron)
        }
        val missionDetail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 6))
        }
        val missionBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(missionHeader)
            addView(missionDetail)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(0x14FFFFFF) }
            addView(planningRow)
            addView(provisionalBox)
            addView(branchBox)
            addView(rerouteBox)
            addView(missionBox)
            addView(rerouteHintRow)
        }
        val strip = StrategyStrip(
            root = root,
            planningRow = planningRow,
            provisionalBox = provisionalBox,
            provisionalQuestion = provisionalQuestion,
            branchBox = branchBox,
            branchQuestion = branchQuestion,
            rerouteBox = rerouteBox,
            rerouteQuestion = rerouteQuestion,
            rerouteHintRow = rerouteHintRow,
            missionBox = missionBox,
            missionHeader = missionHeader,
            missionLine = missionLine,
            missionChevron = missionChevron,
            missionDetail = missionDetail,
        )
        yesButton.setOnClickListener {
            strip.pendingGoalId?.let { strip.callbacksRef?.onProvisionalYes(it) }
        }
        editButton.setOnClickListener {
            strip.pendingGoalId?.let { strip.callbacksRef?.onProvisionalEdit(it) }
        }
        skipButton.setOnClickListener {
            strip.pendingGoalId?.let { strip.callbacksRef?.onProvisionalSkip(it) }
        }
        branchAccept.setOnClickListener { strip.callbacksRef?.onBranchAccept() }
        branchReject.setOnClickListener { strip.callbacksRef?.onBranchReject() }
        rerouteAccept.setOnClickListener { strip.callbacksRef?.onRerouteAccept() }
        rerouteReject.setOnClickListener { strip.callbacksRef?.onRerouteReject() }
        rerouteHintRow.setOnClickListener { strip.callbacksRef?.onReroute() }
        missionHeader.setOnClickListener { strip.callbacksRef?.onMissionToggle() }
        return strip
    }

    /**
     * 按 [ScreenAdvisor.MissionUiState] 渲染策略条：planning → 轻确认问句 → P3 分叉提议 →
     * P3 reroute 待确认 → 任务令；皆无则整条隐藏。reroute 入口行可与任务令并存。
     */
    fun renderStrategyStrip(
        strip: StrategyStrip,
        state: ScreenAdvisor.MissionUiState,
        callbacks: StrategyStripCallbacks,
    ) {
        val context = strip.root.context
        strip.callbacksRef = callbacks
        val mainVisible = when {
            state.planning -> {
                strip.planningRow.visibility = View.VISIBLE
                strip.planningRow.text = context.getString(R.string.float_mission_planning)
                strip.provisionalBox.visibility = View.GONE
                strip.branchBox.visibility = View.GONE
                strip.rerouteBox.visibility = View.GONE
                strip.missionBox.visibility = View.GONE
                true
            }

            state.provisional != null -> {
                strip.pendingGoalId = state.provisional.goalId
                strip.planningRow.visibility = View.GONE
                strip.provisionalBox.visibility = View.VISIBLE
                strip.branchBox.visibility = View.GONE
                strip.rerouteBox.visibility = View.GONE
                strip.missionBox.visibility = View.GONE
                strip.provisionalQuestion.text = context.getString(
                    R.string.float_mission_provisional_q,
                    state.provisional.title,
                )
                true
            }

            state.pendingBranch != null -> {
                strip.pendingGoalId = null
                strip.planningRow.visibility = View.GONE
                strip.provisionalBox.visibility = View.GONE
                strip.branchBox.visibility = View.VISIBLE
                strip.rerouteBox.visibility = View.GONE
                strip.missionBox.visibility = View.GONE
                strip.branchQuestion.text = context.getString(
                    R.string.float_branch_offer,
                    state.pendingBranch.targetLabel,
                )
                true
            }

            state.pendingReroute != null -> {
                strip.pendingGoalId = null
                strip.planningRow.visibility = View.GONE
                strip.provisionalBox.visibility = View.GONE
                strip.branchBox.visibility = View.GONE
                strip.rerouteBox.visibility = View.VISIBLE
                strip.missionBox.visibility = View.GONE
                strip.rerouteQuestion.text = context.getString(
                    R.string.float_reroute_offer,
                    state.pendingReroute.targetLabel,
                )
                true
            }

            state.missionLine != null -> {
                strip.pendingGoalId = null
                strip.planningRow.visibility = View.GONE
                strip.provisionalBox.visibility = View.GONE
                strip.branchBox.visibility = View.GONE
                strip.rerouteBox.visibility = View.GONE
                strip.missionBox.visibility = View.VISIBLE
                strip.missionLine.text = context.getString(
                    R.string.float_mission_line_prefix,
                ) + "：" + state.missionLine
                strip.missionChevron.text = if (state.expanded) "▾" else "▸"
                renderMissionDetail(strip, state)
                true
            }

            else -> {
                strip.pendingGoalId = null
                strip.planningRow.visibility = View.GONE
                strip.provisionalBox.visibility = View.GONE
                strip.branchBox.visibility = View.GONE
                strip.rerouteBox.visibility = View.GONE
                strip.missionBox.visibility = View.GONE
                false
            }
        }
        // reroute 入口行：独立于主态（任务令在时也能点）；待确认条/生成中切忙文案。
        val hintVisible = state.pendingReroute == null && (state.rerouteHint || state.rerouteBusy)
        strip.rerouteHintRow.visibility = if (hintVisible) View.VISIBLE else View.GONE
        strip.rerouteHintRow.text = context.getString(
            if (state.rerouteBusy) R.string.float_reroute_busy else R.string.float_reroute_hint,
        )
        strip.rerouteHintRow.isClickable = !state.rerouteBusy
        strip.root.visibility = if (mainVisible || hintVisible) View.VISIBLE else View.GONE
    }

    private fun renderMissionDetail(strip: StrategyStrip, state: ScreenAdvisor.MissionUiState) {
        val context = strip.root.context
        val detail = state.missionDetail
        strip.missionDetail.visibility = if (state.expanded && detail != null) View.VISIBLE else View.GONE
        strip.missionDetail.removeAllViews()
        if (detail == null || !state.expanded) return

        fun detailLabel(resId: Int) {
            strip.missionDetail.addView(
                TextView(context).apply {
                    text = context.getString(resId)
                    setTextColor(0xFF9DB8F0.toInt())
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(context, 4), 0, dp(context, 1))
                },
            )
        }

        fun detailLine(text: String, danger: Boolean = false) {
            strip.missionDetail.addView(
                TextView(context).apply {
                    this.text = "· $text"
                    setTextColor(if (danger) MENU_DANGER else 0xCCFFFFFF.toInt())
                    textSize = 12.5f
                    setSingleLine(false)
                },
            )
        }

        if (detail.actions.isNotEmpty()) {
            detailLabel(R.string.float_mission_actions)
            detail.actions.forEach { detailLine(it) }
        }
        if (detail.forbidden.isNotEmpty()) {
            detailLabel(R.string.float_mission_forbidden)
            detail.forbidden.forEach { detailLine(it, danger = true) }
        }
        if (detail.signalsToWatch.isNotEmpty()) {
            detailLabel(R.string.float_mission_signals)
            detail.signalsToWatch.forEach { detailLine(it) }
        }
        if (detail.successWhen.isNotBlank()) {
            detailLabel(R.string.float_mission_success)
            detailLine(detail.successWhen)
        }
    }

    /** 方向 chip 选中态渲染：[selectedLabel] 为 null 显示未设置态。 */
    fun bindDirectionChip(chip: TextView, selectedLabel: String?) {
        chip.text = selectedLabel ?: chip.context.getString(R.string.float_dir_chip)
        (chip.background as? GradientDrawable)?.setColor(
            if (selectedLabel != null) DIRECTION_CHIP_ON else DIRECTION_CHIP_OFF,
        )
        chip.setTextColor(if (selectedLabel != null) Color.WHITE else 0xCCFFFFFF.toInt())
    }

    /**
     * 「方向」下拉浮层的纯视图工厂。loading 且无建议时只展示「AI 拟定中…」；
     * 建议行点击回调 [onPick]，另有自定义输入与清除方向。
     */
    fun buildDirectionMenu(
        context: Context,
        suggestions: List<String>,
        loading: Boolean,
        hasSelected: Boolean,
        onPick: (String) -> Unit,
        onCustom: () -> Unit,
        onClear: () -> Unit,
    ): View {
        fun menuRow(text: String, color: Int, click: (() -> Unit)?): TextView =
            TextView(context).apply {
                this.text = text
                setTextColor(color)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9))
                if (click != null) {
                    isClickable = true
                    selectableBackground(this)
                    setOnClickListener { click() }
                }
            }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(context, 188)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14).toFloat()
                setColor(PANEL_COLOR)
            }
            setPadding(0, dp(context, 4), 0, dp(context, 4))
            elevation = dp(context, 10).toFloat()
        }

        if (suggestions.isEmpty() && loading) {
            column.addView(menuRow(context.getString(R.string.float_dir_loading), 0x99FFFFFF.toInt(), null))
        } else {
            suggestions.take(DIRECTION_MENU_MAX)
                .forEach { column.addView(menuRow(it, Color.WHITE) { onPick(it) }) }
        }
        column.addView(menuDivider(context))
        column.addView(menuRow(context.getString(R.string.float_dir_custom), MENU_ACCENT) { onCustom() })
        if (hasSelected) {
            column.addView(
                menuRow(context.getString(R.string.float_dir_clear), MENU_DANGER) { onClear() },
            )
        }
        return column
    }

    /**
     * 「自定义方向」输入浮层的纯视图工厂：单输入框 + 取消/确定。
     * 确定时文本去空白非空才回调 [onConfirm]；回车（IME DONE）等同确定。
     */
    fun buildDirectionInput(
        context: Context,
        initial: String,
        onCancel: () -> Unit,
        onConfirm: (String) -> Unit,
    ): View {
        val field = EditText(context).apply {
            hint = context.getString(R.string.float_dir_input_hint)
            setHintTextColor(0x66FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(0x22FFFFFF.toInt())
            }
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            filters = arrayOf(android.text.InputFilter.LengthFilter(DIRECTION_MAX_CHARS))
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initial)
            setSelection(text.length)
        }
        fun submit() {
            val value = field.text.toString().trim()
            if (value.isNotEmpty()) onConfirm(value)
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 16).toFloat()
                setColor(PANEL_COLOR)
            }
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 12))
            elevation = dp(context, 10).toFloat()
            addView(field, LinearLayout.LayoutParams(dp(context, 248), LinearLayout.LayoutParams.WRAP_CONTENT))

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(context, 10) }
                addView(TextView(context).apply {
                    text = context.getString(R.string.float_dir_input_cancel)
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 14f
                    setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
                    setOnClickListener { onCancel() }
                })
                addView(TextView(context).apply {
                    text = context.getString(R.string.float_dir_input_confirm)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        cornerRadius = dp(context, 16).toFloat()
                        setColor(BALL_COLOR)
                    }
                    setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6))
                    setOnClickListener { submit() }
                })
            }
            addView(row)
        }
        // 服务层在 PopupWindow 获焦后据此 requestFocus + 呼键盘。
        column.tag = field
        return column
    }

    private fun menuDivider(context: Context): View = View(context).apply {
        background = GradientDrawable().apply { setColor(0x1EFFFFFF) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(context, 12)
            marginEnd = dp(context, 12)
            topMargin = dp(context, 2)
            bottomMargin = dp(context, 2)
        }
    }

    /** 列表行按压反馈；Service 主题取不到资源 id 时静默退化（无涟漪）。 */
    private fun selectableBackground(view: View) {
        runCatching {
            val outValue = TypedValue()
            view.context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                outValue,
                true,
            )
            if (outValue.resourceId != 0) view.setBackgroundResource(outValue.resourceId)
        }
    }

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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // ── M2-a 起草档代拟条（方案 §8.1 N4）：草稿态两键 + 编辑态两键，整条默认 GONE ──

    /** 代拟条回调：主键=一键发送/发送编辑稿，次键=改后发送/放弃编辑。服务层接线。 */
    interface DraftStripCallbacks {
        fun onDraftPrimary()
        fun onDraftSecondary()
    }

    /** 代拟条视图句柄：服务层按草稿态/编辑态渲染，回调随渲染刷新。 */
    class DraftStrip(
        val root: LinearLayout,
        val message: TextView,
        val primaryButton: TextView,
        val secondaryButton: TextView,
    ) {
        internal var callbacksRef: DraftStripCallbacks? = null
    }

    private fun buildDraftStrip(context: Context, callbacks: DraftStripCallbacks): DraftStrip {
        val message = TextView(context).apply {
            setTextColor(0xEEFFFFFF.toInt())
            textSize = 13f
            setPadding(dp(context, 16), dp(context, 5), dp(context, 12), dp(context, 2))
        }
        fun draftButton(filled: Boolean): TextView = TextView(context).apply {
            setTextColor(if (filled) Color.WHITE else 0xCCFFFFFF.toInt())
            textSize = 12.5f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 12).toFloat()
                setColor(if (filled) DIRECTION_CHIP_ON else 0x22FFFFFF)
            }
            setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5))
            isClickable = true
            selectableBackground(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(context, 8) }
        }
        val primaryButton = draftButton(filled = true)
        val secondaryButton = draftButton(filled = false)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 6))
            addView(primaryButton)
            addView(secondaryButton)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(0x14FFFFFF) }
            addView(message)
            addView(buttons)
        }
        val strip = DraftStrip(root, message, primaryButton, secondaryButton)
        primaryButton.setOnClickListener { strip.callbacksRef?.onDraftPrimary() }
        secondaryButton.setOnClickListener { strip.callbacksRef?.onDraftSecondary() }
        strip.callbacksRef = callbacks
        return strip
    }

    /**
     * 渲染代拟条：[text] 为 null 整条隐藏；[editing] 切草稿态（预览+一键发送/
     * 改后发送）与编辑态（编辑提示+发送编辑稿/放弃编辑）两套文案。
     */
    fun renderDraftStrip(strip: DraftStrip, editing: Boolean, text: String?) {
        if (text == null) {
            strip.root.visibility = View.GONE
            return
        }
        strip.root.visibility = View.VISIBLE
        if (editing) {
            strip.message.text = strip.message.context.getString(
                R.string.float_draft_editing_hint,
                text,
            )
            strip.primaryButton.text = strip.message.context.getString(R.string.float_draft_send_edited)
            strip.secondaryButton.text = strip.message.context.getString(R.string.float_draft_edit_cancel)
        } else {
            strip.message.text = strip.message.context.getString(R.string.float_draft_preview, text)
            strip.primaryButton.text = strip.message.context.getString(R.string.float_draft_send)
            strip.secondaryButton.text = strip.message.context.getString(R.string.float_draft_edit)
        }
    }
}
