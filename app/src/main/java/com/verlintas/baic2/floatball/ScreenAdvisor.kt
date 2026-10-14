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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.data.repository.MemoryRepository
import com.verlintas.baic2.core.engine.AgentEvent
import com.verlintas.baic2.core.engine.AgentLoop
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.MemoryPrompt
import com.verlintas.baic2.core.model.MemoryText
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.ScreenshotProvider
import com.verlintas.baic2.floatball.ScreenPerceiver.Perception
import com.verlintas.baic2.floatball.advisor.ChatBriefBuilder
import com.verlintas.baic2.floatball.advisor.PersonaExtractor
import com.verlintas.baic2.floatball.brief.ContactBriefSection
import com.verlintas.baic2.floatball.contact.ContactDirectory
import com.verlintas.baic2.floatball.contact.ContactHistoryGateway
import com.verlintas.baic2.floatball.contact.ContactMemoryReader
import com.verlintas.baic2.floatball.contact.ContactRef
import com.verlintas.baic2.keepalive.KeepAlivePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 「老参谋」的大脑。会话管道与主界面聊天同构：OCR/无障碍文本作为用户
 * 消息写入持久化「老参谋」会话，由同一个 [AgentLoop] 以 Chat 模式作答。
 *
 * 单次（长按）与连续（双击）共用 [submitTurn] 的串行队列；每完成一回合
 * 发出 [turnCompletions]（服务层据此点亮红点徽章）。感知本身委托
 * [ScreenPerceiver] 四级阶梯。不含任何窗口/UI 逻辑。
 */
@Singleton
class ScreenAdvisor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenshotProvider: ScreenshotProvider,
    private val ocrProvider: OcrProvider,
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val memoryRepository: MemoryRepository,
    private val agentLoop: AgentLoop,
    private val auxiliaryTasks: AuxiliaryTasks,
    private val perceiver: ScreenPerceiver,
    private val prefs: AdvisorPrefs,
    private val keepAlivePrefs: KeepAlivePrefs,
    private val contactDirectory: ContactDirectory,
    private val historyGateway: ContactHistoryGateway,
    private val memoryReader: ContactMemoryReader,
    private val stitcher: MultiScreenStitcher,
    private val geometryProvider: ScreenGeometryProvider,
    private val noiseRules: NoiseRules,
    private val chatBriefBuilder: ChatBriefBuilder,
    private val personaExtractor: PersonaExtractor,
) {

    /** 面板渲染所需的一切：会话消息 + 正在流式的回答 + 阶段状态。 */
    data class UiState(
        val conversationId: Long = 0L,
        val messages: List<ChatMessage> = emptyList(),
        val streaming: String? = null,
        val status: Status = Status.Idle,
    )

    sealed interface Status {
        data object Idle : Status
        data object OcrRunning : Status
        data object Thinking : Status
        /** 零文本失败后的第 [attempt]/[total] 次重试等待中。 */
        data class Retrying(val attempt: Int, val total: Int) : Status
        data class Error(val reason: String) : Status
    }

    sealed interface Event {
        /** 需要用户授权投影（服务层走 FloatCaptureActivity，授权后重跑）。 */
        data object NeedsConsent : Event

        /** 问题已写入会话，LLM 回合开始。 */
        data class TurnStarted(val question: String) : Event

        /** 本屏与上一屏几乎相同，已短路（不写库、不调 LLM）。 */
        data object Unchanged : Event

        data class Failed(val reason: String) : Event
    }

    private val _transcript = MutableStateFlow(UiState())
    val transcript: StateFlow<UiState> = _transcript.asStateFlow()

    private val _turnCompletions = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val turnCompletions: SharedFlow<Unit> = _turnCompletions.asSharedFlow()

    /**
     * 最近一次参谋解析到的联系人（null=未识别/重名/模糊近邻，走共享会话）。
     * 分屏状态条据此显示「🎯 名称 ▾ / 未绑定 ▸」，只在每次 prepare 时更新。
     */
    private val _activeContact = MutableStateFlow<ContactRef?>(null)
    val activeContact: StateFlow<ContactRef?> = _activeContact.asStateFlow()

    private val advisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null
    private var preheatJob: Job? = null

    @Volatile
    private var preheatedText: String? = null

    /** LLM 串行队列：连续模式差分注入与单次参谋共用，永不并发。 */
    private val turnMutex = Mutex()

    /**
     * 回合后沉淀（digest 刷新 + 画像萃取）的并发闸：两者素材都从全量回合
     * 重算，上一回合沉淀还没跑完时新回合直接跳过，避免慢 LLM 下重复消耗。
     */
    private val consolidationLock = Mutex()

    // ------------------------------------------------------ projection

    fun isCaptureReady(): Boolean = screenshotProvider.ready.value

    /** 无障碍服务连接状态（设置页徽章用）。 */
    val a11yConnected: StateFlow<Boolean> get() = perceiver.a11yConnected

    fun createPermissionIntent(): android.content.Intent =
        screenshotProvider.createPermissionIntent()

    fun onPermissionResult(resultCode: Int, data: android.content.Intent): Boolean =
        screenshotProvider.onPermissionResult(resultCode, data)

    fun releaseProjection() {
        runCatching { screenshotProvider.release() }
    }

    // --------------------------------------------------------- preheat

    /** 触点落下即预热（长按第一触点）：只读无障碍文本，未走长按即丢弃。 */
    fun preheat() {
        if (preheatJob?.isActive == true || preheatedText != null) return
        preheatJob = advisorScope.launch {
            val text = perceiver.readA11yText()
            if (text != null) preheatedText = text
        }
    }

    /** 取走预热结果并取消预热任务（无结果返回 null）。 */
    fun consumePreheat(): String? {
        preheatJob?.cancel()
        preheatJob = null
        val text = preheatedText
        preheatedText = null
        return text
    }

    // -------------------------------------------------------- one shot

    /**
     * 单次参谋。文本感知命中时不需要撤窗（无障碍树只含活动应用窗口）；
     * 像素路径由服务层事先撤掉自身浮层。投影拍到的帧用完即释放。
     */
    fun adviseOnce(preheated: String?): Flow<Event> = flow {
        // 多屏上滑拼接（§6.6）：像素路径且前置条件全满足时，先滚动采集多屏，
        // 成功则直接进入回合；返回 null 静默回退现行单屏感知阶梯。
        val stitched = tryMultiScreenStitch(preheated)
        if (stitched != null) {
            if (stitched.viaProjection) releaseProjection()
            _transcript.updateStatus(Status.OcrRunning)
            emitScreenTurn(stitched.text)
            return@flow
        }
        when (val perception = perceiver.quickPerceive(preheated)) {
            is Perception.Text -> emitScreenTurn(perception.text)

            is Perception.Image -> {
                if (perception.viaProjection) releaseProjection()
                _transcript.updateStatus(Status.OcrRunning)
                val recognized = recognizeCleanBody(perception.bytes)
                if (recognized?.body.isNullOrEmpty()) {
                    _transcript.updateStatus(Status.Error(REASON_NO_TEXT))
                    emit(Event.Failed(REASON_NO_TEXT))
                } else {
                    // L2：a11y 窗口标题缺失时，用 OCR 标题带文本兜底联系人识别。
                    // 此处 recognized 经 isNullOrEmpty()==false 已智能转换为非空。
                    emitScreenTurn(recognized.body, recognized.titleBand)
                }
            }

            Perception.NeedsProjection -> emit(Event.NeedsConsent)

            is Perception.Failed -> {
                _transcript.updateStatus(Status.Error(perception.reason))
                emit(Event.Failed(perception.reason))
            }
        }
    }

    /**
     * 多屏拼接前置校验（方案 §6.6，任一不满足静默回退单屏）：
     * 设置开启；a11y 文本未命中（文本路径无坐标、不滚动）；已绑定联系人
     * （未绑定可能是网页/地图）；无障碍已连接（滚动依赖 swipe）；包名不在
     * 禁滑名单；IME 已收拢（先 back 一次，仍聚焦则放弃）。
     */
    private suspend fun tryMultiScreenStitch(preheated: String?): MultiScreenStitcher.Stitched? {
        val settings = prefs.state.value
        if (settings.autoScrollScreens <= 0) return null
        if (preheated != null && preheated.length >= ScreenPerceiver.MIN_TEXT_CHARS) return null
        if (!perceiver.accessibilityConnected) return null

        val contact = resolveContact() ?: return null
        val pkg = perceiver.foregroundPackage()
        if (pkg != null && pkg in settings.swipeBlockedPackages) return null

        if (perceiver.editableFocused()) {
            perceiver.pressBack() // IM 首按只收键盘不退页
            delay(KEYBOARD_COLLAPSE_WAIT_MS)
            if (perceiver.editableFocused()) return null
        }

        val names = listOf(contact.displayName) + contact.aliases
        return runCatching {
            stitcher.run(
                config = MultiScreenStitcher.Config(
                    extraScreens = settings.autoScrollScreens,
                    settleMs = settings.scrollSettleMs,
                    swipeRatio = settings.swipeHeightRatio,
                    scrollBack = settings.scrollBack,
                ),
                whitelistNames = names,
            )
        }.onFailure { android.util.Log.w("ScreenAdvisor", "multi-screen stitch failed", it) }
            .getOrNull()
    }

    /**
     * 像素帧的三层噪声治理（§12.2）：底部带位图掩膜 → OCR → 按包围盒分流
     * （状态栏丢、标题带不进正文）→ 行级正则库。标题带行同时产出，供 L2
     * 联系人识别（§11.1）：取带内最上方、穿过行级噪声规则的短行。
     */
    private suspend fun recognizeCleanBody(bytes: ByteArray): RecognizedScreen? {
        val imeOpen = perceiver.editableFocused()
        return withContext(Dispatchers.Default) {
            val (width, height) = FrameQuality.bitmapSize(bytes) ?: return@withContext null
            val geometry = geometryProvider.compute(width, height, imeOpen)
            val cropped = FrameQuality.cropBands(bytes, width, height, geometry.bottomBandPx)
            val lines = ocrProvider.recognize(cropped).getOrNull() ?: return@withContext null
            val partition = OcrNoiseFilter.partition(lines, geometry)
            val body = OcrNoiseFilter.clean(partition.bodyLines, noiseRules.get(), emptyList())
                .joinToString("\n") { it.text.trim() }
                .takeIf { it.isNotBlank() }
            val titleBand = pickTitleBand(partition.titleLines)
            body?.let { RecognizedScreen(it, titleBand) }
        }
    }

    /**
     * 标题带选名：titleLines 已按 top,left 排序；优先取穿过噪声规则的首行，
     * 全被规则滤掉时退回首个长度合理的原始短行（群名偶含规则字符的容错）。
     * 角标/群人数/壳后缀的规范化在 ContactRepository.resolve 内完成。
     */
    private fun pickTitleBand(titleLines: List<com.verlintas.baic2.device.api.OcrLine>): String? {
        val cleaned = OcrNoiseFilter.clean(titleLines, noiseRules.get(), emptyList())
            .map { OcrNoiseFilter.normalize(it.text) }
            .firstOrNull { it.length in TITLE_MIN_CHARS..TITLE_MAX_CHARS }
        val raw = cleaned ?: titleLines.asSequence()
            .map { OcrNoiseFilter.normalize(it.text) }
            .firstOrNull { it.length in TITLE_MIN_CHARS..TITLE_MAX_CHARS }
        return raw?.takeIf { !it.matches(TITLE_SYMBOL_ONLY) }
    }

    /** recognizeCleanBody 的产出：正文 + 标题带候选名（可空）。 */
    private data class RecognizedScreen(val body: String, val titleBand: String?)

    /**
     * 单次参谋的统一发射点：先 prepare（去重/落库），确认要真正开跑后才
     * 发 [Event.TurnStarted]（面板随即展开），避免与上一屏重复时白开一次 LLM。
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<Event>.emitScreenTurn(
        raw: String,
        titleFallback: String? = null,
    ) {
        when (val prepared = prepareTurn(autoScreenContent(raw), titleFallback)) {
            is PreparedTurn.Ready -> {
                emit(Event.TurnStarted(raw))
                executePrepared(prepared.conversationId, prepared.contact, backgroundTurn = false)
            }
            PreparedTurn.Unchanged -> emit(Event.Unchanged)
            PreparedTurn.Empty -> {
                _transcript.updateStatus(Status.Error(REASON_NO_TEXT))
                emit(Event.Failed(REASON_NO_TEXT))
            }
        }
    }

    // ---------------------------------------------------- shared turns

    /** 自动屏幕消息的统一标记：转录视图据此折叠，主界面据此识别来源。 */
    fun autoScreenContent(text: String): String =
        context.getString(R.string.float_ask_question_label) + "\n" + text

    /** prepareTurn 的结果：可以开跑 / 与上一屏重复 / 内容为空。 */
    private sealed interface PreparedTurn {
        data class Ready(val conversationId: Long, val contact: ContactRef?) : PreparedTurn
        data object Unchanged : PreparedTurn
        data object Empty : PreparedTurn
    }

    /** 连续模式（差分引擎）回合的处置结果。 */
    enum class SubmitOutcome { RAN, SKIPPED_UNCHANGED, EMPTY }

    /**
     * 连续（差分）模式入口：prepare + 执行一体；重复画面静默跳过。
     */
    suspend fun submitTurn(content: String, backgroundTurn: Boolean = false): SubmitOutcome =
        when (val prepared = prepareTurn(content)) {
            is PreparedTurn.Ready -> {
                executePrepared(prepared.conversationId, prepared.contact, backgroundTurn)
                SubmitOutcome.RAN
            }
            PreparedTurn.Unchanged -> SubmitOutcome.SKIPPED_UNCHANGED
            PreparedTurn.Empty -> SubmitOutcome.EMPTY
        }

    /**
     * 阶段一：识别联系人、路由会话、截断、去重、把屏幕消息写入会话（不跑 LLM）。
     * 自动屏幕消息若与上一条屏幕消息高度相似（[FrameQuality.textSimilarity]
     * ≥ [DUP_SCREEN_SIMILARITY]），直接 [PreparedTurn.Unchanged]——
     * 不写库、不调模型，主屏/锁屏反复点亮不再制造垃圾回合。
     */
    private suspend fun prepareTurn(content: String, titleFallback: String? = null): PreparedTurn {
        val question = content.trim().take(MAX_SCREEN_CHARS)
        if (question.isEmpty()) return PreparedTurn.Empty
        val contact = resolveContact(titleFallback)
        _activeContact.value = contact
        val conversationId = resolveConversation(contact)
        if (isDuplicateScreen(conversationId, question)) {
            _transcript.updateStatus(Status.Idle)
            return PreparedTurn.Unchanged
        }
        conversationRepository.append(
            ChatMessage(
                conversationId = conversationId,
                role = ChatRole.USER,
                content = question,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return PreparedTurn.Ready(conversationId, contact)
    }

    /**
     * 联系人识别（v3 §11.1）：L1 = 前台包名 + a11y 窗口标题；L1 未命中且
     * [titleFallback] 非空（像素路径 OCR 标题带，L2）时用标题带再试一次。
     * 规范化、决策表与懒创建由 [ContactDirectory] 实现侧完成；任何失败按
     * 未绑定处理，不阻断参谋。
     */
    private suspend fun resolveContact(titleFallback: String? = null): ContactRef? {
        val pkg = perceiver.foregroundPackage()
        // 桌面/系统壳不是聊天窗口：不建档、不识别，整轮回落到共享老参谋会话（方案 §6.8④）。
        if (!isChatWindow(pkg)) return null
        runCatching { contactDirectory.resolve(pkg, perceiver.windowTitle()) }
            .getOrNull()
            ?.let { return it }
        if (titleFallback.isNullOrBlank()) return null
        return runCatching { contactDirectory.resolve(pkg, titleFallback) }.getOrNull()
    }

    /**
     * 判断前台包是否可能是聊天窗口。桌面包按 HOME Intent 动态发现（覆盖三方
     * 启动器）；系统固定壳走 [NON_CHAT_PACKAGES]。包名缺失时不阻断既有行为。
     */
    private fun isChatWindow(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg in NON_CHAT_PACKAGES) return false
        return pkg !in homePackages
    }

    private val homePackages: Set<String> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** 绑定联系人走其专属会话；未绑定走共享老参谋会话（现行为）。 */
    private suspend fun resolveConversation(contact: ContactRef?): Long =
        if (contact == null) {
            ensureSharedConversation()
        } else {
            val conversationId = contactDirectory.ensureAdvisorConversation(contact)
            subscribeConversation(conversationId)
            conversationId
        }

    /** 阶段二：串行队列里跑一个 LLM 回合。 */
    private suspend fun executePrepared(
        conversationId: Long,
        contact: ContactRef?,
        backgroundTurn: Boolean,
    ) {
        _transcript.updateStatus(Status.Thinking)
        turnMutex.withLock {
            runAdvisorTurn(conversationId, contact, backgroundTurn)
        }
    }

    /** 自动屏幕消息与上一条屏幕消息的正文相似度判定（前缀行不参与）。 */
    private suspend fun isDuplicateScreen(conversationId: Long, content: String): Boolean {
        if (!content.startsWith(screenLabel)) return false
        val last = conversationRepository.getMessages(conversationId)
            .lastOrNull { it.role == ChatRole.USER && it.content.startsWith(screenLabel) }
            ?: return false
        return FrameQuality.textSimilarity(
            stripScreenLabel(content),
            stripScreenLabel(last.content),
        ) >= DUP_SCREEN_SIMILARITY
    }

    private fun stripScreenLabel(content: String): String =
        content.removePrefix(screenLabel).removePrefix("\n").trim()

    private val screenLabel: String
        get() = context.getString(R.string.float_ask_question_label)

    /** 一次尝试的结果：成功 / 零文本失败（可重试）/ 已有部分输出失败（不重试）。 */
    private sealed interface TurnOutcome {
        data object Done : TurnOutcome
        data class ZeroOutputFailure(val reason: String) : TurnOutcome
        data class PartialOutputFailure(val reason: String, val partial: String) : TurnOutcome
    }

    /**
     * 与 ChatViewModel 的聊天轮同构：AgentLoop 产出、仓储落库。
     * 零文本失败（首字超时/连接失败/零文本 Failed）按递增间隔重试；
     * 已见文本（含思考过程）后失败绝不重试，保存 partial 并报错。
     */
    private suspend fun runAdvisorTurn(conversationId: Long, contact: ContactRef?, tightRetry: Boolean) {
        val config: ProviderConfig = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            ?: run {
                _transcript.updateStatus(Status.Error(REASON_NO_CONFIG))
                return
            }

        // 落库原文完整，但发给模型的是「瘦身历史」：当前屏全文 + 最近若干屏
        // 缩写 + 更早屏幕的一句场景占位/滚动摘要（见 AdvisorHistory）。
        // 绑定联系人会话走 bound 档：近屏保留更多、预算更宽、旧屏报价承诺仍有效。
        val boundDigest = contact?.let { c ->
            runCatching { contactDirectory.digest(c.contactId) }.getOrNull()?.digest
        }
        val allMessages = conversationRepository.getMessages(conversationId)
        val history = AdvisorHistory.build(
            messages = allMessages,
            screenLabel = screenLabel,
            // 绑定联系人只用自己的 digest；尚无 digest 时为 null（省略历史行），
            // 绝不回退共享会话 digest——别的屏幕摘要串进客户线程会误导话术。
            digest = if (contact != null) boundDigest else prefs.screenDigest(),
            bound = contact != null,
        )
        val question = allMessages.lastOrNull { it.role == ChatRole.USER } ?: return

        // 参谋的「阅人」：核心记忆 + 由屏幕文字（绑定联系人再追加业务目标）
        // 线索唤醒的通用笔记。检索是只读浏览，不强化（feedback=false），
        // 看屏不应改写记忆权重。
        val cue = contact?.goal?.takeIf { it.isNotBlank() }?.let { "${question.content} $it" }
            ?: question.content
        val core = runCatching { memoryRepository.getCore() }.getOrNull()
        val primed = runCatching {
            memoryRepository.recall(
                terms = MemoryText.terms(cue),
                limit = PRIMED_NOTES,
                feedback = false,
            )
        }.getOrDefault(emptyList()).map { it.note }
        val memoryContext = MemoryPrompt.context(
            core = core,
            primed = primed,
            now = System.currentTimeMillis(),
        )

        // 绑定联系人：① ChatBrief（§6.2 业务目标/风格/双方人设/话术 Top3）+
        // ② 按人直取的交往脉络 + 到期承诺 + 成交要件 + 转折铁律，各自独立段落
        // 拼进 custom（与上游 memoryContext 分轨；取数纪律见 §6.5）。
        val briefSection = contact?.let { c ->
            runCatching { chatBriefBuilder.buildSection(c.contactId, stripScreenLabel(question.content)) }
                .getOrNull()
        }
        val contactSection = contact?.let { c ->
            runCatching { buildContactSection(c, question.content) }.getOrNull()
        }
        val customSystemPrompt = listOfNotNull(ADVISOR_STYLE, briefSection, contactSection)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        val ka = keepAlivePrefs.state.value
        val maxRetries = if (tightRetry) TIGHT_RETRY_TIMES else ka.retryTimes
        val firstTextTimeoutMs =
            (if (tightRetry) TIGHT_RETRY_TIMEOUT_SEC else ka.retryTimeoutSec) * 1000L
        val delayBaseSec = ka.retryDelaySec.coerceAtLeast(1).toLong()

        try {
            var attempt = 0
            while (true) {
                val outcome = collectAdvisorOnce(
                    config = config,
                    conversationId = conversationId,
                    history = history,
                    memoryContext = memoryContext,
                    customSystemPrompt = customSystemPrompt,
                    triggerMessageId = question.id,
                    firstTextTimeoutMs = firstTextTimeoutMs,
                )
                when (outcome) {
                    is TurnOutcome.Done -> {
                        // 后台滚动摘要 + 画像萃取：独立协程，不占串行队列、不阻塞 UI。
                        // 上一回合的沉淀仍在跑则跳过（素材每次按全量回合重算，不丢信息）。
                        advisorScope.launch {
                            if (consolidationLock.tryLock()) {
                                try {
                                    runCatching { maybeRefreshDigest(conversationId, contact) }
                                    if (contact != null) {
                                        runCatching { maybeExtractPersona(conversationId, contact) }
                                    }
                                } finally {
                                    consolidationLock.unlock()
                                }
                            }
                        }
                        return
                    }
                    is TurnOutcome.PartialOutputFailure -> {
                        if (outcome.partial.isNotBlank()) {
                            conversationRepository.append(
                                ChatMessage(
                                    conversationId = conversationId,
                                    role = ChatRole.ASSISTANT,
                                    content = outcome.partial,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        _transcript.updateStatus(Status.Error(outcome.reason))
                        return
                    }
                    is TurnOutcome.ZeroOutputFailure -> {
                        if (attempt >= maxRetries) {
                            _transcript.updateStatus(Status.Error(outcome.reason))
                            return
                        }
                        attempt++
                        _transcript.updateStatus(Status.Retrying(attempt, maxRetries))
                        // 递增等待：第 n 次重试前等 base × n（5、10、15…），
                        // 可取消 delay 不占线程，turnMutex 内安全。
                        delay(delayBaseSec * 1000L * attempt)
                    }
                }
            }
        } finally {
            _transcript.update { it.copy(streaming = null) }
        }
    }

    /**
     * 绑定联系人回合的提示词段落（设计文档 §6.5–6.6）：
     * ① recallByEntity 按主名+别名直取该联系人的事实（天然防串档）；
     * ② ContactMemoryReader 按人只读取窗内承诺/约见到期置顶（AGREEMENT/PLAN，
     *   不 touch、不强化，避开上游 upcoming() 的全局截断与权重反写）；
     * ③ 当前屏出现成交信号时，跨该联系人全部会话检索成交要件；
     * ④ 渲染器始终附上转折铁律。全部只读、feedback=false。
     */
    private suspend fun buildContactSection(contact: ContactRef, currentScreen: String): String {
        val names = (listOf(contact.displayName) + contact.aliases)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(ENTITY_NAME_LIMIT)
        val now = System.currentTimeMillis()

        val entityNotes = if (names.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                memoryRepository.recallByEntity(
                    entities = names,
                    limit = ENTITY_NOTES,
                    feedback = false,
                )
            }.getOrDefault(emptyList()).map { it.note }
        }

        val dueNotes = runCatching { memoryReader.dueFor(names, now = now) }
            .getOrDefault(emptyList())
            .take(DUE_NOTES)

        val dealFacts = if (historyGateway.hasClosingSignal(stripScreenLabel(currentScreen))) {
            val ids = runCatching { contactDirectory.conversationIds(contact.contactId) }
                .getOrDefault(emptyList())
            runCatching { historyGateway.dealFacts(ids) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        return ContactBriefSection.render(
            contact = contact,
            entityNotes = entityNotes,
            dueNotes = dueNotes,
            dealFacts = dealFacts,
            now = now,
        )
    }

    /**
     * 滚动摘要：每完成 [DIGEST_EVERY_TURNS] 个自动屏幕回合，把最近
     * [DIGEST_TURN_WINDOW] 个已完成回合（屏幕正文/参谋意见各截
     * [DIGEST_ITEM_CHARS] 字）压成 ≤[DIGEST_MAX_CHARS] 字的连续背景。
     * 绑定联系人存 session_bindings（经 [ContactDirectory]），共享会话存
     * AdvisorPrefs。这里重新读库（调用时回复已落库）；任何失败都静默
     * 保留旧摘要，不影响主回合。只统计「屏幕+回复」完整配对，手动追问不计入。
     */
    private suspend fun maybeRefreshDigest(conversationId: Long, contact: ContactRef?) {
        val screenTurns = completedScreenTurns(conversationId)
        val total = screenTurns.size
        val stored = contact?.let { c ->
            runCatching { contactDirectory.digest(c.contactId) }.getOrNull()
        }
        // 绑定联系人首次无摘要时计数从 0 起；绝不继承共享会话的覆盖回合数，
        // 否则旧共享会话用得越多，新联系人的滚动摘要越被永久压制。
        val coveredTurns = when {
            contact == null -> prefs.digestCoveredTurns()
            stored != null -> stored.coveredTurns
            else -> 0
        }
        if (total - coveredTurns < DIGEST_EVERY_TURNS) return

        val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull() ?: return
        val previousDigest = if (contact != null) stored?.digest else prefs.screenDigest()
        val material = buildString {
            previousDigest?.let { append("已有摘要：").append(it).append("\n\n") }
            screenTurns.takeLast(DIGEST_TURN_WINDOW).forEachIndexed { index, (screen, advice) ->
                append("屏幕").append(index + 1).append("：")
                append(stripScreenLabel(screen.content).take(DIGEST_ITEM_CHARS)).append('\n')
                append("参谋：").append(advice.content.take(DIGEST_ITEM_CHARS)).append("\n\n")
            }
        }
        val digest = auxiliaryTasks.complete(
            config = config,
            systemPrompt = if (contact != null) DIGEST_SYSTEM_BOUND else DIGEST_SYSTEM,
            userPrompt = material,
            maxTokens = 220,
        ).take(DIGEST_MAX_CHARS)
        if (digest.isNotBlank()) {
            if (contact != null) {
                runCatching { contactDirectory.saveDigest(contact.contactId, digest, total) }
            } else {
                prefs.setScreenDigest(digest, total)
            }
        }
    }

    /** 会话内全部「屏幕提问 + 参谋回复」完整配对（手动追问不计入），按时间正序。 */
    private suspend fun completedScreenTurns(conversationId: Long): List<Pair<ChatMessage, ChatMessage>> {
        val messages = conversationRepository.getMessages(conversationId)
        return buildList {
            var pendingScreen: ChatMessage? = null
            for (message in messages) {
                when {
                    message.role == ChatRole.USER &&
                        message.content.startsWith(screenLabel) -> pendingScreen = message
                    message.role == ChatRole.ASSISTANT && pendingScreen != null -> {
                        add(pendingScreen to message)
                        pendingScreen = null
                    }
                }
            }
        }
    }

    /**
     * 对方画像萃取（§4.2）：节流/字段目录/草稿冷却全部在 [PersonaExtractor]
     * 与 ContactRepository 内完成，这里只负责组装回合文本。任何失败静默，
     * 不影响参谋主链路；草稿落库后由 P4 画像页供用户 ✓/✕。
     */
    private suspend fun maybeExtractPersona(conversationId: Long, contact: ContactRef) {
        val turns = completedScreenTurns(conversationId).map { (screen, reply) ->
            PersonaExtractor.Turn(
                screen = stripScreenLabel(screen.content),
                reply = reply.content,
                at = screen.createdAt,
            )
        }
        personaExtractor.maybeExtract(contact.contactId, conversationId, turns)
    }

    /**
     * 两阶段收集：首个文本事件（TextDelta/ThinkingDelta/AssistantMessage）
     * 在 [firstTextTimeoutMs] 内必须到达，之后流式总时长不限。超时即取消并
     * 丢弃整条上游 flow——防止迟到回答在重试后重复落库。
     */
    private suspend fun collectAdvisorOnce(
        config: ProviderConfig,
        conversationId: Long,
        history: List<ChatMessage>,
        memoryContext: String?,
        customSystemPrompt: String,
        triggerMessageId: Long,
        firstTextTimeoutMs: Long,
    ): TurnOutcome = kotlinx.coroutines.coroutineScope {
        val events: ReceiveChannel<AgentEvent> = agentLoop.run(
            config = config,
            mode = AppMode.CHAT,
            customSystemPrompt = customSystemPrompt,
            history = history,
            conversationId = conversationId,
            memoryContext = memoryContext,
            triggerMessageId = triggerMessageId,
        ).produceIn(this)

        val live = StringBuilder()
        var sawText = false
        var completed = false
        var failureReason: String? = null

        suspend fun handle(event: AgentEvent) {
            when (event) {
                is AgentEvent.TextDelta -> {
                    sawText = true
                    live.append(event.text)
                    _transcript.update { it.copy(streaming = live.toString()) }
                }
                // 推理模型的思考过程也算「活信号」：思考超过首字超时不应误重试。
                is AgentEvent.ThinkingDelta -> sawText = true
                is AgentEvent.AssistantMessage -> {
                    sawText = true
                    if (event.message.content.isNotBlank()) {
                        completed = true
                        conversationRepository.append(
                            event.message.copy(
                                conversationId = conversationId,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        live.setLength(0)
                        _transcript.update { it.copy(streaming = null, status = Status.Idle) }
                        _turnCompletions.tryEmit(Unit)
                    } else if (live.isNotBlank()) {
                        // 末条消息为空但已有流式文本：以累积文本为准落库，
                        // 不写空气泡、不丢已生成内容。
                        completed = true
                        conversationRepository.append(
                            ChatMessage(
                                conversationId = conversationId,
                                role = ChatRole.ASSISTANT,
                                content = live.toString(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        live.setLength(0)
                        _transcript.update { it.copy(streaming = null, status = Status.Idle) }
                        _turnCompletions.tryEmit(Unit)
                    } else {
                        // 空消息且此前没有任何输出：按零文本失败处理（可重试）。
                        failureReason = failureReason ?: REASON_NO_RESPONSE
                    }
                }
                is AgentEvent.Failed -> failureReason = event.error.message
                else -> Unit
            }
        }

        // 阶段 1：等首个文本事件（RoundStarted/ToolCall 等不算活信号）。
        // 注意 TimeoutCancellationException 是 CancellationException 的子类，
        // 必须先于后者 catch，否则超时会被误当外部取消向上抛。
        try {
            withTimeout(firstTextTimeoutMs) {
                while (true) {
                    val result = events.receiveCatching()
                    if (result.isClosed) break
                    handle(result.getOrThrow())
                    if (sawText) break
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            events.cancel()
            return@coroutineScope TurnOutcome.ZeroOutputFailure(REASON_FIRST_TEXT_TIMEOUT)
        } catch (cancelled: CancellationException) {
            events.cancel()
            throw cancelled
        } catch (error: Exception) {
            events.cancel()
            return@coroutineScope if (sawText) {
                TurnOutcome.PartialOutputFailure(error.message ?: "unknown", live.toString())
            } else {
                TurnOutcome.ZeroOutputFailure(error.message ?: REASON_NETWORK)
            }
        }

        if (!sawText) {
            return@coroutineScope TurnOutcome.ZeroOutputFailure(
                failureReason ?: REASON_NO_RESPONSE,
            )
        }

        // 阶段 2：已有文本，剩余流式不再限时，多长都等完。
        try {
            for (event in events) handle(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@coroutineScope TurnOutcome.PartialOutputFailure(
                error.message ?: "unknown",
                live.toString(),
            )
        }

        val reason = failureReason
        when {
            completed -> TurnOutcome.Done
            reason != null -> TurnOutcome.PartialOutputFailure(reason, live.toString())
            // 兜底：上游有文本流出却没给 AssistantMessage 收尾，保住已生成内容。
            live.isNotBlank() -> {
                conversationRepository.append(
                    ChatMessage(
                        conversationId = conversationId,
                        role = ChatRole.ASSISTANT,
                        content = live.toString(),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                _transcript.update { it.copy(streaming = null, status = Status.Idle) }
                _turnCompletions.tryEmit(Unit)
                TurnOutcome.Done
            }
            else -> TurnOutcome.ZeroOutputFailure(REASON_NO_RESPONSE)
        }
    }

    /** 找到（或新建）未绑定场景共享的持久化「老参谋」会话。 */
    private suspend fun ensureSharedConversation(): Long {
        val existing = prefs.conversationId().takeIf { it > 0 }
            ?.let { id -> runCatching { conversationRepository.get(id) }.getOrNull() }
        val conversationId = existing?.id
            ?: conversationRepository.create(
                agentId = null,
                mode = AppMode.CHAT,
                title = context.getString(R.string.float_ask_conversation_title),
            ).also { id -> prefs.setConversationId(id) }
        subscribeConversation(conversationId)
        return conversationId
    }

    /** 切换转录订阅到目标会话（共享会话与联系人专属会话共用）。 */
    private fun subscribeConversation(conversationId: Long) {
        if (_transcript.value.conversationId == conversationId && observeJob != null) return
        observeJob?.cancel()
        observeJob = advisorScope.launch {
            conversationRepository.observeMessages(conversationId).collect { messages ->
                _transcript.update {
                    if (it.conversationId == conversationId) it.copy(messages = messages) else it
                }
            }
        }
        _transcript.update { it.copy(conversationId = conversationId) }
    }

    /** 浮窗关闭时清掉流式/状态等瞬态，会话与消息保持原样。 */
    fun resetTransient() {
        _transcript.update { it.copy(streaming = null, status = Status.Idle) }
    }

    /** 管道外意外异常（如收集侧崩溃）的兜底上报。 */
    fun reportUnexpected(reason: String) {
        _transcript.updateStatus(Status.Error(reason))
    }

    /** 供设置页调用：清空老参谋会话消息（会话本身保留）。 */
    fun clearConversationAsync() {
        advisorScope.launch { clearConversation() }
    }

    suspend fun clearConversation() {
        val id = prefs.conversationId().takeIf { it > 0 } ?: return
        conversationRepository.clearMessages(id)
        prefs.resetScreenDigest()
        _transcript.update { it.copy(messages = emptyList(), streaming = null, status = Status.Idle) }
    }

    fun advisorScopeCancel() {
        advisorScope.cancel()
    }

    private fun MutableStateFlow<UiState>.updateStatus(status: Status) {
        update { it.copy(status = status) }
    }

    private companion object {
        const val REASON_NO_TEXT = "no_text"
        const val REASON_NO_CONFIG = "no_config"

        /** 零文本失败原因（展示层映射成中文文案；均允许重试）。 */
        const val REASON_FIRST_TEXT_TIMEOUT = "first_text_timeout"
        const val REASON_NETWORK = "network"
        const val REASON_NO_RESPONSE = "no_response"

        /** 连续（差分）参谋的强制重试预算：坏网络下不堵死帧队列。 */
        const val TIGHT_RETRY_TIMES = 1
        const val TIGHT_RETRY_TIMEOUT_SEC = 8

        /** 多屏滚动前发 back 收拢 IME 后的复检等待。 */
        const val KEYBOARD_COLLAPSE_WAIT_MS = 300L

        /** L2 OCR 标题带候选名的合理长度（剥角标/群人数在 repository 侧）。 */
        const val TITLE_MIN_CHARS = 2
        const val TITLE_MAX_CHARS = 16

        /** 桌面之外的固定系统壳：来电/系统 UI/设置/授权器等，永不作为聊天窗口建档。 */
        val NON_CHAT_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.incallui",
            "com.android.phone",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
        )

        /** 纯标点/符号行（如返回箭头被识成 "<"）不算联系人名。 */
        val TITLE_SYMBOL_ONLY = Regex("""^[\p{P}\p{S}\s\d]+$""")

        /** 单屏 OCR/无障碍文本的入库上限：150 字结论用不到整篇长文。 */
        const val MAX_SCREEN_CHARS = 3_000
        const val PRIMED_NOTES = 4

        /** recallByEntity 参与匹配的主名+别名数量上限。 */
        const val ENTITY_NAME_LIMIT = 8

        /** 绑定联系人每轮直取的交往脉络笔记上限。 */
        const val ENTITY_NOTES = 6

        /** 置顶的到期承诺/约见上限。 */
        const val DUE_NOTES = 3

        /** 相邻两屏正文相似度达到该值即判为重复，短路不发 LLM。 */
        const val DUP_SCREEN_SIMILARITY = 0.92

        /** 每完成这么多个自动屏幕回合刷新一次滚动摘要。 */
        const val DIGEST_EVERY_TURNS = 6

        /** 刷新摘要时回看的最近屏幕回合数。 */
        const val DIGEST_TURN_WINDOW = 12

        /** 摘要素材里屏幕正文/参谋意见各自的截断长度。 */
        const val DIGEST_ITEM_CHARS = 200

        /** 滚动摘要的持久化长度上限。 */
        const val DIGEST_MAX_CHARS = 150

        /** 滚动摘要任务的系统提示词：只输出摘要本身。 */
        const val DIGEST_SYSTEM =
            "你是「老参谋」的看屏记录摘要器。把用户连续看过的屏幕与你此前的参谋意见，" +
                "压缩成不超过 150 字的简体中文连续背景：只保留场景脉络、悬而未决的问题和用户的" +
                "连续意图，丢弃重复屏幕与 OCR 噪声。只输出摘要正文，不要标题、不要分点、" +
                "不要寒暄。"

        /**
         * 绑定联系人会话的摘要提示词：强制带日期与意向阶段、保留报价/承诺/
         * 异议原话要点——它要在跨天回合充当「交往背景」，无时态结论会让模型
         * 对已经转意的客户沿用旧话术（设计文档 §6.4）。
         */
        const val DIGEST_SYSTEM_BOUND =
            "你是销售参谋的对话档案摘要器。把与同一客户的连续聊天屏幕与参谋意见，" +
                "压缩成不超过 150 字的简体中文事实背景：必须以「截至 MM-dd，阶段=X」开头" +
                "（X 取破冰/询盘/比价/犹豫/促单/成交/售后之一，无法判断写未知）；" +
                "随后只保留谈过的型号与价格、我方承诺、对方明确表达的顾虑或意向，" +
                "丢弃寒暄、重复屏幕与 OCR 噪声。只输出摘要正文，不要标题分点与寒暄。"

        /** 参谋人设：作为 customSystemPrompt 拼进 AgentLoop 的系统提示词。 */
        const val ADVISOR_STYLE =
            "你是「老参谋」，用户的随身参谋。用户在手机上轻点了一下悬浮球，" +
                "最近一条用户消息是由 OCR 或无障碍树从当前屏幕提取的文本（可能有识别噪声、排版可能混乱），" +
                "更早的屏幕只以一句场景占位或摘要存在，原始文本已经省略——严禁复述旧屏幕，" +
                "始终以当前屏幕为唯一分析对象；用户手动输入的追问优先于一切屏幕内容。" +
                "请用简体中文给出简洁、可直接参考的分析：" +
                "若屏幕是对话/聊天，概括各方立场并给出建议的回应要点；若是文章/资料，提炼要点；" +
                "若是操作界面，说明当前状态与下一步选项。一百五十字以内，不啰嗦、不复述原文。"
    }
}
