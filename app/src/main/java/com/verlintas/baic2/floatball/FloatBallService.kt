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
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.verlintas.baic2.EXTRA_EXIT
import com.verlintas.baic2.EXTRA_FROM_FLOAT
import com.verlintas.baic2.EXTRA_OPEN_TAB
import com.verlintas.baic2.MainActivity
import com.verlintas.baic2.R
import com.verlintas.baic2.TAB_SETTINGS
import com.verlintas.baic2.floatball.director.AdvisorNotificationListener
import com.verlintas.baic2.floatball.auto.CompanionWindowController
import com.verlintas.baic2.floatball.notify.EscalationNotifier
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.keepalive.KeepAliveManager
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
 * 状态机：Idle（蓝球）→ 单击=六子球扇形菜单（单次/连续/自动/主界面/更多/
 * 退出，仅 Idle，双击消歧后弹出）；长按=单次参谋（变色·灰白蒙层·旋转环·
 * 锁定输入，完成自动弹分屏）；双击=连续参谋（红球 + 差分循环 + 红点计数）。
 * 任务期间（OneShot）一切手势忽略；录制态仅保留单击红球（分屏开合）与
 * 双击（停止），长按拒绝。「自动」=托管范围选择器、「更多」=次级入口，
 * 均为贴球卡片弹窗（card menus 分节）；自动托管感知的是聊天 APP 的通知，
 * 与当前屏幕无关（单次/连续才基于当前屏幕内容）。
 */
@AndroidEntryPoint
class FloatBallService : Service() {

    @Inject
    lateinit var screenAdvisor: ScreenAdvisor

    @Inject
    lateinit var screenCapture: com.verlintas.baic2.floatball.auto.ScreenCapture

    @Inject
    lateinit var diffEngine: FrameDiffEngine

    @Inject
    lateinit var advisorPrefs: AdvisorPrefs

    @Inject
    lateinit var keepAliveManager: KeepAliveManager

    /** M2-b 主动保温检点：服务创建（含开机自启路径）时重挂周期闹钟。 */
    @Inject
    lateinit var proactiveCheckpoint: com.verlintas.baic2.floatball.director.ProactiveCheckpoint

    /** M3-b 陪伴窗口：自动档的窗口治理面（互斥/硬审/拟人节奏/收尾汇总）。 */
    @Inject
    lateinit var companionController: CompanionWindowController

    /** 联系人档案与会话查询（自动托管范围选择器的数据面，方案 §3.3）。 */
    @Inject
    lateinit var contactRepository: com.verlintas.baic2.floatball.advisor.ContactRepository

    /** 范围选定后拉起目标聊天页（RPA 搜索导航，方案 §3.3 接线）。 */
    @Inject
    lateinit var imChatNavigator: com.verlintas.baic2.floatball.auto.ImChatNavigator

    private enum class Mode { IDLE, ONESHOT, RECORDING }

    private var mode = Mode.IDLE

    /** M3-b：RECORDING 态的子语义——true=自动陪伴窗口（收尾走窗口汇总通知）。 */
    private var companionWindow = false

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ball: FloatUi.BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var fanMenu: FloatUi.FanMenu? = null

    private var split: FloatUi.SplitPanel? = null
    private var splitParams: WindowManager.LayoutParams? = null

    /**
     * 分屏窗口临时摘除标记（像素采集防污染，铁律 §8.1）：采集前
     * [detachSplitWindow] 摘窗（视图与状态保留），采集完 [reattachSplitWindow]
     * 挂回——「画面重复」路径用户也不丢已打开的结果面板。
     */
    private var splitDetached = false

    /** 键盘弹起前的用户面板高度（-1=当前未避让），键盘收起后还原。 */
    private var splitHeightBeforeIme = -1
    private var transcriptView: TranscriptView? = null
    private var inputHandle: FloatUi.InputBarHandle? = null

    /** 展开的扇形几何（跟随主球移动时定位用）。 */
    private var fanWidth = 0
    private var fanHeight = 0
    private var menuParams: WindowManager.LayoutParams? = null

    /** 更多/范围选择卡片窗（全屏接罩，方案 §3.2/§3.3）：与 menuView 同步清理。 */
    private var cardMenuView: View? = null
    private var cardMenuParams: WindowManager.LayoutParams? = null

    /** 扇形菜单整体拖动开始时主球窗口的起点（onDragBy 累计位移的基准）。 */
    private var fanDragStartX = 0
    private var fanDragStartY = 0

    private var ballX = Int.MIN_VALUE
    private var ballY = Int.MIN_VALUE

    private var askJob: Job? = null

    /** 输入行手动发送的回合（区别于长按单次 askJob / 连续模式 diffEngine）。 */
    private var manualJob: Job? = null
    private var consentPending = false
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
        isRunning = true
        windowManager = getSystemService(WindowManager::class.java)
        // 保活降级路径可能先挂了占位 overlay，正式球出现前摘除。
        keepAliveManager.removePlaceholderOverlay()
        startInForeground()
        keepAliveManager.onServiceCreated()
        showBall()
        // M2-b：开机/服务重启后检点闹钟重挂（总闸关时内部自会撤销）。
        proactiveCheckpoint.ensureScheduled()
        registerReceiver(
            screenOffReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        scope.launch {
            // 未读徽章：只有「连续模式 + 分屏未打开（用户没在看）」时完成的
            // LLM 回合才计入；分屏开着时回答实时可见=已读，不计数。开/合分屏
            // 时在 ensureSplit/closeSplit 维护计数清零。
            screenAdvisor.turnCompletions.collect {
                if (mode == Mode.RECORDING && split == null) {
                    completionCount++
                    ball?.setBadge(completionCount)
                }
            }
        }
        scope.launch {
            // 连续模式 token 预算耗尽：当前回合已完整落库后自动停录。
            screenAdvisor.budgetExceeded.collect {
                if (mode == Mode.RECORDING) {
                    mainHandler.post {
                        toast(R.string.float_token_budget_exhausted)
                        stopRecording()
                    }
                }
            }
        }
        scope.launch {
            // 转录面板跟随会话数据流刷新（分屏开着才渲染）；NON_CHAT 场景变化
            // 同步驱动联系人状态条（v10 §1.3，VA-2 U3 隐藏人设区）。
            screenAdvisor.transcript.collect { state ->
                transcriptView?.render(state)
                applyStatusBarScene()
                // 思考态变化联动输入行发送/停止键外观。
                inputHandle?.refresh()
            }
        }
        scope.launch {
            // 联系人状态条：绑定显示主名，未绑定显示引导，点击进画像管理页；
            // 具体按场景裁决收敛到 applyStatusBarScene（transcript/activeContact 双流汇合）。
            screenAdvisor.activeContact.collect { _ ->
                applyStatusBarScene()
            }
        }
        scope.launch {
            // 即时方向 chip：选中态/文案联动；下拉菜单打开期间 AI 建议到达，
            // 就地重建菜单（loading 行换成建议列表）。
            screenAdvisor.direction.collect { st ->
                split?.let { renderDirectionChip(it.directionChip, st) }
                if (directionPopupKind == DirectionPopupKind.MENU && st.suggestions.isNotEmpty()) {
                    showDirectionMenu()
                }
            }
        }
        scope.launch {
            // P2 分屏策略条：planning/轻确认问句/本轮任务令三态（分屏开着才渲染）。
            screenAdvisor.missionUi.collect { state ->
                mainHandler.post {
                    split?.let { FloatUi.renderStrategyStrip(it.strategyStrip, state, strategyCallbacks) }
                }
            }
        }
    }

    /** 策略条回调：✓对/跳过走 Advisor 串行；✎改一下先关问句再直达目标 Tab。 */
    private val strategyCallbacks = object : FloatUi.StrategyStripCallbacks {
        override fun onProvisionalYes(goalId: Long) {
            scope.launch { screenAdvisor.confirmProvisionalGoal(goalId) }
        }

        override fun onProvisionalEdit(goalId: Long) {
            screenAdvisor.dismissProvisionalGoal()
            // 轻确认问句只会在已绑定联系人回合出现；兜底 -1 由画像页自行落到列表。
            val contactId = screenAdvisor.activeContact.value?.contactId ?: -1L
            openProfileManager(
                contactId = contactId,
                tab = AdvisorProfileActivity.TAB_GOALS,
                goalId = goalId,
            )
        }

        override fun onProvisionalSkip(goalId: Long) {
            scope.launch { screenAdvisor.skipProvisionalGoal(goalId) }
        }

        override fun onMissionToggle() {
            screenAdvisor.toggleMissionExpanded()
        }

        override fun onBranchAccept() {
            scope.launch { screenAdvisor.acceptPendingBranch() }
        }

        override fun onBranchReject() {
            screenAdvisor.rejectPendingBranch()
        }

        override fun onReroute() {
            screenAdvisor.requestReroute()
        }

        override fun onRerouteAccept() {
            screenAdvisor.acceptPendingReroute()
        }

        override fun onRerouteReject() {
            screenAdvisor.rejectPendingReroute()
        }
    }

    /**
     * 快速绑定条回调（§1.5 最快关联/新建）：三条全走 [ScreenAdvisor] 门面，
     * 服务层不直接碰仓库；失败 Toast 报因（§4.5 严禁静默失效）。
     */
    private val bindCallbacks = object : FloatUi.BindSheetCallbacks {
        override fun onCreateBind(imPackage: String, rawTitle: String) {
            scope.launch {
                when (screenAdvisor.quickBind(imPackage, rawTitle)) {
                    null -> {
                        split?.bindSheet?.root?.visibility = View.GONE
                        applyStatusBarScene()
                    }
                    "empty_title" -> toast(R.string.float_bind_empty)
                    else -> toast(R.string.float_bind_ambiguous)
                }
            }
        }

        override fun onBindExisting(contactId: Long) {
            scope.launch {
                if (screenAdvisor.bindExisting(contactId) != null) {
                    split?.bindSheet?.root?.visibility = View.GONE
                    applyStatusBarScene()
                } else {
                    toast(R.string.float_bind_failed)
                }
            }
        }

        override fun onManage() {
            val candidate = screenAdvisor.lastChatCandidate
            openProfileManager(
                contactId = -1L,
                candidateTitle = candidate?.second,
                candidatePkg = candidate?.first,
            )
        }
    }

    /**
     * 联系人状态条按场景渲染（v10 §1.3，VA-2 U3 修订 → §1.5 快速绑定）：
     * NON_CHAT 分屏不出现「未绑定联系人」的人设暗示，显示纯问答标识；CHAT 屏
     * 绑定显示主名、未绑定提示点此关联。点击统一走 [onStatusBarClick]——
     * 已绑定进画像详情，未绑定展开/收起分屏内嵌快速绑定条（场景误判时
     * 用户仍可手动建档纠正，§4.5 严禁静默失效）。transcript 与
     * activeContact 双流的汇合点：任一发射都重读双流现值，两个收集器并发时
     * 以最后一次渲染为准（幂等）。
     */
    private fun applyStatusBarScene() {
        val panel = split ?: return
        panel.statusBar.isClickable = true
        panel.statusBar.setOnClickListener { onStatusBarClick() }
        panel.statusBar.text = if (screenAdvisor.transcript.value.isNonChat) {
            getString(R.string.float_profile_status_nonchat)
        } else {
            val contact = screenAdvisor.activeContact.value
            if (contact != null) {
                getString(R.string.float_profile_status_bound, contact.displayName)
            } else {
                getString(R.string.float_profile_status_unbound)
            }
        }
    }

    /**
     * 状态条点击（§1.5 最快入口）：已绑定 → 进画像详情；未绑定 → 展开/收起
     * 内嵌快速绑定条。展开时预填识别候选（哪个 APP 的谁）；纯问答屏不透传
     * 候选（此回合未跑识别，旧候选有张冠李戴风险），仅保留默认选中 APP。
     */
    private fun onStatusBarClick() {
        val active = screenAdvisor.activeContact.value
        if (active != null) {
            openProfileManager(active.contactId)
            return
        }
        val panel = split ?: return
        val sheet = panel.bindSheet
        if (sheet.root.visibility == View.VISIBLE) {
            sheet.root.visibility = View.GONE
            return
        }
        if (!screenAdvisor.transcript.value.isNonChat) {
            val (pkg, title) = screenAdvisor.lastChatCandidate ?: (null to null)
            if (pkg != null) sheet.selectApp(pkg)
            if (!title.isNullOrBlank()) sheet.nameField.setText(title)
        }
        sheet.root.visibility = View.VISIBLE
        scope.launch {
            FloatUi.renderBindRecents(sheet, screenAdvisor.recentContacts())
        }
    }

    /**
     * 打开联系人画像管理（§7 状态条入口；带当前联系人/会话便于直接改绑）。
     * [tab]=[AdvisorProfileActivity.TAB_GOALS] 时直达目标 Tab，[goalId]>0 自动弹编辑。
     */
    private fun openProfileManager(
        contactId: Long,
        tab: String? = null,
        goalId: Long = -1L,
        candidateTitle: String? = null,
        candidatePkg: String? = null,
    ) {
        startActivity(
            Intent(this, AdvisorProfileActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AdvisorProfileActivity.EXTRA_CONTACT_ID, contactId)
                if (tab != null) putExtra(AdvisorProfileActivity.EXTRA_TAB, tab)
                if (goalId > 0) putExtra(AdvisorProfileActivity.EXTRA_GOAL_ID, goalId)
                // 快速绑定（未绑定 → 一键建档/绑到已有档案）：把识别候选透传给
                // 画像列表页，用户不必手抄微信昵称。会话 ID 读 activeConversationId
                //（回合落库的真值；transcript UiState.conversationId 恒 0 不可用）。
                if (candidateTitle != null) {
                    putExtra(AdvisorProfileActivity.EXTRA_CANDIDATE_TITLE, candidateTitle)
                }
                if (candidatePkg != null) {
                    putExtra(AdvisorProfileActivity.EXTRA_CANDIDATE_PKG, candidatePkg)
                }
                val activeConv = screenAdvisor.activeConversationId
                if (activeConv > 0) {
                    putExtra(AdvisorProfileActivity.EXTRA_CONVERSATION_ID, activeConv)
                }
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BALL -> if (ball == null && mode != Mode.ONESHOT) showBall()
            // 通知权限刚授予后，重挂前台通知让常驻提示立即可见。
            ACTION_REPOST_NOTIFICATION -> startInForeground()
            // 免双击入口（通知栏自动化/测试）：与双击同一状态机路径。
            ACTION_START_RECORDING -> if (mode == Mode.IDLE && ball != null) startRecording()
            ACTION_STOP_RECORDING -> if (mode == Mode.RECORDING) stopRecording()
            // M3-b 陪伴窗口收尾通知「续 30 分钟」：同一状态机入口重启窗口。
            CompanionWindowController.ACTION_RENEW_WINDOW -> onCompanionRenew(intent)
            // M2-a 起草通知点开：分屏预览代拟稿，一键发送/改后发送（§8.1 N4）。
            AdvisorNotificationListener.ACTION_SHOW_DRAFT -> handleShowDraft(intent)
            // 接管/达成通报点开（2026-10-20 通报方案 §4.1）：分屏定位到该会话，
            // 不带代拟条——接管场景答案必须用户亲笔，不把 AI 草稿挂一键发送。
            EscalationNotifier.ACTION_SHOW_ESCALATION -> handleShowEscalation(intent)
        }
        // OneShot 的像素截屏期间严禁把球加回（会污染 OCR 帧），跳过兜底显示。
        val overlayIdle = ball == null && menuView == null && split == null
        if (overlayIdle && mode != Mode.ONESHOT) showBall()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 主任务被划掉：服务与浮层继续存活；永久档确保心跳/闹钟链仍在。
        keepAliveManager.onTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 调度刻意保留：START_STICKY 与心跳负责把服务拉回；显式退出另走 exitApp。
        isRunning = false
        unregisterReceiver(screenOffReceiver)
        mainHandler.removeCallbacksAndMessages(null)
        askJob?.cancel()
        // 陪伴窗口静默清理：服务都要销毁了，不发作何收尾通知。
        companionController.cancelAllSilently()
        diffEngine.destroy()
        screenAdvisor.advisorScopeCancel()
        scope.cancel()
        FloatCaptureBridge.clear()
        recognizer?.destroy()
        recognizer = null
        removeAllOverlays()
        super.onDestroy()
    }

    // -------------------------------------------------------- gestures

    /**
     * 系统手势豁免：贴边窗口的拖动热区整块排除出边缘手势条。球默认贴
     * 屏幕右缘，球身/菜单窗右半落在返回手势（Gesture Monitor）的边缘
     * 条内——从该侧起拖会被系统抢占、触摸流半途 CANCEL，表现为「拖不
     * 动/时灵时不灵」。API 29 前无手势导航，无需处理。
     */
    private fun excludeSystemGestures(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.post {
            view.setSystemGestureExclusionRects(
                listOf(Rect(0, 0, view.width, view.height))
            )
        }
    }

    /** 手势路由：单击/双击/长按交给 GestureDetector，拖动本地处理。 */
    private fun attachBallGesture(view: FrameLayout, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        // 双击仲裁：菜单窗口盖住主球朝向侧，抢先展开会吃掉双击第二击的落点；
        // 但等系统 onSingleTapConfirmed 的 300ms 确认窗太迟钝。改为 tap-UP 后
        // [TAP_ARB_MS] 内无第二击即展开（仍远快于原 300ms），第二击到达则撤销
        // 待展开并立即走双击。suppress 标记防止双击第二击的 UP 再触发单击。
        var pendingExpand: Runnable? = null
        var suppressTapUp = false
        fun cancelPendingExpand() {
            pendingExpand?.let(view::removeCallbacks)
            pendingExpand = null
        }

        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (suppressTapUp) {
                        suppressTapUp = false
                        return true
                    }
                    when (mode) {
                        Mode.IDLE -> {
                            cancelPendingExpand()
                            val r = Runnable {
                                pendingExpand = null
                                if (mode == Mode.IDLE) expandMenu()
                            }
                            pendingExpand = r
                            view.postDelayed(r, TAP_ARB_MS)
                        }
                        Mode.RECORDING -> toggleSplit()
                        Mode.ONESHOT -> Unit // 忙碌：单击无效
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    suppressTapUp = true
                    when (mode) {
                        Mode.IDLE -> {
                            // 第一击在仲裁窗内已展开菜单（间隔>120ms 的双击），
                            // 撤掉再进连续参谋，防菜单残留。
                            dismissMenuNow()
                            cancelPendingExpand()
                            startRecording()
                        }
                        Mode.RECORDING -> stopRecording()
                        Mode.ONESHOT -> Unit // 忙碌：双击无效
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // 长按=单次参谋；OneShot/Recording 态长按拒绝。
                    if (mode == Mode.IDLE) {
                        cancelPendingExpand()
                        startOneShot()
                    }
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
                    // 新一触点：清上一轮双击抑制，避免吞掉后续合法单击。
                    suppressTapUp = false
                    // 长按预热：第一触点即开始读无障碍文本，与系统长按判定
                    // 并行，长按确认后毫秒级进 LLM（拖动/抬起均自动丢弃）。
                    // 面板可见时禁预热：无障碍连 overlay 文字一起读，预读结果
                    // 将全是面板自身文字（bug ①自噬），宁走像素路径。
                    if (mode == Mode.IDLE && (split == null || splitDetached)) {
                        screenAdvisor.preheat()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        requestBallMove(startX + dx.toInt(), startY + dy.toInt())
                    }
                    true
                }
                else -> true
            }
        }
    }

    // 拖动合帧：MOVE 事件频率远高于屏幕刷新率，updateViewLayout 是跨进程
    // 调用，逐事件直发会掉帧+耗电；每帧至多应用一次最新目标位置。
    private var movePending = false
    private var moveTargetX = 0
    private var moveTargetY = 0

    /** 请求把主球移到 (x,y)：帧间多次请求合并为一次窗口平移。 */
    private fun requestBallMove(x: Int, y: Int) {
        moveTargetX = x
        moveTargetY = y
        if (movePending) return
        movePending = true
        ball?.root?.postOnAnimation {
            movePending = false
            val view = ball?.root ?: return@postOnAnimation
            val params = ballParams ?: return@postOnAnimation
            moveBallWithFollow(view, params, moveTargetX, moveTargetY)
        }
    }

    /** 平移主球窗口（钳制在屏内），展开中的扇形菜单跟随球心。 */
    private fun moveBallWithFollow(
        view: View,
        params: WindowManager.LayoutParams,
        x: Int,
        y: Int,
    ) {
        val metrics = resources.displayMetrics
        params.x = x.coerceIn(
            -dp(FloatUi.BALL_SIZE_DP / 2),
            metrics.widthPixels - dp(FloatUi.BALL_SIZE_DP / 2),
        )
        params.y = y.coerceIn(
            dp(FloatUi.MARGIN_DP),
            metrics.heightPixels - dp(FloatUi.BALL_SIZE_DP + FloatUi.MARGIN_DP),
        )
        runCatching { windowManager.updateViewLayout(view, params) }
        // 回写拖动落点：摘窗复挂（单次参谋投影/拒绝授权等）走 showBall()
        // 用 ballX/ballY 重建球，不同步会把球瞬移回旧位。
        ballX = params.x
        ballY = params.y
        followMenu(params)
    }

    // ------------------------------------------------------- one shot

    private fun startOneShot() {
        if (mode != Mode.IDLE) return
        // 入口可能是长按主球，或扇形菜单「问答」子球：菜单（若开着）立即
        // 撤除，不等收起动画——既防自身 UI 污染 OCR，也避免忙碌态可点菜单。
        dismissMenuNow()
        mode = Mode.ONESHOT
        ball?.setMode(FloatUi.BallMode.BUSY, this)
        val preheated = screenAdvisor.consumePreheat()
        val needsHide = preheated == null || preheated.length < ScreenPerceiver.MIN_TEXT_CHARS
        if (needsHide) hideAllOverlays() // 像素路径：先撤自身浮层防污染
        // 无条件摘面板（铁律 §8.1）：旧逻辑以「预读文本够长」跳过摘除——面板
        // 开着时预读的恰是面板自身文字，喂给 LLM 即自噬、面板自关自开闪烁
        // （bug ①）。回合结束由 Unchanged→reattach / TurnStarted→ensureSplit 回挂。
        detachSplitWindow()
        runOneShot(preheated.takeIf { !needsHide }, settleMs = 0L)
    }

    private fun runOneShot(preheated: String?, settleMs: Long, awaitProjection: Boolean = false) {
        // 授权重跑路径会再次进入：恢复忙碌态（授权等待期 flow 结束已把 mode
        // 归位 IDLE），防止等待窗口内被并发触发第二个参谋。
        mode = Mode.ONESHOT
        ball?.setMode(FloatUi.BallMode.BUSY, this)
        // 授权重跑必为像素路径：采集前面板必须不在画面里（首跑已在
        // startOneShot 摘过，此处幂等兜底）。
        if (preheated == null) detachSplitWindow()
        askJob = scope.launch {
            try {
                screenAdvisor.adviseOnce(preheated, awaitProjection).collect { event ->
                    when (event) {
                        ScreenAdvisor.Event.NeedsConsent -> {
                            requestMpConsent { granted ->
                                // 拒绝授权也要给出反馈，不能静默吞掉（用户会以为单击失效）。
                                if (granted) {
                                    runOneShot(null, CONSENT_SETTLE_MS, awaitProjection = true)
                                } else {
                                    reattachSplitWindow() // 采集不再发生，恢复旧结果面板
                                    toast(R.string.float_ask_denied)
                                }
                            }
                        }
                        is ScreenAdvisor.Event.TurnStarted -> ensureSplit()
                        // 与上一屏几乎相同：不再重复 OCR/LLM，但分屏面板必须弹出——
                        // 用户单击就是要看参谋结果，转录仍带着上次回合的内容；
                        // 面板已关时 reattach 是 no-op（真机踩坑：只 toast 不弹窗），
                        // 改走 ensureSplit 两条路都覆盖（复用/重建）。
                        ScreenAdvisor.Event.Unchanged -> {
                            ensureSplit()
                            toast(R.string.float_screen_unchanged)
                        }
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
                // 像素帧走 MPManager 常驻投影（方案 2026-10-22）：会话跨次
                // 复用、状态栏指示灯常驻是既定取舍，不释放。
            }
        }
    }

    /**
     * 单次结束：恢复蓝球（分屏留着展示结果）。
     * 注意「画面重复」这类不开分屏即结束的路径：球实例仍挂着，restoreBall
     * 不会重建它，必须在这里显式复位外观，否则旋转态永远停不下来。
     */
    private fun finishOneShot() {
        if (mode == Mode.ONESHOT) {
            mode = Mode.IDLE
            // 先置空再复位图标：此刻协程仍在 finally 内（isActive=true），
            // isBusy() 会被 Job 拖住；post 到队列尾＝协程完成后才真正执行。
            askJob = null
            ball?.setMode(FloatUi.BallMode.NORMAL, this)
            restoreBall()
            // askJob 收尾晚于 transcript 末次 Idle 发射：不补这一下，输入行
            // 发送/停止键会停在「停止」方块上（末次刷新时 Job 仍 active）。
            mainHandler.post { inputHandle?.refresh() }
        }
    }

    // ----------------------------------------------------- recording

    private fun startRecording(forCompanion: Boolean = false) {
        if (mode != Mode.IDLE) return
        // 三档互斥（方案 v2 §13.2，M3 验收项）：自动陪伴窗口在途时禁止再开
        // 手动连续；窗口自身的启动路径（forCompanion=true）不受此限。
        if (!forCompanion && companionController.windowActive()) {
            toast(R.string.float_companion_gear_conflict)
            return
        }
        mode = Mode.RECORDING
        completionCount = 0
        // 新的录制会话：清零会话级 token 计数（累计总量不受影响）。
        screenAdvisor.resetSessionUsage()
        ball?.setMode(FloatUi.BallMode.RECORDING, this)
        closeSplit(keepBall = true) // 录制开始前撤掉自身分屏
        hideAllOverlays()
        if (screenCapture.projectionAlive) {
            startRecordingLoop()
        } else {
            // 投影授权编排（方案模块 A）：a11y 开自动点弹窗 / Shizuku 静默
            // 直过 / 手动兜底——经 FloatCaptureActivity 中转发起。
            requestMpConsent { granted ->
                if (granted) {
                    mainHandler.postDelayed({
                        // 授权等待期用户可能已双击停止（或服务被重建）：授权
                        // 晚到时绝不能在非 RECORDING 态拉起差分循环。
                        if (mode == Mode.RECORDING) {
                            startRecordingLoop()
                        }
                    }, CONSENT_SETTLE_MS)
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
            is FrameDiffEngine.Signal.Resting -> {
                // 休憩循环不停止录制：提示后引擎自动继续（无人值守 RPA 节奏）。
                toast(getString(R.string.float_recording_resting, signal.restMs / 60_000L))
            }
        }
    }

    private fun stopRecording() {
        diffEngine.stop()
        // MPManager 常驻投影跨会话复用（方案 2026-10-22），停止录制不释放。
        // 陪伴窗口收尾：静默结束——不发「已停止」toast，汇总/续期由控制器通知。
        val wasCompanion = companionWindow
        companionWindow = false
        if (wasCompanion) companionController.finish("service_stop")
        abortRecording()
        if (!wasCompanion) toast(R.string.float_recording_stopped)
    }

    /** 录制态回退到 Idle：球已存在时也要恢复外观（不重建窗口）。 */
    private fun abortRecording() {
        mode = Mode.IDLE
        completionCount = 0
        companionWindow = false
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

    // ------------------------------------------------- companion window

    /**
     * 陪伴窗口入口（M3-b，方案 §8.1 W1）：扇形菜单「自动」子球 / 分屏「自动」
     * 键，对当前绑定联系人开启。三档互斥、总闸、白名单的判定全在
     * [CompanionWindowController.start]，服务层只映射结果为用户提示。
     */
    private fun startCompanionWindow() {
        beginCompanion(screenAdvisor.activeContact.value)
    }

    /** 收尾通知「续 30 分钟」按钮：按联系人 id 重建引用后走同一开启路径。 */
    private fun onCompanionRenew(intent: Intent?) {
        val contactId = intent?.getLongExtra(CompanionWindowController.EXTRA_CONTACT_ID, -1L)
            ?: -1L
        if (contactId <= 0L) return
        scope.launch { beginCompanion(companionController.contactRefFor(contactId)) }
    }

    private fun beginCompanion(contact: ContactRef?) {
        if (contact == null) {
            toast(R.string.float_companion_need_contact)
            return
        }
        if (companionWindow) {
            toast(R.string.float_companion_gear_conflict)
            return
        }
        scope.launch {
            val result = companionController.start(
                contact,
                manualContinuousActive = mode == Mode.RECORDING,
            )
            handleCompanionStart(result)
        }
    }

    private fun handleCompanionStart(result: CompanionWindowController.StartResult) {
        when (result) {
            CompanionWindowController.StartResult.STARTED -> {
                companionWindow = true
                toast(R.string.float_companion_started)
                if (mode == Mode.IDLE) startRecording(forCompanion = true)
            }
            CompanionWindowController.StartResult.REASON_AUTO_DISABLED ->
                toast(R.string.float_companion_disabled)
            CompanionWindowController.StartResult.REASON_NOT_WHITELISTED ->
                toast(R.string.float_companion_not_whitelisted)
            CompanionWindowController.StartResult.REASON_GEAR_CONFLICT ->
                toast(R.string.float_companion_gear_conflict)
            CompanionWindowController.StartResult.REASON_NO_CONTACT ->
                toast(R.string.float_companion_need_contact)
        }
    }

    /** 连续模式的 OCR 前掩膜：自身分屏 + 悬浮球的屏幕矩形。 */
    private fun currentOverlayRects(): List<Rect> {
        val rects = mutableListOf<Rect>()
        val metrics = resources.displayMetrics
        split?.root?.let { root ->
            // 用窗口真实屏坐标：键盘避让后面板被收高且 BOTTOM 落在键盘上沿，
            // 不能再按 params.height/y 推算。
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            if (location[1] >= 0 && root.height > 0) {
                rects.add(Rect(0, location[1], metrics.widthPixels, location[1] + root.height))
            }
        }
        ballParams?.let {
            rects.add(Rect(it.x, it.y, it.x + dp(FloatUi.BALL_SIZE_DP), it.y + dp(FloatUi.BALL_SIZE_DP)))
        }
        return rects
    }

    // ------------------------------------------------------ consent

    /**
     * MPManager 常驻投影授权中转（方案 2026-10-22 模块 A）：MediaProjection
     * 授权必须由 Activity 发起——FloatCaptureActivity(EXTRA_MP) 内执行
     * ScreenCapture.ensureProjection 编排（a11y 自动点弹窗 / Shizuku appops
     * 静默直过 / 手动兜底），布尔结果经 FloatCaptureBridge 回传。
     */
    private fun requestMpConsent(onResult: (Boolean) -> Unit) {
        if (consentPending) return
        consentPending = true
        FloatCaptureBridge.mpConsent = { granted ->
            consentPending = false
            mainHandler.removeCallbacks(consentWatchdog)
            onResult(granted)
        }
        mainHandler.postDelayed(consentWatchdog, CONSENT_WATCHDOG_MS)
        runCatching {
            startActivity(
                Intent(this, FloatCaptureActivity::class.java)
                    .putExtra(FloatCaptureActivity.EXTRA_MP, true)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_NO_HISTORY,
                    ),
            )
        }.onFailure {
            consentPending = false
            FloatCaptureBridge.clear()
            mainHandler.removeCallbacks(consentWatchdog)
            onResult(false)
        }
    }

    private val consentWatchdog = Runnable {
        if (!consentPending) return@Runnable
        consentPending = false
        FloatCaptureBridge.clear()
        if (mode == Mode.ONESHOT) finishOneShot()
        if (mode == Mode.RECORDING) abortRecording()
        reattachSplitWindow() // 授权流程终止：恢复可能被摘下的结果面板
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
            width = dp(FloatUi.BALL_SIZE_DP),
            height = dp(FloatUi.BALL_SIZE_DP),
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
        excludeSystemGestures(view.root)
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
        split?.let {
            // 摘窗采集后的复用路径（TurnStarted/Failed/异常）：先挂回再返回。
            reattachSplitWindow()
            return it
        }
        // 分屏与扇形菜单不共存；悬浮球保留可见（面板开着也能再触发参谋，
        // 球可拖动避让面板；录制态掩膜本就盖住红球）。
        dismissMenuNow()
        val metrics = resources.displayMetrics
        val params = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = (metrics.heightPixels * FloatUi.SPLIT_DEFAULT_RATIO).toInt(),
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
            // 常驻可聚焦：正文长按选择复制（手柄/系统复制菜单）与输入行
            // IME 都要求窗口持有焦点，动态切换时机难踩准（长按菜单刚起
            // 就被回切杀掉）。分屏本身是用户主动唤起的问答面板，期间
            // 下层应用无需输入；面板外的触摸仍穿透（NOT_TOUCH_MODAL）。
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            // 键盘避让自管（syncSplitIme 按重叠量收缩），禁系统默认 resize。
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        val panel = FloatUi.buildSplitPanel(
            this,
            onClose = { closeSplit(keepBall = false) },
            onDirection = { showDirectionMenu() },
            onAuto = { startCompanionWindow() },
            onSmalltalk = { screenAdvisor.requestSmalltalk() },
            inputCallbacks = inputCallbacks,
            draftCallbacks = draftCallbacks,
            bindCallbacks = bindCallbacks,
        )
        panel.handle.setOnTouchListener(makeResizeListener())
        excludeSystemGestures(panel.handle)

        // 正文区挂载轻量转录视图：与主界面同一个「老参谋」会话的数据流。
        // （WebView 替换方案已验证放弃：overlay 窗口内 Chromium 长按选择
        // 完全不生效——连过渡高亮都没有；整段复制走气泡底部复制图标。）
        val transcript = TranscriptView(this)
        transcriptView = transcript
        // P3 §九：行内「✓采纳 / 长按三档重写 / 记错了」接线（复制已在视图内完成）。
        transcript.onAdopt = { index, _ ->
            scope.launch {
                runCatching { screenAdvisor.adoptSuggestion(index) }
                    .onFailure { android.util.Log.e("FloatBallService", "adoptSuggestion failed", it) }
            }
        }
        transcript.onRegenerate = { ordinal ->
            runCatching {
                val kind = ScreenAdvisor.CorrectionKind.entries.getOrNull(ordinal) ?: return@runCatching
                screenAdvisor.regenerateSuggestionAsync(kind)
            }
        }
        transcript.onCorrectMemory = { basis, correction ->
            scope.launch {
                runCatching { screenAdvisor.correctBasisMemory(basis, correction) }
                    .onFailure { android.util.Log.e("FloatBallService", "correctBasisMemory failed", it) }
            }
        }
        panel.transcriptContainer.addView(
            transcript,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // 输入行句柄（思考态刷新/语音追加）。
        inputHandle = panel.inputBar.tag as? FloatUi.InputBarHandle

        // 键盘跟随：overlay 不享受 Activity 式 resize。API30+ 系统会把 overlay
        // 父框架按 IME 收缩，但比父框架高的窗口仍会探进键盘，因此按「窗口与
        // 键盘的实时重叠量」收掉面板高度，输入行始终贴键盘上沿；键盘收拢逐帧
        // 还原。焦点期间另起 100ms 校准 tick，兜底 overlay 丢失末帧 insets。
        ViewCompat.setOnApplyWindowInsetsListener(panel.root) { _, insets ->
            syncSplitIme(panel.root, insets)
            insets
        }

        windowManager.addView(panel.root, params)
        split = panel
        splitParams = params
        // StateFlow 无新值不发射: 新视图创建后立即喂当前转录快照。
        transcript.render(screenAdvisor.transcript.value)
        // 状态条场景化渲染 + 点击接线（§1.5：未绑定点击展开内嵌快速绑定条；
        // NON_CHAT 显示纯问答标识但保留手动关联入口）统一在 applyStatusBarScene。
        applyStatusBarScene()
        renderDirectionChip(panel.directionChip, screenAdvisor.direction.value)
        FloatUi.renderStrategyStrip(
            panel.strategyStrip,
            screenAdvisor.missionUi.value,
            strategyCallbacks,
        )
        inputHandle?.refresh()
        // 面板重建后按当前草稿态恢复代拟条（通知点开/会话切换都会走重建）。
        draftState?.let { st ->
            FloatUi.renderDraftStrip(panel.draftStrip, st.editing, st.draft)
        }
        // 打开分屏=用户开始阅读：未读冒泡立即消失；阅读期间的新回合实时可见，
        // 不再计未读（turnCompletions 收集侧以 split != null 抑制）。
        completionCount = 0
        ball?.setBadge(0)
        return panel
    }

    /** 输入框焦点联动：获得=启动键盘避让校准；失去=收键盘并还原面板高度。
     * 窗口常驻可聚焦（见 ensureSplit），此处不再切换 flags，只管 IME。 */
    private fun setSplitImeActive(focused: Boolean) {
        val root = split?.root ?: return
        val imm = getSystemService(InputMethodManager::class.java)
        if (focused) {
            // overlay 成为 IME 目标后，父框架收缩与 insets 派发存在时序竞争：
            // 最终态常常不再派发（键盘把父框架吃掉后重叠量恒为 0 的错觉），
            // 焦点期间定时按根 insets + 窗口屏坐标校准，保证收敛。
            startImeSyncLoop(root)
        } else {
            stopImeSyncLoop()
            inputHandle?.field?.let { field ->
                imm?.hideSoftInputFromWindow(field.windowToken, 0)
            }
            // 失焦后 overlay 可能收不到 ime 隐藏的 insets 回调，主动复位一次。
            restoreSplitImeLayout(root)
        }
    }

    private var imeSyncing = false
    private val imeSyncRunnable = object : Runnable {
        override fun run() {
            if (!imeSyncing) return
            val root = split?.root
            if (root == null) {
                imeSyncing = false
                return
            }
            ViewCompat.getRootWindowInsets(root)?.let { syncSplitIme(root, it) }
            mainHandler.postDelayed(this, IME_SYNC_INTERVAL_MS)
        }
    }

    private fun startImeSyncLoop(root: View) {
        if (imeSyncing) return
        imeSyncing = true
        // 立即先校准一次，随后按固定节拍跟踪键盘起落与把手拖动。
        ViewCompat.getRootWindowInsets(root)?.let { syncSplitIme(root, it) }
        mainHandler.postDelayed(imeSyncRunnable, IME_SYNC_INTERVAL_MS)
    }

    private fun stopImeSyncLoop() {
        imeSyncing = false
        mainHandler.removeCallbacks(imeSyncRunnable)
    }

    /**
     * 按 IME insets 调整分屏窗口，可随键盘动画逐帧调用；幂等且自收敛。
     *
     * API30+：父框架按 IME 收缩，但更高的 BOTTOM 锚定窗口仍探进键盘，insets
     * 上报的 ime.bottom 就是探入量；高度减去探入量后重叠归零，输入行实时贴住
     * 键盘上沿。API30 以下父框架不收缩：整体上移 y 并收高。键盘隐藏时还原
     * [splitHeightBeforeIme]（用户拖出的分界线高度）。
     */
    private fun syncSplitIme(view: View, insets: WindowInsetsCompat) {
        val p = splitParams ?: return
        val imeType = WindowInsetsCompat.Type.ime()
        val overlap = insets.getInsets(imeType).bottom
        val imeShown = insets.isVisible(imeType) || overlap > 0
        if (!imeShown) {
            restoreSplitImeLayout(view)
            return
        }
        val metrics = view.resources.displayMetrics
        if (splitHeightBeforeIme < 0) {
            // 记录键盘弹出前的面板高（用户拖出的分界线高度）；异常时序下
            // 可能拿到已收缩的高度，低于 minHeight 时保底，防 coerceIn 空区间。
            splitHeightBeforeIme = p.height.coerceAtLeast(
                dp(IME_MIN_PANEL_DP).coerceAtLeast((metrics.heightPixels * FloatUi.SPLIT_MIN_RATIO).toInt()),
            )
        }
        val minHeight = dp(IME_MIN_PANEL_DP)
            .coerceAtLeast((metrics.heightPixels * FloatUi.SPLIT_MIN_RATIO).toInt())
        var changed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API30+：ime.bottom 即窗口底边探入键盘的重叠量。新高度 = 当前高度
            // − 重叠量，重布局后重叠归零，输入行恰好停在键盘上沿（自收敛）。
            val target = (view.height - overlap.coerceAtLeast(0))
                .coerceIn(minHeight, splitHeightBeforeIme.coerceAtLeast(minHeight))
            if (p.y != 0) {
                p.y = 0
                changed = true
            }
            if (p.height != target) {
                p.height = target
                changed = true
            }
        } else {
            // API30 以下父框架不按 IME 收缩：整体上移键盘全高并收高。
            val available = (metrics.heightPixels - overlap).coerceAtLeast(minHeight)
            val target = minOf(splitHeightBeforeIme, available).coerceAtLeast(minHeight)
            if (p.y != overlap) {
                p.y = overlap
                changed = true
            }
            if (p.height != target) {
                p.height = target
                changed = true
            }
        }
        if (changed) runCatching { windowManager.updateViewLayout(view, p) }
    }

    /** 键盘隐藏后还原 y 与用户的分界线高度。 */
    private fun restoreSplitImeLayout(view: View) {
        val p = splitParams ?: return
        var changed = false
        if (p.y != 0) {
            p.y = 0
            changed = true
        }
        if (splitHeightBeforeIme >= 0 && p.height != splitHeightBeforeIme) {
            p.height = splitHeightBeforeIme
            changed = true
        }
        splitHeightBeforeIme = -1
        if (changed) runCatching { windowManager.updateViewLayout(view, p) }
    }

    /** 输入行的行为接线：发送/语音/＋号/停止/焦点。 */
    private val inputCallbacks = object : FloatUi.InputBarCallbacks {
        override fun onSend(text: String) {
            // 保留 Job：手动回合期间发送键要变「停止」，且停止要能真正取消。
            manualJob?.cancel()
            manualJob = scope.launch {
                try {
                    screenAdvisor.submitTurn(text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // 与单次参谋同口径：异常落到 Error 状态，不能让键停在思考态。
                    screenAdvisor.reportUnexpected(error.message ?: "unknown")
                } finally {
                    manualJob = null
                    // transcript 末次 Idle 发射可能早于 Job 真正结束，补一次复位。
                    inputHandle?.refresh()
                }
            }
        }

        override fun onVoice() {
            startVoiceInput()
        }

        override fun onPlus() {
            showMainUi()
            toast(R.string.float_plus_to_main)
        }

        override fun onStop() {
            cancelActiveTurn()
            inputHandle?.refresh()
        }

        override fun onInputFocus(focused: Boolean) {
            setSplitImeActive(focused)
        }

        override fun onInputLongPress(): String? = try {
            getSystemService(ClipboardManager::class.java)?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            // Android 10+ 剪贴板读取依赖窗口焦点，异常时按不可用处理（按钮置灰）。
            null
        }

        override fun onInputTouch() {
            // 窗口常驻可聚焦：直接聚焦输入框并呼出键盘（Android 12+ overlay
            // 不自动弹；立即调用会被 WindowManager 拒绝，稍候落定再显式呼出）。
            val field = inputHandle?.field ?: return
            setSplitImeActive(true)
            field.postDelayed({
                field.requestFocus()
                val imm = getSystemService(InputMethodManager::class.java) ?: return@postDelayed
                imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }

        override fun isBusy(): Boolean {
            // 忙碌的唯一语义=有回合在跑：OCR/思考/重试等待/流式输出（单次参谋、
            // 连续模式、输入行手动发送三条路径都汇入 transcript 状态），再 OR
            // 各路径 Job 尚在的收尾窗口。只看 Job 会在末次 Idle 发射后停在停止态。
            val state = screenAdvisor.transcript.value
            return state.streaming != null ||
                state.status is ScreenAdvisor.Status.OcrRunning ||
                state.status is ScreenAdvisor.Status.Thinking ||
                state.status is ScreenAdvisor.Status.Retrying ||
                askJob?.isActive == true ||
                manualJob?.isActive == true ||
                diffEngine.isRoundActive()
        }
    }

    /** 思考中可打断：取消当前 LLM 回合（部分回答会保留入库）。 */
    private fun cancelActiveTurn() {
        askJob?.cancel()
        askJob = null
        manualJob?.cancel()
        manualJob = null
        diffEngine.cancelRound()
    }

    // --------------------------------------------------------- voice

    private var recognizer: android.speech.SpeechRecognizer? = null

    /** 语音输入：系统识别器，结果追加到输入框（可编辑后再发送）。 */
    private fun startVoiceInput() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(R.string.float_voice_unavailable)
            return
        }
        recognizer?.destroy()
        val listener = object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = toast(R.string.float_voice_listening)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    inputHandle?.field?.append(text)
                    inputHandle?.field?.requestFocus()
                }
            }
        }
        recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
            startListening(
                Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                },
            )
        }
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
                    val minHeight = (metrics.heightPixels * FloatUi.SPLIT_MIN_RATIO).toInt()
                    // 键盘跟随中可用高度只到键盘上沿，把手高度也钳制在此范围内。
                    val root = split?.root
                    val rootInsets = root?.let { ViewCompat.getRootWindowInsets(it) }
                    val imeType = WindowInsetsCompat.Type.ime()
                    val overlap = rootInsets
                        ?.takeIf { it.isVisible(imeType) }
                        ?.getInsets(imeType)?.bottom ?: 0
                    val maxHeight = if (root != null && overlap > 0) {
                        (root.height - overlap).coerceAtLeast(minHeight)
                    } else {
                        (metrics.heightPixels * FloatUi.SPLIT_MAX_RATIO).toInt()
                    }
                    val height = (startHeight + (downY - event.rawY)).toInt()
                        .coerceIn(minHeight, maxHeight)
                    if (height != params.height) {
                        params.height = height
                        // 键盘弹起时拖动也视作用户改分界线，键盘收起后还原到此值。
                        if (splitHeightBeforeIme >= 0) splitHeightBeforeIme = height
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
        manualJob?.cancel()
        manualJob = null
        dismissDirectionPopup()
        split?.let { runCatching { windowManager.removeView(it.root) } }
        split = null
        splitParams = null
        splitDetached = false
        splitHeightBeforeIme = -1
        transcriptView = null
        inputHandle = null
        draftState = null
        screenAdvisor.resetTransient()
        if (!keepBall) restoreBall()
    }

    // ------------------------------------------------- draft (M2-a 起草档)

    /** 当前展示中的代拟草稿；null=面板处于普通态（代拟条隐藏）。 */
    private data class DraftUi(
        val draft: String,
        val imPackage: String,
        val conversationId: Long,
        var editing: Boolean = false,
        var priorInput: String = "",
    )

    private var draftState: DraftUi? = null

    /** 代拟条两键：主键=一键发送/发送编辑稿；次键=改后发送/放弃编辑。 */
    private val draftCallbacks = object : FloatUi.DraftStripCallbacks {
        override fun onDraftPrimary() {
            val state = draftState ?: return
            val field = inputHandle?.field
            val text = if (state.editing) {
                field?.text?.toString()?.trim().orEmpty()
            } else {
                state.draft
            }
            if (text.isEmpty()) {
                toast(R.string.float_draft_empty)
                return
            }
            copyAndJump(text, state.imPackage)
            exitDraftState()
        }

        override fun onDraftSecondary() {
            val state = draftState ?: return
            val field = inputHandle?.field
            if (state.editing) {
                // 放弃编辑：还原预填前输入框内容，回到草稿态。
                field?.setText(state.priorInput)
                state.editing = false
                split?.let { FloatUi.renderDraftStrip(it.draftStrip, false, state.draft) }
            } else {
                // 改后发送：草稿预填进输入框可编辑，焦点呼出键盘。
                state.priorInput = field?.text?.toString().orEmpty()
                field?.setText(state.draft)
                state.editing = true
                split?.let { FloatUi.renderDraftStrip(it.draftStrip, true, state.draft) }
                focusDraftInput()
            }
        }
    }

    /** 起草通知点开（方案 §8.1 N4）：切代拟会话 → 开分屏 → 草稿态渲染代拟条。 */
    private fun handleShowDraft(intent: Intent?) {
        val conversationId = intent?.getLongExtra(
            AdvisorNotificationListener.EXTRA_CONVERSATION_ID, -1L,
        ) ?: -1L
        val contactId = intent?.getLongExtra(
            AdvisorNotificationListener.EXTRA_CONTACT_ID, -1L,
        ) ?: -1L
        val contactName = intent?.getStringExtra(AdvisorNotificationListener.EXTRA_CONTACT_NAME)
        val imPackage = intent?.getStringExtra(AdvisorNotificationListener.EXTRA_IM_PACKAGE)
        val draft = intent?.getStringExtra(AdvisorNotificationListener.EXTRA_DRAFT)
        if (conversationId <= 0 || contactId <= 0 ||
            contactName.isNullOrBlank() || draft.isNullOrBlank()
        ) {
            return
        }
        screenAdvisor.showDraftConversation(conversationId, contactId, contactName)
        ensureSplit()
        draftState = DraftUi(draft, imPackage.orEmpty(), conversationId)
        split?.let { FloatUi.renderDraftStrip(it.draftStrip, editing = false, text = draft) }
    }

    /**
     * 接管/达成通报点开（2026-10-20 通报方案 §4.1）：复用起草路径的分屏定位
     * 与联系人会话跳转，但**不进版本草稿态**（draftState 保持 null、代拟条
     * 隐藏）——H 场景 AI 不得代答，G 场景用户跟进方式自定。
     */
    private fun handleShowEscalation(intent: Intent?) {
        val conversationId = intent?.getLongExtra(EscalationNotifier.EXTRA_CONVERSATION_ID, -1L) ?: -1L
        val contactId = intent?.getLongExtra(EscalationNotifier.EXTRA_CONTACT_ID, -1L) ?: -1L
        val contactName = intent?.getStringExtra(EscalationNotifier.EXTRA_CONTACT_NAME)
        if (conversationId <= 0 || contactId <= 0 || contactName.isNullOrBlank()) return
        screenAdvisor.showDraftConversation(conversationId, contactId, contactName)
        ensureSplit()
        exitDraftState()
    }

    /**
     * 一键发送（M2 人肉一键档，方案 §5.1 发送决策位）：草稿复制进剪贴板并
     * 跳转对应 IM，粘贴发送由用户完成——绝不自动发出任何消息。
     */
    private fun copyAndJump(text: String, imPackage: String) {
        runCatching {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.float_copy_desc), text),
            )
            toast(R.string.float_draft_copied)
            if (imPackage.isNotBlank()) {
                packageManager.getLaunchIntentForPackage(imPackage)?.let { launch ->
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                }
            }
        }.onFailure { android.util.Log.d("FloatBallService", "copyAndJump failed", it) }
    }

    /** 「改后发送」把焦点交回输入行：落定后呼出键盘。 */
    private fun focusDraftInput() {
        val field = inputHandle?.field ?: return
        setSplitImeActive(true)
        field.postDelayed({
            field.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java) ?: return@postDelayed
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    /** 发送（或会话关闭）后退出版本草稿态：代拟条隐藏，编辑残留一并清除。 */
    private fun exitDraftState() {
        draftState = null
        split?.let { FloatUi.renderDraftStrip(it.draftStrip, editing = false, text = null) }
    }

    /**
     * 像素采集前临时摘下分屏窗口：投影/无障碍截图会把 overlay 一并拍进
     * 画面，面板文字污染 OCR 且打断会话路由（铁律 §8.1）。视图对象、
     * 布局参数、转录状态全部保留，采集完 [reattachSplitWindow] 原样挂回。
     */
    private fun detachSplitWindow() {
        val panel = split ?: return
        if (splitDetached) return
        runCatching { windowManager.removeView(panel.root) }
        splitDetached = true
    }

    /** 摘窗后的挂回（幂等：未摘除时无动作）。 */
    private fun reattachSplitWindow() {
        val panel = split ?: return
        if (!splitDetached) return
        splitParams?.let { runCatching { windowManager.addView(panel.root, it) } }
        splitDetached = false
    }

    // -------------------------------------------------------- direction

    /** 当前浮层类型：区分建议菜单（非聚焦）与自定义输入（聚焦弹键盘）。 */
    private enum class DirectionPopupKind { NONE, MENU, INPUT }

    private var directionPopup: PopupWindow? = null
    private var directionPopupKind = DirectionPopupKind.NONE

    private fun renderDirectionChip(chip: TextView, st: ScreenAdvisor.DirectionState) {
        val label = st.selected?.let { getString(R.string.float_dir_set, it) }
        FloatUi.bindDirectionChip(chip, label)
    }

    /**
     * 「方向」下拉：菜单用非聚焦 PopupWindow（不抢面板焦点、点外部消失）；
     * 打开即触发 AI 拟定（有缓存直接用），拟定完成时由 direction 收集侧
     * 重建本菜单。浮层挂在分屏 overlay 的 token 上，show 失败静默不弹窗。
     */
    private fun showDirectionMenu() {
        val anchor = split?.directionChip ?: return
        val st = screenAdvisor.direction.value
        screenAdvisor.suggestDirections()
        dismissDirectionPopup()

        val content = FloatUi.buildDirectionMenu(
            this,
            suggestions = st.suggestions,
            loading = st.loading,
            hasSelected = st.selected != null,
            onPick = { item ->
                screenAdvisor.setDirection(item)
                dismissDirectionPopup()
            },
            onCustom = { showDirectionInput() },
            onClear = {
                screenAdvisor.clearDirection()
                dismissDirectionPopup()
            },
        )
        val popup = PopupWindow(
            content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(10).toFloat()
            setOnDismissListener {
                if (directionPopup === this) {
                    directionPopup = null
                    directionPopupKind = DirectionPopupKind.NONE
                }
            }
        }
        directionPopup = popup
        directionPopupKind = DirectionPopupKind.MENU
        val shown = runCatching {
            popup.showAsDropDown(anchor, 0, dp(4), Gravity.START)
        }.isSuccess
        if (!shown) {
            directionPopup = null
            directionPopupKind = DirectionPopupKind.NONE
        }
    }

    /**
     * 自定义方向输入：聚焦 PopupWindow（独立窗口拿 IME 焦点，无需改分屏
     * overlay 的 NOT_FOCUSABLE flag），确定/取消后关闭。
     */
    private fun showDirectionInput() {
        val anchor = split?.directionChip ?: return
        dismissDirectionPopup()

        val content = FloatUi.buildDirectionInput(
            this,
            initial = screenAdvisor.direction.value.selected.orEmpty(),
            onCancel = { dismissDirectionPopup() },
            onConfirm = { value ->
                screenAdvisor.setDirection(value)
                dismissDirectionPopup()
            },
        )
        val popup = PopupWindow(
            content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(10).toFloat()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setOnDismissListener {
                if (directionPopup === this) {
                    directionPopup = null
                    directionPopupKind = DirectionPopupKind.NONE
                }
            }
        }
        directionPopup = popup
        directionPopupKind = DirectionPopupKind.INPUT
        val shown = runCatching {
            popup.showAsDropDown(anchor, 0, dp(4), Gravity.START)
        }.isSuccess
        if (!shown) {
            directionPopup = null
            directionPopupKind = DirectionPopupKind.NONE
            return
        }
        // 聚焦态 PopupWindow 拿到窗口焦点后，让输入框获焦并呼出键盘。
        val field = content.tag as? EditText
        content.post {
            field?.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun dismissDirectionPopup() {
        val popup = directionPopup ?: return
        directionPopup = null
        directionPopupKind = DirectionPopupKind.NONE
        runCatching { popup.dismiss() }
    }

    // ------------------------------------------------------------- menu

    /** 单击：朝屏幕中央的水平扇形弹出 6 个子球（单次/连续/自动/主界面/更多/退出）。 */
    private fun expandMenu() {
        if (menuView != null || ball == null) return
        val from = ballParams ?: return

        val metrics = resources.displayMetrics
        val ballCenterX = from.x + dp(FloatUi.BALL_SIZE_DP) / 2
        val ballCenterY = from.y + dp(FloatUi.BALL_SIZE_DP) / 2
        // 或左或右：贴左半屏向右展开，贴右半屏向左展开，开口恒朝屏幕中央。
        val facing = if (ballCenterX <= metrics.widthPixels / 2) {
            FloatUi.FanFacing.RIGHT
        } else {
            FloatUi.FanFacing.LEFT
        }
        val fan = FloatUi.buildFanMenu(
            this,
            items = listOf(
                // 「单次/连续」与长按/双击主球同一状态机入口（方案 §3.1）。
                FloatUi.FanItem(R.string.float_fan_oneshot) { startOneShot() },
                FloatUi.FanItem(R.string.float_fan_continuous) { startRecording() },
                // 「自动」= 托管范围选择器（就绪门 + 联系人×APP，方案 §3.3）：
                // 感知面是聊天 APP 的通知，与当前屏幕无关；选定后进陪伴窗口。
                FloatUi.FanItem(R.string.float_split_auto) { openAutoScopePicker() },
                FloatUi.FanItem(R.string.float_fan_main) { showMainUi() },
                // 「更多」= 次级入口卡片（设置/帮助/老参谋设置/联系人画像，§3.2）；
                // 悬浮球常驻，不再提供手动隐藏（不想显示请走「退出」）。
                FloatUi.FanItem(R.string.float_fan_more) { openMoreMenu() },
                FloatUi.FanItem(R.string.float_fan_exit) { exitApp() },
            ),
            facing = facing,
            ballCenterX = ballCenterX,
            ballCenterY = ballCenterY,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onDismiss = { collapseMenu() },
        )
        fanWidth = fan.widthPx
        fanHeight = fan.heightPx
        // 菜单窗口盖住主球朝向侧：菜单空白/子球上的拖动也必须整体移动
        // （否则「按住主球被盖住的一半」时手势被菜单吃掉，时灵时不灵）。
        fan.setDragHandler(
            object : FloatUi.FanDragHandler {
                override fun onDragStart() {
                    val params = ballParams ?: return
                    fanDragStartX = params.x
                    fanDragStartY = params.y
                }

                override fun onDragBy(totalDx: Float, totalDy: Float) {
                    requestBallMove(
                        fanDragStartX + totalDx.toInt(),
                        fanDragStartY + totalDy.toInt(),
                    )
                }

                override fun onDragEnd() = Unit
            },
        )
        // 菜单窗盖住主球朝向侧：双击第二击（仲裁窗 120ms 后到达的）会落在
        // 本窗空白处而非主球——近球空白点按在双击时限内转发为连续参谋。
        fan.setBlankTapListener { x, y -> forwardDoubleTapIfNeeded(x, y) }
        val params = overlayParams(width = fanWidth, height = fanHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            x = fan.windowX
            y = fan.windowY
        }
        windowManager.addView(fan.root, params)
        excludeSystemGestures(fan.root)
        menuView = fan.root
        menuParams = params
        fanMenu = fan
        menuOpenedAt = SystemClock.uptimeMillis()
        fan.expand()
    }

    /** 扇形菜单挂窗时刻（双击转发仲裁用）。 */
    private var menuOpenedAt = 0L

    /**
     * 双击第二击落在菜单窗时的转发：第二击打在主球近旁空白（时间仍在系统
     * 双击时限内）= 用户在双击，转发进连续参谋；其余空白点按照常收起。
     */
    private fun forwardDoubleTapIfNeeded(x: Float, y: Float) {
        if (mode != Mode.IDLE || menuOpenedAt == 0L) return
        // 菜单在 tap-UP 后 TAP_ARB_MS 才挂窗，第二击最晚出现在系统双击时限
        // 末尾：换算成挂窗后允许的转发窗口。
        val windowMs = ViewConfiguration.getDoubleTapTimeout() - TAP_ARB_MS
        if (SystemClock.uptimeMillis() - menuOpenedAt > windowMs) return
        val params = ballParams ?: return
        val cx = params.x + dp(FloatUi.BALL_SIZE_DP) / 2f
        val cy = params.y + dp(FloatUi.BALL_SIZE_DP) / 2f
        val radius = dp(FloatUi.BALL_SIZE_DP).toFloat()
        val hit = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius
        if (hit) {
            dismissMenuNow()
            startRecording()
        }
    }

    /** 拖动主球时，展开中的扇形菜单跟随球心（几何/窗口位置整体重算）。 */
    private fun followMenu(ballParams: WindowManager.LayoutParams) {
        val fan = fanMenu ?: return
        val p = menuParams ?: return
        val ballCenterX = ballParams.x + dp(FloatUi.BALL_SIZE_DP) / 2
        val ballCenterY = ballParams.y + dp(FloatUi.BALL_SIZE_DP) / 2
        fan.place(ballCenterX, ballCenterY)
        p.x = fan.windowX
        p.y = fan.windowY
        runCatching { windowManager.updateViewLayout(fan.root, p) }
    }

    /** 立即撤除扇形菜单（无动画）：单次参谋启动时防 OCR 污染/忙碌态误触。 */
    private fun dismissMenuNow() {
        val view = menuView ?: return
        menuView = null
        fanMenu = null
        menuParams = null
        runCatching { windowManager.removeView(view) }
    }

    private fun collapseMenu() {
        val fan = fanMenu ?: return
        val windowParams = menuParams
        fanMenu = null
        menuView = null
        menuParams = null
        // 收起动画期间窗口转为不可触摸：尚未摘除的窗口若保持可触摸会
        // 吞掉落在其上的点按（僵尸窗——收起后短时间内点主球无响应的
        // 根因），透传后主球在收起全程立即可点。
        if (windowParams != null) {
            windowParams.flags = windowParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(fan.root, windowParams) }
        }
        // 摘窗由收起动画结束回调驱动（原 postDelayed 估时在动画被取消/掉帧
        // 时会残留透明窗——「收起后点球无响应」的另一来源），另保留兜底
        // 定时器：endAction 被吞的罕见路径下再摘一次。
        fan.collapse {
            runCatching { windowManager.removeView(fan.root) }
        }
        mainHandler.postDelayed({
            if (fan.root.isAttachedToWindow) {
                runCatching { windowManager.removeView(fan.root) }
            }
        }, FloatUi.FAN_ANIM_MS * 3)
    }

    // ------------------------------------------------------ card menus

    /**
     * 「更多」子球：贴球弹出次级入口卡片（方案 §3.2）：设置 / 帮助 / 老参谋
     * 设置 / 联系人画像 / 关于。点行=关弹窗再执行；点空白或「✕」=仅关弹窗。
     */
    private fun openMoreMenu() {
        if (cardMenuView != null || ball == null) return
        val params = ballParams ?: return
        val metrics = resources.displayMetrics
        val menu = FloatUi.buildListMenu(
            this,
            items = listOf(
                FloatUi.ListMenuItem(R.string.float_fan_settings) { openMainSettings() },
                FloatUi.ListMenuItem(R.string.float_fan_help) { openHelp() },
                FloatUi.ListMenuItem(R.string.float_more_advisor_settings) {
                    openAdvisorSettings()
                },
                FloatUi.ListMenuItem(R.string.float_more_profile) { openProfileList() },
                FloatUi.ListMenuItem(R.string.float_more_about) { openAbout() },
            ),
            ballCenterX = params.x + dp(FloatUi.BALL_SIZE_DP) / 2,
            ballCenterY = params.y + dp(FloatUi.BALL_SIZE_DP) / 2,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onDismiss = { dismissCardMenu() },
        )
        attachCardMenu(menu)
    }

    /** 「老参谋设置」：老参谋专属设置页（抓帧节奏/权限直达/会话管理等）。 */
    private fun openAdvisorSettings() {
        startActivity(
            Intent(this, FloatSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 「关于」：关于页（复用主框架设置的关于页内容，品牌/版本/版权）。 */
    private fun openAbout() {
        startActivity(
            Intent(this, AdvisorAboutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 「联系人画像」：画像管理列表页（完整管理面，术语铁律 §1.5）。 */
    private fun openProfileList() {
        startActivity(
            Intent(this, AdvisorProfileActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 「自动」子球：托管范围选择器（方案 §3.3）。就绪门——本机没有任何
     * 「联系人 × 关联 APP」聊天会话时提示 [R.string.float_auto_no_apps]
     * 并终止（自动托管监控的是聊天 APP 的通知，与当前屏幕无关）。有数据
     * 才弹选择器：全部联系人 / 某联系人所有 APP / 某联系人的具体 APP 会话。
     */
    private fun openAutoScopePicker() {
        if (cardMenuView != null || ball == null) return
        scope.launch {
            val entries = runCatching { contactRepository.autoScopeEntries() }
                .getOrElse { emptyList() }
            if (entries.isEmpty()) {
                toast(R.string.float_auto_no_apps)
                return@launch
            }
            if (cardMenuView != null) return@launch
            val params = ballParams ?: return@launch
            val metrics = resources.displayMetrics
            val menu = FloatUi.buildScopePicker(
                this@FloatBallService,
                contacts = entries.map { (profile, channels) ->
                    FloatUi.ScopeContact(
                        contactId = profile.id,
                        displayName = profile.displayName,
                        apps = channels.map {
                            FloatUi.ScopeApp(it.imPackage, it.channelKey, it.displayName)
                        },
                    )
                },
                ballCenterX = params.x + dp(FloatUi.BALL_SIZE_DP) / 2,
                ballCenterY = params.y + dp(FloatUi.BALL_SIZE_DP) / 2,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                onDismiss = { dismissCardMenu() },
                callbacks = object : FloatUi.ScopePickerCallbacks {
                    override fun onPickAll() {
                        startAutoForAll()
                    }

                    override fun onPickContact(contact: FloatUi.ScopeContact) {
                        startAutoForContact(contact.contactId, navPkg = null, navEntryName = null)
                    }

                    override fun onPickApp(contact: FloatUi.ScopeContact, app: FloatUi.ScopeApp) {
                        startAutoForContact(contact.contactId, app.imPackage, app.entryName)
                    }
                },
            )
            attachCardMenu(menu)
        }
    }

    /**
     * 「全部联系人」托管：纯通知拟稿模式（方案 §3.3，安全边界——不自动
     * 发送）：开启通知拟稿 + 陌生来信通知，感知面是聊天 APP 的通知。通知
     * 监听未开时提示缺因并引导去系统设置（不静默失效，铁律 §4.5）。
     */
    private fun startAutoForAll() {
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            toast(R.string.float_auto_need_listener)
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        if (!advisorPrefs.state.value.draftEnabled) {
            advisorPrefs.setDraftEnabled(true)
        }
        toast(R.string.float_auto_draft_enabled)
    }

    /**
     * 选定范围的托管（方案 §3.3）：联系人入自动白名单（满
     * [AdvisorPrefs.MAX_AUTO_WHITELIST] 报因终止）→ 指定 APP 会话则 RPA
     * 拉起该聊天页 → 进陪伴窗口（Gatekeeper 硬审在窗口内生效）。导航
     * 失败不阻断托管。
     */
    private fun startAutoForContact(contactId: Long, navPkg: String?, navEntryName: String?) {
        if (!advisorPrefs.addAutoWhitelistContact(contactId)) {
            toast(
                getString(
                    R.string.float_auto_whitelist_full,
                    AdvisorPrefs.MAX_AUTO_WHITELIST,
                ),
            )
            return
        }
        scope.launch {
            if (navPkg != null && navEntryName != null) {
                val nav = imChatNavigator.openChat(navPkg, navEntryName)
                imChatNavigator.toastResult(nav, navEntryName)
            }
            beginCompanion(companionController.contactRefFor(contactId))
        }
    }

    /** 卡片弹窗挂窗：全屏接罩覆盖整屏，点空白由 root 回调关闭（方案 §3.2）。 */
    private fun attachCardMenu(menu: FloatUi.CardMenu) {
        dismissCardMenu()
        val metrics = resources.displayMetrics
        val params = overlayParams(metrics.widthPixels, metrics.heightPixels).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val added = runCatching { windowManager.addView(menu.root, params) }.isSuccess
        if (!added) return
        excludeSystemGestures(menu.root)
        cardMenuView = menu.root
        cardMenuParams = params
    }

    private fun dismissCardMenu() {
        val view = cardMenuView ?: return
        cardMenuView = null
        cardMenuParams = null
        runCatching { windowManager.removeView(view) }
    }

    // ------------------------------------------------ main / dialogs

    /**
     * 「设置」子球：全软件唯一的设置入口是主框架设置页（其中的「老参谋」
     * 分区再进入老参谋专属设置），避免出现两个平行设置中心。
     */
    private fun openMainSettings() {
        collapseMenu()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                var flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                // 方案 §6.5：开启「最近任务隐藏」时，新拉起的主任务一并排除。
                if (keepAliveManager.isRecentsExclusionEnabled()) {
                    flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                addFlags(flags)
                putExtra(EXTRA_FROM_FLOAT, true)
                putExtra(EXTRA_OPEN_TAB, TAB_SETTINGS)
            },
        )
    }

    /** 「帮助」子球：打开使用帮助（完整说明书页）。 */
    private fun openHelp() {
        startActivity(
            Intent(this, AdvisorHelpActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun showMainUi() {
        closeSplit(keepBall = false)
        collapseMenu()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                var flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                // 方案 §6.5：开启「最近任务隐藏」时，新拉起的主任务一并排除。
                if (keepAliveManager.isRecentsExclusionEnabled()) {
                    flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                addFlags(flags)
                putExtra(EXTRA_FROM_FLOAT, true)
            },
        )
    }

    private fun exitApp() {
        // 显式退出：心跳/闹钟不再复活本服务。
        keepAliveManager.onUserExit()
        isRunning = false
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
        // 分屏开着也恢复球（面板留着展示结果，球可拖动避让）；只有菜单
        // 展开时球本就存在，无需重建。
        if (ball == null && menuView == null) showBall()
    }

    private fun hideAllOverlays() {
        ball?.root?.let { runCatching { windowManager.removeView(it) } }
        ball = null
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
        fanMenu = null
        menuParams = null
        cardMenuView?.let { runCatching { windowManager.removeView(it) } }
        cardMenuView = null
        cardMenuParams = null
    }

    private fun removeAllOverlays() {
        hideAllOverlays()
        dismissDirectionPopup()
        split?.let { runCatching { windowManager.removeView(it.root) } }
        split = null
        splitParams = null
        splitDetached = false
        splitHeightBeforeIme = -1
        transcriptView = null
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 服务存活旗标（onCreate/onDestroy 维护），供心跳拉活判断。 */
        @JvmField
        @Volatile
        var isRunning: Boolean = false

        const val CHANNEL_ID = "float_ball"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SHOW_BALL = "com.verlintas.baic2.floatball.SHOW_BALL"
        const val ACTION_REPOST_NOTIFICATION = "com.verlintas.baic2.floatball.REPOST_NOTIFICATION"
        const val ACTION_START_RECORDING = "com.verlintas.baic2.floatball.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.verlintas.baic2.floatball.STOP_RECORDING"
        const val CONSENT_SETTLE_MS = 350L

        /** 单击展开扇形菜单的双击仲裁窗：tap-UP 后该时间内无第二击即展开。 */
        const val TAP_ARB_MS = 120L

        /**
         * 授权孤儿兜底时长。只防「中转 Activity 结果永远不回来」（进程级异常），
         * 不能卡正常授权节奏：系统对话框选范围+确认是慢操作（实测可超 2 分钟），
         * 若早于用户确认触发会把录制态提前撤销、清空结果桥，授权结果回传时被
         * 静默吞掉（表现为授权完毫无反应、球回到蓝色）——故放宽到 10 分钟。
         */
        const val CONSENT_WATCHDOG_MS = 600_000L

        /** 键盘弹起时分屏临时收窄的最小高度（dp），保证输入行与部分转录可见。 */
        const val IME_MIN_PANEL_DP = 220

        /** 焦点期键盘几何校准节拍（ms）：兜底 overlay 丢失末帧 insets 回调。 */
        const val IME_SYNC_INTERVAL_MS = 100L
    }
}
