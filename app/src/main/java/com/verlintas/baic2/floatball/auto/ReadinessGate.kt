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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.device.api.ShellResult
import com.verlintas.baic2.device.api.ShellState
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.director.DirectorRoutes
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单项就绪结果：[id] 为 reason 码（展示层映射中文文案），[detail] 为技术明细。 */
data class ReadinessItem(val id: String, val ok: Boolean, val detail: String)

/** 八项就绪检测报告（§20.1）：全绿才放行模式 B 托管。 */
data class ReadinessReport(val items: List<ReadinessItem>) {

    val allGreen: Boolean get() = items.all { it.ok }

    /** 缺项明细（红行，逐一展示并对齐「给出下一步操作」的用户偏好）。 */
    val missing: List<ReadinessItem> get() = items.filter { !it.ok }

    fun item(id: String): ReadinessItem? = items.firstOrNull { it.id == id }
}

/**
 * 模式 B 就绪校验门（方案 §20.1，8 项全绿才可开免 a11y 托管）：启动前置探针
 * 执行 + 缺项列表产出。禁止裸跑——任一红即拒绝进入 Shizuku 通道；运行中风险
 * 侦测（心跳/投影失效/熔断）由调度侧按 §20.2 续接，不在本类。
 *
 * 录屏授权项按 2026-10-20 用户决策检查 assists-mp 会话（托管找图帧源=assists-mp，
 * 非方案原文的 FloatCaptureBridge）；输入链自检实测 `cmd clipboard` 子命令在
 * API 36 不存在，改走「应用侧剪贴板写入 + `input keyevent KEYCODE_PASTE` 探针」，
 * 与 [ShizukuInjector] 注入路径同构。
 */
@Singleton
class ReadinessGate @Inject constructor(
    private val shell: ShellBridge,
    private val ocr: OcrProvider,
    private val contactRepository: ContactRepository,
    private val screenCapture: ScreenCapture,
    @ApplicationContext private val context: Context,
) {

    /**
     * 执行八项探针（每次调用全量重跑，结果不缓存）。[whitelistIds] 为当前自动档
     * 白名单（[com.verlintas.baic2.floatball.AdvisorPrefs.Settings.autoWhitelistIds]）。
     */
    suspend fun check(whitelistIds: Set<Long>): ReadinessReport = withContext(Dispatchers.Default) {
        ReadinessReport(
            items = listOf(
                checkShizuku(),
                checkProjection(),
                checkOcrSmoke(),
                checkTemplates(),
                checkInputChain(),
                checkWhitelist(whitelistIds),
                checkNotificationListener(),
                checkQuietBaseline(),
            ),
        )
    }

    /** Shizuku 授权入口（红行点击：拉起 Shizuku manager 授权弹窗）。 */
    fun requestShizukuPermission() = shell.requestPermission()

    private suspend fun checkShizuku(): ReadinessItem {
        if (shell.state.value != ShellState.Ready) {
            return ReadinessItem(ID_SHIZUKU, false, "shizuku_not_ready")
        }
        return when (val probe = shell.exec("id", timeoutMs = 5_000, maxOutputChars = 200)) {
            is ShellResult.Output ->
                if (probe.exitCode == 0 && probe.stdout.contains("uid=2000")) {
                    ReadinessItem(ID_SHIZUKU, true, "uid=2000")
                } else {
                    ReadinessItem(ID_SHIZUKU, false, "probe_failed:${probe.stdout.take(60)}")
                }
            else -> ReadinessItem(ID_SHIZUKU, false, "probe_unavailable")
        }
    }

    /** 真判活：isEnable 只看 imageReader（onStop 后不置空，会读到假活）。 */
    private fun checkProjection(): ReadinessItem =
        if (screenCapture.projectionAlive) {
            ReadinessItem(ID_PROJECTION, true, "projection_session_active")
        } else {
            ReadinessItem(ID_PROJECTION, false, "projection_not_authorized")
        }

    /** OCR 引擎冒烟：临时画布写中文样张 → 识别一次（不落盘，用完即回收）。 */
    private suspend fun checkOcrSmoke(): ReadinessItem = runCatching {
        val bitmap = Bitmap.createBitmap(SMOKE_BITMAP_W, SMOKE_BITMAP_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
        }
        canvas.drawText(SMOKE_TEXT, 20f, 72f, paint)
        val bytes = ByteArrayOutputStream().use { sink ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, sink)
            sink.toByteArray()
        }
        bitmap.recycle()
        val lines = ocr.recognize(bytes).getOrNull()
        if (lines.isNullOrEmpty()) {
            ReadinessItem(ID_OCR, false, "ocr_empty_result")
        } else {
            ReadinessItem(ID_OCR, true, "ocr_lines=${lines.size}")
        }
    }.getOrElse { ReadinessItem(ID_OCR, false, "ocr_error:${it.message?.take(60)}") }

    private fun checkTemplates(): ReadinessItem {
        val present = runCatching {
            context.assets.list(TEMPLATE_DIR)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val missing = missingTemplates(present)
        return if (missing.isEmpty()) {
            ReadinessItem(ID_TEMPLATES, true, "templates=${present.size}")
        } else {
            ReadinessItem(ID_TEMPLATES, false, "template_missing:${missing.joinToString(",")}")
        }
    }

    /**
     * 输入链自检：应用侧写剪贴板（前台可写可读，探后恢复原剪贴板）→ shell
     * `input keyevent KEYCODE_PASTE` 退出码 0（注入管线存活；设置页无文本焦点，
     * 键事件无处粘贴，无副作用）。
     */
    private suspend fun checkInputChain(): ReadinessItem {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ReadinessItem(ID_INPUT, false, "no_clipboard_service")
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        val written = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, PROBE_TEXT))
        }.isSuccess
        if (!written) return ReadinessItem(ID_INPUT, false, "clipboard_write_denied")
        val pasted = shell.exec("input keyevent $KEYCODE_PASTE", timeoutMs = 5_000, maxOutputChars = 200)
        previous?.let { runCatching { clipboard.setPrimaryClip(it) } }
        return when (pasted) {
            is ShellResult.Output ->
                if (pasted.exitCode == 0) {
                    ReadinessItem(ID_INPUT, true, "clipboard+keyevent ok")
                } else {
                    ReadinessItem(ID_INPUT, false, "keyevent_exit=${pasted.exitCode}")
                }
            else -> ReadinessItem(ID_INPUT, false, "shell_unavailable")
        }
    }

    /** 托管白名单非空且联系人均绑定 IM 包名（避免派发时无处路由）。 */
    private suspend fun checkWhitelist(whitelistIds: Set<Long>): ReadinessItem {
        if (whitelistIds.isEmpty()) {
            return ReadinessItem(ID_WHITELIST, false, "whitelist_empty")
        }
        val contacts = runCatching { contactRepository.listAlive() }.getOrDefault(emptyList())
        val unbound = whitelistIds.count { id -> contacts.none { it.id == id && it.imPackage.isNotBlank() } }
        return if (unbound == 0) {
            ReadinessItem(ID_WHITELIST, true, "bound=${whitelistIds.size}")
        } else {
            ReadinessItem(ID_WHITELIST, false, "unbound=$unbound/${whitelistIds.size}")
        }
    }

    private fun checkNotificationListener(): ReadinessItem =
        if (NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            ReadinessItem(ID_NOTIFY, true, "listener_connected")
        } else {
            ReadinessItem(ID_NOTIFY, false, "listener_not_enabled")
        }

    /** 免打扰基线：非低电量模式且不在夜间静默窗口（§20.1 第 8 项）。 */
    private fun checkQuietBaseline(): ReadinessItem {
        val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode ?: false
        val inQuiet = DirectorRoutes.inQuietWindow(
            System.currentTimeMillis(),
            CompanionWindowController.DEFAULT_QUIET_START,
            CompanionWindowController.DEFAULT_QUIET_END,
        )
        return if (!powerSave && !inQuiet) {
            ReadinessItem(ID_QUIET, true, "baseline_clear")
        } else {
            ReadinessItem(
                ID_QUIET,
                false,
                "power_save=$powerSave quiet_window=$inQuiet",
            )
        }
    }

    internal companion object {

        const val ID_SHIZUKU = "shizuku_bridge"
        const val ID_PROJECTION = "projection_ready"
        const val ID_OCR = "ocr_engine"
        const val ID_TEMPLATES = "rpa_templates"
        const val ID_INPUT = "input_chain"
        const val ID_WHITELIST = "auto_whitelist"
        const val ID_NOTIFY = "notification_listener"
        const val ID_QUIET = "quiet_baseline"

        /** 找图模板资产目录（ImageMatcher 同款约定）。 */
        const val TEMPLATE_DIR = "rpa_templates"

        /**
         * 必备模板集（§20.1 第 4 项：发送/搜索/返回/更多）；文件名不含目录与
         * .png 后缀。R1 验收时从微信真机采集入库。
         */
        val REQUIRED_TEMPLATES = setOf("send", "search", "back", "more")

        /** 缺失模板列表（纯函数 JVM 可测）：present 为 assets 目录列出的文件名。 */
        internal fun missingTemplates(present: Collection<String>): List<String> {
            val normalized = present.map { it.substringAfterLast('/').removeSuffix(".png") }.toSet()
            return (REQUIRED_TEMPLATES - normalized).sorted()
        }

        private const val CLIP_LABEL = "baic2"
        private const val PROBE_TEXT = "baic2-input-probe"
        private const val KEYCODE_PASTE = 279
        private const val SMOKE_BITMAP_W = 360
        private const val SMOKE_BITMAP_H = 100
        private const val SMOKE_TEXT = "老参谋 123"
    }
}
