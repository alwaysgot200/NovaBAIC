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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
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
import com.verlintas.baic2.floatball.auto.AssistsA11yBridge
import com.verlintas.baic2.floatball.auto.AutoSelectionStore
import com.verlintas.baic2.floatball.auto.AutoSessionScheduler
import com.verlintas.baic2.floatball.auto.CompanionWindowController
import com.verlintas.baic2.floatball.home.AdvisorRunState
import com.verlintas.baic2.floatball.home.AdvisorSelfWindowGate
import com.verlintas.baic2.floatball.notify.EscalationNotifier
import com.verlintas.baic2.floatball.notify.NotifyGate
import com.verlintas.baic2.floatball.rpa.RpaActivity
import com.verlintas.baic2.floatball.rpa.RpaChromeGate
import com.verlintas.baic2.floatball.rpa.RpaEntryPoints
import com.verlintas.baic2.floatball.rpa.analyze.RpaAnalyzeCapture
import com.verlintas.baic2.floatball.rpa.analyze.RpaAnalyzeOverlayController
import com.verlintas.baic2.floatball.rpa.withA11ySpec
import com.verlintas.rpa.model.RpaA11ySpec
import com.verlintas.rpa.model.RpaModePolicy
import com.verlintas.rpa.model.RpaPickKind
import com.verlintas.rpa.model.RpaPickRequest
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.keepalive.KeepAliveManager
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** 自动托管范围持久化（主页两级多选结果，方案 §5.3.1）。 */
    @Inject
    lateinit var autoSelectionStore: AutoSelectionStore

    /** 多会话串行调度器（自动档多目标轮转，方案 §5.4）。 */
    @Inject
    lateinit var autoSessionScheduler: AutoSessionScheduler

    /** 运行态唯一事实源：服务写、主页操作条/状态签读（方案 §9.1）。 */
    @Inject
    lateinit var advisorRunState: AdvisorRunState

    /** 范围选定后拉起目标聊天页（RPA 搜索导航，方案 §3.3 接线）。 */
    @Inject
    lateinit var imChatNavigator: com.verlintas.baic2.floatball.auto.ImChatNavigator

    /**
     * 通知 contentIntent 直达会话（2026-10-27 启动导航修复）：视觉/搜索链在
     * a11y 黑洞 IM + 无 Shizuku 时必败（真机 14:15 实测 32.6s 失败），
     * 通知自带的 PendingIntent 是唯一稳键；启动顺序＝直达优先、搜索链兜底。
     */
    @Inject
    lateinit var imNavigator: com.verlintas.baic2.floatball.auto.ImNavigator

    /** 布局分析采集：冻结帧 + 多窗口节点树（覆盖层挂载前调用）。 */
    @Inject
    lateinit var rpaAnalyzeCapture: RpaAnalyzeCapture

    /** a11y 连接状态：分析器打开期间断开即自动收起，恢复悬浮球。 */
    @Inject
    lateinit var a11yBridge: AssistsA11yBridge

    /** RPA 全屏工具 / 后台运行的悬浮球隐藏闸门（原因集合，服务侧统一裁决）。 */
    @Inject
    lateinit var rpaChromeGate: RpaChromeGate

    /** 拾取会话：编辑器武装请求 → 悬浮球工具回填（方案 §7.8）。 */
    @Inject
    lateinit var pickCoordinator: com.verlintas.baic2.floatball.rpa.RpaPickCoordinator

    /** a11y 拾取直接写脚本库，不经过 Activity 中转。 */
    @Inject
    lateinit var rpaScriptStore: com.verlintas.rpa.store.RpaScriptStore

    /** 好参谋账号门（商业化 P0）：未登录不进入服务形态，拉起登录页后停服。 */
    @Inject
    lateinit var accountManager: com.verlintas.baic2.floatball.account.AccountManager

    /** 客户端升级门：强制更新时停止悬浮球并拉起主界面强制页。 */
    @Inject
    lateinit var updateManager: com.verlintas.baic2.floatball.update.AppUpdateManager

    private var forcedUpdateStopHandled = false

    /** 布局分析覆盖层：非空期间悬浮球保持隐藏，关闭时统一恢复。 */
    private var analyze: RpaAnalyzeOverlayController? = null
    private var analyzeA11yJob: Job? = null

    /**
     * RPA 工具（以图找图 / 后台运行）打开期间由 [RpaChromeGate] 占位隐藏球；
     * 工具退出经 [ACTION_RPA_TOOL_FINISHED] 或运行结束释放，闸门清空即恢复。
     */
    private var chromeHidden = false

    private enum class Mode { IDLE, ONESHOT, RECORDING, AUTO }

    /**
     * 状态机。进入非空闲态先把球从“贴边收起”滑回完整形态；回到空闲后
     * 重新计时，是否真正收起由 [scheduleEdgeTuck] 的贴边/遮挡判定决定。
     */
    private var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (value == Mode.IDLE) {
                scheduleEdgeTuck()
            } else {
                revealBall(restartTimer = false)
            }
        }

    /** M3-b：RECORDING 态的子语义——true=自动陪伴窗口（收尾走窗口汇总通知）。 */
    private var companionWindow = false

    /** 自动档运行标记（主页入口）：true 时球显示脉冲红点、停止统一走 [stopAuto]。 */
    private var autoRun = false

    /** 自动档单会话：陪伴窗口已真正开启（用于窗口到期自动复位）。 */
    private var autoCompanionStarted = false

    /** 自动档启动看门狗（方案 §10 风险表）：超时未进入托管即判失败并复位。 */
    private var autoStartWatchdog: Runnable = Runnable { }

    /** 主页入口连续：关闭分屏即停止（方案 §8.12），仅本次会话有效。 */
    private var stopOnSplitClose = false

    /** 主页入口的首次录制不弹开始 Toast（避免污染首帧 OCR）。 */
    private var quietRecordingStart = false

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

    /**
     * 靠边自动隐藏：逻辑坐标仍是 [ballX]/[ballY]，收起时只把窗口可视 x
     * 推到屏外；[ballTuckAnimator] 只改 WindowManager 参数，不回写逻辑坐标。
     */
    private var ballTucked = false
    private var ballTuckSide: FloatEdgeTuck.Side? = null
    private var ballTuckAnimator: ValueAnimator? = null
    private val ballTuckRunnable = Runnable { tuckBallIfIdle() }

    /**
     * 最近一次布局的屏幕宽：[onConfigurationChanged] 触发时 [ballX] 仍是旧屏
     * 逻辑坐标，用它回算旋转前的贴边侧，再把球映射到新屏同侧。
     */
    private var lastScreenWidth = 0

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

    /**
     * 旋转/分屏等配置变化：悬浮窗 attrs 是旧屏坐标系的摆放值，跨旋转会
     * 落到新屏的错误位置（扇形菜单构建时冻结的旧方向尺寸还会把窗口钳离
     * 主球，子球被裁/消失）。立即按新屏重钳主球并重摆菜单，不等下次拖动。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 分析器基于旧方向冻结帧与屏幕坐标：先关闭并恢复球，再按新屏重钳球。
        analyze?.let { controller ->
            toast(R.string.float_rpa_analyze_rotation_closed)
            controller.close()
        }
        val params = ballParams ?: return
        val view = ball?.root ?: return
        val oldScreenWidth = lastScreenWidth
        val ballSize = dp(FloatUi.BALL_SIZE_DP)
        val screen = screenSize()
        // 收起态下 params.x 是屏外可视值，必须用逻辑坐标重钳，否则旋转后跑位。
        val logicalX = if (ballX != Int.MIN_VALUE) ballX else params.x
        val logicalY = if (ballY != Int.MIN_VALUE) ballY else params.y
        // 旧屏逻辑坐标在新屏没有意义：贴边球（已收起，或按旧屏宽仍贴边）回同侧
        // 完整可见位置再重新计时；中间球维持原 x 交给边界钳制。
        val side = ballTuckSide ?: if (oldScreenWidth > 0) {
            FloatEdgeTuck.sideFor(
                logicalX = logicalX,
                screenWidth = oldScreenWidth,
                ballSize = ballSize,
                proximity = dp(FloatEdgeTuck.EDGE_PROXIMITY_DP),
            )
        } else {
            null
        }
        val targetX = side?.let {
            FloatEdgeTuck.edgeX(it, screen.x, ballSize)
        } ?: logicalX
        moveBallWithFollow(view, params, targetX, logicalY)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WindowManager::class.java)
        // 保活降级路径可能先挂了占位 overlay，正式球出现前摘除。
        keepAliveManager.removePlaceholderOverlay()
        startInForeground()
        if (!accountManager.isLoggedIn) {
            // 登录门（商业化 P0）：未登录不得进入服务形态。此处不能提前
            // return——onDestroy 无条件 unregisterReceiver 会因未注册而崩；
            // post 到 onCreate 收尾后统一停服，走完整生命周期对称清理。
            // 保活心跳/闹钟一并撤销，防止「拉起→停服」循环复活。
            mainHandler.post {
                toast(R.string.float_login_required)
                keepAliveManager.onUserExit()
                proactiveCheckpoint.cancel()
                runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(EXTRA_FROM_FLOAT, true)
                        },
                    )
                }
                stopSelf()
            }
        }
        // 强制更新门：缓存命中立即停；否则启动检查，一旦判定强制就停。
        // 与登录门同一套收尾逻辑，避免保活链把服务反复拉起。
        updateManager.checkOnLaunch()
        scope.launch {
            updateManager.state.collect { state ->
                if (isForcedUpdateState(state) || updateManager.forcedBlocked()) {
                    mainHandler.post { stopForForcedUpdate() }
                }
            }
        }
        if (updateManager.forcedBlocked()) {
            mainHandler.post { stopForForcedUpdate() }
        }
        keepAliveManager.onServiceCreated()
        // RPA 全屏工具 / 运行闸门：只在集合进出时动一次窗口，避免重复摘挂。
        scope.launch {
            rpaChromeGate.reasons.collect { reasons ->
                val hidden = reasons.isNotEmpty()
                if (hidden == chromeHidden) return@collect
                chromeHidden = hidden
                if (hidden) {
                    cancelEdgeTuck()
                    hideAllOverlays()
                } else {
                    restoreBall()
                }
            }
        }
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
                // 自动档单会话：第一轮参谋真正落地后，启动阶段才算走完
                // （2026-10-27 方案 §5.2 S1：状态签从「等待首帧」切「托管中」）。
                if (autoRun) advisorRunState.setAutoPhase(AdvisorRunState.AutoPhase.READY)
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
        scope.launch {
            // 自动档单会话：陪伴窗口到期/被收尾后复位到空闲（方案 §5.3.3 停止口径）。
            companionController.activeWindow.collect { window ->
                if (window != null) {
                    if (autoRun) autoCompanionStarted = true
                } else if (autoRun && autoCompanionStarted) {
                    autoCompanionStarted = false
                    mainHandler.post { finishAutoRun() }
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
            ACTION_SHOW_BALL -> when {
                mode == Mode.ONESHOT -> Unit
                ball == null -> showBall()
                // 已收起时通知栏「显示悬浮球」应把球滑出来并保持可见，
                // 不在 2.5s 后立刻收回；下一次触摸/拖动会重新起计时。
                else -> revealBall(restartTimer = false)
            }
            // 通知权限刚授予后，重挂前台通知让常驻提示立即可见。
            ACTION_REPOST_NOTIFICATION -> startInForeground()
            // 免双击入口（通知栏自动化/测试）：与双击同一状态机路径。
            ACTION_START_RECORDING -> if (mode == Mode.IDLE && ball != null) startRecording()
            ACTION_STOP_RECORDING -> if (mode == Mode.RECORDING) stopRecording()
            // 主页三键（方案 §5.1–§5.3）：先收 action 再 moveTaskToBack，服务等
            // 自身不可见后才采集/托管——绝不在自家主页上截屏。
            ACTION_ONESHOT_FROM_MAIN -> startOneshotFromMain()
            ACTION_CONTINUOUS_FROM_MAIN -> startContinuousFromMain()
            ACTION_AUTO_FROM_MAIN -> startAutoFromMain()
            ACTION_STOP_AUTO -> stopAuto(announce = true)
            // 以图找图 Activity 退出：释放闸门，球由闸门回收器恢复。
            ACTION_RPA_TOOL_FINISHED -> {
                rpaChromeGate.show(RpaChromeGate.REASON_QUICK_FIND)
            }
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
        autoSessionScheduler.cancelAllSilently()
        advisorRunState.setIdle()
        AdvisorSelfWindowGate.reset()
        diffEngine.destroy()
        screenAdvisor.advisorScopeCancel()
        // 分析器先于服务 scope 释放：删窗不需要回调，避免恢复已销毁的球。
        analyzeA11yJob?.cancel()
        analyzeA11yJob = null
        analyze?.dispose()
        analyze = null
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
        // 本次触摸序列是否只是“把收起的球滑出来”：是则吞掉单击/双击/长按，
        // 避免用户想滑出时误开菜单或误进参谋。
        var revealedFromTuck = false

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
                    if (revealedFromTuck) return true
                    if (suppressTapUp) {
                        suppressTapUp = false
                        return true
                    }
                    when (mode) {
                        Mode.IDLE -> {
                            if (autoRun) return true // 自动档启动阶段：单击不开菜单（S1）
                            cancelPendingExpand()
                            val r = Runnable {
                                pendingExpand = null
                                if (mode == Mode.IDLE && !autoRun) expandMenu()
                            }
                            pendingExpand = r
                            view.postDelayed(r, TAP_ARB_MS)
                        }
                        Mode.RECORDING -> toggleSplit()
                        // 自动托管：单击开/合分屏查看当前回合（方案 §5.3.3）。
                        Mode.AUTO -> toggleSplit()
                        Mode.ONESHOT -> Unit // 忙碌：单击无效
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (revealedFromTuck) return true
                    suppressTapUp = true
                    when (mode) {
                        Mode.IDLE -> {
                            // 自动档启动阶段（导航中/陪伴窗口未开）：双击=停止自动，
                            // 不能落进「开手动连续」，否则与在途的自动启动互踩（S2）。
                            if (autoRun) {
                                dismissMenuNow()
                                cancelPendingExpand()
                                stopAuto(announce = true)
                                return true
                            }
                            // 第一击在仲裁窗内已展开菜单（间隔>120ms 的双击），
                            // 撤掉再进连续参谋，防菜单残留。
                            dismissMenuNow()
                            cancelPendingExpand()
                            startRecording()
                        }
                        Mode.RECORDING -> stopRecording()
                        // 自动托管：双击 = 全部停止（方案 §5.3.3）。
                        Mode.AUTO -> stopAuto(announce = true)
                        Mode.ONESHOT -> Unit // 忙碌：双击无效
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (revealedFromTuck) return
                    // 长按=单次参谋；OneShot/Recording 态长按拒绝。
                    // 自动档启动阶段（导航中）同样拒绝，避免单次参谋抢投影（S2）。
                    if (mode == Mode.IDLE && !autoRun) {
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
                    // 从收起态唤醒：先滑回完整球，本次触摸序列只做这件事；
                    // 拖动仍可继续，起点改用逻辑坐标，避免从屏外位置跳变。
                    val wasTucked = ballTucked
                    revealedFromTuck = wasTucked
                    if (wasTucked) revealBall(restartTimer = false)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = if (ballX != Int.MIN_VALUE) ballX else params.x
                    startY = if (ballY != Int.MIN_VALUE) ballY else params.y
                    dragging = false
                    // 新一触点：清上一轮双击抑制，避免吞掉后续合法单击。
                    suppressTapUp = false
                    // 长按预热：第一触点即开始读无障碍文本，与系统长按判定
                    // 并行，长按确认后毫秒级进 LLM（拖动/抬起均自动丢弃）。
                    // 面板可见时禁预热：无障碍连 overlay 文字一起读，预读结果
                    // 将全是面板自身文字（bug ①自噬），宁走像素路径。
                    // 收起唤醒只是一次 UI 动作，不预热。
                    if (!revealedFromTuck &&
                        mode == Mode.IDLE &&
                        (split == null || splitDetached)
                    ) {
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 松手/取消后重新计时；菜单若在仲裁窗后展开，会自行取消计时。
                    scheduleEdgeTuck()
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
        // 任何移动都先取消收起计时/动画，把球恢复到可拖动的逻辑位置。
        mainHandler.removeCallbacks(ballTuckRunnable)
        ballTuckAnimator?.cancel()
        ballTuckAnimator = null
        ballTucked = false
        ballTuckSide = null
        val screen = screenSize()
        params.x = x.coerceIn(
            -dp(FloatUi.BALL_SIZE_DP / 2),
            screen.x - dp(FloatUi.BALL_SIZE_DP / 2),
        )
        params.y = y.coerceIn(
            dp(FloatUi.MARGIN_DP),
            screen.y - dp(FloatUi.BALL_SIZE_DP + FloatUi.MARGIN_DP),
        )
        runCatching { windowManager.updateViewLayout(view, params) }
        // 回写拖动落点：摘窗复挂（单次参谋投影/拒绝授权等）走 showBall()
        // 用 ballX/ballY 重建球，不同步会把球瞬移回旧位。
        ballX = params.x
        ballY = params.y
        lastScreenWidth = screen.x
        followMenu(params)
        // 拖动/旋转结束后重新计时；连续 MOVE 会不断重置，不会提前收起。
        scheduleEdgeTuck()
    }

    // ------------------------------------------------------ edge auto-hide

    /**
     * 靠边收起计时：只有空闲、无菜单/分屏/方向弹层、且逻辑位置贴边时才启动。
     * 每次移动/菜单关闭/状态回到 Idle 都会重置。
     */
    private fun scheduleEdgeTuck() {
        mainHandler.removeCallbacks(ballTuckRunnable)
        if (ballTucked) return
        if (mode != Mode.IDLE || ball == null || ballParams == null) return
        if (menuView != null || cardMenuView != null ||
            split != null || directionPopup != null
        ) {
            return
        }
        if (ballX == Int.MIN_VALUE) return
        val screen = screenSize()
        ballTuckSide = FloatEdgeTuck.sideFor(
            logicalX = ballX,
            screenWidth = screen.x,
            ballSize = dp(FloatUi.BALL_SIZE_DP),
            proximity = dp(FloatEdgeTuck.EDGE_PROXIMITY_DP),
        ) ?: return
        mainHandler.postDelayed(ballTuckRunnable, FloatEdgeTuck.IDLE_DELAY_MS)
    }

    /** 计时到点：再核一遍状态，避免菜单/忙碌态在 2.5s 内打开后仍收起。 */
    private fun tuckBallIfIdle() {
        if (mode != Mode.IDLE || ball == null || ballParams == null) return
        if (menuView != null || cardMenuView != null ||
            split != null || directionPopup != null
        ) {
            return
        }
        val side = ballTuckSide ?: return
        tuckBall(side)
    }

    /** 把球滑到屏外，只留 [FloatEdgeTuck.SLIVER_DP] 宽边条；不写 ballX。 */
    private fun tuckBall(side: FloatEdgeTuck.Side) {
        val view = ball?.root ?: return
        val params = ballParams ?: return
        if (ballX == Int.MIN_VALUE) return
        val target = FloatEdgeTuck.tuckedX(
            side = side,
            screenWidth = screenSize().x,
            ballSize = dp(FloatUi.BALL_SIZE_DP),
            sliver = dp(FloatEdgeTuck.SLIVER_DP),
        )
        ballTucked = true
        ballTuckSide = side
        ballTuckAnimator?.cancel()
        ballTuckAnimator = ValueAnimator.ofInt(ballX, target).apply {
            duration = FloatEdgeTuck.ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (ballTuckAnimator === animation) ballTuckAnimator = null
                }
            })
            start()
        }
    }

    /** 从收起态滑回逻辑位置；[restartTimer] 为 false 表示马上要开菜单/进忙碌态。 */
    private fun revealBall(restartTimer: Boolean = true) {
        mainHandler.removeCallbacks(ballTuckRunnable)
        ballTuckAnimator?.cancel()
        ballTuckAnimator = null
        if (ballTucked) {
            ballTucked = false
            ballTuckSide = null
            val view = ball?.root
            val params = ballParams
            if (view != null && params != null && ballX != Int.MIN_VALUE) {
                params.x = ballX
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        if (restartTimer) scheduleEdgeTuck()
    }

    /** 摘窗/销毁前彻底取消计时与动画，不做回弹（球可能即将被移除）。 */
    private fun cancelEdgeTuck() {
        mainHandler.removeCallbacks(ballTuckRunnable)
        ballTuckAnimator?.cancel()
        ballTuckAnimator = null
        ballTucked = false
        ballTuckSide = null
    }

    // ------------------------------------------------------- one shot

    private fun startOneShot(fromMain: Boolean = false) {
        if (mode != Mode.IDLE) return
        // 入口可能是长按主球，或扇形菜单「问答」子球：菜单（若开着）立即
        // 撤除，不等收起动画——既防自身 UI 污染 OCR，也避免忙碌态可点菜单。
        dismissMenuNow()
        mode = Mode.ONESHOT
        advisorRunState.setOneshot(
            if (fromMain) AdvisorRunState.Source.HOME else AdvisorRunState.Source.FLOAT_BALL,
        )
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
            advisorRunState.setIdle()
            ball?.setMode(FloatUi.BallMode.NORMAL, this)
            restoreBall()
            // askJob 收尾晚于 transcript 末次 Idle 发射：不补这一下，输入行
            // 发送/停止键会停在「停止」方块上（末次刷新时 Job 仍 active）。
            mainHandler.post { inputHandle?.refresh() }
        }
    }

    // ----------------------------------------------------- recording

    private fun startRecording(
        forCompanion: Boolean = false,
        fromMain: Boolean = false,
        quiet: Boolean = false,
    ) {
        if (mode != Mode.IDLE) return
        // 三档互斥（方案 v2 §13.2，M3 验收项）：自动陪伴窗口在途时禁止再开
        // 手动连续；窗口自身的启动路径（forCompanion=true）不受此限。
        if (!forCompanion && companionController.windowActive()) {
            toast(R.string.float_companion_gear_conflict)
            return
        }
        mode = Mode.RECORDING
        quietRecordingStart = quiet
        completionCount = 0
        // 新的录制会话：清零会话级 token 计数（累计总量不受影响）。
        screenAdvisor.resetSessionUsage()
        ball?.setMode(FloatUi.BallMode.RECORDING, this)
        closeSplit(keepBall = true) // 录制开始前撤掉自身分屏
        hideAllOverlays()
        // 主页入口连续：关闭分屏即停止（方案 §8.12）。在 startRecording 内部
        // 的 closeSplit 之后置位，避免刚启动就被自身撤屏动作停掉。
        if (fromMain && !forCompanion) {
            stopOnSplitClose = true
            advisorRunState.setContinuous(AdvisorRunState.Source.HOME, stopOnSplitClose = true)
        } else if (!forCompanion) {
            advisorRunState.setContinuous(AdvisorRunState.Source.FLOAT_BALL, stopOnSplitClose = false)
        }
        if (screenCapture.projectionAlive) {
            startRecordingLoop()
        } else {
            // 投影授权编排（方案模块 A）：a11y 开自动点弹窗 / Shizuku 静默
            // 直过 / 手动兜底——经 FloatCaptureActivity 中转发起。
            requestMpConsent { granted ->
                if (granted) {
                    mainHandler.postDelayed({
                        // 授权等待期用户可能已双击停止（或服务被重建）：授权
                        // 晚到时绝不能在非录制态拉起差分循环。自动档在窗口
                        // 启动成功后已切到 AUTO，这里必须一并放行。
                        if (mode == Mode.RECORDING || (mode == Mode.AUTO && autoRun)) {
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
        // 主页入口：首帧不弹 Toast（避免自家文案进 OCR 画面，方案 §5.2）。
        if (!quietRecordingStart) toast(R.string.float_recording_started)
        quietRecordingStart = false
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

    private fun stopRecording(quiet: Boolean = false) {
        diffEngine.stop()
        // MPManager 常驻投影跨会话复用（方案 2026-10-22），停止录制不释放。
        // 陪伴窗口收尾：静默结束——不发「已停止」toast，汇总/续期由控制器通知。
        val wasCompanion = companionWindow
        companionWindow = false
        if (wasCompanion) companionController.finish("service_stop")
        abortRecording()
        // 分屏「自动」切档等场景静默停止，避免提示音/Toast 污染目标聊天页。
        if (!wasCompanion && !quiet) toast(R.string.float_recording_stopped)
    }

    /** 录制态回退到 Idle：球已存在时也要恢复外观（不重建窗口）。 */
    private fun abortRecording() {
        // 自动档单会话依赖录制循环持续观察目标聊天；投影授权被拒/超时时
        // 录制起不来，必须整体退出自动，否则会出现「球回蓝但状态签仍自动中」。
        val abortedAuto = autoRun
        mode = Mode.IDLE
        completionCount = 0
        companionWindow = false
        quietRecordingStart = false
        stopOnSplitClose = false
        if (abortedAuto) {
            autoRun = false
            autoCompanionStarted = false
            if (autoSessionScheduler.isRunning()) autoSessionScheduler.stop()
            ball?.setAutoRunning(false)
        }
        advisorRunState.setIdle()
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
     * 分屏「自动」键（M3-b）：对当前屏绑定的联系人开启托管。与悬浮球子菜单、
     * 主页自动键走同一个 [applyAutoTargets] 入口——总闸、白名单、选择集、
     * 运行态、导航、陪伴窗口/调度器全部一致，不再出现「某个入口只开了窗口、
     * 没进自动态」的分叉。
     *
     * 2026-10-27（方案 §5.2 S7）：分屏入口是 `navigate=false`，默认信任当前屏；
     * 开窗前先用 [ImChatNavigator.chatPageTitleMatches] 校验「当前页确实就是这个
     * 联系人的会话」，明确不是则改用 `navigate=true` 让统一入口自己导航过去，
     * 避免在错误页面上跑整轮参谋与发送。
     */
    private fun startCompanionWindow() {
        val contact = screenAdvisor.activeContact.value
        if (contact == null) {
            toast(R.string.float_companion_need_contact)
            return
        }
        scope.launch {
            val target = resolveCurrentAutoTarget(contact)
            if (target == null) {
                toast(R.string.float_companion_need_contact)
                return@launch
            }
            val imPackage = target.imPackage
            val onTargetPage = if (imPackage == null) {
                true
            } else {
                // null=不可判定（取帧/OCR 不可用）时按「就在目标页」放行，
                // 只在明确判定为「不是目标会话」时才补一次导航。
                imChatNavigator.chatPageTitleMatches(imPackage, target.channelName ?: contact.displayName)
                    ?: true
            }
            applyAutoTargets(
                targets = listOf(target),
                navigate = !onTargetPage,
                source = AdvisorRunState.Source.FLOAT_BALL,
                announce = true,
            )
        }
    }

    /** 收尾通知「续 30 分钟」按钮：按联系人 id 重建引用后走同一自动入口。 */
    private fun onCompanionRenew(intent: Intent?) {
        val contactId = intent?.getLongExtra(CompanionWindowController.EXTRA_CONTACT_ID, -1L)
            ?: -1L
        if (contactId <= 0L) return
        scope.launch {
            val contact = companionController.contactRefFor(contactId) ?: return@launch
            val target = resolveCurrentAutoTarget(contact) ?: return@launch
            applyAutoTargets(
                targets = listOf(target),
                navigate = false,
                source = AdvisorRunState.Source.FLOAT_BALL,
                announce = false,
            )
        }
    }

    /**
     * 打开陪伴窗口（自动/手动共用）。2026-10-27（方案 §5.2 S2）改为 suspend 并
     * 返回启动结果：早退分支不再只弹 Toast 就返回，调用方可以据此统一收口
     * `finishAutoRun()`，杜绝「autoRun=true / 球红 / 却没窗口」的卡死态。
     */
    private suspend fun beginCompanion(
        contact: ContactRef?,
        announceAuto: Boolean = false,
        imPackage: String? = null,
    ): CompanionWindowController.StartResult {
        if (contact == null) {
            toast(R.string.float_companion_need_contact)
            return CompanionWindowController.StartResult.REASON_NO_CONTACT
        }
        if (companionWindow) {
            toast(R.string.float_companion_gear_conflict)
            return CompanionWindowController.StartResult.REASON_GEAR_CONFLICT
        }
        val result = companionController.start(
            contact = contact,
            imPackage = imPackage,
            manualContinuousActive = mode == Mode.RECORDING,
        )
        handleCompanionStart(result, contact, announceAuto)
        return result
    }

    private fun handleCompanionStart(
        result: CompanionWindowController.StartResult,
        contact: ContactRef?,
        announceAuto: Boolean,
    ) {
        when (result) {
            CompanionWindowController.StartResult.STARTED -> {
                companionWindow = true
                if (autoRun) {
                    // 窗口已起：撤销启动看门狗（后续生命周期由窗口自身管理）。
                    mainHandler.removeCallbacks(autoStartWatchdog)
                    // 自动档单会话：不弹「陪伴窗口已开启」以免污染首帧；起录制
                    // 循环后切 AUTO 态（红球 + 脉冲红点），并立刻主动开第一轮。
                    if (mode == Mode.IDLE) startRecording(forCompanion = true, quiet = true)
                    mode = Mode.AUTO
                    ball?.setMode(FloatUi.BallMode.RECORDING, this)
                    ball?.setAutoRunning(true)
                    autoCompanionStarted = true
                    // 会话已就绪，等首帧/主动首轮/来信（方案 §5.2 S1）。
                    advisorRunState.setAutoPhase(AdvisorRunState.AutoPhase.WAITING)
                    if (contact != null) {
                        companionController.kickoffAuto(contact)
                        if (announceAuto) {
                            // 延迟到首帧采集之后再提示，避免自家 Toast 进 OCR。
                            mainHandler.postDelayed({
                                toast(
                                    getString(
                                        R.string.float_auto_scope_started,
                                        contact.displayName,
                                    ),
                                )
                            }, AUTO_ANNOUNCE_DELAY_MS)
                        }
                    }
                    return
                }
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
        if (autoRun && result != CompanionWindowController.StartResult.STARTED) {
            // 自动档启动失败：整体回退空闲，避免球停在 AUTO 态无窗口可托。
            finishAutoRun()
        }
    }

    // ------------------------------------------------- home commands

    /**
     * 主页三键的「自身不可见」握手（方案 §5.5）：先 `moveTaskToBack`（由
     * [AdvisorHomeLauncher] 在前台完成），服务等到 MainActivity 的 `onStop`
     * 才允许采集；拿不到信号必须放弃本次，绝不在自家主页上截屏。
     */
    private suspend fun awaitSelfHidden(): Boolean {
        val hidden = AdvisorSelfWindowGate.awaitHidden(SELF_HIDE_TIMEOUT_MS)
        if (hidden) delay(SELF_HIDE_SETTLE_MS)
        return hidden
    }

    private fun startOneshotFromMain() {
        if (mode != Mode.IDLE) {
            toast(R.string.float_home_disabled_busy)
            return
        }
        scope.launch {
            if (!awaitSelfHidden()) {
                toast(R.string.float_home_self_hide_timeout)
                return@launch
            }
            if (mode == Mode.IDLE) startOneShot(fromMain = true)
        }
    }

    private fun startContinuousFromMain() {
        if (mode != Mode.IDLE) {
            toast(R.string.float_home_disabled_busy)
            return
        }
        scope.launch {
            if (!awaitSelfHidden()) {
                toast(R.string.float_home_self_hide_timeout)
                return@launch
            }
            if (mode == Mode.IDLE) {
                startRecording(fromMain = true, quiet = true)
            }
        }
    }

    /**
     * 主页「自动」：按 [AutoSelectionStore] 持久化的托管组启动（方案 §5.3.3）。
     * 全部联系人 = 纯通知拟稿（与悬浮菜单同口径，不自动发送）；单会话且带
     * APP 走陪伴窗口；其余走串行调度器。
     */
    private fun startAutoFromMain() {
        if (mode != Mode.IDLE) {
            toast(R.string.float_home_disabled_busy)
            return
        }
        val selection = autoSelectionStore.current.value
        if (selection.allContacts) {
            if (!notificationListenerEnabled()) {
                toast(R.string.float_home_auto_need_notify)
                openNotificationListenerSettings()
                return
            }
            if (!advisorPrefs.state.value.draftEnabled) advisorPrefs.setDraftEnabled(true)
            toast(R.string.float_auto_draft_enabled)
            return
        }
        if (selection.targets.isEmpty()) {
            toast(R.string.float_home_scope_empty)
            return
        }
        scope.launch {
            if (!awaitSelfHidden()) {
                toast(R.string.float_home_self_hide_timeout)
                return@launch
            }
            applyAutoTargets(
                targets = selection.targets,
                navigate = true,
                source = AdvisorRunState.Source.HOME,
                announce = false,
            )
        }
    }

    /**
     * 自动档唯一启动口（子菜单 / 分屏 / 主页三处共用）：
     * 开总闸 → 加白名单 → 持久化选择集 → 单目标导航并开陪伴窗口、
     * 多目标交给串行调度器；两条路径都置 `autoRun`、写 [AdvisorRunState]、
     * 切红球 + 脉冲红点。失败路径均有显式 Toast，绝不静默回到空闲。
     *
     * 分屏「自动」可能在手动连续（RECORDING）中点按：先静默停旧档再进自动，
     * 避免 ModeGate 的三档互斥把用户挡在门外。
     *
     * 2026-10-27（方案 §5.2 S1~S6）：
     * - 单会话分支一进入就切红球 + 脉冲红点 + `AutoPhase.STARTING`，
     *   导航阶段（可能十几秒）不再「点了没反应」；
     * - 多目标/联系人级启动前校验通知使用权（事件源），缺失即报因并跳设置；
     * - 全链路 `auto_start:` 日志（source/targets/result/elapsed/note）。
     */
    private fun applyAutoTargets(
        targets: List<AutoSelectionStore.Target>,
        navigate: Boolean,
        source: AdvisorRunState.Source,
        announce: Boolean,
    ) {
        val startedAt = System.currentTimeMillis()
        if (mode != Mode.IDLE && mode != Mode.RECORDING) {
            toast(R.string.float_home_disabled_busy)
            logAutoStart(source, targets, result = "BUSY", startedAt = startedAt, note = "mode=$mode")
            return
        }
        if (targets.isEmpty()) {
            toast(R.string.float_home_scope_empty)
            logAutoStart(source, targets, result = "FAILED", startedAt = startedAt, note = "empty_scope")
            return
        }
        if (!advisorPrefs.state.value.autoModeEnabled) {
            // 三个入口都是用户显式选择托管目标，视同确认开启总闸；
            // 白名单仍是红线，只有本次选中的联系人会被自动发送。
            advisorPrefs.setAutoModeEnabled(true)
        }
        // ActionDispatcher 硬审要求联系人在白名单内；会话级目标共用联系人白名单。
        for (contactId in targets.map { it.contactId }.distinct()) {
            if (!advisorPrefs.addAutoWhitelistContact(contactId)) {
                toast(getString(R.string.float_auto_whitelist_full, AdvisorPrefs.MAX_AUTO_WHITELIST))
                logAutoStart(source, targets, result = "FAILED", startedAt = startedAt, note = "whitelist_full")
                return
            }
        }
        val single = targets.size == 1 && targets.first().imPackage != null
        // S3：多目标/联系人级完全依赖通知事件入队，缺通知使用权时调度器
        // 只会空转（用户视角就是「选了自动永远不动」），必须前置拦截。
        if (!single && !notificationListenerEnabled()) {
            toast(R.string.float_home_auto_need_notify)
            openNotificationListenerSettings()
            logAutoStart(source, targets, result = "FAILED", startedAt = startedAt, note = "no_notify_access")
            return
        }
        // 持久化本次选择：主页「自动」键与后续续期都按同一目标集启动。
        autoSelectionStore.save(AutoSelectionStore.Selection(targets = targets))
        if (mode == Mode.RECORDING) stopRecording(quiet = true)
        if (single) {
            val target = targets.first()
            val imPackage = target.imPackage ?: return
            val channel = target.channelName.orEmpty()
            val appName = appLabel(imPackage)
            autoRun = true
            autoCompanionStarted = false
            // S1：导航前先把运行态点亮（红球 + 脉冲红点），不再等窗口开成功才可见。
            ball?.setMode(FloatUi.BallMode.RECORDING, this)
            ball?.setAutoRunning(true)
            restoreBall()
            advisorRunState.setAuto(
                targetCount = 1,
                activeContact = target.displayName,
                activeApp = appName,
                source = source,
                phase = AdvisorRunState.AutoPhase.STARTING,
            )
            // 启动看门狗（风险表）：30s 内没能进入托管就判定启动失败并复位，
            // 避免导航卡死留下「球是红的、状态签自动中、其实什么都没跑」。
            autoStartWatchdog = Runnable {
                if (!autoRun) return@Runnable
                val phase = advisorRunState.state.value.autoPhase
                val started = autoCompanionStarted ||
                    phase == AdvisorRunState.AutoPhase.WAITING ||
                    phase == AdvisorRunState.AutoPhase.READY
                if (started) return@Runnable
                Log.w(TAG, "auto_start: watchdog timeout, aborting pending start")
                notifyAutoStartFailure(
                    target.displayName,
                    getString(R.string.float_auto_start_failed_timeout),
                )
                finishAutoRun()
            }
            mainHandler.postDelayed(autoStartWatchdog, AUTO_START_TIMEOUT_MS)
            scope.launch {
                if (navigate) {
                    advisorRunState.setAutoPhase(
                        AdvisorRunState.AutoPhase.NAVIGATING,
                        getString(R.string.float_home_auto_phase_navigating, appName),
                    )
                    // ① 通知 contentIntent 直达（2026-10-27 真机根因修复）：微信
                    // 8.0.79 节点树对 a11y 零暴露 + 本机无 Shizuku，视觉/搜索链实测
                    // 32.6s 必败（目标会话被 47 条置顶/50 未读推深，主列表 4 页扫不到、
                    // FTS 输入框吞字）。通知自带的 PendingIntent 是 IM 自己发布的
                    // 「点通知直达该会话」钥匙，不受我方后台弹窗限制；启动前重扫
                    // 活跃通知补缓存（进程重启/监听重连后缓存为空，只有监听期间
                    // 新到的通知才会自动入缓存）。
                    val seeded = AdvisorNotificationListener.refreshIntentCache()
                    val direct = imNavigator.navigate(
                        imPackage = imPackage,
                        contactId = target.contactId,
                        rawTitleHint = channel,
                        timeoutMs = AUTO_DIRECT_NAV_TIMEOUT_MS,
                    )
                    Log.i(
                        TAG,
                        "auto_start: notify-direct seeded=$seeded result=${direct.outcome}" +
                            " detail=${direct.detail}",
                    )
                    if (direct.outcome == com.verlintas.baic2.floatball.auto.ImNavigator
                            .NavOutcome.NAVIGATED
                    ) {
                        Log.i(TAG, "auto_start: notify-direct navigated, skip search chain")
                    } else {
                        // ② 视觉/搜索链兜底（无缓存键 / PendingIntent 失效 / 超时）。
                        val nav = imChatNavigator.openChat(imPackage, channel)
                        if (nav is com.verlintas.baic2.floatball.auto.ImChatNavigator.NavResult.Fail) {
                            imChatNavigator.toastResult(nav, channel)
                            // S10（2026-10-27）：导航失败不再一律硬失败。陪伴窗口
                            // 要求屏幕停在目标会话页，而 a11y 黑洞 IM + 无 Shizuku 时
                            // 这个落脚点随时可能拿不到（通知被清空、会话被大量置顶/
                            // 未读推深）。只要通知使用权可用，就降级为**通知驱动托管**
                            // （与多会话同源调度器）：来信通知到达时自行导航 → 成稿 →
                            // 按纪律代答，不依赖屏幕停在会话页。
                            if (notificationListenerEnabled()) {
                                autoSessionScheduler.start(targets)
                                mode = Mode.AUTO
                                advisorRunState.setAutoPhase(
                                    AdvisorRunState.AutoPhase.WAITING,
                                    getString(R.string.float_auto_phase_wait_notify),
                                )
                                logAutoStart(
                                    source, targets, result = "DEGRADED", startedAt = startedAt,
                                    note = "nav:${direct.outcome.name.lowercase()}>" +
                                        "${nav.reason}>scheduler",
                                )
                                return@launch
                            }
                            logAutoStart(
                                source, targets, result = "FAILED", startedAt = startedAt,
                                note = "nav:${direct.outcome.name.lowercase()}>${nav.reason}",
                            )
                            notifyAutoStartFailure(
                                target.displayName,
                                getString(R.string.float_auto_start_failed_nav, appName),
                            )
                            finishAutoRun()
                            return@launch
                        }
                    }
                }
                if (!autoRun) return@launch
                val result = beginCompanion(
                    contact = companionController.contactRefFor(target.contactId),
                    announceAuto = announce,
                    imPackage = imPackage,
                )
                if (result != CompanionWindowController.StartResult.STARTED) {
                    // 陪伴窗口没起来（无联系人/档位冲突/总闸关闭）：整体回退空闲，
                    // 并且不只依赖 Toast——关键失败再补一条可查通知（S2/S6）。
                    notifyAutoStartFailure(
                        target.displayName,
                        getString(R.string.float_auto_start_failed_window, result.name),
                    )
                }
                logAutoStart(
                    source, targets,
                    result = if (result == CompanionWindowController.StartResult.STARTED) {
                        "STARTED"
                    } else {
                        "FAILED"
                    },
                    startedAt = startedAt,
                    note = "companion=$result nav=${navigate}",
                )
            }
        } else {
            // 多目标 / 联系人级：通知事件驱动的串行调度器，球红 + 脉冲红点。
            autoRun = true
            autoCompanionStarted = false
            mode = Mode.AUTO
            autoSessionScheduler.start(targets)
            ball?.setMode(FloatUi.BallMode.RECORDING, this)
            ball?.setAutoRunning(true)
            logAutoStart(source, targets, result = "STARTED", startedAt = startedAt, note = "scheduler")
            if (announce) {
                toast(getString(R.string.float_auto_scope_started_many, targets.size))
            }
        }
    }

    /**
     * 启动链统一日志（方案 §5.2 S6）：一行包含来源/目标数/目标/结果/耗时/备注，
     * 便于真机上区分「根本没启动」与「启动了但没消息」。
     */
    private fun logAutoStart(
        source: AdvisorRunState.Source,
        targets: List<AutoSelectionStore.Target>,
        result: String,
        startedAt: Long,
        note: String? = null,
    ) {
        Log.i(
            TAG,
            "auto_start: source=$source targets=${targets.size} " +
                "first=${targets.firstOrNull()?.let { "${it.displayName}@${it.imPackage ?: "all"}" }} " +
                "result=$result elapsed=${System.currentTimeMillis() - startedAt}ms" +
                (note?.let { " note=$it" } ?: ""),
        )
    }

    /**
     * 子菜单选中具体 APP 会话：补全联系人显示名与通道名后走统一入口。
     * 选「所有 APP」（navPkg=null）时 imPackage 留空，交给通知驱动调度器。
     */
    private suspend fun resolveAutoTarget(
        contactId: Long,
        navPkg: String?,
        navEntryName: String?,
    ): AutoSelectionStore.Target? {
        val profile = contactRepository.profile(contactId) ?: return null
        if (navPkg == null) {
            return AutoSelectionStore.Target(
                contactId = contactId,
                imPackage = null,
                channelName = null,
                displayName = profile.displayName,
            )
        }
        val channels = runCatching { contactRepository.channelsOf(contactId) }
            .getOrDefault(emptyList())
        val chosen = channels.firstOrNull {
            it.imPackage == navPkg && (
                navEntryName == null ||
                    it.displayName == navEntryName ||
                    it.channelKey == navEntryName
                )
        } ?: channels.firstOrNull { it.imPackage == navPkg }
        return AutoSelectionStore.Target(
            contactId = contactId,
            imPackage = navPkg,
            channelName = chosen?.displayName ?: navEntryName ?: profile.displayName,
            displayName = profile.displayName,
        )
    }

    /**
     * 分屏「自动」：当前屏联系人可能同时有微信/企微号，优先使用最近识别到的
     * 聊天通道（[ScreenAdvisor.lastChatCandidate]），保证自动发送落在眼前
     * 这个 APP 的会话上，而不是档案默认 APP。
     *
     * 2026-10-27（方案 §5.2 S4）：档案 `imPackage` 为空但联系人有关联通道时，
     * 回退取第一个通道的包名；仍拿不到包名才算「联系人级」目标（交给通知调度器），
     * 不再静默把「明明在聊天页点自动」变成「只等通知」。
     */
    private suspend fun resolveCurrentAutoTarget(contact: ContactRef): AutoSelectionStore.Target? {
        val profile = contactRepository.profile(contact.contactId) ?: return null
        val channels = runCatching { contactRepository.channelsOf(contact.contactId) }
            .getOrDefault(emptyList())
        val candidate = screenAdvisor.lastChatCandidate
        val imPackage = candidate?.first?.takeIf { it.isNotBlank() }
            ?: profile.imPackage
            ?: channels.firstOrNull()?.imPackage
        val channelName = candidate?.second?.takeIf { it.isNotBlank() }
            ?: channels.firstOrNull { it.imPackage == imPackage }?.displayName
            ?: contact.displayName
        return AutoSelectionStore.Target(
            contactId = contact.contactId,
            imPackage = imPackage,
            channelName = channelName,
            displayName = contact.displayName,
        )
    }

    /** 自动档统一停止（主页自动键 / 双击球 / 通知）：清队列、收窗口、灭红点。 */
    private fun stopAuto(announce: Boolean) {
        if (!autoRun && mode != Mode.AUTO) return
        mainHandler.removeCallbacks(autoStartWatchdog)
        autoRun = false
        autoCompanionStarted = false
        if (autoSessionScheduler.isRunning()) autoSessionScheduler.stop()
        if (companionWindow) {
            // 单会话：走陪伴窗口既有收尾（汇总通知），不弹「录制已停止」。
            stopRecording()
        } else {
            if (mode == Mode.AUTO) mode = Mode.IDLE
            // 启动阶段（导航中/陪伴窗口还没开）也可能已经把球点亮成红球（S1），
            // 统一在这里复位，否则失败后会留下「红球但无窗口可托」的假象（S2）。
            if (mode != Mode.RECORDING) {
                ball?.setMode(FloatUi.BallMode.NORMAL, this)
                ball?.setBadge(0)
                restoreBall()
            }
        }
        stopOnSplitClose = false
        advisorRunState.setIdle()
        ball?.setAutoRunning(false)
        if (announce) toast(R.string.float_home_auto_stopped)
    }

    /** 陪伴窗口到期/启动失败：静默复位（不重复 toast，窗口自身已发收尾通知）。 */
    private fun finishAutoRun() {
        if (!autoRun) return
        stopAuto(announce = false)
    }

    private fun notificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    /**
     * 启动失败可见化（方案 §5.2 S6）：Toast 可能被 IM 前景/系统限制吞掉，
     * 关键失败再发一条低优先级通知，保证「选了自动没反应」时至少留下可查痕迹，
     * 用户能区分「根本没启动」与「启动了但没消息」。
     */
    private fun notifyAutoStartFailure(contact: String?, reason: String) {
        runCatching {
            val manager = NotificationManagerCompat.from(this)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val system = getSystemService(NotificationManager::class.java)
                if (system.getNotificationChannel(AUTO_CHANNEL_ID) == null) {
                    system.createNotificationChannel(
                        NotificationChannel(
                            AUTO_CHANNEL_ID,
                            getString(R.string.float_auto_channel),
                            NotificationManager.IMPORTANCE_LOW,
                        ),
                    )
                }
            }
            val text = if (contact.isNullOrBlank()) reason else "$contact：$reason"
            val notification = NotificationCompat.Builder(this, AUTO_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.float_auto_start_failed_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(mainActivityPendingIntent())
                .setAutoCancel(true)
                .build()
            NotifyGate.notifyIfPermitted(this, AUTO_FAIL_NOTIFICATION_ID, notification)
        }.onFailure { Log.d(TAG, "auto fail notification failed", it) }
    }

    private fun openNotificationListenerSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun appLabel(imPackage: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(imPackage, 0)).toString()
    }.getOrDefault(imPackage)

    /** 连续模式的 OCR 前掩膜：自身分屏 + 悬浮球的屏幕矩形。 */
    private fun currentOverlayRects(): List<Rect> {
        val rects = mutableListOf<Rect>()
        val screen = screenSize()
        split?.root?.let { root ->
            // 用窗口真实屏坐标：键盘避让后面板被收高且 BOTTOM 落在键盘上沿，
            // 不能再按 params.height/y 推算。
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            if (location[1] >= 0 && root.height > 0) {
                rects.add(Rect(0, location[1], screen.x, location[1] + root.height))
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
        if (mode == Mode.RECORDING || (mode == Mode.AUTO && autoRun)) abortRecording()
        reattachSplitWindow() // 授权流程终止：恢复可能被摘下的结果面板
        restoreBall()
    }

    // ---------------------------------------------------------- overlays

    private fun showBall() {
        if (ball != null || analyze != null || rpaChromeGate.isHidden) return
        val screen = screenSize()
        lastScreenWidth = screen.x
        if (ballX == Int.MIN_VALUE) {
            ballX = screen.x - dp(FloatUi.BALL_SIZE_DP + FloatUi.MARGIN_DP)
            ballY = screen.y / 3
        }
        val params = overlayParams(
            width = dp(FloatUi.BALL_SIZE_DP),
            height = dp(FloatUi.BALL_SIZE_DP),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
            // 靠边收起要把窗口滑到屏幕外只留一条边；不加此 flag 时 WMS
            // 会把窗口静默钳回父框架，动画看起来像「缩了一下又弹回来」。
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        val view = FloatUi.buildBall(this)
        attachBallGesture(view.root, params)
        windowManager.addView(view.root, params)
        ball = view
        ballParams = params
        excludeSystemGestures(view.root)
        // 重建/首次挂窗都从完全可见形态开始，空闲时重新起收起计时。
        ballTucked = false
        ballTuckSide = null
        scheduleEdgeTuck()
        // 重建后按当前状态机恢复外观（录制/自动=红球）。
        view.setMode(
            when (mode) {
                Mode.RECORDING, Mode.AUTO -> FloatUi.BallMode.RECORDING
                Mode.ONESHOT -> FloatUi.BallMode.BUSY
                Mode.IDLE -> FloatUi.BallMode.NORMAL
            },
            this,
        )
        if (mode == Mode.RECORDING) view.setBadge(completionCount)
        if (mode == Mode.AUTO && autoRun) view.setAutoRunning(true)
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
        val screen = screenSize()
        val params = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = (screen.y * FloatUi.SPLIT_DEFAULT_RATIO).toInt(),
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

        // 正文区挂载轻量转录视图：与主界面同一个「好参谋」会话的数据流。
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
        val screenHeight = screenSize().y
        if (splitHeightBeforeIme < 0) {
            // 记录键盘弹出前的面板高（用户拖出的分界线高度）；异常时序下
            // 可能拿到已收缩的高度，低于 minHeight 时保底，防 coerceIn 空区间。
            splitHeightBeforeIme = p.height.coerceAtLeast(
                dp(IME_MIN_PANEL_DP).coerceAtLeast((screenHeight * FloatUi.SPLIT_MIN_RATIO).toInt()),
            )
        }
        val minHeight = dp(IME_MIN_PANEL_DP)
            .coerceAtLeast((screenHeight * FloatUi.SPLIT_MIN_RATIO).toInt())
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
            val available = (screenHeight - overlap).coerceAtLeast(minHeight)
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
                    val screen = screenSize()
                    val minHeight = (screen.y * FloatUi.SPLIT_MIN_RATIO).toInt()
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
                        (screen.y * FloatUi.SPLIT_MAX_RATIO).toInt()
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
        // 主页入口连续：关闭分屏即停止（方案 §8.12）。内部启动路径的
        // closeSplit 早于置位，不会误停；只有用户主动关屏才会命中。
        if (stopOnSplitClose && mode == Mode.RECORDING && !companionWindow) {
            stopOnSplitClose = false
            mainHandler.post {
                if (mode == Mode.RECORDING) {
                    stopRecording()
                    toast(R.string.float_home_continuous_stopped)
                }
            }
        }
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
        // 菜单锚点必须用逻辑落点：球若正收起，先滑回再取参数。
        revealBall(restartTimer = false)
        val from = ballParams ?: return

        val ballCenterX = from.x + dp(FloatUi.BALL_SIZE_DP) / 2
        val ballCenterY = from.y + dp(FloatUi.BALL_SIZE_DP) / 2
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
                // 「更多」= 次级入口卡片（设置/帮助/好参谋设置/联系人画像，§3.2）；
                // 悬浮球常驻，不再提供手动隐藏（不想显示请走「退出」）。
                FloatUi.FanItem(R.string.float_fan_more) { openMoreMenu() },
                FloatUi.FanItem(R.string.float_fan_exit) { exitApp() },
            ),
            facing = fanFacingFor(ballCenterX),
            ballCenterX = ballCenterX,
            ballCenterY = ballCenterY,
            // 屏幕尺寸实时现查：菜单跨旋转存活，构建时冻结的旧方向尺寸
            // 会在钳制时把窗口拉离主球（子球被裁/消失）。
            screenSize = { screenSize() },
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
            // 免 WMS 钳制：不加此 flag 时 WMS 会把窗静默钳进父框架
            // （fitTypes=STATUS_BARS，横屏实测 360×360 窗请求 (1056,436) 被钳到
            // frame=[920,312][1280,672]），球贴屏角时锚点被挤进窗角、±75° 弧
            // 物理容不下 → 子球被窗缘裁掉/消失。加此后窗可放屏外，锚点恒为
            // 窗口中心，裁切面只剩屏幕本身（solveFan 的屏幕判定管辖）。
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            x = fan.windowX
            y = fan.windowY
        }
        windowManager.addView(fan.root, params)
        excludeSystemGestures(fan.root)
        menuView = fan.root
        menuParams = params
        fanMenu = fan
        menuOpenedAt = SystemClock.uptimeMillis()
        // 首次全局布局后才弹出：此时 syncFanAnchor 已按真实落点定好弧心/
        // 倾角，expand 播种的回弹起点才是精确值（否则贴边时弹出弧线与
        // 最终弧线错位，锚点校正瞬间还会跳变）。
        var expandedOnce = false
        fan.root.viewTreeObserver.addOnGlobalLayoutListener {
            syncFanAnchor()
            if (!expandedOnce) {
                expandedOnce = true
                fan.expand()
            }
        }
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

    /**
     * 扇形展开方向：或左或右——贴左半屏向右展开，贴右半屏向左展开，
     * 开口恒朝屏幕中央（展开与拖动跨中线翻向共用同一判定）。
     */
    private fun fanFacingFor(ballCenterX: Int): FloatUi.FanFacing =
        if (ballCenterX <= screenSize().x / 2) {
            FloatUi.FanFacing.RIGHT
        } else {
            FloatUi.FanFacing.LEFT
        }

    /** 拖动主球时，展开中的扇形菜单跟随球心（几何/窗口位置整体重算）。 */
    private fun followMenu(ballParams: WindowManager.LayoutParams) {
        val fan = fanMenu ?: return
        val p = menuParams ?: return
        val ballCenterX = ballParams.x + dp(FloatUi.BALL_SIZE_DP) / 2
        val ballCenterY = ballParams.y + dp(FloatUi.BALL_SIZE_DP) / 2
        // 拖动跨过屏幕中线即翻向：开口恒朝屏幕中央，否则子球会朝着
        // 近侧屏幕边缘被窗口/屏幕裁掉。
        fan.updateFacing(fanFacingFor(ballCenterX))
        fan.place(ballCenterX, ballCenterY)
        p.x = fan.windowX
        p.y = fan.windowY
        runCatching { windowManager.updateViewLayout(fan.root, p) }
    }

    /**
     * 扇形弧心锚点校正（菜单窗每次全局布局后调用）：悬浮窗 x/y 是 WMS
     * 父框架相对坐标且会被静默二次钳制（父框架尺寸无任何 API 可查），
     * params 摆放值≠真实落点。用两窗 `getLocationOnScreen`（唯一真值）
     * 反推球心相对扇形窗的真实坐标重排子球，让弧心钉死在主球上，免疫
     * 旋转陈旧度量/钳制/挖孔屏。
     */
    private fun syncFanAnchor() {
        val fan = fanMenu ?: return
        val ballRoot = ball?.root ?: return
        val fanLoc = IntArray(2)
        val ballLoc = IntArray(2)
        runCatching {
            fan.root.getLocationOnScreen(fanLoc)
            ballRoot.getLocationOnScreen(ballLoc)
        }.getOrElse { return }
        val half = dp(FloatUi.BALL_SIZE_DP) / 2f
        fan.reanchor(
            ballLoc[0] + half - fanLoc[0],
            ballLoc[1] + half - fanLoc[1],
        )
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
        // 菜单关闭后回到空闲形态，重新开始靠边收起计时。
        scheduleEdgeTuck()
    }

    // ------------------------------------------------------ card menus

    /**
     * 「更多」子球：贴球弹出次级入口卡片（方案 §3.2）。分组顺序固定为
     * RPA 工具置顶、设置与帮助在后；RPA 组按「布局分析 → 以图找图 → RPA 设计」
     * 排列，入口只指向已可用能力，避免死链。
     */
    private fun openMoreMenu() {
        if (cardMenuView != null || ball == null) return
        revealBall(restartTimer = false)
        val params = ballParams ?: return
        val screen = screenSize()
        val menu = FloatUi.buildGroupedListMenu(
            this,
            groups = listOf(
                FloatUi.ListMenuGroup(
                    titleRes = R.string.float_more_group_rpa,
                    items = listOf(
                        FloatUi.ListMenuItem(
                            labelRes = R.string.float_rpa_tool_analyze,
                            descRes = R.string.float_rpa_tool_analyze_desc,
                        ) { openRpaAnalyze() },
                        FloatUi.ListMenuItem(
                            labelRes = R.string.float_rpa_tool_quickfind,
                            descRes = R.string.float_rpa_tool_quickfind_desc,
                        ) { openRpaQuickFind() },
                        FloatUi.ListMenuItem(
                            labelRes = R.string.float_rpa_tool_design,
                            descRes = R.string.float_rpa_tool_design_desc,
                        ) { openRpaDesign() },
                    ),
                ),
                FloatUi.ListMenuGroup(
                    titleRes = R.string.float_more_group_settings,
                    items = listOf(
                        FloatUi.ListMenuItem(R.string.float_fan_settings) {
                            openMainSettings()
                        },
                        FloatUi.ListMenuItem(R.string.float_fan_help) { openHelp() },
                        FloatUi.ListMenuItem(R.string.float_more_advisor_settings) {
                            openAdvisorSettings()
                        },
                        FloatUi.ListMenuItem(R.string.float_more_profile) {
                            openProfileList()
                        },
                        FloatUi.ListMenuItem(R.string.float_more_about) { openAbout() },
                    ),
                ),
            ),
            ballCenterX = params.x + dp(FloatUi.BALL_SIZE_DP) / 2,
            ballCenterY = params.y + dp(FloatUi.BALL_SIZE_DP) / 2,
            screenWidth = screen.x,
            screenHeight = screen.y,
            onDismiss = { dismissCardMenu() },
        )
        attachCardMenu(menu)
    }

    /** 「RPA 设计」：脚本库 + 拖拽编排；从悬浮菜单进入时不预填 IM 包名。 */
    private fun openRpaDesign() {
        startActivity(
            RpaEntryPoints.design(this)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 「以图找图」：拉起透明 Activity 覆盖在目标 App 上，选图后自隐抓帧匹配。
     * 门禁：空闲态（录制/单次/自动期间画面被本 App 占用，抓到的帧不可信）→
     * 布局分析未打开 → 撤分屏与菜单/球，保证抓帧干净。打开期间球保持隐藏，
     * Activity 退出经 [ACTION_RPA_TOOL_FINISHED] 复位并恢复球。
     */
    private fun openRpaQuickFind() {
        if (mode != Mode.IDLE) {
            toast(R.string.float_rpa_quickfind_idle_only)
            return
        }
        if (rpaChromeGate.isHidden || analyze != null) return
        // 闸门先占位：Activity 启动前的窗口切换期也不再重建球。
        rpaChromeGate.hide(RpaChromeGate.REASON_QUICK_FIND)
        closeSplit(keepBall = true)
        hideAllOverlays()
        runCatching { startActivity(RpaEntryPoints.quickFind(this)) }
            .onFailure {
                rpaChromeGate.show(RpaChromeGate.REASON_QUICK_FIND)
                toast(R.string.float_rpa_quickfind_open_failed)
            }
    }

    /**
     * 「布局分析」：不启动 Activity，直接在目标 App 上挂全屏 a11y 覆盖层。
     * 门禁顺序：空闲态 → 无障碍已连接 → 前台不是本 App；随后撤分屏/菜单/球，
     * 让采集拿到干净的目标画面。分析器打开期间悬浮球保持隐藏。
     */
    private fun openRpaAnalyze() {
        if (mode != Mode.IDLE) {
            toast(R.string.float_rpa_analyze_idle_only)
            return
        }
        if (analyze != null) return
        if (AssistsService.getOrNull() == null || !a11yBridge.connected.value) {
            showAnalyzeA11yNotice()
            return
        }
        val foreground = runCatching {
            AssistsCore.getPackageName(AssistsCore.NodeLookupScope.ActiveWindow)
        }.getOrDefault("")
        if (foreground == packageName) {
            toast(R.string.float_rpa_analyze_switch_to_target)
            return
        }
        val screen = screenSize()
        closeSplit(keepBall = true)
        hideAllOverlays()
        // 拾取会话（方案 §7.8）：只有 A11Y 线路在此消费；VISION 请求由以图找图
        // 页消费。任务在打开分析器时取快照，避免分析期间过期判定的抖动。
        val pickRequest = pickCoordinator.current()?.takeIf { it.kind == RpaPickKind.A11Y }
        val controller = RpaAnalyzeOverlayController(
            context = this,
            capture = rpaAnalyzeCapture,
            screenWidth = screen.x,
            screenHeight = screen.y,
            selfPackage = packageName,
            onClosed = {
                analyze = null
                analyzeA11yJob?.cancel()
                analyzeA11yJob = null
                restoreBall()
            },
            onFailure = { toast(R.string.float_rpa_analyze_start_failed) },
            pickHint = pickRequest?.let {
                getString(R.string.float_rpa_pick_banner, it.scriptName, it.displayStep(null))
            },
            onApplyPick = pickRequest?.let { request ->
                { spec: RpaA11ySpec -> applyA11yPick(request, spec) }
            },
            onCancelPick = if (pickRequest != null) {
                {
                    pickCoordinator.cancel()
                    toast(R.string.float_rpa_pick_cancelled)
                }
            } else {
                null
            },
        )
        analyze = controller
        controller.start()
        analyzeA11yJob = scope.launch {
            a11yBridge.connected.collect { connected ->
                if (!connected && analyze != null) {
                    toast(R.string.float_rpa_analyze_a11y_lost)
                    analyze?.close()
                }
            }
        }
    }

    /**
     * a11y 拾取回填（方案 §7.8）：写脚本 → 清请求 → 带 scriptId / stepId 重开
     * 编辑器。复用 [RpaActivity] 的 extras 路径，不新增 Activity、不落结果文件。
     */
    private fun applyA11yPick(request: RpaPickRequest, spec: RpaA11ySpec) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val script = rpaScriptStore.load(request.scriptId)
                        ?: error("script_missing")
                    if (!RpaModePolicy.a11yEnabled(script.mode)) error("mode_without_a11y")
                    rpaScriptStore.save(script.withA11ySpec(request.stepId, spec)).getOrThrow()
                }
            }
            result.fold(
                onSuccess = { script ->
                    pickCoordinator.cancel()
                    runCatching {
                        startActivity(
                            RpaActivity.intent(
                                this@FloatBallService,
                                scriptId = script.id,
                                stepId = request.stepId,
                            ),
                        )
                    }.onFailure { error ->
                        toast(
                            getString(
                                R.string.float_rpa_pick_store_failed,
                                error.message ?: "open_editor_failed",
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    toast(
                        getString(
                            R.string.float_rpa_pick_store_failed,
                            error.message ?: "apply_failed",
                        ),
                    )
                },
            )
        }
    }

    /** 无障碍未开启：贴球卡片给出「去开启 / 取消」，不启动任何分析窗口。 */
    private fun showAnalyzeA11yNotice() {
        if (cardMenuView != null || ball == null) return
        revealBall(restartTimer = false)
        val params = ballParams ?: return
        val screen = screenSize()
        val menu = FloatUi.buildGroupedListMenu(
            this,
            groups = listOf(
                FloatUi.ListMenuGroup(
                    items = listOf(
                        FloatUi.ListMenuItem(R.string.float_rpa_analyze_open_a11y) {
                            startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        FloatUi.ListMenuItem(R.string.float_rpa_analyze_cancel) {},
                    ),
                ),
            ),
            ballCenterX = params.x + dp(FloatUi.BALL_SIZE_DP) / 2,
            ballCenterY = params.y + dp(FloatUi.BALL_SIZE_DP) / 2,
            screenWidth = screen.x,
            screenHeight = screen.y,
            titleRes = R.string.float_rpa_analyze_need_a11y,
            onDismiss = { dismissCardMenu() },
        )
        attachCardMenu(menu)
    }

    /** 「好参谋设置」：好参谋专属设置页（抓帧节奏/权限直达/会话管理等）。 */
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
            val screen = screenSize()
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
                screenWidth = screen.x,
                screenHeight = screen.y,
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
        dismissCardMenu()
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
        // 全量托管保持「纯通知拟稿」安全档，但也持久化选择集，主页状态一致。
        autoSelectionStore.save(AutoSelectionStore.Selection(allContacts = true))
        toast(R.string.float_auto_draft_enabled)
    }

    /**
     * 子菜单选定的托管范围：先补全目标（联系人 + APP 会话），再走与主页/
     * 分屏共用的 [applyAutoTargets]。选具体 APP 时先撤掉范围卡片再导航，
     * 避免自家弹窗遮挡 RPA 搜索/OCR；导航失败显式报因并整体回退空闲。
     */
    private fun startAutoForContact(contactId: Long, navPkg: String?, navEntryName: String?) {
        dismissCardMenu()
        scope.launch {
            val target = resolveAutoTarget(contactId, navPkg, navEntryName)
            if (target == null) {
                toast(R.string.float_companion_need_contact)
                return@launch
            }
            applyAutoTargets(
                targets = listOf(target),
                navigate = target.imPackage != null,
                source = AdvisorRunState.Source.FLOAT_BALL,
                announce = true,
            )
        }
    }

    /** 卡片弹窗挂窗：全屏接罩覆盖整屏，点空白由 root 回调关闭（方案 §3.2）。 */
    private fun attachCardMenu(menu: FloatUi.CardMenu) {
        dismissCardMenu()
        val screen = screenSize()
        val params = overlayParams(screen.x, screen.y).apply {
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
        // 卡片关闭后球恢复可收起；若行动作已切走页面/进忙碌态，计时会自行失效。
        scheduleEdgeTuck()
    }

    // ------------------------------------------------ main / dialogs

    /**
     * 「设置」子球：全软件唯一的设置入口是主框架设置页（其中的「好参谋」
     * 分区再进入好参谋专属设置），避免出现两个平行设置中心。
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
        // 分析器 / RPA 工具闸门仍占位时保持球隐藏；释放后由各自回调恢复。
        if (analyze != null || rpaChromeGate.isHidden) return
        // 分屏开着也恢复球（面板留着展示结果，球可拖动避让）；只有菜单
        // 展开时球本就存在，无需重建。
        if (ball == null && menuView == null) {
            showBall()
        } else {
            scheduleEdgeTuck()
        }
    }

    private fun hideAllOverlays() {
        // 球窗口即将摘除，收起计时/动画必须一起取消，否则回调会操作死窗口。
        cancelEdgeTuck()
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

    /** 强制更新态：停止悬浮球并拉起主界面强制页（与登录门同一套收尾）。 */
    private fun stopForForcedUpdate() {
        if (forcedUpdateStopHandled) return
        forcedUpdateStopHandled = true
        toast(R.string.float_update_required_notice)
        keepAliveManager.onUserExit()
        proactiveCheckpoint.cancel()
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_FROM_FLOAT, true)
                },
            )
        }
        stopSelf()
    }

    private fun isForcedUpdateState(
        state: com.verlintas.baic2.floatball.update.UpdateState,
    ): Boolean = when (state) {
        is com.verlintas.baic2.floatball.update.UpdateState.Forced -> true
        is com.verlintas.baic2.floatball.update.UpdateState.Downloading -> state.forced
        is com.verlintas.baic2.floatball.update.UpdateState.ReadyToInstall -> state.forced
        is com.verlintas.baic2.floatball.update.UpdateState.PermissionRequired -> state.forced
        is com.verlintas.baic2.floatball.update.UpdateState.Failed -> state.forced
        else -> false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 整屏尺寸（px，含系统装饰）——悬浮球/菜单摆放的近似上界。悬浮窗
     * x/y 实际是 WMS 父框架（整屏减状态栏/导航条）坐标，且 WMS 会静默
     * 二次钳制；父框架真实尺寸无任何 API 可查（displayMetrics / getRectSize
     * / maximumWindowMetrics / currentWindowMetrics 实测均返回整屏），
     * 真实落点由 [syncFanAnchor] 用 getLocationOnScreen 回算兜底。两条铁则：
     * ① 必须走 Display 实时查询——Service 的 Resources.displayMetrics 旋转后
     * 会停留在旧方向（横屏实测 widthPixels 仍是竖屏宽），扇形菜单被误钳制；
     * ② 上界宁大勿小（超界部分由 WMS 最终钳掉），误差由真实落点回算吸收。
     */
    private fun screenSize(): Point {
        val rect = Rect()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRectSize(rect)
        return Point(rect.width(), rect.height())
    }

    companion object {
        /** 服务存活旗标（onCreate/onDestroy 维护），供心跳拉活判断。 */
        @JvmField
        @Volatile
        var isRunning: Boolean = false

        const val CHANNEL_ID = "float_ball"
        const val NOTIFICATION_ID = 1001

        /** 启动链日志 TAG（`auto_start:` 一行账的过滤前缀）。 */
        const val TAG = "FloatBallService"

        /** 自动托管启动失败通知（方案 §5.2 S6）：低优先级、独立渠道、独立 id。 */
        const val AUTO_CHANNEL_ID = "floatball_auto"
        const val AUTO_FAIL_NOTIFICATION_ID = 2006
        const val ACTION_SHOW_BALL = "com.verlintas.baic2.floatball.SHOW_BALL"
        const val ACTION_REPOST_NOTIFICATION = "com.verlintas.baic2.floatball.REPOST_NOTIFICATION"
        const val ACTION_START_RECORDING = "com.verlintas.baic2.floatball.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.verlintas.baic2.floatball.STOP_RECORDING"

        /** RPA 工具页（以图找图）退出回传：复位悬浮球隐藏标志。 */
        const val ACTION_RPA_TOOL_FINISHED = "com.verlintas.baic2.floatball.RPA_TOOL_FINISHED"

        /** 主页三键入口（方案 §5.5）：action 由 [com.verlintas.baic2.floatball.home.AdvisorHomeLauncher] 发出。 */
        const val ACTION_ONESHOT_FROM_MAIN = "com.verlintas.baic2.floatball.ONESHOT_FROM_MAIN"
        const val ACTION_CONTINUOUS_FROM_MAIN = "com.verlintas.baic2.floatball.CONTINUOUS_FROM_MAIN"
        const val ACTION_AUTO_FROM_MAIN = "com.verlintas.baic2.floatball.AUTO_FROM_MAIN"
        const val ACTION_STOP_AUTO = "com.verlintas.baic2.floatball.STOP_AUTO"

        /** 自身窗口隐藏握手超时（方案 §5.1 第 5 条：1.5s 拿不到即取消）。 */
        const val SELF_HIDE_TIMEOUT_MS = 1_500L

        /** 确认不可见后的退场稳定时间（方案 §5.1 第 2 条）。 */
        const val SELF_HIDE_SETTLE_MS = 250L

        const val CONSENT_SETTLE_MS = 350L

        /** 自动托管开启提示延迟：避开差分引擎首帧 OCR，防自家 Toast 污染。 */
        const val AUTO_ANNOUNCE_DELAY_MS = 1_200L

        /** 自动档启动看门狗超时（ms）：导航+开窗的总预算，超时判失败并复位。 */
        const val AUTO_START_TIMEOUT_MS = 30_000L

        /**
         * 启动时「通知 contentIntent 直达」的等待预算（ms）：微信/企微等 a11y
         * 黑洞下这是唯一稳键（发布方为 IM，不受我方后台弹窗限制）。命中通常
         * <1s；3s 未到前台即判失败并回落视觉/搜索链，不吞掉看门狗预算。
         */
        const val AUTO_DIRECT_NAV_TIMEOUT_MS = 3_000L

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
