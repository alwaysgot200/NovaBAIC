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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.device.api.ShellResult
import com.verlintas.baic2.device.api.ShellState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

    /** RPA 长按：`input swipe` 同点持续按压（长按菜单 / 拖拽手柄）。 */
    suspend fun longPress(x: Int, y: Int): Result<Unit> =
        exec("input swipe $x $y $x $y $LONG_PRESS_MS")

    /** RPA 滑动（滚动 / 翻页）：Shizuku shell 注入，无需 a11y 手势。 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<Unit> =
        exec("input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceAtLeast(50)}")

    /** RPA 系统按键：back|home|recents|notifications|enter（keycode 注入）。 */
    suspend fun pressKey(key: String): Result<Unit> {
        val code = when (key.lowercase()) {
            "back" -> KEYCODE_BACK
            "home" -> KEYCODE_HOME
            "recents", "recent" -> KEYCODE_RECENTS
            "notifications", "shade" -> KEYCODE_NOTIFICATIONS
            "enter" -> KEYCODE_ENTER
            else -> return Result.failure(
                IllegalArgumentException("unknown_key: use back|home|recents|notifications|enter"),
            )
        }
        return exec("input keyevent $code")
    }

    /** 系统返回键（KEYCODE_BACK=4）：页面回退（如聊天页→IM 主列表）。 */
    suspend fun back(): Result<Unit> = exec("input keyevent $KEYCODE_BACK")

    /**
     * 输入法是否已展开（dumpsys input_method 的 mInputShown 口径，单次 shell
     * 调用 ~115ms 真机实测）。2026-10-27 回复慢归因新增：IME 已弹起 ⇒ 聊天页
     * 输入框已持有焦点 ⇒ 注入前聚焦步（取帧+OCR+坐标 tap，实测 2.2~2.5s）
     * 整段可省。见 [OcrSendLocator.focusInput]。
     */
    suspend fun imeShown(): Boolean {
        val result = shell.exec(
            "dumpsys input_method | grep -m1 mInputShown=",
            timeoutMs = SHELL_TIMEOUT_MS,
            maxOutputChars = 200,
        )
        return (result as? ShellResult.Output)
            ?.stdout
            ?.contains("mInputShown=true") == true
    }

    /**
     * 发送成功后收起输入法（best effort）：键盘/Sogou「随写」面板会把工具条
     * 与候选文字一并送进下一帧 OCR，既污染正文也会诱发自回声回合（2026-10-27
     * 真机：发完一条后键盘仍展开）。先查 dumpsys 确认 IME 确实展开再发 BACK，
     * 否则 BACK 会把聊天页退回消息列表。
     */
    suspend fun hideImeIfShown(): Boolean {
        if (!imeShown()) return false
        return back().isSuccess
    }

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

    override suspend fun foregroundPackage(): String? =
        parseResumedPackage(dumpResumedLine() ?: "")

    /** 前台 Activity 全名（形如 pkg/.ui.chatting.ChattingUI）：页面级判别用。 */
    suspend fun foregroundActivity(): String? =
        parseResumedActivity(dumpResumedLine() ?: "")

    /**
     * 清晰帧通道（模式 2 感知面主源，2026-10-21 真机实测定案）：shell uid 有
     * screencap 权限，产出原生分辨率帧——HyperOS 投影帧 ~2× 下采样模糊
     * （拉普拉斯方差 225 vs 硬件截图 714，ML Kit OCR lines=0），screencap 无此
     * 问题。通道：App 读 shell 写的文件在 /sdcard/Android/data 下被 FUSE 拒，
     * /data/local/tmp + chmod 666 的文件 App uid 可完整读取。文件名带
     * nanoTime 防并发串写；用完即删不留痕。解码走 [Dispatchers.Default]
     * （AGENTS §8 教训 3：主线程禁位图解码）。null = 通道失败，调用方降级。
     */
    suspend fun screencap(): Bitmap? = withContext(Dispatchers.Default) {
        val path = "$SCREENCAP_DIR/baic2_frame_${System.nanoTime()}.png"
        try {
            val shot = shell.exec(
                "screencap -p $path && chmod 666 $path",
                timeoutMs = SCREENCAP_TIMEOUT_MS,
                maxOutputChars = 1_000,
            )
            if ((shot as? ShellResult.Output)?.exitCode != 0) return@withContext null
            val bytes = runCatching { File(path).readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            runCatching {
                shell.exec("rm -f $path", timeoutMs = SHELL_TIMEOUT_MS, maxOutputChars = 200)
            }
        }
    }

    /** dumpsys 前台 Activity 行（[foregroundPackage]/[foregroundActivity] 共用）。 */
    private suspend fun dumpResumedLine(): String? {
        // 前台键名三格式兼容（2026-10-21 真机实测 HyperOS 无 mResumedActivity）：
        // topResumedActivity=（新 AOSP/HyperOS）/ ResumedActivity:（HyperOS task
        // 区块）/ mResumedActivity:（旧 AOSP——本模式作为 ResumedActivity: 的
        // 子串一并命中）。
        val result = shell.exec(
            "dumpsys activity activities | grep -m1 -E 'topResumedActivity=|ResumedActivity:'",
            timeoutMs = SHELL_TIMEOUT_MS,
            maxOutputChars = 2_000,
        )
        return (result as? ShellResult.Output)?.takeIf { it.exitCode == 0 }?.stdout
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
        private const val KEYCODE_BACK = 4
        private const val KEYCODE_HOME = 3
        private const val KEYCODE_RECENTS = 187
        private const val KEYCODE_NOTIFICATIONS = 83
        private const val KEYCODE_ENTER = 66

        /** 长按按压时长（系统长按阈值 ~500ms，留余量）。 */
        private const val LONG_PRESS_MS = 800

        /** screencap 临时文件目录（真机实测：/sdcard FUSE 拒 App 读 shell 写文件，此目录 chmod 666 后可读）。 */
        private const val SCREENCAP_DIR = "/data/local/tmp"

        /** screencap 单次超时（1080×2460 PNG 编码+写文件，比 input 注入慢）。 */
        private const val SCREENCAP_TIMEOUT_MS = 10_000L
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
         * 解析 `dumpsys activity` 的前台 Activity 行（grep 已单行截取）。
         * 形如 `mResumedActivity: ActivityRecord{6c55ff6 u0 com.tencent.mm/...UI t1234}`。
         */
        internal fun parseResumedPackage(stdout: String): String? =
            Regex("""\bu\d+ (\S+)/""").find(stdout)?.groupValues?.get(1)

        /** 同 [parseResumedPackage]，但提取完整 Activity 全名（pkg/.Activity）。 */
        internal fun parseResumedActivity(stdout: String): String? =
            Regex("""\bu\d+ (\S+) """).find(stdout)?.groupValues?.get(1)
    }
}
