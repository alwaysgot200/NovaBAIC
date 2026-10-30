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
import com.ven.assists.window.AssistsWindowManager
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 浮窗动作列表（母规格 §7.5）的覆盖层生命周期，范式对齐
 * [com.verlintas.baic2.floatball.rpa.analyze.RpaAnalyzeOverlayController]：
 *
 * - 经 [AssistsWindowManager] 挂覆盖层（isTouchable，窗口默认不可聚焦——文本
 *   输入是 p2-4 弹框的事）；收起 / 展开 / 拖宽原地 [AssistsWindowManager.updateViewLayout]；
 * - 宽 = 屏宽 × 55%，右缘拖柄 45%~75%，比例以 float 记在 `rpa_panel` 偏好里，
 *   下次打开恢复；高 = 内容自适应（上限屏高 65%，UI 内部截断）；
 * - 标题栏拖动改 LayoutParams x/y，松手贴边吸附最近左 / 右屏缘；
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

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 展示状态（外部状态经 [update] 注入；收起态由本类自持）。 */
    private var panelState by mutableStateOf(RpaActionPanelState())

    private var view: ComposeView? = null
    private var treeOwner: PanelViewTreeOwner? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var added = false
    private var released = false

    /** 面板宽度比例（屏宽 ×比例，钳在 45%~75%），下次打开恢复。 */
    private var widthRatio: Float = prefs.getFloat(KEY_WIDTH_RATIO, DEFAULT_WIDTH_RATIO)
        .coerceIn(MIN_WIDTH_RATIO, MAX_WIDTH_RATIO)

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
     * 挂起浮窗。[extraDialogs] 是 p2-4 / p2-6 的弹框槽（步骤编辑弹框、全局
     * 设置弹框等叠加在面板之上渲染）。
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

        // 默认贴左缘、距顶约 12% 屏高；后续由标题栏拖动 / 贴边吸附决定。
        windowX = 0
        windowY = (screenHeightPx * INITIAL_Y_RATIO).roundToInt()

        if (!attach()) {
            close()
        }
    }

    /** 注入外部状态（运行态 / 脚本）；收起态由 controller 自持，不被覆盖。 */
    fun update(state: RpaActionPanelState) {
        panelState = state.copy(collapsed = collapsed)
    }

    /** 用户关闭退出：摘窗 + 释放 + 恢复悬浮球（解除 gate 互斥占位）。 */
    fun close() {
        if (released) return
        released = true
        detach()
        releaseView()
        scope.cancel()
        gate.show(RpaChromeGate.REASON_EDIT)
    }

    /** 服务销毁：同 [close]（无额外回调，宿主与服务一起收尾）。 */
    fun dispose() = close()

    private fun attach(): Boolean {
        if (added) return true
        val overlay = view ?: return false
        val params = AssistsWindowManager.createLayoutParams()
        // AAR 3.5.9 的 add(view, params, isStack, isTouchable, viewTag) 无 uniqueId
        // 形参（vendor 源码的 uniqueId 版尚未发布）；移除按 View 匹配即可。
        applySize(params)
        params.x = windowX
        params.y = windowY
        val wrapper = AssistsWindowManager.add(
            view = overlay,
            layoutParams = params,
            isStack = true,
            isTouchable = true,
        )
        added = wrapper != null
        windowParams = params
        return added
    }

    private fun detach() {
        if (!added) return
        view?.let { AssistsWindowManager.removeWindow(it) }
        added = false
        windowParams = null
    }

    private fun releaseView() {
        treeOwner?.onDestroy()
        treeOwner = null
        view = null
    }

    // ── 窗口几何 ──────────────────────────────────────────────────────

    private fun applySize(params: WindowManager.LayoutParams) {
        if (collapsed) {
            params.width = ballPx + shadowPadPx * 2
            params.height = ballPx + shadowPadPx * 2
        } else {
            params.width = (screenWidthPx * widthRatio).roundToInt() + shadowPadPx * 2
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
    }

    private fun currentWindowWidth(): Int =
        if (collapsed) ballPx + shadowPadPx * 2
        else (screenWidthPx * widthRatio).roundToInt() + shadowPadPx * 2

    private fun applyLayout() {
        val overlay = view ?: return
        val params = windowParams ?: return
        applySize(params)
        params.x = windowX
        params.y = windowY
        scope.launch { AssistsWindowManager.updateViewLayout(overlay, params) }
    }

    /**
     * p2-4 弹框（步骤编辑 / 全局设置）打开期间切窗口可聚焦，IME 才能弹出；
     * 关闭时恢复默认不可聚焦（面板外触摸穿透不受影响）。
     * 范式参照 FloatBallService 的 FLAG_NOT_FOCUSABLE 位运算切换。
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
        scope.launch { AssistsWindowManager.updateViewLayout(overlay, params) }
    }

    // ── 窗口手势（RpaActionPanelActions）────────────────────────────────

    override fun onTitleDragBy(dx: Float, dy: Float) {
        val maxX = (screenWidthPx - currentWindowWidth()).coerceAtLeast(0)
        windowX = (windowX + dx.roundToInt()).coerceIn(0, maxX)
        windowY = (windowY + dy.roundToInt()).coerceIn(0, (screenHeightPx - ballPx).coerceAtLeast(0))
        applyLayout()
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
    }

    override fun onWidthDragBy(dx: Float) {
        if (collapsed) return
        widthRatio = (widthRatio + dx / screenWidthPx).coerceIn(MIN_WIDTH_RATIO, MAX_WIDTH_RATIO)
        applyLayout()
    }

    override fun onWidthDragEnd() {
        if (collapsed) return
        prefs.edit().putFloat(KEY_WIDTH_RATIO, widthRatio).apply()
        // 面板变宽后钳回屏内。
        windowX = windowX.coerceIn(0, (screenWidthPx - currentWindowWidth()).coerceAtLeast(0))
        applyLayout()
    }

    override fun onCollapse() {
        if (collapsed) return
        // 缩为 44dp 圆球；面板 x/y 保留，点球恢复原位。
        collapsed = true
        panelState = panelState.copy(collapsed = true)
        applyLayout()
    }

    override fun onExpand() {
        if (!collapsed) return
        collapsed = false
        panelState = panelState.copy(collapsed = false)
        windowX = windowX.coerceIn(0, (screenWidthPx - currentWindowWidth()).coerceAtLeast(0))
        applyLayout()
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
        const val PREFS_NAME = "rpa_panel"
        const val KEY_WIDTH_RATIO = "width_ratio"
        const val DEFAULT_WIDTH_RATIO = 0.55f
        const val MIN_WIDTH_RATIO = 0.45f
        const val MAX_WIDTH_RATIO = 0.75f
        const val BALL_DP = 44
        const val SHADOW_PAD_DP = 8
        const val INITIAL_Y_RATIO = 0.12f
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
