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

package com.verlintas.baic2.floatball

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.verlintas.baic2.R
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole

/**
 * 分屏浮窗的转录视图：渲染「老参谋」会话的消息流（与主界面同一会话、
 * 同一数据源；主界面里是完整 Compose 聊天界面，浮窗用轻量 TextView——
 * 服务窗口里 Compose 需要完整 SavedStateRegistry 宿主，得不偿失）。
 *
 * 自动发送的屏幕内容（带【屏幕内容】前缀）默认折叠为一行摘要，点按
 * 展开/收起——省面积；手动输入的消息恒展开。参谋回复（ASSISTANT）气泡
 * 右上角带复制按钮，复制**纯回复正文**（不带【参谋】前缀，粘进 IM 即可用）。
 * 观察 [ScreenAdvisor.transcript] 即可，无自有业务状态。
 */
internal class TranscriptView(context: Context) : LinearLayout(context) {

    /** 一条气泡的句柄：[container] 挂入列表，[text] 承载正文更新。 */
    private class Bubble(val container: View, val text: TextView)

    private val list = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(8))
    }
    private val scroll = ScrollView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1f)
        addView(list)
    }

    /** 消息 id → 气泡，增量更新避免整树重建（流式时只更新最后一个）。 */
    private val bubbles = HashMap<Long, Bubble>()

    /** 自动屏幕消息的展开状态（默认折叠；会话期内保留用户选择）。 */
    private val expanded = HashSet<Long>()
    private var streamingBubble: TextView? = null

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
            bubbles.remove(stale)?.let { list.removeView(it.container) }
        }
        visible.forEach { message -> upsertBubble(message) }
        renderStreaming(state.streaming)
        renderStatus(state)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun upsertBubble(message: ChatMessage) {
        val existing = bubbles[message.id] ?: run {
            val created = buildBubble(message)
            bubbles[message.id] = created
            list.addView(created.container)
            created
        }

        if (isAutoScreenMessage(message)) {
            val open = message.id in expanded
            existing.text.apply {
                if (open) {
                    text = message.content
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                } else {
                    text = collapsedText(message)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                // 折叠态整块可点；展开态点正文不误收，收起靠尾部「收起」。
                setOnClickListener {
                    if (message.id in expanded) expanded.remove(message.id) else expanded.add(message.id)
                    upsertBubble(message)
                }
            }
            // 展开态尾部补「收起」提示。
            if (open) {
                existing.text.text =
                    message.content + "\n" + context.getString(R.string.float_bubble_collapse)
                existing.text.setOnClickListener {
                    expanded.remove(message.id)
                    upsertBubble(message)
                }
            }
        } else {
            if (existing.text.text.toString() != message.content) existing.text.text = message.content
            existing.text.maxLines = Int.MAX_VALUE
            existing.text.ellipsize = null
            existing.text.setOnClickListener(null)
            existing.text.isClickable = false
        }
    }

    /**
     * 气泡构建：用户消息（含自动屏幕消息）是单 TextView；参谋回复是
     * FrameLayout——正文 + 右上角复制小按钮。背景/内边距统一在容器上。
     */
    private fun buildBubble(message: ChatMessage): Bubble {
        val isUser = message.role == ChatRole.USER
        val bubbleColor = if (isUser) 0x22FFFFFF.toInt() else 0x332E5FD0.toInt()
        val body = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = if (isUser) 12f else 14f
            maxLines = Int.MAX_VALUE
        }
        if (isUser) {
            body.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(bubbleColor)
            }
            body.setPadding(dp(10), dp(8), dp(10), dp(8))
            body.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(6), dp(8), 0)
            }
            return Bubble(body, body)
        }

        // 参谋回复：右侧给复制按钮留位，避免压住首行文字。
        body.setPadding(dp(10), dp(8), dp(34), dp(8))
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(bubbleColor)
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(6), dp(8), 0)
            }
            addView(
                body,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(buildCopyButton(message.content))
        }
        return Bubble(container, body)
    }

    /** 参谋回复右上角的复制按钮：常态半透明白，按下全亮；复制纯回复正文。 */
    private fun buildCopyButton(content: String): ImageView =
        ImageView(context).apply {
            setImageResource(R.drawable.fb_ic_copy)
            alpha = 0.55f
            contentDescription = context.getString(R.string.float_copy_desc)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(2)
                marginEnd = dp(2)
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> view.alpha = 1f
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL,
                    -> view.alpha = 0.55f
                }
                false // 仍交给 click 处理
            }
            setOnClickListener { copyReply(content) }
        }

    private fun copyReply(content: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) return
        clipboard.setPrimaryClip(ClipData.newPlainText("advisor_reply", content))
        Toast.makeText(context, R.string.float_copy_done, Toast.LENGTH_SHORT).show()
    }

    private fun isAutoScreenMessage(message: ChatMessage): Boolean =
        message.role == ChatRole.USER &&
            message.content.startsWith(context.getString(R.string.float_ask_question_label))

    /** 折叠摘要：前缀 + 字数 + 展开指示，一行内省面积。 */
    private fun collapsedText(message: ChatMessage): String {
        val length = message.content.length
        return context.getString(R.string.float_bubble_collapsed, length)
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
            is ScreenAdvisor.Status.Retrying ->
                context.getString(R.string.float_ask_retrying, status.attempt, status.total)
            is ScreenAdvisor.Status.Error -> when (status.reason) {
                "no_text" -> context.getString(R.string.float_ask_no_text)
                "no_config" -> context.getString(R.string.float_ask_no_config)
                "blank_frame" -> context.getString(R.string.float_ask_blank)
                "first_text_timeout" -> context.getString(R.string.float_ask_error_timeout)
                "no_response" -> context.getString(R.string.float_ask_error_no_response)
                "network" -> context.getString(R.string.float_ask_error_network)
                else -> context.getString(R.string.float_ask_failed, status.reason)
            }
            ScreenAdvisor.Status.Idle -> ""
        }
        if (text.isEmpty()) return
        val key = STATUS_KEY
        val holder = bubbles[key] ?: run {
            val created = TextView(context).apply {
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 13f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), dp(6), dp(8), 0)
                }
            }
            bubbles[key] = Bubble(created, created)
            list.addView(created)
            bubbles[key]!!
        }
        holder.text.text = text
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        /** 状态行的伪 id（真实消息 id 是数据库自增正数）。 */
        const val STATUS_KEY = -1L
    }
}
