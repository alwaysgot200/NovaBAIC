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
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.auto

import kotlinx.coroutines.delay

/**
 * 发送后上屏回读的证据源：[contains] 判定消息文本是否已出现在可读界面
 * （模式 A=a11y findText 节点树；模式 B=当前帧 OCR 文本）。定位与执行解耦的
 * 同款纪律：证据采集由通道决定，判定逻辑收敛在 [PostSendVerifier]。
 */
fun interface SendEvidenceFinder {

    suspend fun contains(text: String): Boolean
}

/**
 * 发送证据三态（2026-10-27 真机「auto_sent 假成功」修复）：单纯文本回读无法
 * 区分「草稿仍在输入框」与「消息已上屏」——两者 OCR 文本相同。像素通道可见
 * 发送按钮，点击后按钮仍在 = 未发出（[NOT_SENT]，强证据）；按钮消失且文本
 * 命中 = 已发出（[SENT]）；按钮消失但文本未命中/取帧失败 = 证据不足
 * （[UNKNOWN]，不重试，按失败降级，防重复发送）。
 */
enum class SendProof { SENT, NOT_SENT, UNKNOWN }

/** 三态证据源（模式 B 像素通道）：[tapped] 为本次点击的按钮位置。 */
fun interface SendProofFinder {

    suspend fun proof(clips: List<String>, tapped: SendTarget): SendProof
}

/**
 * 发送后验证器（托管方案 §四.A.3）：点击发送 1s 后回读「草稿前缀 8 字」——
 * 消息气泡文本会在节点树/OCR 帧中出现，未命中即视为发送未生效，按
 * SEND_TAP_FAILED 处理并清输入框残留（宁降级不假成功，防误判成功后循环在
 * 错误状态上继续）。UI 渲染抖动容错：未命中隔一拍重查一次，两次都未命中才判负。
 *
 * 2026-10-26 真机假阴性修复：12 字片段常跨 OCR 行/含 IME 标点或 v1 之类易错
 * 字符，导致「实际已发出但被判失败」并误挂降级通知。回读改为主片段 8 字 +
 * 备选 6 字（去掉易错尾巴）多前缀回退；证据源的空白/标点归一化由
 * [OcrSendLocator.frameContains] 同口径承担。
 */
object PostSendVerifier {

    /** 发送点击到消息上屏的基础等待。 */
    const val VERIFY_DELAY_MS = 1_000L

    /** 一次重查前的追加等待（渲染滞后容错）。 */
    const val RETRY_DELAY_MS = 800L

    /** 主回读片段长度（前缀包含匹配，越短误命中风险越高）。 */
    const val CLIP_MAX_CHARS = 8

    /** 备选回读片段长度（主片段含易错字符/跨行时回退）。 */
    const val CLIP_FALLBACK_CHARS = 6

    /**
     * 回读片段候选 = 草稿首个非空行的前 [CLIP_MAX_CHARS]/[CLIP_FALLBACK_CHARS]
     * 字（去重，空草稿返回空列表——调用方担保非空草稿，空片段验证直接放行）。
     */
    fun clipsOf(draft: String): List<String> {
        val line = draft.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isEmpty()) return emptyList()
        return listOf(
            line.take(CLIP_MAX_CHARS).trim(),
            line.take(CLIP_FALLBACK_CHARS).trim(),
        ).filter { it.isNotEmpty() }.distinct()
    }

    /** 主回读片段（兼容既有调用/断言）：首个候选，空草稿返回空串。 */
    fun clipOf(draft: String): String = clipsOf(draft).firstOrNull().orEmpty()

    /**
     * 回读判定：等 [delayMs] 查一次，未命中隔 [retryMs] 再查一次；两次都未命中
     * = false。空片段（无从回读）恒 true——是否发送由调用方的草稿非空担保。
     */
    suspend fun verify(
        finder: SendEvidenceFinder,
        clip: String,
        delayMs: Long = VERIFY_DELAY_MS,
        retryMs: Long = RETRY_DELAY_MS,
    ): Boolean = verify(finder, listOf(clip), delayMs, retryMs)

    /** 多片段回退版：每轮依次尝试候选，任一命中即通过；全未命中才进入重查。 */
    suspend fun verify(
        finder: SendEvidenceFinder,
        clips: List<String>,
        delayMs: Long = VERIFY_DELAY_MS,
        retryMs: Long = RETRY_DELAY_MS,
    ): Boolean {
        val candidates = clips.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return true
        delay(delayMs)
        if (matches(finder, candidates)) return true
        delay(retryMs)
        return matches(finder, candidates)
    }

    private suspend fun matches(finder: SendEvidenceFinder, candidates: List<String>): Boolean =
        candidates.any { finder.contains(it) }
}
