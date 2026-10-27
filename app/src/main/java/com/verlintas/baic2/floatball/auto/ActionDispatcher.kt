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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.R
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.floatball.AdvisorPrefs
import com.verlintas.baic2.floatball.FloatBallService
import com.verlintas.baic2.floatball.advisor.data.AdvisorDatabase
import com.verlintas.baic2.floatball.advisor.data.DirectorLogEntity
import com.verlintas.baic2.floatball.advisor.data.ROUTE_NOOP
import com.verlintas.baic2.floatball.advisor.data.ROUTE_REPLY
import com.verlintas.baic2.floatball.advisor.data.TRIGGER_AUTO
import com.verlintas.baic2.floatball.director.AdvisorNotificationListener
import com.verlintas.baic2.floatball.notify.NotifyGate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 一次自动发送请求（调用方 = M3-b 节奏调度：拟人延迟 8~90s 后触发，本类不做延迟）。 */
data class AutoSendRequest(
    val contactId: Long,
    val contactName: String,
    val conversationId: Long,
    val imPackage: String,
    val draft: String,
    val turnMessageId: Long? = null,

    /** 草稿来源码（SendableDraftExtractor.SOURCE_*，入台账 reason 供回放兜底触发率）。 */
    val draftSource: String? = null,
)

/** 派发结果：Sent=三步全部成功；DegradedToDraft=执行失败已降级起草通知；Rejected=总闸/白名单拒绝。 */
sealed interface AutoSendResult {
    data class Sent(val fingerprint: String) : AutoSendResult

    /** [reason] 为 REASON_* 码，用户文案由展示层映射。 */
    data class DegradedToDraft(val reason: String, val fingerprint: String) : AutoSendResult

    /** 总闸关闭 / 联系人不在白名单：不触碰 IM、不发通知，调用方自行决定提示。 */
    data class Rejected(val reason: String) : AutoSendResult
}

/** 发送前文本指纹（SHA-256 前 16 hex，纯函数 JVM 可测）：错发率对账的唯一凭据。 */
object SendFingerprint {

    fun of(text: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        digest.take(8).joinToString("") { "%02x".format(it) }
    }.getOrDefault(text.length.toString())
}

/** 自动档前置闸（纯函数 JVM 可测）：总闸 + 白名单双检，任一不过即拒绝派发。 */
object DispatchGate {

    const val REASON_AUTO_DISABLED = "auto_mode_disabled"
    const val REASON_NOT_WHITELISTED = "contact_not_whitelisted"

    /** 返回 null = 放行；否则为拒绝原因码。 */
    fun vetoReason(autoEnabled: Boolean, whitelist: Set<Long>, contactId: Long): String? = when {
        !autoEnabled -> REASON_AUTO_DISABLED
        contactId !in whitelist -> REASON_NOT_WHITELISTED
        else -> null
    }
}

/** 一条发送记录（指纹 + 结局原因码 + 时间）。 */
data class SendRecord(val fingerprint: String, val reason: String, val at: Long)

/**
 * 发送指纹内存对账表（方案 §8.3「发送前文本指纹记录」，纯 JVM 可测）：
 * 每联系人一个有界队列（新→旧），支撑「错发率 0」回放验证；进程内存、重启即清，
 * 不落盘草稿内容（本地优先红线）。
 */
internal class AutoSendLedger(private val cap: Int = LEDGER_DEFAULT_CAP) {

    private val byContact = HashMap<Long, ArrayDeque<SendRecord>>()

    fun record(contactId: Long, fingerprint: String, reason: String, at: Long) {
        synchronized(byContact) {
            val deque = byContact.getOrPut(contactId) { ArrayDeque() }
            deque.addFirst(SendRecord(fingerprint, reason, at))
            while (deque.size > cap) deque.removeLast()
        }
    }

    /** 指定联系人的最近发送记录（新→旧）。 */
    fun recent(contactId: Long): List<SendRecord> = synchronized(byContact) {
        byContact[contactId]?.toList().orEmpty()
    }

    private companion object {
        const val LEDGER_DEFAULT_CAP = 32
    }
}

/**
 * 发送三步状态机（方案 §8.2：定位输入 → 注入文本 → 点击发送，每步存在性校验
 * 与一次重试）。**绝不盲点坐标**——一切触点都来自 [SendLocator] 定位结果；
 * 任何一步失败即放弃发送（由调用方降级起草档），不做第二类尝试。
 *
 * R1 §17.2 定位/执行解耦：定位器产出坐标（模式 A=a11y 节点树
 * [A11ySendLocator]；模式 B=录屏抽帧+OCR/找图 [OcrSendLocator]），
 * 注入/点击由 [InputInjector] 双实现执行，三步底座两通道共用。
 * 纯编排、零 Android 依赖，JVM 用 fake 注入器/定位器即可单测。
 */
internal object SendSequence {

    const val REASON_A11Y_DISCONNECTED = "a11y_not_connected"
    const val REASON_WRONG_FOREGROUND = "wrong_foreground"
    const val REASON_INPUT_FAILED = "input_inject_failed"
    const val REASON_SEND_NOT_FOUND = "send_button_not_found"
    const val REASON_SEND_TAP_FAILED = "send_tap_failed"

    /** 发送前页面守卫判定「当前不是目标会话页」（2026-10-27 通讯录页误发事故）。 */
    const val REASON_WRONG_CONVERSATION = "wrong_conversation"

    /** 点击后既看不到发送按钮、文本回读也未命中：证据不足，按失败降级（不重试防重发）。 */
    const val REASON_SEND_NOT_VERIFIED = "send_not_verified"

    /**
     * 失败原因是否「确定未发出」（2026-10-27 通道降级链闸）：只有确定没发出去的
     * 失败才允许换通道再来一遍。`send_not_verified` 是**不确定**（可能已经发出），
     * 宁可降级起草档，绝不赌第二枪造成重发。
     */
    fun safeToRetryElsewhere(reason: String?): Boolean {
        val code = reason?.substringBefore(':') ?: return false
        return code in RETRYABLE_ON_OTHER_CHANNEL
    }

    private val RETRYABLE_ON_OTHER_CHANNEL = setOf(
        REASON_A11Y_DISCONNECTED,
        REASON_WRONG_FOREGROUND,
        REASON_INPUT_FAILED,
        REASON_SEND_NOT_FOUND,
        REASON_SEND_TAP_FAILED,
    )

    /** 明细规范化：台账 reason 以 `:` 分段，明细内不再出现冒号/空白/中文标点。 */
    fun detail(raw: String): String = raw.trim()
        .map { if (it.isLetterOrDigit() || it == '_' || it == '.' || it == '-') it else '_' }
        .joinToString("")
        .take(DETAIL_MAX_CHARS)
        .ifEmpty { "unknown" }

    /** 追加失败明细（无明细时原样返回原因码）。 */
    private fun withDetail(reason: String, detail: String?): String =
        if (detail.isNullOrBlank()) reason else "$reason:$detail"

    /** 异常 → 明细素材（类型名 + 消息，已规范化）。 */
    private fun detailOf(result: Result<Unit>): String? {
        if (result.isSuccess) return null
        val error = result.exceptionOrNull()
        val raw = listOfNotNull(error?.javaClass?.simpleName, error?.message)
            .joinToString("_")
            .ifBlank { "unknown" }
        return detail(raw)
    }

    /** 执行完整序列（解耦形态）：返回 null = 发送成功，否则为失败原因码。
     *  [evidence] 提供时发送后回读上屏（§四.A.3），失败按 SEND_TAP_FAILED 处理；
     *  [proofFinder] 提供时改用三态像素证据（按钮仍在=未发出，可重定位再点一次；
     *  证据不足=失败但不重点，防重复发送）；
     *  [focusStep] 为注入前聚焦步（§四.A.4，best-effort，失败不阻断注入）；
     *  [trace] 提供时逐段记账（focus/inject/locate/tap/proof），回答「RPA 动作
     *  慢在哪一步」——2026-10-27 真机取证一次落同一行台账。 */
    suspend fun run(
        injector: InputInjector,
        locator: SendLocator,
        foregroundPackage: String?,
        imPackage: String,
        draft: String,
        evidence: SendEvidenceFinder? = null,
        proofFinder: SendProofFinder? = null,
        focusStep: (suspend () -> Boolean)? = null,
        trace: TimingTrace? = null,
    ): String? {
        if (!injector.available) return injector.unavailableReason
        // 前台包名校验：不在目标 IM 的会话界面绝不注入（防错发到别的 App）。
        if (foregroundPackage != imPackage) return REASON_WRONG_FOREGROUND
        // 聚焦步（best-effort）：失败不阻断——SET_TEXT/节点注入可自选目标。
        val focusBlock: suspend () -> Unit = { runCatching { focusStep?.invoke() }; Unit }
        if (trace != null) trace.stageSuspend("focus") { focusBlock() } else focusBlock()
        // 第 1 步：注入文本（实现方自决 SET_TEXT / input text / 剪贴板+粘贴）；
        // 失败重试一次（界面可能尚未就绪）。失败明细随原因码返回（真机排障要
        // 区分「无焦点可编辑节点」/「shell 拒绝」/「剪贴板失败」，2026-10-27）。
        val injectFailure = if (trace != null) {
            trace.stageSuspend("inject") { injectWithRetry(injector, draft) }
        } else {
            injectWithRetry(injector, draft)
        }
        injectFailure?.let { return withDetail(REASON_INPUT_FAILED, it) }
        // 第 2 步：定位发送按钮（实际存在才继续）。
        var target = if (trace != null) {
            trace.stageSuspend("locate") { locateRetry(locator) }
        } else {
            locateRetry(locator)
        }
        if (target == null) {
            // 2026-10-27 真机稳定性修复（12:16/12:17 send_button_not_found）：
            // 定位失败时草稿已注入，旧逻辑直接放弃且**不清空输入框**——残留
            // 草稿会与下一次 PASTE 叠加（12:26 单气泡文本重复两遍的嫌疑路径）。
            // 先清空，再重走一轮聚焦→注入→定位（页面漂移/键盘形态变化多半
            // 能自愈）；仍失败才清空降级，绝不把半成品留在输入框里。
            runCatching { injector.clearInput() }
            if (trace != null) trace.stageSuspend("focus2") { focusBlock() } else focusBlock()
            val reinjected = if (trace != null) {
                trace.stageSuspend("inject2") { injectWithRetry(injector, draft) }
            } else {
                injectWithRetry(injector, draft)
            }
            if (reinjected != null) {
                runCatching { injector.clearInput() }
                return withDetail(REASON_INPUT_FAILED, reinjected)
            }
            target = if (trace != null) {
                trace.stageSuspend("locate2") { locateRetry(locator) }
            } else {
                locateRetry(locator)
            } ?: run {
                runCatching { injector.clearInput() }
                return REASON_SEND_NOT_FOUND
            }
        }
        val sendTarget = target ?: return REASON_SEND_NOT_FOUND
        // 第 3 步：点击发送；点击未生效则重定位一次再点，仍失败降级。
        var tappedTarget = sendTarget
        val tapped = if (trace != null) trace.stageSuspend("tap") { tap(injector, sendTarget) }
        else tap(injector, sendTarget)
        if (!tapped) {
            val relocated = locator.locate()
            if (relocated == null || !tap(injector, relocated)) {
                // best effort 清空输入框残留，避免降级后人肉档面对半成品。
                runCatching { injector.clearInput() }
                return REASON_SEND_TAP_FAILED
            }
            tappedTarget = relocated
        }
        // 第 4 步：发送后证据（§四.A.3）——宁降级不假成功。
        // 模式 B 优先用三态像素证据：发送按钮仍在原位说明草稿还在输入框，
        // 文本回读即使命中也是输入框内容（2026-10-27 真机 auto_sent 假成功根因）。
        val clips = PostSendVerifier.clipsOf(draft)
        if (proofFinder != null) {
            var proof = if (trace != null) {
                trace.stageSuspend("proof") { proofFinder.proof(clips, tappedTarget) }
            } else {
                proofFinder.proof(clips, tappedTarget)
            }
            if (proof == SendProof.NOT_SENT) {
                // 强证据「未发出」：重定位再点一次，然后重新取证。
                val relocated = locator.locate()
                if (relocated != null && tap(injector, relocated)) {
                    tappedTarget = relocated
                    proof = if (trace != null) {
                        trace.stageSuspend("proof2") { proofFinder.proof(clips, relocated) }
                    } else {
                        proofFinder.proof(clips, relocated)
                    }
                }
            }
            if (proof != SendProof.SENT) {
                runCatching { injector.clearInput() }
                return if (proof == SendProof.NOT_SENT) {
                    // 强证据「按钮还在原位」：自己也确认没发出去，换通道重试是安全的。
                    withDetail(REASON_SEND_TAP_FAILED, "proof_not_sent")
                } else {
                    REASON_SEND_NOT_VERIFIED
                }
            }
        } else if (evidence != null) {
            // 多前缀回退（8 字主片段 + 6 字备选）降低 OCR 跨行/易错字符假阴性。
            val verified = if (trace != null) {
                trace.stageSuspend("verify") { PostSendVerifier.verify(evidence, clips) }
            } else {
                PostSendVerifier.verify(evidence, clips)
            }
            if (!verified) {
                runCatching { injector.clearInput() }
                return REASON_SEND_TAP_FAILED
            }
        }
        return null
    }

    /** 模式 A 便捷入口（a11y 节点定位 + a11y 执行），既有调用方与单测签名不变。 */
    suspend fun run(bridge: AccessibilityBridge, imPackage: String, draft: String): String? = run(
        injector = A11yInjector(bridge),
        locator = A11ySendLocator(bridge),
        foregroundPackage = bridge.foregroundPackage(),
        imPackage = imPackage,
        draft = draft,
    )

    /** 注入重试一次；返回 null = 成功，否则为最后一次失败的明细（台账可读）。 */
    private suspend fun injectWithRetry(injector: InputInjector, draft: String): String? {
        val first = injector.type(draft)
        if (first.isSuccess) return null
        delay(INJECT_RETRY_DELAY_MS)
        val second = injector.type(draft)
        if (second.isSuccess) return null
        // 两次都失败：优先报第二次（更接近放弃时刻的真实状态）。
        return detailOf(second) ?: detailOf(first) ?: "unknown"
    }

    private suspend fun locateRetry(locator: SendLocator): SendTarget? {
        locator.locate()?.let { return it }
        delay(FIND_RETRY_DELAY_MS)
        return locator.locate()
    }

    private suspend fun tap(injector: InputInjector, target: SendTarget): Boolean {
        delay(TAP_DELAY_MS)
        return injector.tap(target.centerX, target.centerY).isSuccess
    }

    private const val INJECT_RETRY_DELAY_MS = 600L
    private const val FIND_RETRY_DELAY_MS = 900L
    private const val TAP_DELAY_MS = 400L

    /** 失败明细最长字符数（台账 reason 一行的可读上限）。 */
    private const val DETAIL_MAX_CHARS = 48
}

/**
 * 自动发送执行器（方案 §8.2 W5，M3-a；R1 §17.2 扩为三模执行面）：按
 * [InjectorSelector]（配置 > A > B > C）选执行通道，编排三步发送，
 * 失败即降级 M2 起草通知（ACTION_SHOW_DRAFT 路径），绝不盲点坐标、绝不丢弃草稿。
 *
 * 职责边界：Gatekeeper 硬审在调用方（审过才调 [dispatch]）；拟人延迟/窗口上限/
 * 夜间静默属 M3-b 节奏调度——本类设计为「延迟后由调用方触发」的即时执行形态。
 * 每次执行（成功/失败/拒绝）全部落 director_logs（trigger=auto），发送前文本
 * 指纹记录进内存对账表，支撑「错发率 0」回放验证。
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val bridge: AccessibilityBridge,
    private val assists: AssistsA11yBridge,
    private val a11yInjector: A11yInjector,
    private val shizukuInjector: ShizukuInjector,
    private val ocrSendLocator: OcrSendLocator,
    private val imNavigator: ImNavigator,
    private val imChatNavigator: ImChatNavigator,
    private val prefs: AdvisorPrefs,
    private val db: AdvisorDatabase,
    @ApplicationContext private val context: Context,
) {

    private val ledger = AutoSendLedger()

    /**
     * 执行一次自动发送：总闸+白名单前置闸 → 三步状态机 → 指纹入账 →
     * director_logs 落库 → 失败降级起草通知。全程不抛异常（降级矩阵纪律）。
     *
     * 2026-10-27 真机修复：执行面改为**按序降级链**（[InjectorSelector.plan]）。
     * auto 档下模式 A 失败且「确定未发出」时自动回落模式 B（Shizuku + OCR 定位）
     * 再走一遍完整三步；证据不足（`send_not_verified`）绝不换道重试，防重发。
     * 通道链与分段耗时同时落台账（`ch=`/`auto_timing:`）与 logcat，供回放定位。
     */
    suspend fun dispatch(request: AutoSendRequest): AutoSendResult {
        val settings = prefs.state.value
        val veto = DispatchGate.vetoReason(
            autoEnabled = settings.autoModeEnabled,
            whitelist = settings.autoWhitelistIds,
            contactId = request.contactId,
        )
        if (veto != null) return AutoSendResult.Rejected(veto)

        val fingerprint = SendFingerprint.of(request.draft)
        val trace = TimingTrace()
        val attempt = withContext(Dispatchers.Default) {
            val plan = InjectorSelector.plan(
                configured = settings.autoExecutorChannel,
                a11yReady = bridge.connected.value,
                shizukuReady = shizukuInjector.available,
            )
            executeChain(plan, request, trace)
        }
        Log.i(AutoTiming.TAG, "${trace.render()} fingerprint=$fingerprint")
        val failure = attempt.failure
        val base = if (failure == null) {
            "auto_sent:$fingerprint:ch=${attempt.chain()}"
        } else {
            "$REASON_DISPATCH_FAILED:$failure:$fingerprint:ch=${attempt.chain()}"
        }
        logSend(request, withDraftSource(base, request.draftSource))
        ledger.record(request.contactId, fingerprint, base, System.currentTimeMillis())
        return if (failure == null) {
            AutoSendResult.Sent(fingerprint)
        } else {
            postDraftFallback(request)
            AutoSendResult.DegradedToDraft(failure, fingerprint)
        }
    }

    /** 一次派发的通道链结果：[failure]=null 表示成功；[chain] 记录实际走过的通道。 */
    private data class ChainAttempt(
        val failure: String?,
        val channels: List<InjectorChannel>,
        val used: InjectorChannel?,
    ) {
        /** 通道链文本（`A11y>Shizuku`；成功时附加 `=>Shizuku` 标明生效通道）。 */
        fun chain(): String {
            val walk = channels.joinToString(">") { it.name }
            return when {
                used != null && channels.size > 1 -> "$walk=>${used.name}"
                else -> walk
            }
        }
    }

    /**
     * 按 [plan] 顺序尝试发送（每个通道跑完整三步序列 + 各自的前台探针/证据源）。
     * 换道条件见 [SendSequence.safeToRetryElsewhere]；计划为空 = 无可用执行通道，
     * 直接按 C 档降级起草。
     */
    private suspend fun executeChain(
        plan: List<InjectorChannel>,
        request: AutoSendRequest,
        trace: TimingTrace,
    ): ChainAttempt {
        if (plan.isEmpty()) {
            trace.add("no_executor", 0L)
            return ChainAttempt(REASON_NO_EXECUTOR, listOf(InjectorChannel.DraftFallback), null)
        }
        val walked = ArrayList<InjectorChannel>()
        var failure: String? = null
        for ((index, channel) in plan.withIndex()) {
            walked += channel
            val reason = trace.stageSuspend("send_${channel.name.lowercase()}") {
                attemptOn(channel, request, trace)
            }
            if (reason == null) {
                return ChainAttempt(null, walked.toList(), channel)
            }
            failure = reason
            val last = index == plan.lastIndex
            // 确定未发出才换下一条通道（A 的病灶多半是节点树不可用，B 走 Shizuku/OCR）；
            // 证据不足/最后一条都在此收口，交调用方降级起草。
            if (last || !SendSequence.safeToRetryElsewhere(reason)) break
            Log.w(TAG, "channel ${channel.name} failed($reason), falling back to ${plan[index + 1].name}")
        }
        return ChainAttempt(failure, walked.toList(), null)
    }

    /**
     * 单通道完整尝试：前台探针 → 必要时会话导航（一次） → 三步发送 + 三态证据。
     * 返回 null = 该通道发送成功（或有证据的已发出），否则为失败原因码（可带明细）。
     */
    private suspend fun attemptOn(
        channel: InjectorChannel,
        request: AutoSendRequest,
        trace: TimingTrace,
    ): String? {
        // 发送后上屏回读证据源（§四.A.3）：A=a11y 节点树；B=当前帧 OCR。
        val evidence: SendEvidenceFinder? = when (channel) {
            InjectorChannel.A11y -> SendEvidenceFinder { clip -> bridge.findText(clip).isNotEmpty() }
            InjectorChannel.Shizuku -> SendEvidenceFinder { clip -> ocrSendLocator.frameContains(clip) }
            InjectorChannel.DraftFallback -> null
        }
        // 模式 B 三态证据（2026-10-27 真机假成功修复）：像素可见发送按钮时，
        // 「按钮仍在原位」是「未发出」的强证据，优先于纯文本回读。
        val proofFinder: SendProofFinder? = when (channel) {
            InjectorChannel.Shizuku -> SendProofFinder { clips, tapped ->
                ocrSendLocator.sendProof(clips, tapped)
            }
            else -> null
        }
        // 聚焦步（§四.A.4）：模式 A 先 tap 首个可编辑节点；模式 B 无节点树，
        // 按当前帧 OCR 判定输入栏形态（语音条先切键盘）再点输入框，并清空
        // 聚焦时可能误触的字符——否则剪贴板 PASTE 无焦点会静默丢失。
        // 2026-10-27 快通道：IME 已弹起（dumpsys，~115ms）即输入框已聚焦，
        // 跳过取帧/OCR/tap 整段（真机实测 2.2~2.5s → ~0.15s，回复慢归因优化）。
        val focusStep: (suspend () -> Boolean)? = when (channel) {
            InjectorChannel.A11y -> { { assists.focusFirstEditable() } }
            InjectorChannel.Shizuku -> {
                {
                    val focused = ocrSendLocator.focusInput(shizukuInjector) { shizukuInjector.imeShown() }
                    // 无论聚焦判定成功与否都清一次输入框：PASTE 是「插入」语义，
                    // 残留草稿会让同一条回复叠加两份（2026-10-27 真机 12:26 单
                    // 气泡文本重复两遍；旧逻辑只在 focused=true 时清空）。
                    runCatching { shizukuInjector.clearInput() }
                    focused
                }
            }
            InjectorChannel.DraftFallback -> null
        }
        // 会话导航（§四.A.2）：前台不在目标 IM → 通知 contentIntent 拉起 +
        // 双确认；失败按 REASON_NAV_FAILED 系降级起草（人肉档现状不变）。
        // 导航成功后再探一次：后续三步用导航后的真实前台。
        val foregroundPackage = trace.stageSuspend("fg_${channel.name.lowercase()}") {
            probeForeground(channel)
        }
        val effectiveForeground = if (foregroundPackage == request.imPackage) {
            foregroundPackage
        } else {
            val nav = trace.stageSuspend("nav") {
                imNavigator.navigate(
                    imPackage = request.imPackage,
                    contactId = request.contactId,
                    rawTitleHint = request.contactName,
                )
            }
            if (nav.outcome != ImNavigator.NavOutcome.NAVIGATED) {
                return "${ImNavigator.REASON_NAV_FAILED}:${nav.outcome.name.lowercase()}"
            }
            probeForeground(channel)
        }
        // 发送前会话页守卫（2026-10-27 真机 12:16/12:17）：导航自称 Ok 后
        // 页面可能已漂到主列表/通讯录页（微信主列表与聊天页同为 LauncherUI），
        // 旧链路只校验前台包名 → 在非会话页注入、locate 必败并留下草稿。
        // 仅 Shizuku 通道做视觉守卫（无 a11y 节点树可查）；判定 null=无法判定，
        // 一律放行，绝不因 OCR 抖动拦住正常发送。
        if (channel == InjectorChannel.Shizuku) {
            val verdict = trace.stageSuspend("guard") {
                imChatNavigator.chatPageTitleMatches(request.imPackage, request.contactName)
            }
            if (verdict == false) {
                Log.w(TAG, "guard: not on '${request.contactName}' chat page, re-navigating")
                val nav = trace.stageSuspend("nav_guard") {
                    imChatNavigator.openChat(request.imPackage, request.contactName)
                }
                if (nav !is ImChatNavigator.NavResult.Ok) {
                    val why = (nav as? ImChatNavigator.NavResult.Fail)?.reason ?: "nav_fail"
                    return "${SendSequence.REASON_WRONG_CONVERSATION}:$why"
                }
                val recheck = trace.stageSuspend("guard2") {
                    imChatNavigator.chatPageTitleMatches(request.imPackage, request.contactName)
                }
                if (recheck == false) {
                    return "${SendSequence.REASON_WRONG_CONVERSATION}:header_mismatch"
                }
            }
        }
        return runCatching {
            when (channel) {
                InjectorChannel.A11y -> SendSequence.run(
                    injector = a11yInjector,
                    locator = A11ySendLocator(bridge),
                    foregroundPackage = effectiveForeground,
                    imPackage = request.imPackage,
                    draft = request.draft,
                    evidence = evidence,
                    focusStep = focusStep,
                    trace = trace,
                )
                InjectorChannel.Shizuku -> {
                    val result = SendSequence.run(
                        injector = shizukuInjector,
                        locator = ocrSendLocator,
                        foregroundPackage = effectiveForeground,
                        imPackage = request.imPackage,
                        draft = request.draft,
                        evidence = evidence,
                        proofFinder = proofFinder,
                        focusStep = focusStep,
                        trace = trace,
                    )
                    if (result == null) {
                        // 发送成功即收起键盘：Sogou 工具条/候选条会被下一帧
                        // OCR 当成来信，诱发自问自答回合（2026-10-27 真机）。
                        runCatching { shizukuInjector.hideImeIfShown() }
                    }
                    result
                }
                // C（起草档）不算「托管」：无可用注入通道，直接挂起草通知。
                InjectorChannel.DraftFallback -> REASON_NO_EXECUTOR
            }
        }.getOrElse { REASON_DISPATCH_ERROR }
    }

    /** Gatekeeper 硬审拒绝的台账记录（调用方审稿后调用；只落库绝不发送）。 */
    suspend fun logGateBlocked(request: AutoSendRequest, gateReason: String) {
        val fingerprint = SendFingerprint.of(request.draft)
        logSend(
            request,
            withDraftSource("$REASON_GATE_BLOCKED:$gateReason:$fingerprint", request.draftSource),
        )
        ledger.record(request.contactId, fingerprint, REASON_GATE_BLOCKED, System.currentTimeMillis())
    }

    /**
     * v8：抽取与「代言者修复调用」都没拿到可发送回复（绝不回落到分析稿）。
     * 只落台账供回放，不触碰 IM、不发通知。
     */
    suspend fun logNoSendable(contactId: Long, conversationId: Long, reason: String) {
        runCatching {
            db.directorLogDao().insert(
                DirectorLogEntity(
                    contactId = contactId,
                    conversationId = conversationId,
                    trigger = TRIGGER_AUTO,
                    route = ROUTE_NOOP,
                    reason = "$REASON_NO_SENDABLE:$reason",
                    createdAt = System.currentTimeMillis(),
                ),
            )
            db.directorLogDao().trimFor(contactId)
        }.onFailure { Log.d(TAG, "no-sendable log failed", it) }
    }

    /**
     * 自动档分段耗时落台账（2026-10-27 真机「回复慢」归因）：
     * 一行 `auto_timing:...`（[AutoTiming.PREFIX]）只用于回放定位瓶颈段，
     * 不参与任何决策、不触碰 IM、不发通知。
     */
    suspend fun logTiming(contactId: Long, conversationId: Long, summary: String) {
        if (summary.isBlank()) return
        runCatching {
            db.directorLogDao().insert(
                DirectorLogEntity(
                    contactId = contactId,
                    conversationId = conversationId,
                    trigger = TRIGGER_AUTO,
                    route = ROUTE_NOOP,
                    reason = summary,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            db.directorLogDao().trimFor(contactId)
        }.onFailure { Log.d(TAG, "timing log failed", it) }
    }

    /**
     * 直接降级起草通知（M3-b 节奏调度用：窗口上限/夜间静默/派发互斥不满足时
     * 草稿不丢弃、不尝试发送）。与 [dispatch] 的失败降级同一条通知路径，
     * 差异仅在台账原因码由调用方给定（REASON_* 口径）。
     */
    suspend fun degradeToDraft(request: AutoSendRequest, reason: String): AutoSendResult.DegradedToDraft {
        val fingerprint = SendFingerprint.of(request.draft)
        logSend(request, withDraftSource("$REASON_DEGRADED:$reason:$fingerprint", request.draftSource))
        ledger.record(request.contactId, fingerprint, reason, System.currentTimeMillis())
        postDraftFallback(request)
        return AutoSendResult.DegradedToDraft(reason, fingerprint)
    }

    /** 台账 reason 追加草稿来源码（兜底触发率回放；无来源不追加）。 */
    private fun withDraftSource(reason: String, source: String?): String =
        if (source.isNullOrBlank()) reason else "$reason:src=$source"

    /** 前台包名探针（通道自选）：A=a11y 桥；B=Shizuku dumpsys；C=null（拒注入）。 */
    private suspend fun probeForeground(channel: InjectorChannel): String? = when (channel) {
        InjectorChannel.A11y -> bridge.foregroundPackage()
        InjectorChannel.Shizuku -> shizukuInjector.foregroundPackage()
        InjectorChannel.DraftFallback -> null
    }

    /** 发送事件落 director_logs（旁路，任何持久化失败只 Log 不上抛）。 */
    private suspend fun logSend(request: AutoSendRequest, reason: String) {
        runCatching {
            db.directorLogDao().insert(
                DirectorLogEntity(
                    contactId = request.contactId,
                    conversationId = request.conversationId,
                    trigger = TRIGGER_AUTO,
                    route = ROUTE_REPLY,
                    reason = reason,
                    turnMessageId = request.turnMessageId,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            db.directorLogDao().trimFor(request.contactId)
        }.onFailure { Log.d(TAG, "auto send log failed", it) }
    }

    /**
     * 降级起草档（方案 §8.1 W5→N5）：复刻 M2 起草通知形态，经 service action
     * 拉起分屏预览，一键发送/改后发送仍由人完成。通知文本即取即用，本类
     * 不落盘草稿内容（本地优先红线）。
     */
    private fun postDraftFallback(request: AutoSendRequest) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.float_draft_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val contentIntent = PendingIntent.getForegroundService(
                context,
                derivedIdFor(request.contactId),
                Intent(context, FloatBallService::class.java)
                    .setAction(AdvisorNotificationListener.ACTION_SHOW_DRAFT)
                    .putExtra(AdvisorNotificationListener.EXTRA_CONVERSATION_ID, request.conversationId)
                    .putExtra(AdvisorNotificationListener.EXTRA_CONTACT_ID, request.contactId)
                    .putExtra(AdvisorNotificationListener.EXTRA_CONTACT_NAME, request.contactName)
                    .putExtra(AdvisorNotificationListener.EXTRA_IM_PACKAGE, request.imPackage)
                    .putExtra(AdvisorNotificationListener.EXTRA_DRAFT, request.draft),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.float_draft_notif_title, request.contactName))
                .setContentText(request.draft)
                .setStyle(NotificationCompat.BigTextStyle().bigText(request.draft))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            NotifyGate.notifyIfPermitted(context, derivedIdFor(request.contactId), notification)
        }.onFailure { Log.d(TAG, "auto draft fallback failed", it) }
    }

    /**
     * 按 contactId 派生稳定唯一通知/PendingIntent id（W2）：多联系人并发降级
     * 通知互不覆盖（同联系人后稿覆盖前稿，只保最新草稿）；requestCode 同源
     * 派生，防 FLAG_UPDATE_CURRENT 下同码 PI 互改 contentIntent 造成错路由。
     * 渠道不变（floatball_draft，M2 起草通知同渠道）。
     */
    private fun derivedIdFor(contactId: Long): Int = (NOTIFICATION_ID_BASE + contactId).toInt()

    private companion object {
        private const val TAG = "ActionDispatcher"

        /** 与 M2 起草通知同渠道（floatball_draft）分 id 发，避免互相覆盖。 */
        private const val CHANNEL_ID = "floatball_draft"
        private const val NOTIFICATION_ID_BASE = 2_000_000

        private const val REASON_DISPATCH_ERROR = "dispatch_error"
        private const val REASON_DISPATCH_FAILED = "auto_dispatch_failed"
        private const val REASON_GATE_BLOCKED = "auto_gate_blocked"
        private const val REASON_DEGRADED = "auto_degraded"

        /** v8：无任何可发送回复（抽取/修复调用均失败），只落台账。 */
        private const val REASON_NO_SENDABLE = "auto_no_sendable"

        /** 通道选择落到 C（A/B 均不可用或显式配置通道不可用）：无注入通道，挂起草。 */
        private const val REASON_NO_EXECUTOR = "no_executor_channel"
    }
}
