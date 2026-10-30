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

package com.verlintas.baic2.floatball.auto

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import com.ven.assists.service.AssistsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 触摸探索（Touch Exploration）脉冲门——微信反自动化屏蔽的 a11y 解锁**兜底**通道。
 *
 * 背景（2026-10-29 真机取证定案，微信 8.0.79 / Android 15）：微信
 * `com.tencent.mm.sdk.platformtools.b.c()`（smartDisableAccessibility）的
 * **唯一放行条件是「启用的无障碍服务列表里存在 TalkBack 家族服务
 * （`com.google.android.marvin.talkback` 包下任一服务）」，与触摸探索状态无关**。
 * 「触摸探索开」与「autojs 白名单桩」两条线真机 A/B 实测均无效（dex 静态取证
 * 的「白名单 ID / isTouchExplorationEnabled」两条件疑被微信 Tinker 热补丁改写，
 * 静态取证必须真机 A/B 复核）；不放行时反射调 `setStateLocked(0)` 摘树，
 * 任意页面只剩 381 字节空根，开↔关秒级实时生效、无需重启微信。
 *
 * 解锁路线（2026-10-29 定版）：
 * 1. **主路线=[WechatTreeUnlock]**：分析会话级临时启用「随选朗读」
 *    （TalkBack 家族里副作用最小的载体），acquire/release 严格配对还原，
 *    唯一入口见 AGENTS.md §8 教训 11；
 * 2. **兜底=本类脉冲**（树退化的退化场景）：节点树退化时短暂
 *    `setServiceInfo` 请求触摸探索，用完即关。注意 MIUI/HyperOS（Android 15）
 *    真机上运行时 setServiceInfo 增加该 flag 是空操作（2026-10-29 取证，
 *    dumpsys 60 采样 touchExplorationEnabled 恒 false），此兜底在该机型不可用；
 * 3. 【禁止】把 `flagRequestTouchExplorationMode` 静态写进
 *    accessibility_service_config.xml——会随服务绑定常开触摸探索，全设备触摸
 *    变「先探索后激活」、悬浮球拖不动（2026-10-29 性能事故）。
 *
 * 其它约束：
 * - 脉冲打开期间全设备触摸变为「先探索后激活」（单击=朗读/悬停，双击=激活），
 *   所以只允许包住**无人操作的短窗口**（采集快照、脚本执行），用完即关；
 * - **节点树已就绪时不脉冲**（[acquire] 内置跳过）：TalkBack 家族服务放行时
 *   微信全树常驻可读，任何路径都不应再闪切触摸探索；
 * - 若用户本就开着触摸探索（如 TalkBack），恢复时保持原状不代关；
 * - 能力依赖服务配置 `canRequestTouchExplorationMode="true"`（device/impl
 *   accessibility_service_config.xml），缺省时 setServiceInfo 的 flag 会被忽略。
 */
object TouchExplorationGate {

    private val lock = Any()
    private var depth = 0

    /** 深度归零时是否需要关掉触摸探索（即由本门打开的，而非用户原本开着）。 */
    private var restoreOff = false

    /**
     * 进入门（可重入）。第一次进入时若触摸探索未开**且节点树尚未就绪**，则置
     * [AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE] 请求开启；
     * TalkBack 家族服务放行时树本就就绪，直接跳过（零触摸探索副作用）。
     * 无无障碍服务时静默降级（后续 [awaitTreeSettled] 快速返回）。
     */
    fun acquire() {
        synchronized(lock) {
            if (depth == 0) {
                val wasEnabled = isTouchExplorationEnabled()
                restoreOff = !wasEnabled && !isTreeSettled() && setRequested(true)
            }
            depth++
        }
    }

    /** 出门（与 [acquire] 严格配对，finally 调用）。深度归零时恢复触摸探索原状。 */
    fun release() {
        synchronized(lock) {
            if (depth == 0) return
            depth--
            if (depth == 0 && restoreOff) {
                setRequested(false)
                restoreOff = false
            }
        }
    }

    /**
     * 等待活动窗口节点树就绪（根节点出现子节点）。触摸探索打开后微信在秒级
     * 重建节点树，这里轮询到就绪或超时即返回；普通应用树本就绪，首查即回。
     */
    suspend fun awaitTreeSettled(timeoutMs: Long = SETTLE_TIMEOUT_MS) =
        withContext(Dispatchers.Main) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                val ready = runCatching {
                    AssistsService.getOrNull()?.rootInActiveWindow?.childCount ?: 0 > 0
                }.getOrDefault(false)
                if (ready) return@withContext
                delay(POLL_INTERVAL_MS)
            }
        }

    private fun isTouchExplorationEnabled(): Boolean = runCatching {
        val service = AssistsService.getOrNull() ?: return@runCatching false
        val manager = service.getSystemService(AccessibilityManager::class.java)
            ?: return@runCatching false
        manager.isTouchExplorationEnabled
    }.getOrDefault(false)

    /** 活动窗口节点树是否已就绪（根节点带子节点）。TalkBack 家族服务放行时恒为 true。 */
    private fun isTreeSettled(): Boolean = runCatching {
        (AssistsService.getOrNull()?.rootInActiveWindow?.childCount ?: 0) > 0
    }.getOrDefault(false)

    private fun setRequested(requested: Boolean): Boolean = runCatching {
        val service = AssistsService.getOrNull() ?: return@runCatching false
        val info = service.serviceInfo ?: return@runCatching false
        info.flags = if (requested) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        }
        service.serviceInfo = info
        true
    }.getOrDefault(false)

    private const val SETTLE_TIMEOUT_MS = 2_500L
    private const val POLL_INTERVAL_MS = 150L
}
