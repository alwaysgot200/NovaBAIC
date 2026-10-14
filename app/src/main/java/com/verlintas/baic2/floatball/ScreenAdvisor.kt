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
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.data.repository.MemoryRepository
import com.verlintas.baic2.core.engine.AgentEvent
import com.verlintas.baic2.core.engine.AgentLoop
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.MemoryPrompt
import com.verlintas.baic2.core.model.MemoryText
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.ScreenshotProvider
import com.verlintas.baic2.floatball.ScreenPerceiver.Perception
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val perceiver: ScreenPerceiver,
    private val prefs: AdvisorPrefs,
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
        data class Error(val reason: String) : Status
    }

    sealed interface Event {
        /** 需要用户授权投影（服务层走 FloatCaptureActivity，授权后重跑）。 */
        data object NeedsConsent : Event

        /** 问题已写入会话，LLM 回合开始。 */
        data class TurnStarted(val question: String) : Event

        data class Failed(val reason: String) : Event
    }

    private val _transcript = MutableStateFlow(UiState())
    val transcript: StateFlow<UiState> = _transcript.asStateFlow()

    private val _turnCompletions = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val turnCompletions: SharedFlow<Unit> = _turnCompletions.asSharedFlow()

    private val advisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null
    private var preheatJob: Job? = null

    @Volatile
    private var preheatedText: String? = null

    /** LLM 串行队列：连续模式差分注入与单次参谋共用，永不并发。 */
    private val turnMutex = Mutex()

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
        when (val perception = perceiver.quickPerceive(preheated)) {
            is Perception.Text -> {
                emit(Event.TurnStarted(perception.text))
                submitTurn(autoScreenContent(perception.text))
            }

            is Perception.Image -> {
                if (perception.viaProjection) releaseProjection()
                _transcript.updateStatus(Status.OcrRunning)
                val text = withContext(Dispatchers.Default) {
                    ocrProvider.recognizeText(perception.bytes).getOrNull()?.trim()
                }
                if (text.isNullOrEmpty()) {
                    _transcript.updateStatus(Status.Error(REASON_NO_TEXT))
                    emit(Event.Failed(REASON_NO_TEXT))
                } else {
                    emit(Event.TurnStarted(text))
                    submitTurn(autoScreenContent(text))
                }
            }

            Perception.NeedsProjection -> emit(Event.NeedsConsent)

            is Perception.Failed -> {
                _transcript.updateStatus(Status.Error(perception.reason))
                emit(Event.Failed(perception.reason))
            }
        }
    }

    // ---------------------------------------------------- shared turns

    /** 自动屏幕消息的统一标记：转录视图据此折叠，主界面据此识别来源。 */
    fun autoScreenContent(text: String): String =
        context.getString(R.string.float_ask_question_label) + "\n" + text

    /**
     * 写入用户消息并跑一个 LLM 回合（串行）。单次与连续共用；
     * 失败也返回（不抛），错误已写入 transcript 状态。
     */
    suspend fun submitTurn(content: String) {
        val question = content.trim().take(MAX_SCREEN_CHARS)
        if (question.isEmpty()) return
        val conversationId = ensureConversation()
        conversationRepository.append(
            ChatMessage(
                conversationId = conversationId,
                role = ChatRole.USER,
                content = question,
                createdAt = System.currentTimeMillis(),
            ),
        )
        _transcript.updateStatus(Status.Thinking)
        turnMutex.withLock {
            runAdvisorTurn(conversationId)
        }
    }

    /** 与 ChatViewModel 的聊天轮同构：AgentLoop 产出、仓储落库。 */
    private suspend fun runAdvisorTurn(conversationId: Long) {
        val config: ProviderConfig = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
            ?: run {
                _transcript.updateStatus(Status.Error(REASON_NO_CONFIG))
                return
            }

        val history = conversationRepository.getMessages(conversationId)
            .takeLast(HISTORY_MESSAGES)
        val question = history.lastOrNull { it.role == ChatRole.USER } ?: return

        // 参谋的「阅人」：核心记忆 + 由屏幕文字线索唤醒的笔记。检索是
        // 只读浏览，不强化（feedback=false），看屏不应改写记忆权重。
        val core = runCatching { memoryRepository.getCore() }.getOrNull()
        val primed = runCatching {
            memoryRepository.recall(
                terms = MemoryText.terms(question.content),
                limit = PRIMED_NOTES,
                feedback = false,
            )
        }.getOrDefault(emptyList()).map { it.note }
        val memoryContext = MemoryPrompt.context(
            core = core,
            primed = primed,
            now = System.currentTimeMillis(),
        )

        val live = StringBuilder()
        try {
            agentLoop.run(
                config = config,
                mode = AppMode.CHAT,
                customSystemPrompt = ADVISOR_STYLE,
                history = history,
                conversationId = conversationId,
                memoryContext = memoryContext,
                triggerMessageId = question.id,
            ).collect { event ->
                when (event) {
                    is AgentEvent.TextDelta -> {
                        live.append(event.text)
                        _transcript.update { it.copy(streaming = live.toString()) }
                    }
                    is AgentEvent.AssistantMessage -> {
                        conversationRepository.append(
                            event.message.copy(
                                conversationId = conversationId,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        live.setLength(0)
                        _transcript.update { it.copy(streaming = null, status = Status.Idle) }
                        _turnCompletions.tryEmit(Unit)
                    }
                    is AgentEvent.Failed -> {
                        live.setLength(0)
                        _transcript.updateStatus(Status.Error(event.error.message))
                    }
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val partial = live.toString()
            if (partial.isNotBlank()) {
                conversationRepository.append(
                    ChatMessage(
                        conversationId = conversationId,
                        role = ChatRole.ASSISTANT,
                        content = partial,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
            _transcript.updateStatus(Status.Error(error.message ?: "unknown"))
        } finally {
            _transcript.update { it.copy(streaming = null) }
        }
    }

    /** 找到（或新建）持久化的「老参谋」会话，并订阅其消息流。 */
    private suspend fun ensureConversation(): Long {
        val existing = prefs.conversationId().takeIf { it > 0 }
            ?.let { id -> runCatching { conversationRepository.get(id) }.getOrNull() }
        val conversationId = existing?.id
            ?: conversationRepository.create(
                agentId = null,
                mode = AppMode.CHAT,
                title = context.getString(R.string.float_ask_conversation_title),
            ).also { id -> prefs.setConversationId(id) }

        if (_transcript.value.conversationId != conversationId || observeJob == null) {
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
        return conversationId
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
        const val MAX_SCREEN_CHARS = 6_000
        const val HISTORY_MESSAGES = 16
        const val PRIMED_NOTES = 4

        /** 参谋人设：作为 customSystemPrompt 拼进 AgentLoop 的系统提示词。 */
        const val ADVISOR_STYLE =
            "你是「老参谋」，用户的随身参谋。用户在手机上轻点了一下悬浮球，" +
                "最近一条用户消息是由 OCR 或无障碍树从当前屏幕提取的文本（可能有识别噪声、排版可能混乱），" +
                "此前的历史消息是你之前给出的参谋意见。请用简体中文给出简洁、可直接参考的分析：" +
                "若屏幕是对话/聊天，概括各方立场并给出建议的回应要点；若是文章/资料，提炼要点；" +
                "若是操作界面，说明当前状态与下一步选项。一百五十字以内，不啰嗦、不复述原文。"
    }
}
