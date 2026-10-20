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
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.device.api.ShellResult
import com.verlintas.baic2.device.api.ShellState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * 模式 B 执行通道（方案 §17.2：Shizuku 免 a11y 托管）：shell 层注入，目标 App
 * 侧无 a11y 服务痕迹（InputReader 虚拟设备），检测面比 a11y 小一个量级。
 *
 * - 点击/按键：`input tap x y` / `input keyevent`（InputManager 系统注入）；
 * - 文本注入两条子路径（方案 §17.2，shell `input text` 不支持 Unicode）：
   * ① 纯 ASCII 无空白 → `input text`（shell 单引号转义）；
   * ② 中文/含空白 → 应用侧写系统剪贴板（与 F2 同原语）→ `input keyevent
 *     KEYCODE_PASTE` → 延迟恢复原剪贴板（PASTE 异步生效，恢复必须延迟）；
 * - 前台包名：`dumpsys activity` 解析 mResumedActivity（未知即拒绝注入，防错发）；
 * - 可用性：复用自家 [ShellBridge] 状态（设置页授权入口现成，方案 §21.1 定稿
 *   不引 assists 的 shizuku 能力）。
 */
@Singleton
class ShizukuInjector @Inject constructor(
    private val shell: ShellBridge,
    @ApplicationContext private val context: Context,
) : InputInjector {

    override val channel = InjectorChannel.Shizuku

    override val available: Boolean
        get() = shell.state.value == ShellState.Ready

    override val unavailableReason = REASON_SHIZUKU_NOT_READY

    override suspend fun tap(x: Int, y: Int): Result<Unit> =
        exec("input tap $x $y")

    override suspend fun type(text: String): Result<Unit> {
        if (viaInputText(text)) return exec("input text ${shellQuote(text)}")
        // 中文/含空白子路径：剪贴板写入 + KEYCODE_PASTE（方案 §17.2 默认①）。
        val clipboard = runCatching {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        }.getOrNull() ?: return Result.failure(IOException("no clipboard service"))
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        val written = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        }
        if (written.isFailure) return written
        // 键事件到达焦点 TextView 前留出剪贴板落定时间。
        delay(PASTE_SETTLE_MS)
        val pasted = paste()
        restoreClipboardLater(clipboard, previous)
        return pasted
    }

    override suspend fun paste(): Result<Unit> = exec("input keyevent $KEYCODE_PASTE")

    override suspend fun clearInput(): Result<Unit> {
        // Ctrl+A 全选（keycombination 子命令为 Android 11+）→ DEL 删除。
        val selectAll = exec("input keycombination $META_CTRL_LEFT $KEYCODE_A")
        if (selectAll.isFailure) return selectAll
        return exec("input keyevent $KEYCODE_DEL")
    }

    override suspend fun foregroundPackage(): String? {
        val result = shell.exec(
            "dumpsys activity activities | grep -m1 'mResumedActivity:'",
            timeoutMs = SHELL_TIMEOUT_MS,
            maxOutputChars = 2_000,
        )
        val stdout = (result as? ShellResult.Output)?.takeIf { it.exitCode == 0 }?.stdout
        return parseResumedPackage(stdout ?: "")
    }

    /** 粘贴后延迟恢复原剪贴板（PASTE 异步生效，立即恢复会抢在读取之前——F2 同款时序）。 */
    private fun restoreClipboardLater(clipboard: ClipboardManager, previous: ClipData?) {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                runCatching {
                    if (previous != null) {
                        clipboard.setPrimaryClip(previous)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard.clearPrimaryClip()
                    }
                }
            },
            RESTORE_DELAY_MS,
        )
    }

    private suspend fun exec(command: String): Result<Unit> = when (
        val result = shell.exec(command, timeoutMs = SHELL_TIMEOUT_MS, maxOutputChars = 1_000)
    ) {
        is ShellResult.Output ->
            if (result.exitCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("exit=${result.exitCode} ${result.stderr.take(120)}"))
            }
        else -> Result.failure(IOException("shell_unavailable: $result"))
    }

    internal companion object {

        const val REASON_SHIZUKU_NOT_READY = "shizuku_not_ready"

        /** input/keyevent 单命令超时（注入即返，超时视为通道异常）。 */
        private const val SHELL_TIMEOUT_MS = 5_000L

        /** 剪贴板写入到 KEYCODE_PASTE 之间的落定等待。 */
        private const val PASTE_SETTLE_MS = 120L

        /** 粘贴后恢复原剪贴板的延迟（F2 同款）。 */
        private const val RESTORE_DELAY_MS = 120L

        private const val CLIP_LABEL = "baic2"

        // input/keyevent 关键码（仅此处使用，不引 android.view.KeyEvent 常量歧义）。
        private const val KEYCODE_PASTE = 279
        private const val KEYCODE_DEL = 67
        private const val KEYCODE_A = 29
        private const val META_CTRL_LEFT = 113

        /** `input text` 只可靠支持可打印 ASCII 且不含空白（含空白/中文走剪贴板路径）。 */
        internal fun viaInputText(text: String): Boolean =
            text.isNotEmpty() && text.all { it.code in 33..126 }

        /** shell 单引号转义（exec 走 `sh -c`，内嵌单引号需闭合再拼接）。 */
        internal fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

        /**
         * 解析 `dumpsys activity` 的 mResumedActivity 行（grep 已单行截取）。
         * 形如 `mResumedActivity: ActivityRecord{6c55ff6 u0 com.tencent.mm/...UI t1234}`。
         */
        internal fun parseResumedPackage(stdout: String): String? =
            Regex("""\bu\d+ (\S+)/""").find(stdout)?.groupValues?.get(1)
    }
}
