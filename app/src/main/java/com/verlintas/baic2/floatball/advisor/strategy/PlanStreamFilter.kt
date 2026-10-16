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

package com.verlintas.baic2.floatball.advisor.strategy

/**
 * 两段式流式剥离器：把模型回复开头的 `<plan>…</plan>` 战术块从可见流中摘掉。
 *
 * 状态机：
 * - HEAD：逐字符前缀匹配开标记（容忍跨 chunk 的 `<`、`<p`）；普通正文立即放行并继续搜索，
 *   仅当某个 `<` 开头的缓冲确认不是开标记时才放行该缓冲；
 * - PLAN：chunk 全部进 hiddenBuffer 不上屏，发现闭标记则截取 plan、剩余转 BODY；
 *   hidden 超 [MAX_PLAN_CHARS] 仍无闭合 → 畸形兜底，hidden 全部交还可见、plan=null；
 * - BODY：直通（plan 正常闭合或畸形兜底后进入）。
 *
 * 流结束必须调 [endStream]：未闭合的 hidden 原样补还，保证落库消息与气泡完整（§7.1）。
 * 每个参谋回合 new 一个实例，非线程安全（设计为单流使用）。
 */
class PlanStreamFilter {

    private enum class State { HEAD, PLAN, BODY }

    private var state = State.HEAD

    /** HEAD 态下可能成为开标记前缀的待判缓冲。 */
    private val prefix = StringBuilder()

    /** PLAN 态下已截留的隐藏文本。 */
    private val hidden = StringBuilder()

    private var planRaw: String? = null

    /** 喂入一个流式分片，返回本片段中应对用户可见的文本。 */
    fun feed(chunk: String): String {
        if (chunk.isEmpty() || state == State.BODY) {
            // BODY 直通；空片短路。
            return if (state == State.BODY) chunk else ""
        }
        val out = StringBuilder()
        for (ch in chunk) {
            when (state) {
                State.HEAD -> {
                    if (prefix.isEmpty()) {
                        // 空窗期：普通正文直接放行但不放弃搜索，只有 '<' 开启待判窗口。
                        if (ch == '<') prefix.append(ch) else out.append(ch)
                    } else {
                        prefix.append(ch)
                        val p = prefix.toString()
                        when {
                            p == PLAN_OPEN -> {
                                prefix.setLength(0)
                                state = State.PLAN
                            }
                            PLAN_OPEN.startsWith(p) -> {
                                // 仍是开标记前缀，继续等待后续字符。
                            }
                            else -> {
                                // 确认不是开标记：整段缓冲放行，回到空窗继续在剩余字符里搜索。
                                out.append(p)
                                prefix.setLength(0)
                            }
                        }
                    }
                }
                State.PLAN -> appendHidden(ch, out)
                State.BODY -> out.append(ch)
            }
        }
        return out.toString()
    }

    /** 流结束兜底调用：交还任何未闭合/未判定的截留文本；之后 filter 即 BODY 态。 */
    fun endStream(): String {
        val residual = when (state) {
            State.HEAD -> prefix.toString().also { prefix.setLength(0) }
            State.PLAN -> {
                // 未闭合：开标记 + hidden 全部交还可见，plan 置空（与超长兜底同口径）。
                val back = PLAN_OPEN + hidden.toString()
                hidden.setLength(0)
                planRaw = null
                back
            }
            State.BODY -> ""
        }
        state = State.BODY
        return residual
    }

    /** 截到的 plan 原文（含可能的 json 围栏，交给 Tactician 清洗解析）；未截到返回 null。 */
    fun plan(): String? = planRaw

    private fun appendHidden(ch: Char, out: StringBuilder) {
        hidden.append(ch)
        val closeIdx = hidden.indexOf(PLAN_CLOSE)
        if (closeIdx >= 0) {
            planRaw = hidden.substring(0, closeIdx)
            val rest = hidden.substring(closeIdx + PLAN_CLOSE.length)
            hidden.setLength(0)
            state = State.BODY
            if (rest.isNotEmpty()) out.append(rest)
            return
        }
        // 宽限 CLOSE.length：hidden 末尾可能恰好是闭标记的跨 chunk 前缀。
        if (hidden.length > MAX_PLAN_CHARS + PLAN_CLOSE.length) {
            out.append(PLAN_OPEN).append(hidden.toString())
            hidden.setLength(0)
            planRaw = null
            state = State.BODY
        }
    }

    companion object {
        const val PLAN_OPEN = "<plan>"
        const val PLAN_CLOSE = "</plan>"

        /** 战术块体积上限：超出仍无闭合视为畸形输出（防模型把整篇回复塞进 plan）。 */
        const val MAX_PLAN_CHARS = 1200

        /**
         * 对一条完整文本做一次性剥离（AgentLoop 通道的 AssistantMessage 走这里，
         * 增量流走 [feed]）：返回 (可见正文, plan 原文?)。块缺失或未闭合时
         * 正文原样返回、plan=null（未闭合内容继续算正文，不吞用户可见输出）。
         */
        fun split(full: String): Pair<String, String?> {
            val start = full.indexOf(PLAN_OPEN)
            if (start < 0) return full to null
            val closeAt = full.indexOf(PLAN_CLOSE, startIndex = start + PLAN_OPEN.length)
            if (closeAt < 0) return full to null
            val plan = full.substring(start + PLAN_OPEN.length, closeAt)
            val body = (full.substring(0, start) + full.substring(closeAt + PLAN_CLOSE.length)).trim()
            return body to plan.takeIf { it.isNotBlank() }
        }
    }
}
