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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.device.api.ShellResult
import com.verlintas.baic2.device.api.ShellState
import com.ven.assists.service.AssistsService
import kotlinx.coroutines.delay

/**
 * 微信节点树解锁门（2026-10-29 真机取证定版）——目标 App 反自动化摘树时，
 * 临时启用「随选朗读」把节点树解回来，分析会话结束即还原。
 *
 * 取证结论（Redmi 23077RABDC / HyperOS OS2.0.205.0 / Android 15 / 微信 8.0.79）：
 * 微信 `smartDisableAccessibility` 的真正放行条件是「启用的无障碍服务列表里
 * 存在 TalkBack 家族服务（com.google.android.marvin.talkback 包下任一服务）」，与触摸
 * 探索无关。真机 A/B 矩阵（`uiautomator dump` 采微信前台窗口）：
 * - 仅好参谋 / +autojs 白名单桩 / +桩且 touchExplorationEnabled=true → 均摘树
 *   （381 字节空根）；
 * - +「随选朗读」（触摸探索=false）→ 272 节点全树（92,694 字节，含「通讯录」），
 *   开↔关同一微信进程内秒级实时开合；
 * - +TalkBack（触摸探索=true）→ 277 节点全树。
 * dex 静态取证的「autojs 白名单 ID / isTouchExplorationEnabled」两条件在运行时
 * （疑被 Tinker 热补丁 `patch-5a32c721` 改写）均不成立，见
 * `docs/2026-10-27-悬浮菜单RPA入口与三工具交互方案.md` §7.14.3 复核修正。
 *
 * 设计约束：
 * - 快路径零副作用：树可读、或用户已开 TalkBack 家族服务（微信本就放行）时
 *   不写任何设置；
 * - 只在摘树（根节点无子节点）时启用，且只追加/移除随选朗读一个组件，
 *   用户启用的其它服务一律原样保留；用户自开的 TalkBack 家族服务永不代关；
 * - 写 `enabled_accessibility_services` 会触发系统全部无障碍服务解绑重绑
 *   （整机约 1 秒卡顿），所以只包住分析会话：[acquire] 启用、[release] 还原，
 *   与 FloatBallService 分析覆盖层的开/关严格配对；
 * - 需要 shell 通道（Shizuku）写安全设置；不可用时返回 [Outcome.Failed]，
 *   由调用方报因并引导手动开启「随选朗读」，不静默失效。
 */
object WechatTreeUnlock {

    sealed interface Outcome {
        /** 目标树本就可读，未做任何改动。 */
        object Settled : Outcome

        /** 用户已开 TalkBack 家族服务（目标 App 本就放行），未做任何改动。 */
        object Covered : Outcome

        /** 已临时启用随选朗读；须与 [release] 配对。 */
        object Held : Outcome

        /**
         * 自动解锁不可用（无 shell 通道 / 写入未生效 / 树仍未恢复）；
         * 调用方报因并引导手动开启「随选朗读」。
         */
        data class Failed(val reason: String) : Outcome
    }

    private val lock = Any()
    private var held = false

    /**
     * 进入分析会话：目标树被摘空（微信式反自动化）且用户未开 TalkBack 家族服务时，
     * 临时启用「随选朗读」并等待目标 App 重建节点树（实测 <1s）。
     * 快路径（树可读 / 用户已开家族服务）零副作用；失败就地回滚。
     */
    suspend fun acquire(context: Context, shell: ShellBridge): Outcome {
        synchronized(lock) { if (held) return Outcome.Held }
        if (!treeStripped()) return Outcome.Settled
        val current = enabledList(context)
        if (hasMarvin(current)) return Outcome.Covered
        if (current.isEmpty()) return Outcome.Failed("no_enabled_service")
        if (shell.state.value != ShellState.Ready) return Outcome.Failed("no_shell")
        val withUnlock = current.split(':')
            .filter { it.isNotBlank() && !it.equals(SELECT_TO_SPEAK, ignoreCase = true) }
            .plus(SELECT_TO_SPEAK)
            .joinToString(":")
        if (!writeEnabled(shell, withUnlock)) return Outcome.Failed("write_failed")
        // 等系统绑定随选朗读 + 目标 App 重建节点树（开合实验：秒级生效）
        val listUp = poll(LIST_SETTLE_TIMEOUT_MS) { hasMarvin(enabledList(context)) }
        val treeUp = listUp && poll(TREE_SETTLE_TIMEOUT_MS) { !treeStripped() }
        if (!treeUp) {
            // 没生效就地回滚（如非微信式摘树），不留全局副作用
            writeEnabled(shell, current)
            return Outcome.Failed("tree_still_stripped")
        }
        synchronized(lock) { held = true }
        return Outcome.Held
    }

    /** 退出分析会话：还原启用列表（仅移除本门追加的随选朗读，其余原样保留）。幂等。 */
    suspend fun release(context: Context, shell: ShellBridge) {
        synchronized(lock) {
            if (!held) return
            held = false
        }
        val next = enabledList(context).split(':')
            .filter { it.isNotBlank() && !it.equals(SELECT_TO_SPEAK, ignoreCase = true) }
            .joinToString(":")
        if (next.isEmpty()) {
            runCatching {
                shell.exec(
                    "settings delete secure enabled_accessibility_services",
                    timeoutMs = SHELL_TIMEOUT_MS,
                    maxOutputChars = 200,
                )
            }
        } else {
            writeEnabled(shell, next)
        }
    }

    /** 摘树签名：活动窗口根节点存在但无子节点（微信 `setStateLocked(0)` 后的空根）。 */
    private fun treeStripped(): Boolean {
        val root = AssistsService.getOrNull()?.rootInActiveWindow ?: return false
        return root.childCount == 0
    }

    private fun enabledList(context: Context): String = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
    }.getOrDefault("")

    /** 启用列表里是否已有 TalkBack 家族服务（TalkBack / 随选朗读 / 其无障碍菜单同包）。 */
    private fun hasMarvin(list: String): Boolean =
        list.split(':').any { it.trim().startsWith(MARVIN_PREFIX) }

    private suspend fun writeEnabled(shell: ShellBridge, value: String): Boolean = runCatching {
        shell.exec(
            "settings put secure enabled_accessibility_services " +
                ShizukuInjector.shellQuote(value),
            timeoutMs = SHELL_TIMEOUT_MS,
            maxOutputChars = 200,
        ) is ShellResult.Output
    }.getOrDefault(false)

    private suspend fun poll(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching { condition() }.getOrDefault(false)) return true
            delay(POLL_INTERVAL_MS)
        }
        return runCatching { condition() }.getOrDefault(false)
    }

    /** TalkBack 家族包前缀：TalkBackService / SelectToSpeakService / AccessibilityMenuService 同属此包。 */
    private const val MARVIN_PREFIX = "com.google.android.marvin.talkback/"

    /** 「随选朗读」：TalkBack 家族里副作用最小的载体——不开启触摸探索、不朗读全文。 */
    private const val SELECT_TO_SPEAK =
        "com.google.android.marvin.talkback/" +
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    private const val LIST_SETTLE_TIMEOUT_MS = 2_000L
    private const val TREE_SETTLE_TIMEOUT_MS = 4_000L
    private const val POLL_INTERVAL_MS = 150L
    private const val SHELL_TIMEOUT_MS = 5_000L
}
