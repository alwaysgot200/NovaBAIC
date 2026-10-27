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

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import com.ven.assists.window.AssistsWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 布局分析覆盖层的生命周期。
 *
 * 关键时序：**先摘窗口，等一帧再抓**。无障碍截图会把 `TYPE_ACCESSIBILITY_OVERLAY`
 * 自身也画进去；首次打开时悬浮球/菜单刚被服务移除，画面仍未稳定，因此首帧也统一
 * 等待 [SETTLE_MS] 后再采集。刷新走同一条路径，`close()` 幂等，`dispose()` 静默。
 */
internal class RpaAnalyzeOverlayController(
    private val context: Context,
    private val capture: RpaAnalyzeCapture,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val selfPackage: String,
    private val onClosed: () -> Unit,
    private val onFailure: () -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var view: RpaAnalyzeOverlayView? = null
    private var refreshJob: Job? = null
    private var attached = false
    private var closed = false
    private var started = false
    private var closedNotified = false

    fun start() {
        if (started) return
        started = true
        val overlay = RpaAnalyzeOverlayView(
            context,
            object : RpaAnalyzeOverlayView.Callbacks {
                override fun onExit() = close()

                override fun onRefresh() = refresh(first = false)
            },
        )
        view = overlay
        refresh(first = true)
    }

    private fun refresh(first: Boolean) {
        if (closed) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (!first) detach()
            view?.showLoading()
            // 覆盖层/菜单/悬浮球刚摘掉时 surface 还没真正消失，直接抓会带残影。
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
            result.onSuccess { snapshot ->
                view?.renderSnapshot(snapshot)
            }.onFailure { error ->
                view?.renderError(error.message)
            }
        }
    }

    /** 覆盖层挂窗；`AssistsWindowManager` 未初始化 / 重复 id 时返回 false。 */
    private fun attach(): Boolean {
        val overlay = view ?: return false
        if (attached) return true
        val params = runCatching { AssistsWindowManager.createLayoutParams() }.getOrNull()
            ?: return false
        params.width = screenWidth
        params.height = screenHeight
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        val wrapper = runCatching {
            AssistsWindowManager.add(
                view = overlay,
                layoutParams = params,
                isStack = true,
                isTouchable = true,
                uniqueId = "rpa_analyze_${SystemClock.uptimeMillis()}",
            )
        }.getOrNull()
        attached = wrapper != null
        return attached
    }

    private fun detach() {
        if (!attached) return
        attached = false
        val overlay = view ?: return
        runCatching { AssistsWindowManager.removeWindow(overlay) }
    }

    fun close() {
        if (closed) return
        closed = true
        refreshJob?.cancel()
        detach()
        releaseView()
        scope.cancel()
        notifyClosed()
    }

    /** 服务销毁路径：摘窗、释放位图引用，但不回调（服务自身正在收尾）。 */
    fun dispose() {
        if (closed) return
        closed = true
        refreshJob?.cancel()
        detach()
        releaseView()
        scope.cancel()
    }

    private fun releaseView() {
        view?.release()
        view = null
    }

    private fun notifyClosed() {
        if (closedNotified) return
        closedNotified = true
        onClosed()
    }

    private companion object {
        const val SETTLE_MS = 180L
    }
}
