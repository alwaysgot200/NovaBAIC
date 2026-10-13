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

package com.verlintas.baic2.floatball

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import com.verlintas.baic2.EXTRA_EXIT
import com.verlintas.baic2.EXTRA_FROM_FLOAT
import com.verlintas.baic2.MainActivity
import com.verlintas.baic2.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮球前台服务：窗口增删、手势路由、状态机、把 [ScreenAdvisor] 的事件
 * 渲染到 [FloatUi] 视图。
 *
 * 状态机：Idle（蓝球）→ 单击=单次参谋（旋转环·锁定输入）→ 完成自动弹
 * 分屏；Idle → 双击=连续参谋（红球 + 差分循环 + 红点计数）；长按=六子球
 * 扇形菜单（仅 Idle）。任务期间（OneShot/Recording）长按被拒绝。
 */
@AndroidEntryPoint
class FloatBallService : Service() {

    @Inject
    lateinit var screenAdvisor: ScreenAdvisor

    @Inject
    lateinit var diffEngine: FrameDiffEngine

    @Inject
    lateinit var advisorPrefs: AdvisorPrefs

    private enum class Mode { IDLE, ONESHOT, RECORDING }

    private var mode = Mode.IDLE

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ball: FloatUi.BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var fanMenu: FloatUi.FanMenu? = null

    private var split: FloatUi.SplitPanel? = null
    private var splitParams: WindowManager.LayoutParams? = null
    private var transcriptView: TranscriptView? = null

    private var ballX = Int.MIN_VALUE
    private var ballY = Int.MIN_VALUE

    private var askJob: Job? = null
    private var consentPending = false

    /** 同意授权后的去向：单次重跑 or 进入录制。 */
    private var pendingConsentAction: ((Int, android.content.Intent?) -> Unit)? = null
    private var completionCount = 0

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> diffEngine.pause()
                Intent.ACTION_SCREEN_ON -> diffEngine.resume()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        startInForeground()
        showBall()
        registerReceiver(
            screenOffReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        scope.launch {
            // 每个完成的 LLM 回合点亮一次红点（连续模式的用户信号）。
            screenAdvisor.turnCompletions.collect {
                if (mode == Mode.RECORDING) {
                    completionCount++
                    ball?.setBadge(completionCount)
                }
            }
        }
        scope.launch {
            // 转录面板跟随会话数据流刷新（分屏开着才渲染）。
            screenAdvisor.transcript.collect { state ->
                transcriptView?.render(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BALL -> if (ball == null && mode != Mode.ONESHOT) showBall()
            // 免双击入口（通知栏自动化/测试）：与双击同一状态机路径。
            ACTION_START_RECORDING -> if (mode == Mode.IDLE && ball != null) startRecording()
            ACTION_STOP_RECORDING -> if (mode == Mode.RECORDING) stopRecording()
        }
        // OneShot 的像素截屏期间严禁把球加回（会污染 OCR 帧），跳过兜底显示。
        val overlayIdle = ball == null && menuView == null && split == null
        if (overlayIdle && mode != Mode.ONESHOT) showBall()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        mainHandler.removeCallbacksAndMessages(null)
        askJob?.cancel()
        diffEngine.destroy()
        screenAdvisor.advisorScopeCancel()
        scope.cancel()
        FloatCaptureBridge.clear()
        removeAllOverlays()
        super.onDestroy()
    }

    // -------------------------------------------------------- gestures

    /** 手势路由：单击确认/双击/长按交给 GestureDetector，拖动本地处理。 */
    private fun attachBallGesture(view: FrameLayout, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    when (mode) {
                        Mode.IDLE -> startOneShot()
                        Mode.RECORDING -> toggleSplit()
                        Mode.ONESHOT -> Unit // 忙碌：单击无效
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    when (mode) {
                        Mode.IDLE -> startRecording()
                        Mode.RECORDING -> stopRecording()
                        Mode.ONESHOT -> Unit // 忙碌：双击无效
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // 任务期间拒绝长按，空闲才出扇形菜单。
                    if (mode == Mode.IDLE) expandMenu()
                }
            },
        )

        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    // 单击预热：第一触点即开始读无障碍文本，摊销消歧延迟。
                    if (mode == Mode.IDLE) screenAdvisor.preheat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        params.x = (startX + dx.toInt()).coerceIn(
                            -dp(FloatUi.BALL_SIZE_DP / 2),
                            metrics.widthPixels - dp(FloatUi.BALL_SIZE_DP / 2),
                        )
                        params.y = (startY + dy.toInt()).coerceIn(
                            dp(FloatUi.MARGIN_DP),
                            metrics.heightPixels - dp(FloatUi.BALL_SIZE_DP + FloatUi.MARGIN_DP),
                        )
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                else -> true
            }
        }
    }

    // ------------------------------------------------------- one shot

    private fun startOneShot() {
        if (mode != Mode.IDLE) return
        mode = Mode.ONESHOT
        ball?.setMode(FloatUi.BallMode.BUSY, this)
        val preheated = screenAdvisor.consumePreheat()
        val needsHide = preheated == null || preheated.length < ScreenPerceiver.MIN_TEXT_CHARS
        if (needsHide) hideAllOverlays() // 像素路径：先撤自身浮层防污染
        runOneShot(preheated.takeIf { !needsHide }, settleMs = 0L)
    }

    private fun runOneShot(preheated: String?, settleMs: Long) {
        // 授权重跑路径会再次进入：恢复忙碌态（授权等待期 flow 结束已把 mode
        // 归位 IDLE），防止等待窗口内被并发触发第二个参谋。
        mode = Mode.ONESHOT
        ball?.setMode(FloatUi.BallMode.BUSY, this)
        askJob = scope.launch {
            try {
                screenAdvisor.adviseOnce(preheated).collect { event ->
                    when (event) {
                        ScreenAdvisor.Event.NeedsConsent -> requestProjectionConsent { granted ->
                            if (granted) runOneShot(null, settleMs = CONSENT_SETTLE_MS)
                        }
                        is ScreenAdvisor.Event.TurnStarted -> ensureSplit()
                        is ScreenAdvisor.Event.Failed -> ensureSplit() // 错误也要可见
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                screenAdvisor.reportUnexpected(error.message ?: "unknown")
                ensureSplit()
            } finally {
                finishOneShot()
            }
        }
    }

    /** 单次结束：恢复蓝球（分屏留着展示结果）。 */
    private fun finishOneShot() {
        if (mode == Mode.ONESHOT) {
            mode = Mode.IDLE
            restoreBall()
        }
    }

    // ----------------------------------------------------- recording

    private fun startRecording() {
        if (mode != Mode.IDLE) return
        mode = Mode.RECORDING
        completionCount = 0
        ball?.setMode(FloatUi.BallMode.RECORDING, this)
        closeSplit(keepBall = true) // 录制开始前撤掉自身分屏
        hideAllOverlays()
        if (screenAdvisor.isCaptureReady()) {
            startRecordingLoop()
        } else {
            requestProjectionConsent { granted ->
                if (granted) {
                    mainHandler.postDelayed({ startRecordingLoop() }, CONSENT_SETTLE_MS)
                } else {
                    abortRecording()
                }
            }
        }
    }

    private fun startRecordingLoop() {
        diffEngine.start(
            maskProvider = { currentOverlayRects() },
            onSignal = { signal -> mainHandler.post { handleEngineSignal(signal) } },
        )
        restoreBall()
        toast(R.string.float_recording_started)
    }

    private fun handleEngineSignal(signal: FrameDiffEngine.Signal) {
        when (signal) {
            is FrameDiffEngine.Signal.Failed -> {
                toast(R.string.float_recording_failed)
                stopRecording()
            }
            FrameDiffEngine.Signal.SessionExpired -> {
                toast(R.string.float_recording_session_expired)
                stopRecording()
            }
        }
    }

    private fun stopRecording() {
        diffEngine.stop()
        screenAdvisor.releaseProjection()
        abortRecording()
        toast(R.string.float_recording_stopped)
    }

    /** 录制态回退到 Idle：球已存在时也要恢复外观（不重建窗口）。 */
    private fun abortRecording() {
        mode = Mode.IDLE
        completionCount = 0
        ball?.setMode(FloatUi.BallMode.NORMAL, this)
        ball?.setBadge(0)
        restoreBall()
    }

    /** 录屏中单击红球：开/合实时分屏。 */
    private fun toggleSplit() {
        if (split != null) {
            closeSplit(keepBall = true)
        } else {
            ensureSplit()
        }
    }

    /** 连续模式的 OCR 前掩膜：自身分屏 + 悬浮球的屏幕矩形。 */
    private fun currentOverlayRects(): List<Rect> {
        val rects = mutableListOf<Rect>()
        val metrics = resources.displayMetrics
        split?.let {
            val height = splitParams?.height ?: 0
            if (height > 0) rects.add(Rect(0, metrics.heightPixels - height, metrics.widthPixels, metrics.heightPixels))
        }
        ballParams?.let {
            rects.add(Rect(it.x, it.y, it.x + dp(FloatUi.BALL_SIZE_DP), it.y + dp(FloatUi.BALL_SIZE_DP)))
        }
        return rects
    }

    // ------------------------------------------------------ consent

    /** MediaProjection 授权必须由 Activity 发起：透明 Activity 中转。 */
    private fun requestProjectionConsent(onResult: (Boolean) -> Unit) {
        if (consentPending) return
        consentPending = true
        pendingConsentAction = { resultCode, data ->
            consentPending = false
            mainHandler.removeCallbacks(consentWatchdog)
            val granted = resultCode == Activity.RESULT_OK && data != null &&
                screenAdvisor.onPermissionResult(resultCode, data)
            onResult(granted)
        }
        FloatCaptureBridge.consentIntent = screenAdvisor.createPermissionIntent()
        FloatCaptureBridge.onConsentResult = { resultCode, data ->
            pendingConsentAction?.invoke(resultCode, data)
            pendingConsentAction = null
        }
        mainHandler.postDelayed(consentWatchdog, CONSENT_WATCHDOG_MS)
        runCatching {
            startActivity(
                Intent(this, FloatCaptureActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_NO_HISTORY,
                ),
            )
        }.onFailure {
            consentPending = false
            pendingConsentAction = null
            FloatCaptureBridge.clear()
            mainHandler.removeCallbacks(consentWatchdog)
            onResult(false)
        }
    }

    private val consentWatchdog = Runnable {
        if (!consentPending) return@Runnable
        consentPending = false
        pendingConsentAction = null
        FloatCaptureBridge.clear()
        if (mode == Mode.ONESHOT) finishOneShot()
        if (mode == Mode.RECORDING) abortRecording()
        restoreBall()
    }

    // ---------------------------------------------------------- overlays

    private fun showBall() {
        if (ball != null) return
        val metrics = resources.displayMetrics
        if (ballX == Int.MIN_VALUE) {
            ballX = metrics.widthPixels - dp(FloatUi.BALL_SIZE_DP + FloatUi.MARGIN_DP)
            ballY = metrics.heightPixels / 3
        }
        val params = overlayParams(
            width = dp(FloatUi.BALL_SIZE_DP + 12),
            height = dp(FloatUi.BALL_SIZE_DP + 12),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
        }
        val view = FloatUi.buildBall(this)
        attachBallGesture(view.root, params)
        windowManager.addView(view.root, params)
        ball = view
        ballParams = params
        // 重建后按当前状态机恢复外观（录制态=红球）。
        view.setMode(
            when (mode) {
                Mode.RECORDING -> FloatUi.BallMode.RECORDING
                Mode.ONESHOT -> FloatUi.BallMode.BUSY
                Mode.IDLE -> FloatUi.BallMode.NORMAL
            },
            this,
        )
        if (mode == Mode.RECORDING) view.setBadge(completionCount)
    }

    private fun ensureSplit(): FloatUi.SplitPanel {
        split?.let { return it }
        // 录制态保留红球（用户靠再点红球合分屏，掩膜会盖住它）；其余场景
        // 撤掉全部浮层，避免分屏与球同屏（遮挡/误触）。
        if (mode != Mode.RECORDING) hideAllOverlays()
        val metrics = resources.displayMetrics
        val params = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = (metrics.heightPixels * FloatUi.SPLIT_DEFAULT_RATIO).toInt(),
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
        }
        val panel = FloatUi.buildSplitPanel(this, onClose = { closeSplit(keepBall = false) })
        panel.handle.setOnTouchListener(makeResizeListener())

        // 正文区挂载轻量转录视图：与主界面同一个「老参谋」会话的数据流。
        val transcript = TranscriptView(this)
        transcriptView = transcript
        panel.transcriptContainer.addView(
            transcript,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        windowManager.addView(panel.root, params)
        split = panel
        splitParams = params
        // StateFlow 无新值不发射: 新视图创建后立即喂当前转录快照。
        transcript.render(screenAdvisor.transcript.value)
        return panel
    }

    /** 上下拖动把手：调整分屏高度，即「上下分界线」。 */
    private fun makeResizeListener(): View.OnTouchListener {
        var downY = 0f
        var startHeight = 0
        return View.OnTouchListener { _, event ->
            val params = splitParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startHeight = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = resources.displayMetrics
                    val height = (startHeight + (downY - event.rawY)).toInt().coerceIn(
                        (metrics.heightPixels * FloatUi.SPLIT_MIN_RATIO).toInt(),
                        (metrics.heightPixels * FloatUi.SPLIT_MAX_RATIO).toInt(),
                    )
                    if (height != params.height) {
                        params.height = height
                        runCatching { windowManager.updateViewLayout(split?.root, params) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun closeSplit(keepBall: Boolean) {
        askJob?.cancel()
        askJob = null
        split?.let { runCatching { windowManager.removeView(it.root) } }
        split = null
        splitParams = null
        transcriptView = null
        screenAdvisor.resetTransient()
        if (!keepBall) restoreBall()
    }

    // ------------------------------------------------------------- menu

    /** 长按：悬浮球周围弹出 6 个子球（问答/主界面/设置/隐藏/关于/退出）。 */
    private fun expandMenu() {
        if (menuView != null || ball == null) return
        val from = ballParams ?: return

        val metrics = resources.displayMetrics
        val side = FloatUi.fanSidePx(this)
        val ballCenterX = from.x + dp(FloatUi.BALL_SIZE_DP) / 2
        val ballCenterY = from.y + dp(FloatUi.BALL_SIZE_DP) / 2
        val params = overlayParams(width = side, height = side).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (ballCenterX - side / 2).coerceIn(0, (metrics.widthPixels - side).coerceAtLeast(0))
            y = (ballCenterY - side / 2).coerceIn(0, (metrics.heightPixels - side).coerceAtLeast(0))
        }

        val items = listOf(
            FloatUi.FanItem(R.string.float_fan_ask) { startOneShot() },
            FloatUi.FanItem(R.string.float_fan_main) { showMainUi() },
            FloatUi.FanItem(R.string.float_fan_settings) { openFloatSettings() },
            FloatUi.FanItem(R.string.float_fan_hide) { hideBall() },
            FloatUi.FanItem(R.string.float_fan_about) { showAboutDialog() },
            FloatUi.FanItem(R.string.float_fan_exit) { exitApp() },
        )
        val fan = FloatUi.buildFanMenu(this, items, onDismiss = { collapseMenu() })
        windowManager.addView(fan.root, params)
        menuView = fan.root
        fanMenu = fan
        fan.expand()
    }

    private fun collapseMenu() {
        val fan = fanMenu ?: return
        fanMenu = null
        menuView = null
        fan.collapse()
        // 等收起动画走完再摘窗口。
        mainHandler.postDelayed({
            runCatching { windowManager.removeView(fan.root) }
        }, FloatUi.FAN_ANIM_MS + 60)
    }

    // ------------------------------------------------ main / dialogs

    private fun openFloatSettings() {
        startActivity(
            Intent(this, FloatSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun showMainUi() {
        closeSplit(keepBall = false)
        collapseMenu()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
                putExtra(EXTRA_FROM_FLOAT, true)
            },
        )
    }

    private fun showAboutDialog() {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val message = listOf(
            getString(R.string.float_about_tagline),
            getString(
                R.string.float_about_version,
                info?.versionName ?: "?",
                info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
            ),
            getString(R.string.float_about_package, packageName),
            getString(R.string.float_about_license),
            getString(R.string.float_about_copyright),
        ).joinToString("\n")
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle(getString(R.string.app_name))
            .setMessage(message)
            .setPositiveButton(getString(R.string.float_about_ok), null)
            .create()
        applyOverlayWindowType(dialog)
        dialog.show()
    }

    /** 服务上下文没有窗口 token，对话框窗口必须声明为应用叠加层。 */
    private fun applyOverlayWindowType(dialog: AlertDialog) {
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    /** 隐藏悬浮球（子球「隐藏」）：通知栏「显示悬浮球」或冷启动恢复。 */
    private fun hideBall() {
        collapseMenu()
        ballParams?.let {
            ballX = it.x
            ballY = it.y
        }
        ball?.root?.let { runCatching { windowManager.removeView(it) } }
        ball = null
        toast(R.string.float_ball_hidden_hint)
    }

    private fun exitApp() {
        removeAllOverlays()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_EXIT, true)
            },
        )
    }

    // ------------------------------------------------------ notifications

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.float_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.float_notification_title))
            .setContentText(getString(R.string.float_notification_text))
            .setContentIntent(mainActivityPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_view,
                getString(R.string.float_notification_show),
                showBallPendingIntent(),
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun mainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).putExtra(EXTRA_FROM_FLOAT, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showBallPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, FloatBallService::class.java).setAction(ACTION_SHOW_BALL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------- utils

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )

    private fun restoreBall() {
        if (ball == null && menuView == null && split == null) showBall()
    }

    private fun hideAllOverlays() {
        ball?.root?.let { runCatching { windowManager.removeView(it) } }
        ball = null
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
        fanMenu = null
    }

    private fun removeAllOverlays() {
        hideAllOverlays()
        split?.let { runCatching { windowManager.removeView(it.root) } }
        split = null
        splitParams = null
        transcriptView = null
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val CHANNEL_ID = "float_ball"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SHOW_BALL = "com.verlintas.baic2.floatball.SHOW_BALL"
        const val ACTION_START_RECORDING = "com.verlintas.baic2.floatball.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.verlintas.baic2.floatball.STOP_RECORDING"
        const val CONSENT_SETTLE_MS = 350L
        const val CONSENT_WATCHDOG_MS = 120_000L
    }
}
