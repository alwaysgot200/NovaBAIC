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

package com.verlintas.baic2.floatball.rpa

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ven.assists.service.AssistsService
import com.ven.assists.window.AssistsWindowManager
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 浮窗动作列表 + 动作编辑（母规格 §7.5 双浮窗）的覆盖层生命周期，范式对齐
 * [com.verlintas.baic2.floatball.rpa.analyze.RpaAnalyzeOverlayController]：
 *
 * - 经 [AssistsWindowManager] 挂覆盖层（isTouchable）；a11y 服务未在线时回退普通
 *   应用覆盖窗（TYPE_APPLICATION_OVERLAY，§4.5 双模铁律「无 a11y 可编辑」）；
 *   列表窗默认不可聚焦（无文本输入），编辑窗**常开可聚焦**（IME 才能弹出）；
 * - 列表窗宽 = 屏宽 × **80% 定值居中**（§7.5 决策⑫：无右缘拖柄、无比例记忆），
 *   高 = 内容自适应（步骤列表固定 3 行高）；标题栏拖动改 LayoutParams x/y，松手
 *   贴边吸附最近左 / 右屏缘；
 * - 动作编辑窗 = **第二 overlay 窗口**（§7.5 下位）：等宽对齐列表窗、固定高 ≈ 屏高
 *   50%，默认吸附列表窗正下方接壤并跟随列表拖动；编辑窗标题拖动即解除吸附独立
 *   移动，✕ = 取消当前步骤编辑；
 * - 互斥：open 时 `gate.hide(REASON_EDIT)`，close/dispose 时 `gate.show(REASON_EDIT)`；
 * - 运行态 / 脚本是外部状态：[RpaActionPanelState] 由 [update] 注入；
 * - ComposeView 轻量宿主：服务窗口没有 Activity，手动驱动 Lifecycle 与
 *   SavedStateRegistry（挂窗前设好 ViewTree，销毁时置 DESTROYED）。
 */
internal class RpaActionPanelController(
    private val context: Context,
    private val gate: RpaChromeGate,
    private val host: RpaActionPanelHost,
) : RpaActionPanelActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 展示状态（外部状态经 [update] 注入；收起态由本类自持）。 */
    private var panelState by mutableStateOf(RpaActionPanelState())

    private var view: ComposeView? = null
    private var treeOwner: PanelViewTreeOwner? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var added = false

    // ── 动作编辑窗（第二 overlay 窗口）─────────────────────────────────
    private var editView: ComposeView? = null
    private var editTreeOwner: PanelViewTreeOwner? = null
    private var editWindowParams: WindowManager.LayoutParams? = null
    private var editAdded = false

    /** 列表 / 编辑窗各自挂载时走的窗口管理路（摘除 / 重排按挂载路走，防错配泄漏）。 */
    private var listViaAssists = false
    private var editViaAssists = false

    private var editX = 0
    private var editY = 0

    /** 编辑窗是否吸附列表窗正下方（编辑窗标题拖动即解除；列表拖动时吸附态跟随）。 */
    private var editAttached = true

    /** 编辑窗内容三元组（状态化，换步骤重开只需换内容不动窗）。 */
    private var editTitle by mutableStateOf("")
    private var editOnDismiss by mutableStateOf<(() -> Unit)?>(null)
    private var editContent by mutableStateOf<(@Composable () -> Unit)?>(null)

    private var released = false

    private var windowX = 0
    private var windowY = 0
    private var collapsed = false

    private val screenWidthPx: Int get() = context.resources.displayMetrics.widthPixels
    private val screenHeightPx: Int get() = context.resources.displayMetrics.heightPixels
    private val density: Float get() = context.resources.displayMetrics.density
    private val shadowPadPx: Int get() = (SHADOW_PAD_DP * density).roundToInt()
    private val ballPx: Int get() = (BALL_DP * density).roundToInt()

    // ── 生命周期 ──────────────────────────────────────────────────────

    /**
     * 挂起浮窗。[extraDialogs] 是 p2-4 / p2-6 的弹框槽（全局设置弹框等叠加在
     * 面板之上渲染；步骤编辑走 [openEdit] 编辑浮窗，不在此槽）。
     */
    fun open(extraDialogs: @Composable () -> Unit = {}) {
        if (released || view != null) return
        gate.hide(RpaChromeGate.REASON_EDIT)
        collapsed = false
        panelState = panelState.copy(collapsed = false)

        val owner = PanelViewTreeOwner().also { it.onCreate() }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                RpaActionPanel(
                    state = panelState,
                    actions = this@RpaActionPanelController,
                    extraDialogs = extraDialogs,
                )
            }
        }
        treeOwner = owner
        view = composeView

        // 默认上部居中（§7.5）：宽 80% 定值水平居中，y ≈ 屏高 8%；后续由标题栏拖动 / 贴边吸附决定。
        windowX = ((screenWidthPx - panelWidthPx()) / 2).coerceAtLeast(0)
        windowY = (screenHeightPx * INITIAL_Y_RATIO).roundToInt()

        if (!attach()) {
            close()
        }
    }

    /** 注入外部状态（运行态 / 脚本）；收起态由 controller 自持，不被覆盖。 */
    fun update(state: RpaActionPanelState) {
        panelState = state.copy(collapsed = collapsed)
    }

    /** 用户关闭退出：摘双窗 + 释放 + 恢复悬浮球（解除 gate 互斥占位）。 */
    fun close() {
        if (released) return
        released = true
        closeEdit()
        detach()
        releaseView()
        scope.cancel()
        gate.show(RpaChromeGate.REASON_EDIT)
    }

    /** 服务销毁：同 [close]（无额外回调，宿主与服务一起收尾）。 */
    fun dispose() = close()

    // ── 动作编辑窗（第二 overlay 窗口，§7.5/§7.6）──────────────────────

    /**
     * 挂起 / 更新动作编辑窗（等宽对齐列表窗、固定高 ≈ 屏高 50%）。默认吸附列表窗
     * 正下方接壤；已开时只换内容不动窗（换步骤编辑场景）。
     *
     * [onDismiss] 是 ✕「取消当前步骤编辑」的回调（宿主清步骤编辑态后由其驱动
     * [closeEdit]）；[content] 是编辑内容（[RpaStepDialog] 骨架）。
     */
    fun openEdit(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
        if (released || view == null) return
        editTitle = title
        editOnDismiss = onDismiss
        editContent = content

        if (editView == null) {
            val owner = PanelViewTreeOwner().also { it.onCreate() }
            val composeView = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    val body = editContent
                    if (body != null) {
                        RpaActionEditWindow(
                            title = editTitle,
                            onTitleDragBy = { dx, dy -> onEditTitleDragBy(dx, dy) },
                            onTitleDragEnd = { onEditTitleDragEnd() },
                            onClose = { editOnDismiss?.invoke() },
                            content = body,
                        )
                    }
                }
            }
            editTreeOwner = owner
            editView = composeView
            editAttached = true
            placeEditBelowList()
            if (!attachEdit()) {
                closeEdit()
            }
        } else if (editAttached) {
            placeEditBelowList()
        }
    }

    /** 收起动作编辑窗：摘窗 + 释放（幂等，close/dispose 连带调用）。 */
    fun closeEdit() {
        editContent = null
        editTitle = ""
        editOnDismiss = null
        detachEdit()
        releaseEditView()
        editAttached = true
    }

    /** 编辑窗标题拖动：解除吸附后独立移动（钳在屏内）。 */
    fun onEditTitleDragBy(dx: Float, dy: Float) {
        if (!editAdded) return
        editAttached = false
        val maxX = (screenWidthPx - editWidthPx()).coerceAtLeast(0)
        editX = (editX + dx.roundToInt()).coerceIn(0, maxX)
        editY = (editY + dy.roundToInt()).coerceIn(0, (screenHeightPx - editHeightPx()).coerceAtLeast(0))
        applyEditLayout()
    }

    /** 编辑窗标题拖动松手：y 钳回屏内（保持独立吸附态，不回贴列表窗）。 */
    fun onEditTitleDragEnd() {
        if (!editAdded) return
        editY = editY.coerceIn(0, (screenHeightPx - editHeightPx()).coerceAtLeast(0))
        applyEditLayout()
    }

    /**
     * 吸附态跟随：编辑窗 x 对齐列表窗、y 接壤列表窗正下方（§7.5）。
     * 两窗内容各有 8dp 阴影留白，定位时上提 2×留白让卡片边缘直接贴合、无缝隙
     * （用户定案 2026-10-31：不要 gap）。
     */
    private fun placeEditBelowList() {
        editX = windowX
        editY = windowY + listMeasuredHeightPx() - shadowPadPx * 2
        applyEditLayout()
    }

    /** 列表窗实测高（含阴影留白）；未测量时用骨架估算兜底，不静默错位。 */
    private fun listMeasuredHeightPx(): Int =
        view?.height?.takeIf { it > 0 } ?: (PANEL_FALLBACK_HEIGHT_DP * density).roundToInt()

    private fun attachEdit(): Boolean {
        if (editAdded) return true
        val overlay = editView ?: return false
        val params = overlayParams()
        // 编辑窗含文本输入，常开可聚焦（IME）；列表窗保持默认不可聚焦。
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.width = editWidthPx()
        params.height = editHeightPx()
        params.x = editX
        params.y = editY
        val viaAssists = assistsOnline()
        editAdded = addOverlay(overlay, params, viaAssists)
        editViaAssists = viaAssists
        editWindowParams = params
        return editAdded
    }

    private fun detachEdit() {
        if (!editAdded) return
        editView?.let { removeOverlay(it, editViaAssists) }
        editAdded = false
        editWindowParams = null
    }

    private fun releaseEditView() {
        editTreeOwner?.onDestroy()
        editTreeOwner = null
        editView = null
    }

    private fun applyEditLayout() {
        val overlay = editView ?: return
        val params = editWindowParams ?: return
        params.width = editWidthPx()
        params.height = editHeightPx()
        params.x = editX
        params.y = editY
        relayoutOverlay(overlay, params, editViaAssists)
    }

    // ── 窗口挂摘 ──────────────────────────────────────────────────────

    // 窗口管理双路（§4.5 双模铁律）：a11y 服务在线走布局分析同款 AssistsWindowManager
    // （TYPE_ACCESSIBILITY_OVERLAY）；未在线回退普通应用覆盖窗（TYPE_APPLICATION_OVERLAY，
    // 与悬浮球同权限），保证「无 a11y 可编辑」不崩。拾取链路（armPick）先关面板再
    // 截屏，两路都无截屏自遮挡。

    private val fallbackWindowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun assistsOnline(): Boolean = AssistsService.getOrNull() != null

    private fun overlayParams(): WindowManager.LayoutParams =
        AssistsWindowManager.createLayoutParams().apply {
            if (!assistsOnline()) type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    /** 挂窗（AAR 3.5.9 的 add(view, params, isStack, isTouchable, viewTag) 无 uniqueId 形参）。 */
    private fun addOverlay(
        overlay: View,
        params: WindowManager.LayoutParams,
        viaAssists: Boolean,
    ): Boolean =
        if (viaAssists) {
            AssistsWindowManager.add(
                view = overlay,
                layoutParams = params,
                isStack = true,
                isTouchable = true,
            ) != null
        } else {
            runCatching { fallbackWindowManager.addView(overlay, params) }.isSuccess
        }

    private fun removeOverlay(overlay: View, viaAssists: Boolean) {
        if (viaAssists) {
            AssistsWindowManager.removeWindow(overlay)
        } else {
            runCatching { fallbackWindowManager.removeView(overlay) }
        }
    }

    private fun relayoutOverlay(
        overlay: View,
        params: WindowManager.LayoutParams,
        viaAssists: Boolean,
    ) {
        scope.launch {
            if (viaAssists) {
                AssistsWindowManager.updateViewLayout(overlay, params)
            } else {
                runCatching { fallbackWindowManager.updateViewLayout(overlay, params) }
            }
        }
    }

    private fun attach(): Boolean {
        if (added) return true
        val overlay = view ?: return false
        val params = overlayParams()
        applySize(params)
        params.x = windowX
        params.y = windowY
        val viaAssists = assistsOnline()
        added = addOverlay(overlay, params, viaAssists)
        listViaAssists = viaAssists
        windowParams = params
        return added
    }

    private fun detach() {
        if (!added) return
        view?.let { removeOverlay(it, listViaAssists) }
        added = false
        windowParams = null
    }

    private fun releaseView() {
        treeOwner?.onDestroy()
        treeOwner = null
        view = null
    }

    // ── 窗口几何 ──────────────────────────────────────────────────────

    /** 列表窗内容宽：屏宽 × 80% 定值（§7.5 决策⑫）+ 阴影留白。 */
    private fun panelWidthPx(): Int =
        (screenWidthPx * WIDTH_RATIO).roundToInt() + shadowPadPx * 2

    /** 编辑窗宽 = 列表窗宽（§7.5 等宽对齐）。 */
    private fun editWidthPx(): Int = panelWidthPx()

    /** 编辑窗高：固定档 ≈ 屏高 50%（§7.5「高 = 固定档 ≈ 屏高 50%」）+ 阴影留白。 */
    private fun editHeightPx(): Int =
        (screenHeightPx * EDIT_HEIGHT_RATIO).roundToInt() + shadowPadPx * 2

    private fun applySize(params: WindowManager.LayoutParams) {
        if (collapsed) {
            params.width = ballPx + shadowPadPx * 2
            params.height = ballPx + shadowPadPx * 2
        } else {
            params.width = panelWidthPx()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
    }

    private fun currentWindowWidth(): Int =
        if (collapsed) ballPx + shadowPadPx * 2 else panelWidthPx()

    private fun applyLayout() {
        val overlay = view ?: return
        val params = windowParams ?: return
        applySize(params)
        params.x = windowX
        params.y = windowY
        relayoutOverlay(overlay, params, listViaAssists)
    }

    /**
     * p2-4 弹框（全局设置等）打开期间切列表窗可聚焦，IME 才能弹出；
     * 关闭时恢复默认不可聚焦（面板外触摸穿透不受影响）。步骤编辑窗常开可聚焦，
     * 不走此开关。范式参照 FloatBallService 的 FLAG_NOT_FOCUSABLE 位运算切换。
     */
    fun setDialogFocusable(focusable: Boolean) {
        val overlay = view ?: return
        val params = windowParams ?: return
        val newFlags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (newFlags == params.flags) return
        params.flags = newFlags
        relayoutOverlay(overlay, params, listViaAssists)
    }

    // ── 窗口手势（RpaActionPanelActions）────────────────────────────────

    override fun onTitleDragBy(dx: Float, dy: Float) {
        val maxX = (screenWidthPx - currentWindowWidth()).coerceAtLeast(0)
        windowX = (windowX + dx.roundToInt()).coerceIn(0, maxX)
        windowY = (windowY + dy.roundToInt()).coerceIn(0, (screenHeightPx - ballPx).coerceAtLeast(0))
        applyLayout()
        if (editAttached) placeEditBelowList()
    }

    override fun onTitleDragEnd() {
        // 松手贴边吸附最近左 / 右屏缘；y 钳制屏内。
        val width = currentWindowWidth()
        windowX = if (windowX + width / 2 < screenWidthPx / 2) {
            0
        } else {
            (screenWidthPx - width).coerceAtLeast(0)
        }
        val measuredHeight = view?.height ?: 0
        windowY = windowY.coerceIn(0, (screenHeightPx - measuredHeight).coerceAtLeast(0))
        applyLayout()
        if (editAttached) placeEditBelowList()
    }

    override fun onCollapse() {
        if (collapsed) return
        // 缩为 44dp 圆球；面板 x/y 保留，点球恢复原位。
        collapsed = true
        panelState = panelState.copy(collapsed = true)
        applyLayout()
        if (editAttached) placeEditBelowList()
    }

    override fun onExpand() {
        if (!collapsed) return
        collapsed = false
        panelState = panelState.copy(collapsed = false)
        windowX = windowX.coerceIn(0, (screenWidthPx - currentWindowWidth()).coerceAtLeast(0))
        applyLayout()
        if (editAttached) placeEditBelowList()
    }

    // ── 宿主动作转发（p2-6 接线）────────────────────────────────────────

    override fun onOpenSettings() = host.onOpenSettings()

    override fun onClose() {
        // ✕ 关闭退出：本控制器收口窗口与闸门，宿主做应用级退出收尾。
        close()
        host.onClose()
    }

    override fun onEditStep(stepId: String) = host.onEditStep(stepId)

    override fun onMoveStep(from: Int, to: Int) = host.onMoveStep(from, to)

    override fun onDuplicateStep(stepId: String) = host.onDuplicateStep(stepId)

    override fun onToggleStep(stepId: String) = host.onToggleStep(stepId)

    override fun onJumpTarget(stepId: String) = host.onJumpTarget(stepId)

    override fun onDeleteStep(stepId: String) = host.onDeleteStep(stepId)

    override fun onAddStep() = host.onAddStep()

    override fun onRun() = host.onRun()

    override fun onStop() = host.onStop()

    override fun onSave() = host.onSave()

    override fun onClearSteps() = host.onClearSteps()

    override fun onToggleLogs() = host.onToggleLogs()

    override fun onOpenVariables() = host.onOpenVariables()

    override fun onExport() = host.onExport()

    override fun onImport() = host.onImport()

    private companion object {
        /** 列表窗宽 = 屏宽 × 80% 定值（§7.5 决策⑫）。 */
        const val WIDTH_RATIO = 0.8f

        /** 编辑窗高 = 屏高 × 50% 固定档（§7.5）。 */
        const val EDIT_HEIGHT_RATIO = 0.5f

        const val BALL_DP = 44
        const val SHADOW_PAD_DP = 8

        /** 列表窗初始 y ≈ 屏高 8%（§7.5）。 */
        const val INITIAL_Y_RATIO = 0.08f

        /**
         * 列表窗未测量时的高估算兜底（dp）：阴影 16 + 标题 48 + 3 行列表 156 +
         * 添加行 48 + 底栏 56 ≈ 324dp，仅用于编辑窗初次吸附定位。
         */
        const val PANEL_FALLBACK_HEIGHT_DP = 324
    }
}

/**
 * ComposeView 轻量宿主：服务窗口没有 Activity，手动把 Lifecycle 与
 * SavedStateRegistry 接到 ViewTree 上，Compose 才能安全组合 / 拆解。
 */
private class PanelViewTreeOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /** 挂窗前调用：恢复 SavedState 并推到 RESUMED。 */
    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** release 时调用：置 DESTROYED，Compose 随之拆解。 */
    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
