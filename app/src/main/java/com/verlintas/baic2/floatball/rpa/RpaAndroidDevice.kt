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
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.floatball.auto.AssistsA11yBridge
import com.verlintas.baic2.floatball.auto.CvMatch
import com.verlintas.baic2.floatball.auto.ImChatNavigator
import com.verlintas.baic2.floatball.auto.ScreenCapture
import com.verlintas.baic2.floatball.auto.ShizukuInjector
import com.verlintas.rpa.engine.RpaDevice
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaCapabilities
import com.verlintas.rpa.model.RpaHit
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }.mapCatching {
                if (!foregroundProbeAvailable(line)) {
                    // 纯投影视觉线没有前台探针（无 Shizuku）；startActivity 成功
                    // 即视为启动完成，给冷启动留出稳定时间，绝不空等超时报错。
                    if (line == RpaLine.A11Y) error("a11y_not_ready")
                    delay(LAUNCH_SETTLE_MS)
                } else if (!awaitForeground(packageName, line)) {
                    error("launch_timeout: $packageName")
                }
            }
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
                IllegalStateException("open_chat_failed: ${result.reason}"),
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
    ): Result<Unit> = when (line) {
        RpaLine.A11Y -> bridge.typeText(text)
        RpaLine.VISION -> {
            val cleared = if (clearFirst) {
                runCatching { shizuku.clearInput() }.getOrNull()
            } else {
                null
            }
            if (cleared?.isFailure == true) {
                Result.failure(
                    IllegalStateException("clear_input_failed: ${cleared.exceptionOrNull()?.message}"),
                )
            } else {
                shizuku.type(text)
            }
        }
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

    override suspend fun textLines(line: RpaLine): List<String> = when (line) {
        RpaLine.A11Y -> a11yTextLines()
        RpaLine.VISION -> {
            val frame = visionFrame() ?: return emptyList()
            ocrLines(frame).map { it.text }
        }
    }

    override suspend fun foregroundPackage(line: RpaLine): String? = when (line) {
        RpaLine.A11Y -> bridge.foregroundPackage()
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
        val nodes = runCatching { AssistsCore.getAllNodes() }.getOrDefault(emptyList())
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

    private suspend fun a11yTextLines(): List<String> = withContext(Dispatchers.Main) {
        runCatching {
            AssistsCore.getAllNodes().asSequence()
                .filter { it.isVisibleToUser }
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

    private suspend fun awaitForeground(packageName: String, line: RpaLine): Boolean {
        repeat(FOREGROUND_POLLS) {
            delay(FOREGROUND_POLL_MS)
            if (foregroundPackage(line) == packageName) return true
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
        const val FOREGROUND_POLLS = 20
        const val FOREGROUND_POLL_MS = 250L
        const val CLOSE_POLLS = 10
        const val CLOSE_POLL_MS = 200L
        const val LAUNCH_SETTLE_MS = 1_200L
        const val OCR_JPEG_QUALITY = 90
    }
}
