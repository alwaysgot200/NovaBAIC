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
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.verlintas.baic2.R
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.floatball.account.RechargeActivity
import com.verlintas.baic2.floatball.advisor.strategy.ReplySuggestions
import com.verlintas.baic2.floatball.advisor.strategy.TacticalBrief

/**
 * 分屏浮窗的转录视图：渲染「好参谋」会话的消息流（与主界面同一会话、
 * 同一数据源；主界面里是完整 Compose 聊天界面，浮窗用轻量 TextView——
 * 服务窗口里 Compose 需要完整 SavedStateRegistry 宿主，得不偿失）。
 *
 * 自动发送的屏幕内容（带【屏幕内容】前缀）默认折叠为一行摘要，点按
 * 展开/收起——省面积；手动输入的消息恒展开。正文长按进入系统选择复制
 * （手柄拖动调起止 +「复制/全选」工具条），与主界面聊天同构。
 * 观察 [ScreenAdvisor.transcript] 即可，无自有业务状态。
 */
internal class TranscriptView(context: Context) : FrameLayout(context) {

    /**
     * 一条气泡的句柄：[container] 挂入列表，[text] 承载正文更新；
     * 参谋回复的 [contentBox] 可在正文下挂行内建议/纠偏/依据（P3 §九）。
     * [bindSignature] 为上次参谋绑定的指纹，流式 token 引发的重复渲染在此短路。
     */
    private class Bubble(
        val container: View,
        val text: TextView,
        val contentBox: LinearLayout? = null,
    ) {
        var bindSignature: String? = null
    }

    // ── P3 §九：行内交互回调（由服务接线，视图本身不碰业务） ─────────────
    /** 参数：建议编号（1 起）、建议正文（视图已先复制到剪贴板）。 */
    var onAdopt: ((Int, String) -> Unit)? = null

    /** 参数：[ScreenAdvisor.CorrectionKind] 的 ordinal。 */
    var onRegenerate: ((Int) -> Unit)? = null

    /** 参数：原依据关键词、用户更正文本。 */
    var onCorrectMemory: ((List<String>, String) -> Unit)? = null

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(8))
    }
    private val scroll = ScrollView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(list)
    }

    /** 消息 id → 气泡，增量更新避免整树重建（流式时只更新最后一个）。 */
    private val bubbles = HashMap<Long, Bubble>()

    /** 自动屏幕消息的展开状态（默认折叠；会话期内保留用户选择）。 */
    private val expanded = HashSet<Long>()
    private var streamingBubble: TextView? = null

    init {
        addView(scroll)
    }

    fun render(state: ScreenAdvisor.UiState) {
        val visible = state.messages.filter {
            it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT
        }
        // 移除已消失的消息（清空会话）。
        val ids = visible.mapTo(HashSet()) { it.id }
        bubbles.keys.filterNot { it == STATUS_KEY || ids.contains(it) }.toList().forEach { stale ->
            bubbles.remove(stale)?.let { list.removeView(it.container) }
        }
        visible.forEach { message -> upsertBubble(message) }
        // P3 §九：参谋气泡行内建议/采纳/纠偏/依据重绑（任务令态变化即刷新）。
        val lastAssistantId = visible.lastOrNull { it.role == ChatRole.ASSISTANT }?.id
        val lastUserId = visible.lastOrNull { it.role == ChatRole.USER }?.id
        visible.forEach { message ->
            if (message.role == ChatRole.ASSISTANT) {
                bubbles[message.id]?.let { bubble ->
                    bindAssistant(
                        message = message,
                        bubble = bubble,
                        state = state,
                        isLastAssistant = message.id == lastAssistantId,
                        lastUserId = lastUserId,
                    )
                }
            }
        }
        renderStreaming(state.streaming)
        renderStatus(state)
        // 仅当原本就贴近底部时跟随流式滚动；用户翻阅历史时不打扰。
        scroll.post {
            val child = scroll.getChildAt(0)
            val nearBottom = child == null ||
                child.bottom - (scroll.scrollY + scroll.height) < dp(96)
            if (nearBottom) scroll.fullScroll(View.FOCUS_DOWN)
        }
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
        } else if (message.role == ChatRole.USER) {
            if (existing.text.text.toString() != message.content) existing.text.text = message.content
            existing.text.maxLines = Int.MAX_VALUE
            existing.text.ellipsize = null
            existing.text.setOnClickListener(null)
            existing.text.isClickable = false
        }
        // ASSISTANT 的正文由 [bindAssistant] 按「引言 + 编号建议 + 依据」重绑。
    }

    /**
     * 气泡构建：用户消息（含自动屏幕消息）是单 TextView；参谋回复是
     * FrameLayout——正文容器 + 行内建议/依据。背景/内边距统一在容器上。
     */
    private fun buildBubble(message: ChatMessage): Bubble {
        val isUser = message.role == ChatRole.USER
        val bubbleColor = if (isUser) 0x22FFFFFF.toInt() else 0x332E5FD0.toInt()
        val body = object : TextView(context) {
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                // bindAssistant 每次 removeAllViews+重挂正文，重挂后 Editor 的
                // 选择控制器会失活——长按有焦点却无手柄/菜单（RecyclerView
                // 同款坑，stackoverflow.com/a/38626276）：重挂瞬间 disable→
                // enable 强制 Editor 重建选择控制器。
                if (isTextSelectable && isEnabled) {
                    isEnabled = false
                    isEnabled = true
                }
            }
        }.apply {
            setTextColor(Color.WHITE)
            textSize = if (isUser) 12f else 14f
            maxLines = Int.MAX_VALUE
            // 长按走系统标准选择复制（手柄/复制菜单由系统提供，与主界面
            // 聊天一致）；窗口可聚焦由宿主保证。
            setTextIsSelectable(true)
        }
        if (isUser) {
            body.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(bubbleColor)
            }
            body.setPadding(dp(10), dp(8), dp(10), dp(8))
            body.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(6), dp(8), 0)
            }
            return Bubble(body, body)
        }

        // 参谋回复：contentBox 承载正文 + 行内建议/依据。
        val contentBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(bubbleColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(6), dp(8), 0)
            }
            addView(
                contentBox,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return Bubble(container, body, contentBox)
    }

    /**
     * P3 §九：重绑参谋气泡。最新参谋气泡且解析出 ≥2 条编号建议、且存在
     * pending 任务令时，逐条挂「✓采纳」；长按建议条弹三档纠偏；气泡下挂
     * 「依据：… · 记错了」。其余气泡只显示原文 + 依据小字。
     */
    private fun bindAssistant(
        message: ChatMessage,
        bubble: Bubble,
        state: ScreenAdvisor.UiState,
        isLastAssistant: Boolean,
        lastUserId: Long?,
    ) {
        val box = bubble.contentBox ?: return
        val pendingMission = state.missions.firstOrNull { it.pending }
        val regenUsed = lastUserId != null && lastUserId in state.regeneratedTurns
        val basis = state.basisByMessage[message.id].orEmpty()
        val tactical = state.tacticalBriefs[message.id]
        val playbooks = state.playbooksByMessage[message.id].orEmpty()
        // 指纹短路：内容/任务令态/依据/战术约束/参考话术都没变（典型：流式 token
        // 触发的重渲染）时跳过整块重建，避免每个 token 都 removeAllViews + 重建 Span。
        val signature = "${message.content.hashCode()}|$isLastAssistant|" +
            "${pendingMission != null}|${pendingMission?.adoptedIndex}|$regenUsed|$basis|$tactical|$playbooks"
        if (bubble.bindSignature == signature) return
        bubble.bindSignature = signature
        box.removeAllViews()
        val body = bubble.text
        val items = ReplySuggestions.parse(message.content)?.takeIf { isLastAssistant }
        if (items == null) {
            body.text = message.content
            body.maxLines = Int.MAX_VALUE
            box.addView(body)
        } else {
            val preamble = ReplySuggestions.preamble(message.content)
            if (preamble.isNotBlank()) {
                body.text = preamble
                body.maxLines = Int.MAX_VALUE
                box.addView(body)
            }
            val canAdopt = pendingMission != null
            val adoptedIndex = pendingMission?.adoptedIndex
            items.forEach { item ->
                box.addView(
                    buildSuggestionView(
                        item = item,
                        canAdopt = canAdopt,
                        adoptedIndex = adoptedIndex,
                        regenUsed = regenUsed,
                    ),
                )
            }
        }
        tactical?.let { box.addView(buildTacticalFooter(it)) }
        playbooks.takeIf { it.isNotEmpty() }?.let { box.addView(buildPlaybookFooter(it)) }
        basis.takeIf { it.isNotEmpty() }?.let { box.addView(buildBasisFooter(box, it)) }
        box.addView(buildCopyFooter(message, bubble))
    }

    /**
     * F3（2026-10-22 方案）：气泡下「战术约束（增强档）」折叠小字——增强档预分析
     * 的四段硬约束对用户可见（原来只注入提示词，不可感知）。默认单行省略摘要，
     * 点击展开完整段（避免/必须回应/必须做到/语气）；再点收起。智能档与回退
     * 回合无此 footer，天然区分增强档差异。
     */
    private fun buildTacticalFooter(brief: TacticalBrief): View {
        val lines = buildList {
            if (brief.donts.isNotEmpty()) {
                add(context.getString(R.string.float_tactical_avoid, brief.donts.joinToString("；")))
            }
            if (brief.mustAnswer.isNotEmpty()) {
                add(context.getString(R.string.float_tactical_must, brief.mustAnswer.joinToString("；")))
            }
            if (brief.hardActions.isNotEmpty()) {
                add(context.getString(R.string.float_tactical_do, brief.hardActions.joinToString("；")))
            }
            if (brief.toneGuard.isNotEmpty()) {
                add(context.getString(R.string.float_tactical_tone, brief.toneGuard))
            }
        }.takeIf { it.isNotEmpty() } ?: return View(context)
        val label = context.getString(R.string.float_tactical_brief_label)
        val collapsed = SpannableStringBuilder(label).append("：").append(lines.joinToString("；"))
        val expandedText = buildString {
            appendLine(label)
            lines.forEach { appendLine(it) }
        }.trimEnd()
        val footer = newAssistantText(12f, BASIS_COLOR)
        footer.tag = TAG_TACTICAL_FOOTER
        footer.text = collapsed
        footer.maxLines = 1
        footer.ellipsize = TextUtils.TruncateAt.END
        var expanded = false
        footer.setOnClickListener {
            expanded = !expanded
            if (expanded) {
                footer.text = expandedText
                footer.maxLines = Int.MAX_VALUE
                footer.ellipsize = null
            } else {
                footer.text = collapsed
                footer.maxLines = 1
                footer.ellipsize = TextUtils.TruncateAt.END
            }
        }
        return footer
    }

    /**
     * 2026-10-23 阶段 B：气泡下「参考话术」折叠条——本回合进提示词的
     * 话术 Top-3（与【可参考的话术】段同源同序）对用户可见，让「参考」可感知。
     * 默认单行省略摘要，点按展开完整 Top-3，再点收起（与战术约束折叠同款交互）。
     */
    private fun buildPlaybookFooter(lines: List<String>): View {
        val label = context.getString(R.string.float_playbook_ref_label)
        val collapsed = SpannableStringBuilder(label).append("：").append(lines.joinToString("；"))
        val expandedText = buildString {
            appendLine(label)
            lines.forEach { appendLine(it) }
        }.trimEnd()
        val footer = newAssistantText(12f, BASIS_COLOR)
        footer.text = collapsed
        footer.maxLines = 1
        footer.ellipsize = TextUtils.TruncateAt.END
        var expanded = false
        footer.setOnClickListener {
            expanded = !expanded
            if (expanded) {
                footer.text = expandedText
                footer.maxLines = Int.MAX_VALUE
                footer.ellipsize = null
            } else {
                footer.text = collapsed
                footer.maxLines = 1
                footer.ellipsize = TextUtils.TruncateAt.END
            }
        }
        return footer
    }

    /**
     * 回复底部一行：左 token 用量小字（随消息持久化的 usageInput/usageOutput，
     * 模型上报过才显示——每条历史回复各自带）+ 右复制图标——两者
     * 合并成单行省一行的纵向占位。复制图标一键复制该回复全文（overlay
     * 窗口挂不上系统选择手柄与复制菜单——Chromium/Editor 均验证失败，
     * 此为整段复制的出口）。点按后图标闪 ✓ 1.2s 作反馈——Toast 会被
     * 面板自身遮挡（overlay 层级高于 TYPE_TOAST），图标态变化是唯一
     * 可靠的可见反馈。
     */
    private fun buildCopyFooter(message: ChatMessage, bubble: Bubble): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        val usage = TextView(context).apply {
            setTextColor(USAGE_COLOR)
            textSize = 11f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { weight = 1f }
        }
        // 逐条积分消耗（完成时快照；旧消息按 token + 当前倍率兜底换算）。
        val pointsMilli = message.usagePointsMilli ?: if (message.usageInput != null || message.usageOutput != null) {
            com.verlintas.baic2.core.model.PointsPricing.deductPointsMilli(
                promptTokens = message.usageInput ?: 0L,
                completionTokens = message.usageOutput ?: 0L,
                coefficientMilli = com.verlintas.baic2.core.model.ModelPricing.coefficientMilli(message.model),
            )
        } else {
            null
        }
        if (pointsMilli != null) {
            usage.text = "积分 " + String.format("%.1f", pointsMilli / 1000.0)
            usage.visibility = View.VISIBLE
        }
        row.addView(usage)
        row.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.fb_ic_copy)
                contentDescription = context.getString(R.string.float_copy_desc)
                alpha = 0.75f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x22FFFFFF)
                }
                setOnClickListener {
                    copyReply(message.content)
                    setImageResource(R.drawable.fb_ic_check)
                    postDelayed({ setImageResource(R.drawable.fb_ic_copy) }, 1200L)
                }
            },
        )
        return row
    }

    /** 单条编号建议：正文 + 行尾采纳/已采纳；长按弹三档纠偏条。 */
    private fun buildSuggestionView(
        item: ReplySuggestions.Item,
        canAdopt: Boolean,
        adoptedIndex: Int?,
        regenUsed: Boolean,
    ): View {
        val itemBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            layoutParams = lp
        }
        val tv = newAssistantText()
        val builder = SpannableStringBuilder(item.text)
        when {
            adoptedIndex == item.number -> {
                builder.append("  ")
                builder.append(
                    context.getString(R.string.float_suggestion_adopted),
                    ForegroundColorSpan(ADOPTED_COLOR),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            canAdopt -> {
                builder.append("  ")
                builder.append(
                    context.getString(R.string.float_suggestion_adopt),
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            copyReply(item.text)
                            onAdopt?.invoke(item.number, item.text)
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = LINK_COLOR
                            ds.isUnderlineText = false
                        }
                    },
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        tv.text = builder
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.setOnLongClickListener {
            if (regenUsed) {
                Toast.makeText(context, R.string.float_regen_used, Toast.LENGTH_SHORT).show()
            } else {
                showCorrectionBar(itemBox)
            }
            true
        }
        itemBox.addView(tv)
        return itemBox
    }

    /** 长按后在建议条下插入三档纠偏 chips（再次长按先移除旧的）。 */
    private fun showCorrectionBar(itemBox: LinearLayout) {
        itemBox.findViewWithTag<View>(TAG_CORRECTION_BAR)?.let { itemBox.removeView(it) }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = TAG_CORRECTION_BAR
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            layoutParams = lp
        }
        val kinds = listOf(
            ScreenAdvisor.CorrectionKind.TOO_DIRECT to R.string.float_correction_too_direct,
            ScreenAdvisor.CorrectionKind.TOO_SOFT to R.string.float_correction_too_soft,
            ScreenAdvisor.CorrectionKind.CHANGE_DIRECTION to R.string.float_correction_change,
        )
        kinds.forEach { (kind, labelRes) ->
            val chip = TextView(context).apply {
                text = context.getString(labelRes)
                setTextColor(LINK_COLOR)
                textSize = 12f
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x33FFFFFF)
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(6)
                layoutParams = lp
                setOnClickListener {
                    onRegenerate?.invoke(kind.ordinal)
                    // 重写触发后整树会随状态重绑；这里立即移除避免连点。
                    itemBox.removeView(bar)
                }
            }
            bar.addView(chip)
        }
        itemBox.addView(bar)
    }

    /** 气泡下「依据：X、Y · 记错了」；点记错了就地换输入框，确认写记忆（不直删）。 */
    private fun buildBasisFooter(box: LinearLayout, basis: List<String>): View {
        val footer = newAssistantText(12f, BASIS_COLOR)
        footer.tag = TAG_BASIS_FOOTER
        val builder = SpannableStringBuilder(context.getString(R.string.float_basis_label))
        builder.append(basis.joinToString("、"))
        builder.append("  ·  ")
        builder.append(
            context.getString(R.string.float_basis_wrong),
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val index = box.indexOfChild(footer)
                    box.removeView(footer)
                    val editor = buildBasisEditor(box, basis, index)
                    box.addView(editor, index)
                }
                override fun updateDrawState(ds: TextPaint) {
                    ds.color = LINK_COLOR
                    ds.isUnderlineText = false
                }
            },
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        footer.text = builder
        footer.movementMethod = LinkMovementMethod.getInstance()
        return footer
    }

    private fun buildBasisEditor(box: LinearLayout, basis: List<String>, index: Int): View {
        val editor = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(2)
            layoutParams = lp
        }
        val input = EditText(context).apply {
            setText(basis.joinToString("、"))
            setTextColor(Color.WHITE)
            textSize = 12f
            hint = context.getString(R.string.float_basis_correct_hint)
            setHintTextColor(0x80FFFFFF.toInt())
            setSingleLine(false)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0x22FFFFFF)
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        editor.addView(input)
        // 按 editor 自身引用定位替换：期间若发生整块重绑，缓存的 index 会失准，
        // removeViewAt(index) 可能误删兄弟视图甚至越界崩溃。
        fun replaceEditor(replacement: View) {
            val pos = box.indexOfChild(editor)
            if (pos >= 0) {
                box.removeView(editor)
                box.addView(replacement, pos)
            } else {
                box.addView(replacement)
            }
        }
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(footerAction(context.getString(R.string.float_basis_correct_save)) {
            val correction = input.text.toString()
            if (correction.isBlank()) {
                Toast.makeText(context, R.string.float_basis_correct_empty, Toast.LENGTH_SHORT).show()
                return@footerAction
            }
            onCorrectMemory?.invoke(basis, correction)
            Toast.makeText(context, R.string.float_basis_correct_done, Toast.LENGTH_SHORT).show()
            replaceEditor(buildBasisReadonly(basis))
        })
        buttons.addView(footerAction(context.getString(R.string.float_basis_correct_cancel)) {
            replaceEditor(buildBasisFooter(box, basis))
        })
        editor.addView(buttons)
        return editor
    }

    private fun buildBasisReadonly(basis: List<String>): View =
        newAssistantText(12f, BASIS_COLOR).apply {
            text = context.getString(R.string.float_basis_label) + basis.joinToString("、")
        }

    private fun footerAction(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(LINK_COLOR)
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(2))
            setOnClickListener { action() }
        }

    private fun newAssistantText(
        size: Float = 14f,
        color: Int = Color.WHITE,
    ): TextView = TextView(context).apply {
        setTextColor(color)
        textSize = size
        maxLines = Int.MAX_VALUE
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
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
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
                "regenerate_failed" -> context.getString(R.string.float_ask_regen_failed)
                "no_points" -> noPointsText()
                else -> context.getString(R.string.float_ask_failed, status.reason)
            }
            ScreenAdvisor.Status.Idle -> ""
        }
        if (text.isEmpty()) {
            // 回到 Idle：状态行必须摘除，否则上一条「思考中/错误」永久残留
            // （STATUS_KEY 被陈旧清理逻辑豁免，不会随消息清理走）。
            bubbles.remove(STATUS_KEY)?.let { list.removeView(it.container) }
            return
        }
        val key = STATUS_KEY
        val holder = bubbles[key] ?: run {
            val created = TextView(context).apply {
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 13f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), dp(6), dp(8), 0)
                }
            }
            bubbles[key] = Bubble(created, created)
            list.addView(created)
            bubbles[key]!!
        }
        holder.text.text = text
        // 「余额不足，请充值」的「充值」是可点链接：Spanned 文本才挂 LinkMovementMethod，
        // 纯文本摘掉避免状态行误吃点击。
        holder.text.movementMethod = if (text is Spanned) LinkMovementMethod.getInstance() else null
    }

    /** 「余额不足，请充值」：「充值」可点 → 购买充值 WebView 页（[RechargeActivity]）。 */
    private fun noPointsText(): CharSequence {
        val full = context.getString(R.string.float_ask_no_points)
        val linkLabel = context.getString(R.string.float_recharge_link)
        val start = full.indexOf(linkLabel)
        if (start < 0) return full
        return SpannableStringBuilder(full).apply {
            setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        context.startActivity(
                            Intent(context, RechargeActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = LINK_COLOR
                        ds.isUnderlineText = true
                    }
                },
                start,
                start + linkLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun compactTokens(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> {
            val k = n / 1_000
            val rem = (n % 1_000) / 100
            if (rem == 0L) "${k}k" else "${k}.${rem}k"
        }
        else -> "${n / 1_000_000}M"
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        /** 状态行的伪 id（真实消息 id 是数据库自增正数）。 */
        const val STATUS_KEY = -1L

        /** 用量小字用色（弱于状态行，纯辅助信息）。 */
        val USAGE_COLOR = 0x59FFFFFF.toInt()

        /** P3 §九：行内交互的视图 tag 与配色。 */
        const val TAG_CORRECTION_BAR = "p3_correction_bar"
        const val TAG_BASIS_FOOTER = "p3_basis_footer"
        const val TAG_TACTICAL_FOOTER = "f3_tactical_footer"
        val LINK_COLOR = 0xFF9EC9FF.toInt()
        val ADOPTED_COLOR = 0x99FFFFFF.toInt()
        val BASIS_COLOR = 0xB3FFFFFF.toInt()
    }
}
