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
 * 发送后验证器（托管方案 §四.A.3）：点击发送 1s 后回读「草稿前 8~12 字」——
 * 消息气泡文本会在节点树/OCR 帧中出现，未命中即视为发送未生效，按
 * SEND_TAP_FAILED 处理并清输入框残留（宁降级不假成功，防误判成功后循环在
 * 错误状态上继续）。UI 渲染抖动容错：未命中隔一拍重查一次，两次都未命中才判负。
 */
object PostSendVerifier {

    /** 发送点击到消息上屏的基础等待。 */
    const val VERIFY_DELAY_MS = 1_000L

    /** 一次重查前的追加等待（渲染滞后容错）。 */
    const val RETRY_DELAY_MS = 800L

    /** 回读片段取「草稿前 8~12 字」的上限（前缀包含匹配，越短误命中风险越高）。 */
    const val CLIP_MAX_CHARS = 12

    /**
     * 回读片段 = 草稿首个非空行的前 [CLIP_MAX_CHARS] 字；空草稿返回空串
     * （调用方担保非空草稿，空片段验证直接放行）。
     */
    fun clipOf(draft: String): String =
        draft.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(CLIP_MAX_CHARS)
            ?.trim()
            .orEmpty()

    /**
     * 回读判定：等 [delayMs] 查一次，未命中隔 [retryMs] 再查一次；两次都未命中
     * = false。空片段（无从回读）恒 true——是否发送由调用方的草稿非空担保。
     */
    suspend fun verify(
        finder: SendEvidenceFinder,
        clip: String,
        delayMs: Long = VERIFY_DELAY_MS,
        retryMs: Long = RETRY_DELAY_MS,
    ): Boolean {
        if (clip.isBlank()) return true
        delay(delayMs)
        if (finder.contains(clip)) return true
        delay(retryMs)
        return finder.contains(clip)
    }
}
