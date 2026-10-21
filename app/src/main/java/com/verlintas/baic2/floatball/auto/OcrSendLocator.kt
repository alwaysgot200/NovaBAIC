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

import android.graphics.Bitmap
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.device.api.OcrProvider
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 模式 B 定位器（方案 §17.2「定位改由录屏抽帧+OCR+找图产出的坐标驱动」）：
 * [FrameSource] 取帧 → OCR 识别 → 与模式 A 同口径筛选发送按钮（候选词包含匹配 +
 * 小控件尺寸过滤，最靠下其次最靠右）。
 *
 * 帧源阶梯优先 a11y 免授权截图，无 a11y 时走 assists-mp 投影兜底——模式 B 的
 * 感知面由此成立。OCR 全帧识别比 a11y 节点定位慢（预算超 find→tap≤300ms），
 * A 期接入找图模板（ImageMatcher，全屏匹配 ≤60ms）后由调用方按可用性择优。
 * 选择逻辑为纯函数 [pickSend]，JVM 可测。
 */
@Singleton
class OcrSendLocator @Inject constructor(
    private val frameSource: FrameSource,
    private val ocr: OcrProvider,
) : SendLocator {

    override suspend fun locate(): SendTarget? = withContext(Dispatchers.Default) {
        pickSend(frameLines() ?: return@withContext null)
    }

    /**
     * 发送后上屏回读（方案 §四.A.3，模式 B 证据源）：当前帧 OCR 文本是否包含
     * [clip]。取帧失败按「未包含」处理（调用方判负降级——宁降级不假成功）。
     */
    suspend fun frameContains(clip: String): Boolean = withContext(Dispatchers.Default) {
        val lines = frameLines() ?: return@withContext false
        lines.any { it.text.contains(clip) }
    }

    /** 取当前帧并 OCR（locate/frameContains 共用）；任一环节失败返回 null。 */
    private suspend fun frameLines(): List<OcrLine>? {
        val frame = frameSource.grabFrame().getOrNull() ?: return null
        val bytes = ByteArrayOutputStream().use { sink ->
            frame.compress(Bitmap.CompressFormat.PNG, 100, sink)
            sink.toByteArray()
        }
        return ocr.recognize(bytes).getOrNull()
    }

    internal companion object {

        /** 从 OCR 行中挑出发送按钮点击中心（与 [A11ySendLocator] 同一启发式口径）。 */
        internal fun pickSend(lines: List<OcrLine>): SendTarget? = lines.asSequence()
            .filter { line -> SEND_BUTTON_QUERIES.any { line.text.trim().lowercase().contains(it) } }
            .filter { it.bottom - it.top <= MAX_SEND_NODE_HEIGHT }
            .filter { it.right - it.left <= MAX_SEND_NODE_WIDTH }
            .maxWithOrNull(compareBy({ it.bottom }, { it.right }))
            ?.let { SendTarget(it.centerX, it.centerY) }
    }
}
