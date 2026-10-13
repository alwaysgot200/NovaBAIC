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
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.verlintas.baic2.R
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole

/**
 * 分屏浮窗的转录视图：渲染「老参谋」会话的消息流（与主界面同一会话、
 * 同一数据源；主界面里是完整 Compose 聊天界面，浮窗用轻量 TextView——
 * 服务窗口里 Compose 需要完整 SavedStateRegistry 宿主，得不偿失）。
 * 观察 [ScreenAdvisor.transcript] 即可，无自有业务状态。
 */
internal class TranscriptView(context: Context) : LinearLayout(context) {

    private val list = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(8))
    }
    private val scroll = ScrollView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1f)
        addView(list)
    }

    /** 消息 id → 气泡，增量更新避免整树重建（流式时只更新最后一个）。 */
    private val bubbles = HashMap<Long, TextView>()
    private var streamingBubble: TextView? = null
    private var lastRole: ChatRole? = null

    init {
        orientation = VERTICAL
        addView(scroll)
    }

    fun render(state: ScreenAdvisor.UiState) {
        val visible = state.messages.filter {
            it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT
        }
        // 移除已消失的消息（清空会话）。
        val ids = visible.mapTo(HashSet()) { it.id }
        bubbles.keys.filterNot(ids::contains).toList().forEach { stale ->
            bubbles.remove(stale)?.let { list.removeView(it) }
        }
        visible.forEach { message -> upsertBubble(message) }
        renderStreaming(state.streaming)
        renderStatus(state)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun upsertBubble(message: ChatMessage) {
        val existing = bubbles[message.id]
        if (existing != null) {
            if (existing.text.toString() != message.content) existing.text = message.content
            return
        }
        val bubble = TextView(context).apply {
            text = decorate(message)
            setTextColor(Color.WHITE)
            textSize = if (message.role == ChatRole.USER) 12f else 14f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(
                    if (message.role == ChatRole.USER) 0x22FFFFFF.toInt() else 0x332E5FD0.toInt(),
                )
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(6), dp(8), 0)
            }
        }
        bubbles[message.id] = bubble
        list.addView(bubble)
        lastRole = message.role
    }

    private fun decorate(message: ChatMessage): String {
        val prefix = context.getString(
            if (message.role == ChatRole.USER) R.string.float_ask_question_label
            else R.string.float_answer_label,
        )
        return prefix + "\n" + message.content
    }

    private fun renderStreaming(streaming: String?) {
        val bubble = streamingBubble
        if (streaming == null) {
            bubble?.let { list.removeView(it) }
            streamingBubble = null
            return
        }
        if (bubble == null) {
            streamingBubble = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x332E5FD0.toInt())
                }
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), dp(6), dp(8), 0)
                }
                list.addView(this)
            }
        }
        streamingBubble?.text = streaming + " ▍"
    }

    private fun renderStatus(state: ScreenAdvisor.UiState) {
        if (state.streaming != null) return
        val text = when (val status = state.status) {
            is ScreenAdvisor.Status.OcrRunning -> context.getString(R.string.float_ask_status_ocr)
            is ScreenAdvisor.Status.Thinking -> context.getString(R.string.float_ask_thinking)
            is ScreenAdvisor.Status.Error -> when (status.reason) {
                "no_text" -> context.getString(R.string.float_ask_no_text)
                "no_config" -> context.getString(R.string.float_ask_no_config)
                "blank_frame" -> context.getString(R.string.float_ask_blank)
                else -> context.getString(R.string.float_ask_failed, status.reason)
            }
            ScreenAdvisor.Status.Idle -> ""
        }
        if (text.isEmpty()) return
        if (bubbles[STATUS_KEY] == null) {
            bubbles[STATUS_KEY] = TextView(context).apply {
                id = generateViewId()
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 13f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), dp(6), dp(8), 0)
                }
                list.addView(this)
            }
        }
        bubbles.getValue(STATUS_KEY).text = text
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        /** 状态行的伪 id（真实消息 id 是数据库自增正数）。 */
        const val STATUS_KEY = -1L
    }
}
