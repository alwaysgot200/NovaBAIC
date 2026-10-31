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

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.device.api.ShellResult
import com.verlintas.baic2.device.api.ShellState
import com.verlintas.baic2.floatball.auto.AssistsA11yBridge
import com.verlintas.baic2.floatball.auto.CvMatch
import com.verlintas.baic2.floatball.auto.ImChatNavigator
import com.verlintas.baic2.floatball.auto.ScreenCapture
import com.verlintas.baic2.floatball.auto.ShizukuInjector
import com.verlintas.rpa.engine.RpaDevice
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaHit
import com.verlintas.rpa.model.RpaInputChannel
import com.verlintas.rpa.model.RpaLine
import com.verlintas.rpa.model.RpaPick
import com.verlintas.rpa.model.RpaRect
import com.verlintas.rpa.model.RpaSelector
import com.verlintas.rpa.model.RpaTarget
import com.verlintas.rpa.model.RpaTextMatch
import com.verlintas.rpa.model.RpaVisionKind
import com.verlintas.rpa.model.RpaVisionLocator
import com.verlintas.rpa.model.RpaVisionSpec
import com.verlintas.rpa.store.RpaScriptStore
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 取 [RpaAndroidDevice] 的 Hilt 入口。**必须声明在主源码**：Hilt 组件在编译
 * 主模块时聚合 @EntryPoint，测试源集里声明的入口接口不在组件里，运行期会
 * ClassCastException（真机仪器测试取设备实例的唯一通道）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RpaAndroidDeviceEntryPoint {
    fun rpaDevice(): RpaAndroidDevice

    /** 真机仪器测试的 shell 通道（微信节点树解锁 WechatTreeUnlock 写安全设置用）。 */
    fun shellBridge(): ShellBridge
}

/**
 * [RpaDevice] 的 Android 实现：严格按线路分流，绝不「a11y 失败偷偷换 Shizuku」。
 *
 * | 能力 | A11Y 线 | VISION 线 |
 * |---|---|---|
 * | 定位 | [AssistsCore.getAllNodes] 选择器 | 抽帧 + OCR / OpenCV 模板 / 比例 |
 * | 点击 | [AssistsA11yBridge.tap] | [ShizukuInjector.tap] |
 * | 输入 | [AssistsA11yBridge.typeText]（SET_TEXT） | Shizuku input text / PASTE |
 * | 按键 | 无障碍全局动作 | `input keyevent` |
 * | 前台 | 无障碍根节点包名 | `dumpsys activity` |
 *
 * VISION 线的帧源只走 Shizuku screencap / MediaProjection（不用 a11y 截图），
 * 保证「无 a11y」语义成立。
 */
@Singleton
class RpaAndroidDevice @Inject constructor(
    private val bridge: AssistsA11yBridge,
    private val shizuku: ShizukuInjector,
    private val shell: ShellBridge,
    private val screenCapture: ScreenCapture,
    private val ocr: OcrProvider,
    private val templateMatcher: RpaTemplateMatcher,
    private val imChatNavigator: ImChatNavigator,
    private val store: RpaScriptStore,
    @ApplicationContext private val context: Context,
) : RpaDevice {

    override val capabilities: RpaCapabilities
        get() {
            // 与 ImChatNavigator 同一判定口径：assists 全局实例 + 桥接连接态
            // 都就绪，才认为 a11y 线路可执行（避免开门后线路内部静默切换）。
            val a11yReady = AssistsService.getOrNull() != null && bridge.connected.value
            val shizukuReady = shizuku.available
            val projectionReady = screenCapture.projectionAlive
            return RpaCapabilities(
                a11yReady = a11yReady,
                // 双态报因（§7.4）：区分「未开启」与「已启用未连接」，
                // 复用 assists 能力检测组件（AssistsCore.isA11yEnabled）。
                a11yEnabledInSettings = AssistsCore.isA11yEnabled(context),
                shizukuReady = shizukuReady,
                // 视觉线路的可执行性由 Shizuku 注入决定；录屏只提供抽帧，
                // 没有注入能力时点击 / 输入 / 滑动都无从谈起。
                visionReady = shizukuReady,
                ocrReady = true,
                projectionReady = projectionReady,
                screenshotReady = shizukuReady || projectionReady,
            )
        }

    /**
     * 双模式就绪态变化流（用户定案「一开始就要实时判断」）：a11y 桥连接态与
     * Shizuku shell 状态任一变化即发信号，供 UI 在 ON_RESUME 之外实时复检
     * 运行能力徽标（无障碍开 / 关、Shizuku 启动 / 断开都会立刻反映到状态卡）。
     */
    val readinessChanges: Flow<Unit> = merge(
        bridge.connected.map { },
        shell.state.map { },
    )

    override suspend fun launchApp(
        packageName: String,
        line: RpaLine,
        restart: Boolean,
        closeOnly: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            // 图5「仅关闭应用 / 重新打开(重启)」：关闭相先行；仅关闭到此为止
            if (closeOnly) return@withContext closeApp(packageName, line)
            if (restart) {
                val closed = closeApp(packageName, line)
                if (closed.isFailure) return@withContext closed
            }
            val launch = runCatching {
                context.packageManager.getLaunchIntentForPackage(packageName)
            }.getOrNull() ?: return@withContext Result.failure(
                IllegalStateException("app_missing: $packageName"),
            )
            // 「启动应用」= 应用首页（2026-10-31 真机定案）：纯 FLAG_ACTIVITY_NEW_TASK
            // 对已有任务是「带到前台即完」——目标 App 停在二级页（如微信聊天页）时
            // launcher intent 被顶页无效投递，启动完成判定（readySelector/readyText
            // 所等的首页元素）永远等不到 → launch_timeout。CLEAR_TOP 清掉入口 Activity
            // 之上的页面，保证判定所等的首页元素必然出现，启动语义与任务残留状态无关。
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 投递通道（2026-10-31 真机续案）：部分系统会静默丢弃后台进程的
            // startActivity（HyperOS「后台弹出界面」MIUIOP 10021 实测拦截：调用
            // 正常返回但界面不动，启动完成判定只能等满步骤超时误报 launch_timeout）。
            // shell 通道（Shizuku，shell 身份投递 am start）不受该限制，优先走它；
            // 无 shell 通道 / shell 投递失败再回退标准 startActivity。启动投递是
            // 线路中立的设备控制（同 closeApp），不属感知/注入通道，不算换线。
            if (!launchViaShell(launch)) {
                runCatching { context.startActivity(launch) }.getOrElse {
                    return@withContext Result.failure(it)
                }
            }
            if (!foregroundProbeAvailable(line)) {
                // 纯投影视觉线没有前台探针（无 Shizuku）；投递成功
                // 即视为启动完成，给冷启动留出稳定时间，绝不空等超时报错。
                if (line == RpaLine.A11Y) {
                    return@withContext Result.failure(IllegalStateException("a11y_not_ready"))
                }
                delay(LAUNCH_SETTLE_MS)
            } else if (!awaitForeground(packageName, line)) {
                return@withContext Result.failure(
                    IllegalStateException("launch_timeout: $packageName"),
                )
            }
            return@withContext Result.success(Unit)
        }

    /** shell 通道投递 am start；无 shell 通道 / 投递失败返回 false，调用方回退 startActivity。 */
    private suspend fun launchViaShell(launch: Intent): Boolean {
        if (shell.state.value != ShellState.Ready) return false
        val component = launch.component?.flattenToShortString() ?: return false
        val result = runCatching {
            shell.exec(
                "am start -f 0x${Integer.toHexString(launch.flags)} -n $component",
                timeoutMs = LAUNCH_SHELL_TIMEOUT_MS,
            )
        }.getOrNull()
        return result is ShellResult.Output &&
            result.exitCode == 0 &&
            !result.stdout.contains("Error") &&
            !result.stderr.contains("Error")
    }

    override suspend fun openChat(
        imPackage: String,
        contactName: String,
        line: RpaLine,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val result = when (line) {
            RpaLine.A11Y -> {
                // 严格 a11y 线路：这里必须同时确认 assists 全局实例与桥接连接态，
                // 否则 ImChatNavigator.openChat 会在 a11y 关闭时静默切到视觉线，
                // 破坏 RPA「显式换线」语义。
                if (AssistsService.getOrNull() == null || !bridge.connected.value) {
                    return@withContext Result.failure(
                        IllegalStateException("a11y_not_ready"),
                    )
                }
                imChatNavigator.openChat(imPackage, contactName)
            }
            RpaLine.VISION -> imChatNavigator.openChatByVision(imPackage, contactName)
        }
        when (result) {
            is ImChatNavigator.NavResult.Ok -> Result.success(Unit)
            is ImChatNavigator.NavResult.Fail -> Result.failure(
                // IM 未安装是前置缺失：按 app_missing 收口（B1 兜底语义），
                // 引擎据此禁换线直接失败。
                if (result.reason == ImChatNavigator.REASON_APP_MISSING) {
                    IllegalStateException("app_missing: $imPackage")
                } else {
                    IllegalStateException("open_chat_failed: ${result.reason}")
                },
            )
        }
    }

    override suspend fun tap(x: Int, y: Int, line: RpaLine, holdMs: Int): Result<Unit> =
        if (holdMs > 0) {
            // 图6「按下时间」：同点滑动 = 按住该毫秒再抬起；两线各走本线滑动通道
            swipe(x, y, x, y, holdMs, line)
        } else {
            when (line) {
                RpaLine.A11Y -> bridge.tap(x, y)
                RpaLine.VISION -> shizuku.tap(x, y)
            }
        }

    override suspend fun longPress(x: Int, y: Int, line: RpaLine): Result<Unit> = when (line) {
        RpaLine.A11Y -> bridge.longPress(x, y)
        RpaLine.VISION -> shizuku.longPress(x, y)
    }

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        line: RpaLine,
    ): Result<Unit> = when (line) {
        RpaLine.A11Y -> bridge.swipe(x1, y1, x2, y2, durationMs)
        RpaLine.VISION -> shizuku.swipe(x1, y1, x2, y2, durationMs)
    }

    override suspend fun inputText(
        text: String,
        line: RpaLine,
        clearFirst: Boolean,
        inputSelector: RpaSelector?,
        appendMode: Boolean,
        inputChannel: RpaInputChannel,
        focusPoint: RpaRect?,
    ): Result<Unit> = when (line) {
        RpaLine.A11Y -> typeViaA11y(text, clearFirst, inputSelector, appendMode)
        RpaLine.VISION -> typeViaVision(text, clearFirst, inputChannel, focusPoint)
    }

    /**
     * A11Y 线输入：inputSelector 指定目标可编辑节点并点按聚焦（miss 显式报
     * `input_focus_missing`，绝不静默写到别处）；appendMode 且不清空时读旧文本
     * 拼接（SET_TEXT 是整体替换，不拼接就丢旧文）；最终 [AssistsA11yBridge.typeText] 写入。
     * 输入框有界等待：上一步导航/点击后新窗口与输入焦点常在几百毫秒后才就绪
     * （2026-10-31 真机：点搜索图标后立即输入报 no_focused_input），定位与写入
     * 前都先等输入焦点或可编辑节点出现，预算耗尽照常报因，绝不吞错。
     */
    private suspend fun typeViaA11y(
        text: String,
        clearFirst: Boolean,
        inputSelector: RpaSelector?,
        appendMode: Boolean,
    ): Result<Unit> {
        if (inputSelector != null && !inputSelector.isEmpty()) {
            val hit = awaitLocateA11y(RpaA11ySpec(selectors = listOf(inputSelector)))
                ?: return Result.failure(IllegalStateException("input_focus_missing"))
            val tapped = bridge.tap(hit.x, hit.y)
            if (tapped.isFailure) return tapped
        }
        awaitA11yInputReady()
        val payload = if (appendMode && !clearFirst) {
            existingEditableText()?.plus(text) ?: text
        } else {
            text
        }
        return bridge.typeText(payload)
    }

    /** 有界重试定位（新窗口未就绪的竞态）：预算 [INPUT_READY_BUDGET_MS] 内轮询，耗尽即 miss。 */
    private suspend fun awaitLocateA11y(spec: RpaA11ySpec): RpaHit? {
        val deadline = System.currentTimeMillis() + INPUT_READY_BUDGET_MS
        while (true) {
            locateA11y(spec)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            delay(INPUT_READY_POLL_MS)
        }
    }

    /**
     * 有界等待输入框就绪：焦点输入节点或可编辑节点出现（与
     * [AssistsA11yBridge.typeText] 候选同口径）即续写；等不到不拦，
     * 交 typeText 如实报 `no_focused_input`。
     */
    private suspend fun awaitA11yInputReady() {
        if (AssistsService.getOrNull() == null) return
        val deadline = System.currentTimeMillis() + INPUT_READY_BUDGET_MS
        while (!a11yInputReady()) {
            if (System.currentTimeMillis() >= deadline) return
            delay(INPUT_READY_POLL_MS)
        }
    }

    private suspend fun a11yInputReady(): Boolean = boundedProbe(A11Y_PROBE_TIMEOUT_MS) {
        val service = AssistsService.getOrNull() ?: return@boundedProbe false
        if (service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null) {
            return@boundedProbe true
        }
        AssistsCore.getAllNodes().any { it.isEditable && it.isEnabled }
    } == true

    /** 追加写入前读旧文本：优先取 isFocused 的可编辑节点，其次全树首个可编辑节点。 */
    private fun existingEditableText(): String? = runCatching {
        val nodes = AssistsCore.getAllNodes()
        val focused = nodes.firstOrNull { it.isFocused && it.isEditable }
        (focused ?: nodes.firstOrNull { it.isEditable })?.text?.toString()
    }.getOrNull()

    /**
     * VISION 线输入：focusPoint 先点按聚焦；clearFirst 走本线 Ctrl+A+DEL 清空；
     * 写入通道只按 lineParams 显式选择——CLIPBOARD_PASTE 剪贴板粘贴（用后恢复旧
     * 剪贴板），SHELL_INPUT 走 `input text`。绝不回退 a11y 输入通道。
     */
    private suspend fun typeViaVision(
        text: String,
        clearFirst: Boolean,
        inputChannel: RpaInputChannel,
        focusPoint: RpaRect?,
    ): Result<Unit> {
        focusPoint?.let { point ->
            val (w, h) = screenSize() ?: (0 to 0)
            if (w <= 0 || h <= 0) {
                return Result.failure(IllegalStateException("input_focus_missing"))
            }
            shizuku.tap((point.left * w).toInt(), (point.top * h).toInt())
        }
        if (clearFirst) {
            val cleared = runCatching { shizuku.clearInput() }.getOrNull()
            if (cleared?.isFailure == true) {
                return Result.failure(
                    IllegalStateException("clear_input_failed: ${cleared.exceptionOrNull()?.message}"),
                )
            }
        }
        return when (inputChannel) {
            RpaInputChannel.CLIPBOARD_PASTE -> pasteViaClipboard(text)
            RpaInputChannel.SHELL_INPUT -> shizuku.type(text)
        }
    }

    /** 剪贴板粘贴：setPrimaryClip + KEYCODE_PASTE；粘贴完成后恢复旧剪贴板，不留用户剪贴板残余。 */
    private suspend fun pasteViaClipboard(text: String): Result<Unit> {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return Result.failure(IllegalStateException("clipboard_unavailable"))
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        val written = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("rpa-input", text))
        }
        if (written.isFailure) {
            return Result.failure(
                IllegalStateException("clipboard_write_failed: ${written.exceptionOrNull()?.message}"),
            )
        }
        val pasted = shizuku.paste()
        delay(CLIPBOARD_RESTORE_DELAY_MS)
        runCatching {
            if (previous != null) {
                clipboard.setPrimaryClip(previous)
            }
        }
        return pasted
    }

    override suspend fun pressKey(key: String, line: RpaLine): Result<Unit> = when (line) {
        RpaLine.A11Y -> bridge.pressKey(key)
        RpaLine.VISION -> shizuku.pressKey(key)
    }

    override suspend fun openLink(url: String, line: RpaLine): Result<Unit> =
        withContext(Dispatchers.IO) {
            // scheme 白名单由校验器 / 引擎把关；平台级 startActivity 与线路无关
            //（与 launchApp 同口径），无处理应用时异常如实带回，不吞错。
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }

    override suspend fun locate(
        scriptId: String,
        target: RpaTarget,
        line: RpaLine,
    ): RpaHit? = when (line) {
        RpaLine.A11Y -> target.a11y?.let { locateA11y(it) }
        RpaLine.VISION -> target.vision?.let { locateVision(scriptId, it) }
    }

    override suspend fun textLines(
        line: RpaLine,
        readScope: RpaSelector?,
        ocrRegion: RpaRect?,
    ): List<String> = when (line) {
        RpaLine.A11Y -> a11yTextLines(readScope)
        RpaLine.VISION -> {
            val frame = visionFrame() ?: return emptyList()
            val region = ocrRegion?.toRect(frame.width, frame.height)
            ocrLines(frame)
                .filter { region == null || region.contains(it.centerX, it.centerY) }
                .map { it.text }
        }
    }

    override suspend fun foregroundPackage(line: RpaLine): String? = when (line) {
        // A11Y 根节点查询必须走有界探针（见 [boundedProbe]）：对无响应窗口的
        // binder 查询会无限挂起（2026-10-31 真机实测前台探针卡 24 分钟把整条
        // 运行拖死），超时按「未知」返回，由轮询预算兜底收口 launch_timeout。
        RpaLine.A11Y -> boundedProbe(A11Y_PROBE_TIMEOUT_MS) { bridge.foregroundPackage() }
        // VISION 线走 shell `dumpsys` 通道，[ShizukuInjector] 自带单命令超时。
        RpaLine.VISION -> shizuku.foregroundPackage()
    }

    override fun screenSize(): Pair<Int, Int>? {
        bridge.screenSize()?.let { return it }
        val metrics = context.resources.displayMetrics
        return if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            metrics.widthPixels to metrics.heightPixels
        } else {
            null
        }
    }

    // ------------------------- 配置页 / 就绪门辅助 -------------------------

    /**
     * 「以图找图」配置页帧源：与 VISION 执行线路同源（Shizuku / 投影），
     * 即使本机开着 a11y 也不借用 a11y 截图，确保配置出来的模板所见即所得。
     */
    suspend fun visionConfigFrame(): Bitmap? = visionFrame()

    /** 以图找图配置页：对保存的模板做即时匹配测试。 */
    suspend fun testTemplate(
        frame: Bitmap,
        templateFile: java.io.File,
        threshold: Double,
        region: RpaRect?,
        grayscale: Boolean = true,
    ): Result<CvMatch> =
        templateMatcher.find(frame, templateFile, threshold, region, grayscale)

    /** 模板文件被覆盖/删除后让匹配缓存立即失效。 */
    fun invalidateTemplate(templateFile: java.io.File) {
        templateMatcher.invalidate(templateFile)
    }

    /**
     * 快捷找图（P3）：返回**不设阈值**的最佳匹配，由调用方决定是否命中，
     * 从而能展示「未命中但最佳得分 0.62」而不是只有一句失败原因。
     */
    suspend fun bestTemplateMatch(
        frame: Bitmap,
        templateFile: java.io.File,
        region: RpaRect? = null,
        grayscale: Boolean = true,
    ): Result<CvMatch> =
        templateMatcher.bestMatch(frame, templateFile, region, grayscale)

    /**
     * 快捷找图的帧源：与 VISION 执行线路同源（Shizuku / 投影），且带
     * 空白帧质检——FLAG_SECURE / 息屏时显式返回 `blank_frame`，不拿黑图硬匹配。
     */
    suspend fun quickFindFrame(): Result<Bitmap> =
        screenCapture.captureNow(qualityCheck = true)

    suspend fun ocrLines(frame: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
        val png = frame.toJpegBytes() ?: return@withContext emptyList()
        ocr.recognize(png).getOrDefault(emptyList())
    }

    /** 模式 B 运行前：无 Shizuku 时至少拿到一条投影帧源。 */
    suspend fun ensureVisionReady(): Boolean {
        if (shizuku.available || screenCapture.projectionAlive) return true
        return screenCapture.ensureProjection()
    }

    // ------------------------- A11Y 定位 -------------------------

    private suspend fun locateA11y(spec: RpaA11ySpec): RpaHit? = withContext(Dispatchers.Main) {
        if (AssistsService.getOrNull() == null) return@withContext null
        // 节点树直查同样可能在 binder 层无限挂起（见 [boundedProbe]），超时取空
        // 树让本步按 target_timeout 收口，绝不把整条运行挂死。
        val nodes = boundedProbe(A11Y_PROBE_TIMEOUT_MS) { AssistsCore.getAllNodes() }
            ?: emptyList()
        val (screenW, screenH) = screenSize() ?: (0 to 0)
        val candidates = ArrayList<Candidate>()
        var order = 0
        spec.selectors.forEachIndexed { selectorIndex, selector ->
            if (selector.isEmpty()) return@forEachIndexed
            nodes.forEach { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@forEach
                if (screenW > 0 && screenH > 0 &&
                    !bounds.intersects(0, 0, screenW, screenH)
                ) {
                    return@forEach
                }
                if (matches(node, selector)) {
                    candidates += Candidate(bounds, selectorIndex, order++)
                }
            }
        }
        pickCandidate(candidates, spec)
            ?: spec.snapshot?.bounds
                ?.takeIf { !it.isEmpty }
                ?.let { RpaHit(it.centerX, it.centerY, 1.0, "a11y snapshot", RpaLine.A11Y) }
    }

    private fun matches(node: AccessibilityNodeInfo, selector: RpaSelector): Boolean {
        if (selector.enabledOnly && !node.isEnabled) return false
        if (selector.clickableOnly && !node.isClickable) return false
        if (selector.editableOnly && !node.isEditable) return false
        selector.text?.takeIf { it.isNotBlank() }?.let { pattern ->
            if (!RpaTextMatch.matches(node.text?.toString(), pattern, selector.textMatch)) {
                return false
            }
        }
        selector.description?.takeIf { it.isNotBlank() }?.let { pattern ->
            if (!RpaTextMatch.matches(
                    node.contentDescription?.toString(),
                    pattern,
                    selector.textMatch,
                )
            ) {
                return false
            }
        }
        selector.viewId?.takeIf { it.isNotBlank() }?.let { id ->
            val actual = node.viewIdResourceName ?: return false
            if (actual != id && !actual.endsWith(":id/$id") && !actual.endsWith("/$id")) {
                return false
            }
        }
        selector.className?.takeIf { it.isNotBlank() }?.let { cls ->
            val actual = node.className?.toString() ?: return false
            if (actual != cls && !actual.endsWith(".$cls")) return false
        }
        return true
    }

    private fun pickCandidate(candidates: List<Candidate>, spec: RpaA11ySpec): RpaHit? {
        if (candidates.isEmpty()) return null
        val comparator: Comparator<Candidate> = when (spec.pick) {
            RpaPick.FIRST -> compareBy<Candidate> { it.selectorIndex }.thenBy { it.order }
            RpaPick.TOP_MOST -> compareBy<Candidate> { it.bounds.top }
                .thenBy { it.selectorIndex }
            RpaPick.BOTTOM_MOST -> compareByDescending<Candidate> { it.bounds.bottom }
                .thenBy { it.selectorIndex }
            RpaPick.LARGEST -> compareByDescending<Candidate> {
                it.bounds.width().toLong() * it.bounds.height()
            }.thenBy { it.selectorIndex }
            RpaPick.NEAREST -> {
                val anchorX = spec.snapshot?.bounds?.centerX ?: Int.MIN_VALUE
                val anchorY = spec.snapshot?.bounds?.centerY ?: Int.MIN_VALUE
                if (anchorX == Int.MIN_VALUE) {
                    compareBy<Candidate> { it.selectorIndex }.thenBy { it.order }
                } else {
                    compareBy<Candidate> {
                        val dx = it.bounds.centerX() - anchorX
                        val dy = it.bounds.centerY() - anchorY
                        dx.toLong() * dx + dy.toLong() * dy
                    }.thenBy { it.selectorIndex }
                }
            }
        }
        val best = candidates.minWith(comparator)
        val label = best.bounds.let { "${it.left},${it.top}-${it.right},${it.bottom}" }
        return RpaHit(
            x = best.bounds.centerX(),
            y = best.bounds.centerY(),
            score = 1.0,
            detail = "a11y $label",
            line = RpaLine.A11Y,
        )
    }

    // ------------------------- VISION 定位 -------------------------

    private suspend fun locateVision(scriptId: String, spec: RpaVisionSpec): RpaHit? =
        locateVisionLocator(scriptId, spec.primary)
            ?: spec.fallback?.let { locateVisionLocator(scriptId, it) }

    private suspend fun locateVisionLocator(
        scriptId: String,
        locator: RpaVisionLocator,
    ): RpaHit? = when (locator.kind) {
        RpaVisionKind.TEMPLATE -> {
            val templateId = locator.templateId ?: return null
            val frame = visionFrame() ?: return null
            val file = store.templateFile(scriptId, templateId)
            templateMatcher.find(
                frame = frame,
                templateFile = file,
                threshold = locator.threshold,
                region = locator.region,
                grayscale = locator.grayscale,
            ).getOrNull()?.let { match ->
                RpaHit(
                    x = match.centerX + locator.offsetX,
                    y = match.centerY + locator.offsetY,
                    score = match.score,
                    detail = "vision template=$templateId ${"%.3f".format(match.score)}",
                    line = RpaLine.VISION,
                )
            }
        }
        RpaVisionKind.OCR_TEXT -> {
            val text = locator.ocrText ?: return null
            val frame = visionFrame() ?: return null
            val region = locator.region?.toRect(frame.width, frame.height)
            val hits = ocrLines(frame).filter { line ->
                (region == null || region.contains(line.centerX, line.centerY)) &&
                    RpaTextMatch.matches(line.text, text, locator.ocrMatch, ignoreWhitespace = true)
            }
            pickOcr(hits, locator.pick)?.let { line ->
                RpaHit(
                    x = line.centerX + locator.offsetX,
                    y = line.centerY + locator.offsetY,
                    score = 1.0,
                    detail = "vision ocr='${line.text}'",
                    line = RpaLine.VISION,
                )
            }
        }
        RpaVisionKind.RATIO -> {
            // 比例坐标始终相对整帧（与以图找图配置页「点画面取点」一致）；
            // region 只服务模板 / OCR 的 ROI，不参与比例换算，避免配置页与
            // 执行器各按一套基准解释同一个 ratioX/ratioY。
            val frame = visionFrame()
            val width = frame?.width ?: screenSize()?.first ?: return null
            val height = frame?.height ?: screenSize()?.second ?: return null
            val x = (width * locator.ratioX).toInt()
            val y = (height * locator.ratioY).toInt()
            RpaHit(
                x = x + locator.offsetX,
                y = y + locator.offsetY,
                score = 1.0,
                detail = "vision ratio=${locator.ratioX},${locator.ratioY}",
                line = RpaLine.VISION,
            )
        }
    }

    private fun pickOcr(lines: List<OcrLine>, pick: RpaPick): OcrLine? {
        if (lines.isEmpty()) return null
        return when (pick) {
            RpaPick.FIRST -> lines.minByOrNull { it.top }
            RpaPick.TOP_MOST -> lines.minByOrNull { it.top }
            RpaPick.BOTTOM_MOST -> lines.maxByOrNull { it.bottom }
            RpaPick.LARGEST -> lines.maxByOrNull {
                (it.right - it.left).toLong() * (it.bottom - it.top)
            }
            RpaPick.NEAREST -> lines.minByOrNull { it.centerY }
        }
    }

    /**
     * A11Y 线取屏文本：readScope 非空时只读 scope 节点范围内的文本（按节点
     * bounds 中心过滤，禁跨范围取值）；scope 节点未命中时读空，不退回整屏。
     */
    private suspend fun a11yTextLines(readScope: RpaSelector? = null): List<String> =
        withContext(Dispatchers.Main) {
            runCatching {
                // 同 locateA11y：直查经有界探针，超时取空不挂死调用方。
                val nodes = (boundedProbe(A11Y_PROBE_TIMEOUT_MS) { AssistsCore.getAllNodes() }
                    ?: emptyList<AccessibilityNodeInfo>()).filter { it.isVisibleToUser }
                val scoped = if (readScope == null || readScope.isEmpty()) {
                    nodes
                } else {
                    val scopeBounds = nodes.firstOrNull { matches(it, readScope) }
                        ?.let { node ->
                            val bounds = Rect()
                            node.getBoundsInScreen(bounds)
                            bounds
                        } ?: return@runCatching emptyList<String>()
                    nodes.filter { node ->
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        !bounds.isEmpty && scopeBounds.contains(bounds.centerX(), bounds.centerY())
                    }
                }
                scoped.asSequence()
                    .mapNotNull { node ->
                        node.text?.toString()?.takeIf { text -> text.isNotBlank() }
                            ?: node.contentDescription?.toString()?.takeIf { text -> text.isNotBlank() }
                    }
                    .distinct()
                    .take(400)
                    .toList()
            }.getOrDefault(emptyList())
        }

    /** VISION 线帧源：Shizuku screencap 优先，其次投影；绝不用 a11y 截图。 */
    private suspend fun visionFrame(): Bitmap? =
        runCatching { shizuku.screencap() }.getOrNull()
            ?: screenCapture.captureNow(qualityCheck = false).getOrNull()

    private fun foregroundProbeAvailable(line: RpaLine): Boolean = when (line) {
        RpaLine.A11Y -> AssistsService.getOrNull() != null && bridge.connected.value
        RpaLine.VISION -> shizuku.available
    }

    /** [boundedProbe] 专用线程池：守护线程，承接可能永久卡死的 binder 查询。 */
    private val probeExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "rpa-a11y-probe").apply { isDaemon = true }
    }

    /**
     * 有界 a11y 查询（spec §六 有界干净退出的执行面保障）：无障碍节点 / 根节点
     * 查询在目标窗口无响应时会在 binder 层**无限挂起**（协程取消无法打断阻塞的
     * binder 调用），2026-10-31 真机实测前台探针卡 24 分钟拖死整条运行。任何
     * a11y 直查都必须经本方法包一层：[timeoutMs] 内拿不到结果按「未知」（null）
     * 返回，由调用方的轮询预算收口成稳定错误码；卡死的查询线程是守护线程，
     * 不阻塞调用方、不碍进程退出。
     */
    private suspend fun <T> boundedProbe(timeoutMs: Long, block: () -> T): T? =
        withContext(Dispatchers.IO) {
            val future = probeExecutor.submit(Callable { block() })
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                null
            } catch (e: Exception) {
                null
            }
        }

    private suspend fun awaitForeground(packageName: String, line: RpaLine): Boolean {
        // 墙钟预算（原「20 次 × 250ms」在探针阻塞时会被无限放大）：预算耗尽
        // 即干净失败 launch_timeout，冷启动慢的 App 也够等（10s）。
        val deadline = System.currentTimeMillis() + FOREGROUND_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (foregroundPackage(line) == packageName) return true
            delay(FOREGROUND_POLL_MS)
        }
        return false
    }

    /**
     * 关闭应用（图5「仅关闭应用 / 重新打开(重启)」的关闭相）：
     * 平台级 [ActivityManager.killBackgroundProcesses]（与 startActivity 同级、
     * 线路中立）先杀后台进程；VISION 线另有本线 Shizuku `am force-stop` 强杀通道；
     * A11Y 线没有 shell 强杀通道——前台进程杀不掉时显式报 `close_app_failed`，
     * 绝不静默假装关闭成功。
     */
    private suspend fun closeApp(packageName: String, line: RpaLine): Result<Unit> {
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.killBackgroundProcesses(packageName)
        }
        if (line == RpaLine.VISION && shizuku.available) {
            val stopped = shizuku.forceStop(packageName)
            if (stopped.isFailure) {
                return Result.failure(
                    IllegalStateException(
                        "close_app_failed: ${stopped.exceptionOrNull()?.message}",
                    ),
                )
            }
        }
        if (!foregroundProbeAvailable(line)) {
            // 与 launchApp 同口径：纯投影视觉线无前台探针只能尽力关闭；
            // A11Y 线探针缺失必须报因，不许静默。
            if (line == RpaLine.A11Y) {
                return Result.failure(IllegalStateException("a11y_not_ready"))
            }
            return Result.success(Unit)
        }
        repeat(CLOSE_POLLS) {
            if (foregroundPackage(line) != packageName) return Result.success(Unit)
            delay(CLOSE_POLL_MS)
        }
        return Result.failure(IllegalStateException("close_app_failed: $packageName"))
    }

    private fun Bitmap.toJpegBytes(): ByteArray? = runCatching {
        ByteArrayOutputStream().use { sink ->
            if (compress(Bitmap.CompressFormat.JPEG, OCR_JPEG_QUALITY, sink)) {
                sink.toByteArray()
            } else {
                null
            }
        }
    }.getOrNull()

    private fun RpaRect.toRect(frameWidth: Int, frameHeight: Int): Rect {
        val r = normalized()
        return Rect(
            (r.left * frameWidth).toInt().coerceIn(0, frameWidth - 1),
            (r.top * frameHeight).toInt().coerceIn(0, frameHeight - 1),
            (r.right * frameWidth).toInt().coerceIn(1, frameWidth),
            (r.bottom * frameHeight).toInt().coerceIn(1, frameHeight),
        )
    }

    private data class Candidate(
        val bounds: Rect,
        val selectorIndex: Int,
        val order: Int,
    )

    private companion object {
        const val FOREGROUND_BUDGET_MS = 10_000L
        const val FOREGROUND_POLL_MS = 250L
        const val A11Y_PROBE_TIMEOUT_MS = 2_000L
        const val CLOSE_POLLS = 10
        const val CLOSE_POLL_MS = 200L
        const val LAUNCH_SETTLE_MS = 1_200L
        const val LAUNCH_SHELL_TIMEOUT_MS = 5_000L
        const val INPUT_READY_BUDGET_MS = 3_000L
        const val INPUT_READY_POLL_MS = 200L
        const val OCR_JPEG_QUALITY = 90
        const val CLIPBOARD_RESTORE_DELAY_MS = 300L
    }
}
