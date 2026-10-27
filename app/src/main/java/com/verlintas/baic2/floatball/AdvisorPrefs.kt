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
import com.verlintas.baic2.floatball.auto.HumanPace
import com.verlintas.baic2.floatball.auto.InjectorSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 好参谋专属设置（独立于上游 SettingsRepository，避免触碰上游）。
 * 运行中修改下一轮循环即生效；持久化与「好参谋」会话 ID 同文件。
 */
@Singleton
class AdvisorPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    data class Settings(
        /** 连续模式抓帧节奏（毫秒）。 */
        val pollIntervalMs: Long = 800L,
        /** 连续模式单次时长上限（毫秒），0 = 不限。 */
        val maxSessionMs: Long = 30 * 60_000L,
        /**
         * 连续模式工作段时长（毫秒）：到点后停歇 [restMs] 再继续（休憩循环，
         * 无人值守 RPA 的录屏节奏——不能一直不停录，中间要停歇降功耗降痕迹）。
         * restMs=0 时该值不生效。2026-10-27 默认改 1 分钟（真机联调要求
         * 「录 1 分钟停 1 分钟」反复循环），旧装机的 10 分钟档照旧可读。
         */
        val workMs: Long = DEFAULT_WORK_REST_MS,
        /** 工作段之间的停歇时长（毫秒），0 = 不停歇（连续抓帧）。 */
        val restMs: Long = DEFAULT_WORK_REST_MS,
        /** 单次参谋额外上滑几屏（0=单屏，上限 3；方案 §6.6）。 */
        val autoScrollScreens: Int = 2,
        /** 每次滑动后等待渲染的毫秒（600~1500）。 */
        val scrollSettleMs: Long = 800L,
        /** 拼接完成后下滑回底，恢复原阅读位置。 */
        val scrollBack: Boolean = true,
        /** 滑动距离占内容区高度比例（<1 故意留重叠带）。 */
        val swipeHeightRatio: Float = 0.72f,
        /** 禁滑 IM 包名（实测某 IM 上滑触发已读时加入；空=不禁）。 */
        val swipeBlockedPackages: Set<String> = emptySet(),
        /**
         * 连续模式静止画面的抓帧降频上限倍数：1=不降频，2/4=间隔最多倍增
         * 到 2 倍/4 倍（检测到运动立即恢复）。省电与反应延迟的权衡。
         */
        val idleBackoffMultiplier: Int = 2,
        /** 回合后后台滚动摘要（一次额外 LLM 调用）总开关。 */
        val digestEnabled: Boolean = true,
        /** 每完成多少个屏幕回合刷新一次滚动摘要。 */
        val digestEveryTurns: Int = 6,
        /** 回合后后台对方画像萃取（一次额外 LLM 调用，仅绑定联系人）总开关。 */
        val extractEnabled: Boolean = true,
        /** 每完成多少个屏幕回合至多萃取一次画像。 */
        val extractEveryTurns: Int = 5,
        /**
         * 回合内记忆工具模式：
         * 0=智能（手动参谋/追问走全功能 AgentLoop，可自主检索/写入记忆；
         *   连续模式自动回合走无工具直连，省掉每回合 7 个工具 schema 与
         *   记忆协议长提示词）；1=始终增强（全部回合带工具，最聪明最费 token）；
         * 2=始终精简（全部回合无工具直连，最省，记忆仍由 App 侧主动注入）。
         */
        val memoryMode: Int = MEMORY_MODE_AUTO,
        /** 参谋回答长度档：0=简洁(~120字)，1=标准(~260字)，2=详细(~500字)。 */
        val answerLength: Int = ANSWER_STANDARD,
        /** 屏幕去重灵敏度：0=宽松(多问少漏)，1=标准，2=严格(少问省 token)。 */
        val dedupLevel: Int = DEDUP_STANDARD,
        /** 连续模式单次会话 token 预算（输入+输出），0=不限；达到后自动停录。 */
        val sessionTokenBudget: Long = 0L,
        /**
         * 三级目标/策略卡模式（P2 §11 设置）：
         * 0=关闭（只走 P1 风格）；1=智能（默认，目标/阶段/策略卡全启用）；
         * 2=增强（预留位，读到时回退为智能）。
         */
        val strategyMode: Int = STRATEGY_SMART,
        /**
         * M2-a 起草档总开关：通知监听到达聊天通知时后台代拟回复并发起草
         * 通知。默认关闭（隐私优先：通知读取是强权限，须显式开启）。
         */
        val draftEnabled: Boolean = false,
        /**
         * M2-b 主动保温总闸（周期检点载体开关）：开启后按 [proactiveIntervalHours]
         * 周期检点 proactive_policies，命中频控的联系人才产出保温草稿。
         * 默认关闭（主动是稀缺资源，方案 §7.3）。
         */
        val proactiveEnabled: Boolean = false,
        /** 周期检点间隔（小时），固定档位见 [PROACTIVE_INTERVAL_OPTIONS]。 */
        val proactiveIntervalHours: Int = PROACTIVE_DEFAULT_INTERVAL,
        /**
         * M3 自动档总闸（方案 §8.3）：开启后对白名单联系人允许 Gatekeeper 硬审
         * 通过即自动发送。默认关闭（平台风控/无人值守错发双高风险，须显式开启）。
         */
        val autoModeEnabled: Boolean = false,
        /**
         * M3 自动档联系人白名单（contactId 字符串集合，上限 [MAX_AUTO_WHITELIST]）。
         * 数据层只做容量约束；设置 UI 与风险告知确认由 M3-b 落地。
         */
        val autoWhitelist: Set<String> = emptySet(),
        /**
         * M3-b 陪伴窗口单窗口自动发送上限（条），0=不限（不建议）。达上限后
         * 窗口继续感知、草稿一律降级起草通知。
         */
        val companionMaxSends: Int = DEFAULT_COMPANION_MAX_SENDS,
        /**
         * 会话复用断崖阈值 X（V-B，UltraAdvisor v2.2 方案 §3）：同联系人回看的
         * 最高内容相似度低于该值判定「重大话题转变」，自动新开会话并关联同
         * 档案；达到该值（含等于）取最高者续用。默认保守（宁可续用，不轻易
         * 新建）；调低更保守、调高更易分叉。
         */
        val reuseCliffSimilarity: Float = REUSE_CLIFF_DEFAULT,
        /**
         * 自动发送执行通道（R1 §17.2 三模执行面，[InjectorSelector.CONFIG_OPTIONS]）：
         * auto=A→B→C 自动降级（默认）；a11y=强制模式 A；shizuku=强制模式 B。
         * 显式通道不可用时不静默换道，直接降起草档。
         */
        val autoExecutorChannel: String = InjectorSelector.CONFIG_AUTO,
        /**
         * 拟人节奏下限/上限（毫秒，闭区间均匀采样，方案 §8.2）：自动档派发前的
         * 随机延迟区间。真机联调抱怨「回复太慢」，根因之一即默认 8~90 秒
         * （均值 ~49s）；档位见 [PACE_OPTIONS]，取值必须整对命中档位。
         */
        val autoPaceMinMs: Long = HumanPace.MIN_DELAY_MS,
        val autoPaceMaxMs: Long = HumanPace.MAX_DELAY_MS,
        /**
         * 接管/达成通报总闸（2026-10-20 通报方案 §十）：H 接管型与 G 达成型命中
         * 后铃声通知主人。默认开启——通报只是旁路提醒，不改变任何发送决策。
         */
        val escalationEnabled: Boolean = true,
        /**
         * uncertain 严格档：false=宽松（放行+私人事实回避约束，默认）；
         * true=拿不准也按接管通报（宁打扰勿漏报）。
         */
        val escalationStrict: Boolean = false,
        /** 邮件通报开关（E3 接入 SMTP 实发后消费；E1 仅落设置无 UI 入口）。 */
        val escalationMailEnabled: Boolean = false,
        /** SMTP 主机（通报邮件通道，E3 消费；默认=测试环境阿里企业邮）。 */
        val smtpHost: String = DEFAULT_SMTP_HOST,
        /** SMTP 端口（SSL 通常 465）。 */
        val smtpPort: Int = DEFAULT_SMTP_PORT,
        val smtpSsl: Boolean = true,
        val smtpUser: String = DEFAULT_SMTP_USER,
        val smtpPassword: String = DEFAULT_SMTP_PASSWORD,
        val smtpFromName: String = DEFAULT_SMTP_FROM_NAME,
        val smtpFromEmail: String = DEFAULT_SMTP_FROM_EMAIL,
        /** SMTP 连接/读超时（秒）。 */
        val smtpTimeoutSec: Int = DEFAULT_SMTP_TIMEOUT_SEC,
        /**
         * 审查官情绪红线开关（E-f，情感分析师方案 D3）：盛怒稿玩笑/推进词表命中
         * 拦截换安抚稿（不丢稿 + 台账 emotion_veto）。默认开启，设置页可关。
         */
        val emotionRedlineEnabled: Boolean = true,
    ) {
        /** 白名单的 Long 视图（非法项静默忽略；与 [addAutoWhitelistContact] 同口径）。 */
        val autoWhitelistIds: Set<Long>
            get() = autoWhitelist.mapNotNull { it.toLongOrNull() }.toSet()

        /** OCR 层文本相似度阈值（FrameDiffEngine 用）：越严格阈值越高，越少发问。 */
        val ocrDedupSimilarity: Float
            get() = when (dedupLevel) {
                DEDUP_LOOSE -> 0.72f
                DEDUP_STRICT -> 0.86f
                else -> 0.80f
            }

        /** 入库前相邻屏幕去重阈值（ScreenAdvisor 用）。 */
        val screenDedupSimilarity: Float
            get() = when (dedupLevel) {
                DEDUP_LOOSE -> 0.88f
                DEDUP_STRICT -> 0.95f
                else -> 0.92f
            }

        /** 回答长度档对应的硬 maxTokens（输出 token 单价远高于输入）。 */
        val answerMaxTokens: Int
            get() = when (answerLength) {
                ANSWER_CONCISE -> 220
                ANSWER_DETAILED -> 850
                else -> 420
            }

        /** 回答长度档对应的中文字数目标（拼进系统提示词）。 */
        val answerTargetChars: Int
            get() = when (answerLength) {
                ANSWER_CONCISE -> 120
                ANSWER_DETAILED -> 500
                else -> 260
            }
    }

    /** Token 用量累计（主参谋回合；后台摘要/萃取不计入）。 */
    data class TokenStats(
        val totalInput: Long = 0L,
        val totalOutput: Long = 0L,
        val totalTurns: Long = 0L,
        val sessionInput: Long = 0L,
        val sessionOutput: Long = 0L,
        /** 累计消耗积分（毫积分）。 */
        val totalPointsMilli: Long = 0L,
        /** 本次会话消耗积分（毫积分）。 */
        val sessionPointsMilli: Long = 0L,
    ) {
        val sessionTotal: Long get() = sessionInput + sessionOutput
        val totalPoints: Double get() = totalPointsMilli / 1000.0
        val sessionPoints: Double get() = sessionPointsMilli / 1000.0
    }

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadSettings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    private fun loadSettings(): Settings =
        Settings(
            pollIntervalMs = prefs.getLong(KEY_POLL, 800L).coerceIn(300L, 5_000L),
            maxSessionMs = prefs.getLong(KEY_MAX_SESSION, DEFAULT_MAX_SESSION),
            workMs = prefs.getLong(KEY_WORK_MS, DEFAULT_WORK_REST_MS)
                .takeIf { it in WORK_REST_OPTIONS } ?: DEFAULT_WORK_REST_MS,
            restMs = prefs.getLong(KEY_REST_MS, DEFAULT_WORK_REST_MS)
                .takeIf { it in WORK_REST_OPTIONS } ?: DEFAULT_WORK_REST_MS,
            autoScrollScreens = prefs.getInt(KEY_SCROLL_SCREENS, 2).coerceIn(0, 3),
            scrollSettleMs = prefs.getLong(KEY_SCROLL_SETTLE, 800L).coerceIn(600L, 1_500L),
            scrollBack = prefs.getBoolean(KEY_SCROLL_BACK, true),
            swipeHeightRatio = prefs.getFloat(KEY_SWIPE_RATIO, 0.72f).coerceIn(0.5f, 0.9f),
            swipeBlockedPackages = prefs.getStringSet(KEY_SWIPE_BLOCKLIST, emptySet())
                ?.filter { it.isNotBlank() }?.toSet().orEmpty(),
            idleBackoffMultiplier = prefs.getInt(KEY_IDLE_BACKOFF, 2).let {
                if (it in BACKOFF_OPTIONS) it else 2
            },
            digestEnabled = prefs.getBoolean(KEY_DIGEST_ENABLED, true),
            digestEveryTurns = prefs.getInt(KEY_DIGEST_EVERY, 6).let {
                if (it in DIGEST_OPTIONS) it else 6
            },
            extractEnabled = prefs.getBoolean(KEY_EXTRACT_ENABLED, true),
            extractEveryTurns = prefs.getInt(KEY_EXTRACT_EVERY, 5).let {
                if (it in EXTRACT_OPTIONS) it else 5
            },
            memoryMode = prefs.getInt(KEY_MEMORY_MODE, MEMORY_MODE_AUTO).let {
                if (it in MEMORY_MODE_OPTIONS) it else MEMORY_MODE_AUTO
            },
            answerLength = prefs.getInt(KEY_ANSWER_LENGTH, ANSWER_STANDARD).let {
                if (it in ANSWER_OPTIONS) it else ANSWER_STANDARD
            },
            dedupLevel = prefs.getInt(KEY_DEDUP_LEVEL, DEDUP_STANDARD).let {
                if (it in DEDUP_LEVEL_OPTIONS) it else DEDUP_STANDARD
            },
            sessionTokenBudget = prefs.getLong(KEY_SESSION_BUDGET, 0L)
                .takeIf { it in TOKEN_BUDGET_OPTIONS } ?: 0L,
            strategyMode = prefs.getInt(KEY_STRATEGY_MODE, STRATEGY_SMART).let {
                // P3：2=增强（双调用）已开放；非法值回退智能档。
                if (it == STRATEGY_OFF || it == STRATEGY_SMART || it == STRATEGY_ENHANCED) {
                    it
                } else {
                    STRATEGY_SMART
                }
            },
            draftEnabled = prefs.getBoolean(KEY_DRAFT_ENABLED, false),
            proactiveEnabled = prefs.getBoolean(KEY_PROACTIVE_ENABLED, false),
            proactiveIntervalHours = prefs.getInt(KEY_PROACTIVE_INTERVAL, PROACTIVE_DEFAULT_INTERVAL)
                .let { if (it in PROACTIVE_INTERVAL_OPTIONS) it else PROACTIVE_DEFAULT_INTERVAL },
            autoModeEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false),
            autoWhitelist = prefs.getStringSet(KEY_AUTO_WHITELIST, emptySet())
                ?.filter { it.isNotBlank() }?.take(MAX_AUTO_WHITELIST)?.toSet().orEmpty(),
            companionMaxSends = prefs.getInt(KEY_COMPANION_MAX_SENDS, DEFAULT_COMPANION_MAX_SENDS)
                .let { if (it in COMPANION_SEND_OPTIONS) it else DEFAULT_COMPANION_MAX_SENDS },
            reuseCliffSimilarity = prefs.getFloat(KEY_REUSE_CLIFF, REUSE_CLIFF_DEFAULT)
                .coerceIn(0f, 1f),
            autoExecutorChannel = prefs.getString(KEY_AUTO_EXECUTOR, InjectorSelector.CONFIG_AUTO)
                ?.takeIf { it in InjectorSelector.CONFIG_OPTIONS } ?: InjectorSelector.CONFIG_AUTO,
            autoPaceMinMs = prefs.getLong(KEY_PACE_MIN, HumanPace.MIN_DELAY_MS)
                .coerceIn(0L, PACE_CEILING_MS),
            autoPaceMaxMs = prefs.getLong(KEY_PACE_MAX, HumanPace.MAX_DELAY_MS)
                .coerceIn(0L, PACE_CEILING_MS),
            escalationEnabled = prefs.getBoolean(KEY_ESCALATION_ENABLED, true),
            escalationStrict = prefs.getBoolean(KEY_ESCALATION_STRICT, false),
            escalationMailEnabled = prefs.getBoolean(KEY_ESCALATION_MAIL, false),
            smtpHost = prefs.getString(KEY_SMTP_HOST, DEFAULT_SMTP_HOST)
                ?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_SMTP_HOST,
            smtpPort = prefs.getInt(KEY_SMTP_PORT, DEFAULT_SMTP_PORT).coerceIn(1, 65_535),
            smtpSsl = prefs.getBoolean(KEY_SMTP_SSL, true),
            smtpUser = prefs.getString(KEY_SMTP_USER, DEFAULT_SMTP_USER)?.trim().orEmpty(),
            smtpPassword = prefs.getString(KEY_SMTP_PASSWORD, DEFAULT_SMTP_PASSWORD).orEmpty(),
            smtpFromName = prefs.getString(KEY_SMTP_FROM_NAME, DEFAULT_SMTP_FROM_NAME)?.trim().orEmpty(),
            smtpFromEmail = prefs.getString(KEY_SMTP_FROM_EMAIL, DEFAULT_SMTP_FROM_EMAIL)?.trim().orEmpty(),
            smtpTimeoutSec = prefs.getInt(KEY_SMTP_TIMEOUT, DEFAULT_SMTP_TIMEOUT_SEC).coerceIn(5, 120),
            emotionRedlineEnabled = prefs.getBoolean(KEY_EMOTION_REDLINE, true),
        )

    private val _tokenStats = MutableStateFlow(loadTokenStats())
    val tokenStats: StateFlow<TokenStats> = _tokenStats.asStateFlow()

    fun setPollInterval(ms: Long) {
        val value = ms.coerceIn(300L, 5_000L)
        prefs.edit().putLong(KEY_POLL, value).apply()
        _state.update { it.copy(pollIntervalMs = value) }
    }

    fun setMaxSession(ms: Long) {
        prefs.edit().putLong(KEY_MAX_SESSION, ms).apply()
        _state.update { it.copy(maxSessionMs = ms) }
    }

    /** 工作段/停歇档（毫秒）：0 仅对停歇合法（=不停歇）；运行中改下轮循环生效。 */
    fun setWorkMs(ms: Long) {
        val value = ms.takeIf { it in WORK_REST_OPTIONS } ?: 10 * 60_000L
        prefs.edit().putLong(KEY_WORK_MS, value).apply()
        _state.update { it.copy(workMs = value) }
    }

    fun setRestMs(ms: Long) {
        val value = ms.takeIf { it in WORK_REST_OPTIONS } ?: 10 * 60_000L
        prefs.edit().putLong(KEY_REST_MS, value).apply()
        _state.update { it.copy(restMs = value) }
    }

    /** 连续模式静止降频倍数（仅接受 1/2/4，运行中改下一抓拍循环即生效）。 */
    fun setIdleBackoffMultiplier(multiplier: Int) {
        val value = multiplier.takeIf { it in BACKOFF_OPTIONS } ?: 2
        prefs.edit().putInt(KEY_IDLE_BACKOFF, value).apply()
        _state.update { it.copy(idleBackoffMultiplier = value) }
    }

    fun setDigestEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIGEST_ENABLED, enabled).apply()
        _state.update { it.copy(digestEnabled = enabled) }
    }

    fun setDigestEveryTurns(turns: Int) {
        val value = turns.takeIf { it in DIGEST_OPTIONS } ?: 6
        prefs.edit().putInt(KEY_DIGEST_EVERY, value).apply()
        _state.update { it.copy(digestEveryTurns = value) }
    }

    fun setExtractEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXTRACT_ENABLED, enabled).apply()
        _state.update { it.copy(extractEnabled = enabled) }
    }

    fun setExtractEveryTurns(turns: Int) {
        val value = turns.takeIf { it in EXTRACT_OPTIONS } ?: 5
        prefs.edit().putInt(KEY_EXTRACT_EVERY, value).apply()
        _state.update { it.copy(extractEveryTurns = value) }
    }

    /** 回合内记忆工具模式（0 智能 / 1 始终增强 / 2 始终精简，下一回合生效）。 */
    fun setMemoryMode(mode: Int) {
        val value = mode.takeIf { it in MEMORY_MODE_OPTIONS } ?: MEMORY_MODE_AUTO
        prefs.edit().putInt(KEY_MEMORY_MODE, value).apply()
        _state.update { it.copy(memoryMode = value) }
    }

    fun setAnswerLength(length: Int) {
        val value = length.takeIf { it in ANSWER_OPTIONS } ?: ANSWER_STANDARD
        prefs.edit().putInt(KEY_ANSWER_LENGTH, value).apply()
        _state.update { it.copy(answerLength = value) }
    }

    fun setDedupLevel(level: Int) {
        val value = level.takeIf { it in DEDUP_LEVEL_OPTIONS } ?: DEDUP_STANDARD
        prefs.edit().putInt(KEY_DEDUP_LEVEL, value).apply()
        _state.update { it.copy(dedupLevel = value) }
    }

    fun setSessionTokenBudget(tokens: Long) {
        val value = tokens.takeIf { it in TOKEN_BUDGET_OPTIONS } ?: 0L
        prefs.edit().putLong(KEY_SESSION_BUDGET, value).apply()
        _state.update { it.copy(sessionTokenBudget = value) }
    }

    /** 三级目标/策略卡模式（0 关 / 1 智能，默认；2 增强=每回合多一次快速战术预分析）。 */
    fun setStrategyMode(mode: Int) {
        val value = when (mode) {
            STRATEGY_OFF -> STRATEGY_OFF
            STRATEGY_ENHANCED -> STRATEGY_ENHANCED
            else -> STRATEGY_SMART
        }
        prefs.edit().putInt(KEY_STRATEGY_MODE, value).apply()
        _state.update { it.copy(strategyMode = value) }
    }

    /** M2-a 起草档总开关（通知监听随开关即时生效/停发）。 */
    fun setDraftEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DRAFT_ENABLED, enabled).apply()
        _state.update { it.copy(draftEnabled = enabled) }
    }

    /** M2-b 主动保温总闸（调度侧随开关重挂/撤销闹钟，见 ProactiveCheckpoint）。 */
    fun setProactiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROACTIVE_ENABLED, enabled).apply()
        _state.update { it.copy(proactiveEnabled = enabled) }
    }

    /** M2-b 周期检点间隔（固定档位，改后下次重挂闹钟即生效）。 */
    fun setProactiveIntervalHours(hours: Int) {
        val value = hours.takeIf { it in PROACTIVE_INTERVAL_OPTIONS } ?: PROACTIVE_DEFAULT_INTERVAL
        prefs.edit().putInt(KEY_PROACTIVE_INTERVAL, value).apply()
        _state.update { it.copy(proactiveIntervalHours = value) }
    }

    // ── M3-a：自动档总闸 + 白名单（数据层；设置 UI 与风险告知由 M3-b 落地） ──

    /** M3 自动档总闸（默认关；关闭时 dispatcher 拒绝一切派发）。 */
    fun setAutoModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
        _state.update { it.copy(autoModeEnabled = enabled) }
    }

    /**
     * 白名单添加联系人：上限 [MAX_AUTO_WHITELIST] 个，超出拒绝添加并返回 false
     * （调用方据返回值提示；已存在的 id 幂等返回 true）。
     */
    fun addAutoWhitelistContact(contactId: Long): Boolean {
        if (contactId <= 0L) return false
        val current = _state.value.autoWhitelist
        val next = current + contactId.toString()
        if (next.size > MAX_AUTO_WHITELIST) return false
        prefs.edit().putStringSet(KEY_AUTO_WHITELIST, next).apply()
        _state.update { it.copy(autoWhitelist = next) }
        return true
    }

    /** 白名单移除联系人（不存在时静默）。 */
    fun removeAutoWhitelistContact(contactId: Long) {
        val next = _state.value.autoWhitelist - contactId.toString()
        prefs.edit().putStringSet(KEY_AUTO_WHITELIST, next).apply()
        _state.update { it.copy(autoWhitelist = next) }
    }

    /** M3-b 陪伴窗口单窗口自动发送上限（固定档位，下一窗口生效）。 */
    fun setCompanionMaxSends(sends: Int) {
        val value = sends.takeIf { it in COMPANION_SEND_OPTIONS } ?: DEFAULT_COMPANION_MAX_SENDS
        prefs.edit().putInt(KEY_COMPANION_MAX_SENDS, value).apply()
        _state.update { it.copy(companionMaxSends = value) }
    }

    /** 自动发送执行通道（R1 §17.2；非法值回退 auto，下一回合派发生效）。 */
    fun setAutoExecutorChannel(channel: String) {
        val value = channel.takeIf { it in InjectorSelector.CONFIG_OPTIONS }
            ?: InjectorSelector.CONFIG_AUTO
        prefs.edit().putString(KEY_AUTO_EXECUTOR, value).apply()
        _state.update { it.copy(autoExecutorChannel = value) }
    }

    /**
     * 拟人节奏区间（毫秒）：入参区间倒置自动纠正，各自夹到
     * [0, PACE_CEILING_MS]；下一回合的派发延迟即生效（在途延迟任务不变）。
     */
    fun setAutoPace(minMs: Long, maxMs: Long) {
        val lo = minOf(minMs, maxMs).coerceIn(0L, PACE_CEILING_MS)
        val hi = maxOf(minMs, maxMs).coerceIn(0L, PACE_CEILING_MS)
        prefs.edit().putLong(KEY_PACE_MIN, lo).putLong(KEY_PACE_MAX, hi).apply()
        _state.update { it.copy(autoPaceMinMs = lo, autoPaceMaxMs = hi) }
    }

    // ── 接管/达成通报（2026-10-20 通报方案 §十） ──

    /** 通报总闸（judgeEscalation 即时读，下一封来信生效）。 */
    fun setEscalationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ESCALATION_ENABLED, enabled).apply()
        _state.update { it.copy(escalationEnabled = enabled) }
    }

    /** uncertain 严格档开关（false=宽松放行+回避约束，true=疑似即通报）。 */
    fun setEscalationStrict(strict: Boolean) {
        prefs.edit().putBoolean(KEY_ESCALATION_STRICT, strict).apply()
        _state.update { it.copy(escalationStrict = strict) }
    }

    /** 邮件通报开关（E3 消费）。 */
    fun setEscalationMailEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ESCALATION_MAIL, enabled).apply()
        _state.update { it.copy(escalationMailEnabled = enabled) }
    }

    /** 审查官情绪红线开关（E-f，方案 D3：默认开，设置页可关）。 */
    fun setEmotionRedlineEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EMOTION_REDLINE, enabled).apply()
        _state.update { it.copy(emotionRedlineEnabled = enabled) }
    }

    /** SMTP 配置一次性保存（设置页对话框提交；主机留空回退默认）。 */
    fun setSmtpConfig(
        host: String,
        port: Int,
        ssl: Boolean,
        user: String,
        password: String,
        fromName: String,
        fromEmail: String,
        timeoutSec: Int,
    ) {
        val trimmedHost = host.trim()
        val safeHost = trimmedHost.takeIf { it.isNotBlank() } ?: DEFAULT_SMTP_HOST
        val safePort = port.coerceIn(1, 65_535)
        val safeTimeout = timeoutSec.coerceIn(5, 120)
        prefs.edit()
            .putString(KEY_SMTP_HOST, safeHost)
            .putInt(KEY_SMTP_PORT, safePort)
            .putBoolean(KEY_SMTP_SSL, ssl)
            .putString(KEY_SMTP_USER, user.trim())
            .putString(KEY_SMTP_PASSWORD, password)
            .putString(KEY_SMTP_FROM_NAME, fromName.trim())
            .putString(KEY_SMTP_FROM_EMAIL, fromEmail.trim())
            .putInt(KEY_SMTP_TIMEOUT, safeTimeout)
            .apply()
        _state.update {
            it.copy(
                smtpHost = safeHost,
                smtpPort = safePort,
                smtpSsl = ssl,
                smtpUser = user.trim(),
                smtpPassword = password,
                smtpFromName = fromName.trim(),
                smtpFromEmail = fromEmail.trim(),
                smtpTimeoutSec = safeTimeout,
            )
        }
    }

    /** 记录一个主参谋回合的实际用量（模型 Usage 事件；缺测按 0 计），累计持久化。 */
    fun recordTurnUsage(inputTokens: Long, outputTokens: Long) {
        val current = _tokenStats.value
        val turnPointsMilli = com.verlintas.baic2.core.model.PointsPricing.deductPointsMilli(
            promptTokens = inputTokens.coerceAtLeast(0L),
            completionTokens = outputTokens.coerceAtLeast(0L),
            coefficientMilli = com.verlintas.baic2.core.model.ModelPricing.coefficientMilli(null),
        )
        val updated = TokenStats(
            totalInput = current.totalInput + inputTokens.coerceAtLeast(0L),
            totalOutput = current.totalOutput + outputTokens.coerceAtLeast(0L),
            totalTurns = current.totalTurns + 1,
            sessionInput = current.sessionInput + inputTokens.coerceAtLeast(0L),
            sessionOutput = current.sessionOutput + outputTokens.coerceAtLeast(0L),
            totalPointsMilli = current.totalPointsMilli + turnPointsMilli,
            sessionPointsMilli = current.sessionPointsMilli + turnPointsMilli,
        )
        prefs.edit()
            .putLong(KEY_TOTAL_INPUT, updated.totalInput)
            .putLong(KEY_TOTAL_OUTPUT, updated.totalOutput)
            .putLong(KEY_TOTAL_TURNS, updated.totalTurns)
            .putLong(KEY_SESSION_INPUT, updated.sessionInput)
            .putLong(KEY_SESSION_OUTPUT, updated.sessionOutput)
            .putLong(KEY_TOTAL_POINTS, updated.totalPointsMilli)
            .putLong(KEY_SESSION_POINTS, updated.sessionPointsMilli)
            .apply()
        _tokenStats.value = updated
    }

    /** 开始新一轮连续参谋时清零会话级计数（总计不清）。 */
    fun resetSessionUsage() {
        val current = _tokenStats.value
        val updated = current.copy(sessionInput = 0L, sessionOutput = 0L, sessionPointsMilli = 0L)
        prefs.edit().remove(KEY_SESSION_INPUT).remove(KEY_SESSION_OUTPUT).remove(KEY_SESSION_POINTS).apply()
        _tokenStats.value = updated
    }

    /** 设置页「清零」：总计与会话计数一并清空。 */
    fun clearAllUsage() {
        prefs.edit()
            .remove(KEY_TOTAL_INPUT).remove(KEY_TOTAL_OUTPUT).remove(KEY_TOTAL_TURNS)
            .remove(KEY_SESSION_INPUT).remove(KEY_SESSION_OUTPUT)
            .remove(KEY_TOTAL_POINTS).remove(KEY_SESSION_POINTS)
            .apply()
        _tokenStats.value = TokenStats()
    }

    private fun loadTokenStats(): TokenStats = TokenStats(
        totalInput = prefs.getLong(KEY_TOTAL_INPUT, 0L).coerceAtLeast(0L),
        totalOutput = prefs.getLong(KEY_TOTAL_OUTPUT, 0L).coerceAtLeast(0L),
        totalTurns = prefs.getLong(KEY_TOTAL_TURNS, 0L).coerceAtLeast(0L),
        sessionInput = prefs.getLong(KEY_SESSION_INPUT, 0L).coerceAtLeast(0L),
        sessionOutput = prefs.getLong(KEY_SESSION_OUTPUT, 0L).coerceAtLeast(0L),
        totalPointsMilli = prefs.getLong(KEY_TOTAL_POINTS, 0L).coerceAtLeast(0L),
        sessionPointsMilli = prefs.getLong(KEY_SESSION_POINTS, 0L).coerceAtLeast(0L),
    )

    fun conversationId(): Long = prefs.getLong(KEY_CONVERSATION_ID, 0L)

    fun setConversationId(id: Long) {
        prefs.edit().putLong(KEY_CONVERSATION_ID, id).apply()
    }

    /** 更早屏幕的滚动摘要（ AdvisorHistory 的占位 ASSISTANT 消息用）。 */
    fun screenDigest(): String? = prefs.getString(KEY_DIGEST, null)?.takeIf { it.isNotBlank() }

    /** 摘要已覆盖到第几个自动屏幕回合。 */
    fun digestCoveredTurns(): Int = prefs.getInt(KEY_DIGEST_TURNS, 0)

    fun setScreenDigest(digest: String, coveredTurns: Int) {
        prefs.edit()
            .putString(KEY_DIGEST, digest.take(300))
            .putInt(KEY_DIGEST_TURNS, coveredTurns)
            .apply()
    }

    /** 清空会话时同步重置摘要。 */
    fun resetScreenDigest() {
        prefs.edit().remove(KEY_DIGEST).remove(KEY_DIGEST_TURNS).apply()
    }

    companion object {
        private const val PREFS_FILE = "floatball_advisor"
        const val KEY_POLL = "poll_interval_ms"
        const val KEY_MAX_SESSION = "max_session_ms"
        const val KEY_WORK_MS = "work_segment_ms"
        const val KEY_REST_MS = "rest_segment_ms"
        const val KEY_SCROLL_SCREENS = "auto_scroll_screens"
        const val KEY_SCROLL_SETTLE = "scroll_settle_ms"
        const val KEY_SCROLL_BACK = "scroll_back"
        const val KEY_SWIPE_RATIO = "swipe_height_ratio"
        const val KEY_SWIPE_BLOCKLIST = "swipe_blocked_packages"
        const val KEY_CONVERSATION_ID = "advisor_conversation_id"
        const val KEY_DIGEST = "screen_digest"
        const val KEY_DIGEST_TURNS = "screen_digest_turns"
        const val KEY_IDLE_BACKOFF = "idle_backoff_multiplier"
        const val KEY_DIGEST_ENABLED = "digest_enabled"
        const val KEY_DIGEST_EVERY = "digest_every_turns"
        const val KEY_EXTRACT_ENABLED = "extract_enabled"
        const val KEY_EXTRACT_EVERY = "extract_every_turns"
        const val KEY_MEMORY_MODE = "memory_mode"
        const val KEY_ANSWER_LENGTH = "answer_length"
        const val KEY_DEDUP_LEVEL = "dedup_level"
        const val KEY_SESSION_BUDGET = "session_token_budget"
        const val KEY_STRATEGY_MODE = "strategy_mode"
        const val KEY_DRAFT_ENABLED = "draft_reply_enabled"
        const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
        const val KEY_PROACTIVE_INTERVAL = "proactive_interval_hours"
        const val KEY_AUTO_ENABLED = "auto_mode_enabled"
        const val KEY_AUTO_WHITELIST = "auto_whitelist_contacts"
        const val KEY_COMPANION_MAX_SENDS = "companion_max_sends"
        const val KEY_REUSE_CLIFF = "reuse_cliff_similarity"
        const val KEY_AUTO_EXECUTOR = "auto_executor_channel"
        const val KEY_PACE_MIN = "auto_pace_min_ms"
        const val KEY_PACE_MAX = "auto_pace_max_ms"
        const val KEY_ESCALATION_ENABLED = "escalation_enabled"
        const val KEY_ESCALATION_STRICT = "escalation_strict"
        const val KEY_ESCALATION_MAIL = "escalation_mail_enabled"
        const val KEY_SMTP_HOST = "escalation_smtp_host"
        const val KEY_SMTP_PORT = "escalation_smtp_port"
        const val KEY_SMTP_SSL = "escalation_smtp_ssl"
        const val KEY_SMTP_USER = "escalation_smtp_user"
        const val KEY_SMTP_PASSWORD = "escalation_smtp_password"
        const val KEY_SMTP_FROM_NAME = "escalation_smtp_from_name"
        const val KEY_SMTP_FROM_EMAIL = "escalation_smtp_from_email"
        const val KEY_SMTP_TIMEOUT = "escalation_smtp_timeout"
        const val KEY_EMOTION_REDLINE = "emotion_redline_enabled"

        // ── 通报邮件 SMTP 缺省（测试环境：阿里企业邮，用户 2026-10-20 口径） ──
        const val DEFAULT_SMTP_HOST = "smtp.qiye.aliyun.com"
        const val DEFAULT_SMTP_PORT = 465
        const val DEFAULT_SMTP_USER = "marketing@80fafa.com"
        const val DEFAULT_SMTP_PASSWORD = "Topway#123456"
        const val DEFAULT_SMTP_FROM_NAME = "80lili帮您理理"
        const val DEFAULT_SMTP_FROM_EMAIL = "marketing@80fafa.com"
        const val DEFAULT_SMTP_TIMEOUT_SEC = 30

        /** 断崖阈值默认值（V-B，方案 §3「默认保守」）：低于共享池续用线 0.45，日常聊天噪声不触发分叉。 */
        const val REUSE_CLIFF_DEFAULT = 0.25f

        /** M3 自动档白名单上限（方案 §8.3：白名单联系人 ≤5）。 */
        const val MAX_AUTO_WHITELIST = 5

        /** M3-b 陪伴窗口单窗口发送上限档（0=不限）与默认值（方案 §8.2：建议 6 条）。 */
        val COMPANION_SEND_OPTIONS = setOf(0, 3, 6, 10)
        const val DEFAULT_COMPANION_MAX_SENDS = 6
        const val KEY_TOTAL_INPUT = "usage_total_input"
        const val KEY_TOTAL_OUTPUT = "usage_total_output"
        const val KEY_TOTAL_TURNS = "usage_total_turns"
        const val KEY_SESSION_INPUT = "usage_session_input"
        const val KEY_SESSION_OUTPUT = "usage_session_output"
        const val KEY_TOTAL_POINTS = "usage_total_points_milli"
        const val KEY_SESSION_POINTS = "usage_session_points_milli"
        const val DEFAULT_MAX_SESSION = 30 * 60_000L

        /**
         * 工作/停歇段允许档（毫秒）：1/5/10/15/20 分钟；0 仅停歇合法（=不停歇）。
         * 1 分钟档供真机联调「录 1 分钟停 1 分钟」快循环（2026-10-27）。
         */
        val WORK_REST_OPTIONS = setOf(0L, 60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L, 20 * 60_000L)

        /** 工作/停歇默认档（2026-10-27：10 分钟 → 1 分钟，快循环便于联调）。 */
        const val DEFAULT_WORK_REST_MS = 60_000L

        /** 拟人节奏区间上限（10 分钟）：超出的持久化值读回时按上限夹紧。 */
        const val PACE_CEILING_MS = 10 * 60_000L

        /**
         * 拟人节奏档位（发送前随机延迟区间，毫秒；设置页与调试广播共用）：
         * 快速=联调档（1~3s，只为压缩测试反馈环）、标准（3~10s）、
         * 拟人=默认（8~90s，方案 §8.2 原口径）、从容（30~180s，高风控场景）。
         * 单挑选定；非法值回退 [PACE_HUMAN]。
         */
        val PACE_FAST = 1_000L to 3_000L
        val PACE_STANDARD = 3_000L to 10_000L
        val PACE_HUMAN = HumanPace.MIN_DELAY_MS to HumanPace.MAX_DELAY_MS
        val PACE_PATIENT = 30_000L to 180_000L
        val PACE_OPTIONS = listOf(PACE_FAST, PACE_STANDARD, PACE_HUMAN, PACE_PATIENT)

        /** 静止降频允许的倍数档。 */
        val BACKOFF_OPTIONS = setOf(1, 2, 4)

        /** 后台摘要/萃取的允许频率档（回合数）。 */
        val DIGEST_OPTIONS = setOf(3, 6, 12)
        val EXTRACT_OPTIONS = setOf(3, 5, 10)

        /** 记忆工具模式档。 */
        const val MEMORY_MODE_AUTO = 0
        const val MEMORY_MODE_FULL = 1
        const val MEMORY_MODE_LEAN = 2
        val MEMORY_MODE_OPTIONS = setOf(0, 1, 2)

        /** 回答长度档。 */
        const val ANSWER_CONCISE = 0
        const val ANSWER_STANDARD = 1
        const val ANSWER_DETAILED = 2
        val ANSWER_OPTIONS = setOf(0, 1, 2)

        /** 去重灵敏度档。 */
        const val DEDUP_LOOSE = 0
        const val DEDUP_STANDARD = 1
        const val DEDUP_STRICT = 2
        val DEDUP_LEVEL_OPTIONS = setOf(0, 1, 2)

        /** 连续模式 token 预算档（0=不限）。 */
        val TOKEN_BUDGET_OPTIONS = setOf(0L, 20_000L, 50_000L, 100_000L)

        /** M2-b 周期检点间隔档（小时）与默认值。 */
        val PROACTIVE_INTERVAL_OPTIONS = setOf(1, 2, 4, 6, 12)
        const val PROACTIVE_DEFAULT_INTERVAL = 4

        /** 策略模式档。 */
        const val STRATEGY_OFF = 0
        const val STRATEGY_SMART = 1
        const val STRATEGY_ENHANCED = 2
    }
}
