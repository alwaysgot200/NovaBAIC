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

import android.content.Context
import com.ven.assists.window.AssistsWindowManager
import com.verlintas.rpa.model.RpaA11ySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 布局分析覆盖层生命周期（方案 §5.2）。
 *
 * 关键时序：**先摘覆盖层，再抓帧，再重挂覆盖层**。否则 a11y 截图/投影会把
 * 分析器自己的绿框一起拍进冻结帧，「刷新」永远脏一帧。首次启动同样等待
 * [SETTLE_MS]，让悬浮球收起动画结束。
 */
internal class RpaAnalyzeOverlayController(
    private val context: Context,
    private val capture: RpaAnalyzeCapture,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val selfPackage: String,
    private val onClosed: () -> Unit,
    private val onFailure: () -> Unit,
    /** 拾取会话横幅文案；null 表示普通分析（不提供回填入口）。 */
    private val pickHint: String? = null,
    private val onApplyPick: ((RpaA11ySpec) -> Unit)? = null,
    private val onCancelPick: (() -> Unit)? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: RpaAnalyzeOverlayView? = null
    private var added = false
    private var closed = false
    private var refreshing: Job? = null

    fun start() {
        if (closed || view != null) return
        val overlay = RpaAnalyzeOverlayView(
            context = context,
            callbacks = object : RpaAnalyzeOverlayView.Callbacks {
                override fun onExit() {
                    close()
                }

                override fun onRefresh() {
                    refresh(first = false)
                }

                override fun onApplyPick(spec: RpaA11ySpec) {
                    val handler = onApplyPick ?: return
                    handler(spec)
                    close()
                }

                override fun onCancelPick() {
                    val handler = onCancelPick ?: return
                    handler()
                    close()
                }
            },
            pickHint = pickHint,
        )
        view = overlay
        refresh(first = true)
    }

    /**
     * 重新采集。[first] 为 true 时覆盖层尚未挂载，只等待球/菜单隐藏落定；
     * 其余情况先摘窗，避免把自身绿框拍进帧。
     */
    private fun refresh(first: Boolean) {
        if (closed) return
        val overlay = view ?: return
        refreshing?.cancel()
        refreshing = scope.launch {
            if (!first) detach()
            overlay.showLoading()
            delay(SETTLE_MS)

            val result = capture.capture(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                selfPackage = selfPackage,
            )
            if (closed) return@launch
            if (!attach()) {
                onFailure()
                close()
                return@launch
            }
            result.fold(
                onSuccess = { snapshot -> overlay.renderSnapshot(snapshot) },
                onFailure = { error ->
                    overlay.renderError(error.message ?: ERROR_UNKNOWN)
                },
            )
        }
    }

    private fun attach(): Boolean {
        if (added) return true
        val overlay = view ?: return false
        val params = AssistsWindowManager.createLayoutParams()
        // AAR 3.5.9 的 add(view, params, isStack, isTouchable, viewTag) 无
        // uniqueId 形参（vendor 源码的 uniqueId 版尚未发布）；句柄由内部生成，
        // 移除时按 View 匹配即可。
        val wrapper = AssistsWindowManager.add(
            view = overlay,
            layoutParams = params,
            isStack = true,
            isTouchable = true,
        )
        added = wrapper != null
        return added
    }

    private fun detach() {
        if (!added) return
        view?.let { AssistsWindowManager.removeWindow(it) }
        added = false
    }

    /** 用户退出/旋转/无障碍断开：删除窗口并回调服务恢复悬浮球。 */
    fun close() {
        if (closed) return
        closed = true
        refreshing?.cancel()
        refreshing = null
        detach()
        view?.release()
        view = null
        scope.cancel()
        onClosed()
    }

    /** 服务销毁：静默释放，不回调（球与服务即将一起消失）。 */
    fun dispose() {
        if (closed) return
        closed = true
        refreshing?.cancel()
        refreshing = null
        detach()
        view?.release()
        view = null
        scope.cancel()
    }

    private companion object {
        const val SETTLE_MS = 180L
        const val ERROR_UNKNOWN = "unknown"
    }
}
