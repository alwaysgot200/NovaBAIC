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

package com.verlintas.baic2.floatball.experts.strategist

import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.floatball.PromptDedup
import com.verlintas.baic2.floatball.advisor.data.PRIVACY_POLICY_ASSESS
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 军师 Strategist 门面（2026-10-21 五岗子代理架构整合方案 §二）：**军师岗位的
 * 快车道 LLM 职责=Permit 许可单**（本回合方针与敏感手段授权），路由/话题/谋略
 * 等其余军师事务由 director/ 既有实体承担。五件套同构：Permit（交接物）/
 * StrategistRules（纯函数执法）/ StrategistAssets（手段库+词表）/
 * StrategistPrompt（提示词）/ 本门面（触发+缓存+调用）。
 *
 * 成本纪律（总方案 §三 D1/D7）：触发条件=任一敏感维 assess/free 档或
 * stageIntent 非空，否则零成本跳过；≤180 tok、8s 硬超时、temperature 0.2
 * （与 HandoffGate 同款慢车道）；90s 许可单缓存 + 来信指纹骤变失效——
 * 同屏连发多回合零重复调用。**失败降级**：任何失败 → [PermitOutcome.ruled]=null，
 * 调用方渲染禁令句（等价 never），回合链条零中断。
 */
@Singleton
class Strategist @Inject constructor(
    private val agentRepository: AgentRepository,
    private val auxiliaryTasks: AuxiliaryTasks,
) {

    private data class CacheEntry(
        val ruled: RuledPermit?,
        val createdAt: Long,
        val fingerprint: Set<String>,
        /** E-e 情绪行键：情绪变了许可单必须重出（缓存不跨情绪复用）。 */
        val emotionKey: String,
    )

    private val cache = HashMap<Long, CacheEntry>()
    private val cacheMutex = Mutex()

    /**
     * 许可单：触发条件不满足 → [PermitOutcome](null, false)（零成本）；
     * 缓存命中 → 直接复用（fromCache=true）；否则轻 LLM 一发 + 四道闸执法。
     * [emotion] 为 E-e 情绪→方针映射产物（方案 §4.6）：进许可单现场段与
     * enforce 约束，并纳入缓存键；**不进触发条件**（情绪不新增 LLM 调用，
     * 方案 §五 预算锁）。
     */
    suspend fun permit(
        contactId: Long,
        strategy: TalkerStrategy,
        privacyPolicy: String?,
        stageIntent: String,
        incomingText: String,
        recentDigest: String,
        personaDigest: String,
        privacyDomain: String?,
        emotion: EmotionRoute = EmotionRoute.INACTIVE,
    ): PermitOutcome {
        val fingerprint = PromptDedup.fingerprint(incomingText)
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            val cached = cache[contactId]
            if (cached != null && now - cached.createdAt <= CACHE_TTL_MS &&
                cached.emotionKey == emotion.key &&
                fingerprintSimilar(cached.fingerprint, fingerprint)
            ) {
                return PermitOutcome(
                    ruled = cached.ruled,
                    fromCache = true,
                    triggered = triggered(strategy, privacyPolicy, stageIntent),
                )
            }
        }

        if (!triggered(strategy, privacyPolicy, stageIntent)) {
            return PermitOutcome(null, fromCache = false, triggered = false)
        }

        val raw = withContext(Dispatchers.Default) {
            val config = runCatching { agentRepository.resolveConfig(null) }.getOrNull()
                ?: return@withContext null
            withTimeoutOrNull(PERMIT_TIMEOUT_MS) {
                runCatching {
                    auxiliaryTasks.complete(
                        config = config,
                        systemPrompt = StrategistPrompt.systemPrompt(),
                        userPrompt = StrategistPrompt.userPrompt(
                            stageIntent = stageIntent,
                            incomingText = incomingText,
                            recentDigest = recentDigest,
                            personaDigest = personaDigest,
                            strategy = strategy,
                            privacyPolicy = privacyPolicy.orEmpty(),
                            privacyDomain = privacyDomain,
                            emotion = emotion,
                        ),
                        maxTokens = PERMIT_MAX_TOKENS,
                        temperature = PERMIT_TEMPERATURE,
                    )
                }.getOrNull()
            }
        }
        val ruled = raw?.let { parsed -> StrategistRules.parse(parsed)?.let {
            StrategistRules.enforce(it, strategy, privacyPolicy, emotion)
        } }

        cacheMutex.withLock {
            cache[contactId] = CacheEntry(ruled, System.currentTimeMillis(), fingerprint, emotion.key)
        }
        return PermitOutcome(ruled, fromCache = false, triggered = true)
    }

    /** 触发条件（方案 §3.1）：任一敏感维 assess/free，或 stageIntent 非空。 */
    private fun triggered(strategy: TalkerStrategy, privacyPolicy: String?, stageIntent: String): Boolean {
        if (stageIntent.isNotBlank()) return true
        if (privacyPolicy == PRIVACY_POLICY_ASSESS) return true
        return strategy.lying != TALKER_NEVER ||
            strategy.promising != TALKER_NEVER ||
            strategy.exemplifying != TALKER_NEVER
    }

    /** 来信指纹相似度（复用 PromptDedup 指纹，覆比 ≥ 阈值视为同一话题）。 */
    private fun fingerprintSimilar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val overlap = a.intersect(b).size
        return overlap.toFloat() / minOf(a.size, b.size) >= FINGERPRINT_OVERLAP_MIN
    }

    private companion object {
        /** 许可单缓存 TTL（总方案 §三 D7 拍板 90s）。 */
        const val CACHE_TTL_MS = 90_000L

        /** 来信指纹覆比阈值（PromptDedup.OVERLAP_DUP 同口径）。 */
        const val FINGERPRINT_OVERLAP_MIN = 0.5f

        /** 慢车道口径（同 HandoffGate 量级）。 */
        const val PERMIT_MAX_TOKENS = 180
        const val PERMIT_TEMPERATURE = 0.2
        const val PERMIT_TIMEOUT_MS = 8_000L
    }
}
