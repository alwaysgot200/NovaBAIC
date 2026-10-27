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

/**
 * 自动档分段耗时账（2026-10-27 真机「回复慢」归因，纯 JVM 可测，零 Android 依赖）。
 *
 * 背景：真机目视只能看到「发了但慢」，看不到慢在哪一段。把一条回复的完整链
 * 拆成可测段落后逐段计时，才能回答「是抓帧/OCR 慢、提示词组装慢、LLM 慢、
 * 抽取/过闸慢、拟人延迟长，还是 RPA 动作慢」：
 *
 * ```
 * 感知段（FrameDiffEngine）   wait → frame → ocr
 * 回合段（ScreenAdvisor）     prep → llm        （组装 + 大模型往返）
 * 治理段（WindowController）  echo → extract → gate → rewrite
 * 执行段（ActionDispatcher）  pace → nav → send
 * ```
 *
 * 渲染成一行 `auto_timing:llm=8123ms,send=2410ms` 同时落 logcat（TAG=[TAG]）
 * 与 advisor.db 台账（route=noop），真机复测按前缀 grep 即可定位瓶颈段。
 * 计时本身零成本量级（System.nanoTime），不参与任何决策。
 */
object AutoTiming {

    const val TAG = "AutoTiming"

    /** 台账/日志统一前缀：`grep auto_timing:` 一条链全见。 */
    const val PREFIX = "auto_timing:"

    /** 单条耗时账单的段数上限（防异常路径无限膨胀；超限静默丢弃后续段）。 */
    const val MAX_STAGES = 24

    /** 段名规范：小写字母数字下划线，≤24 字（台账 reason 里不带特殊字符）。 */
    fun sanitize(name: String): String = name.trim().lowercase()
        .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
        .joinToString("")
        .filter { it.isLetterOrDigit() || it == '_' }
        .take(24)
        .ifEmpty { "stage" }

    /** 渲染为 `auto_timing:a=1ms,b=2ms`；空账单返回空串（调用方据此跳过落库）。 */
    fun render(stages: List<Pair<String, Long>>): String =
        if (stages.isEmpty()) {
            ""
        } else {
            stages.joinToString(separator = ",", prefix = PREFIX) { (name, ms) ->
                "${sanitize(name)}=${ms.coerceAtLeast(0L)}ms"
            }
        }

    /** 段名前置序号（同一段多次测量各自成段：`llm1`/`llm2` 对应第几次重试）。 */
    fun indexed(name: String, index: Int): String = sanitize(name) + (index + 1)
}

/**
 * 一次链路的耗时累加器：`stage("ocr") { ... }` 记账并原样返回块结果，
 * 段名重复即多次成段（重试可见）。渲染口径见 [AutoTiming.render]。
 *
 * 线程纪律：无锁 ArrayList——调用方按单一链路（拉起的协程/循环内）使用；
 * 需要跨线程时由调用方自持锁，本类不引入额外同步开销。
 */
class TimingTrace {

    private val stages = ArrayList<Pair<String, Long>>()

    val isEmpty: Boolean get() = stages.isEmpty()

    /** 手工记账（已知耗时，如 delay 时长）。 */
    fun add(name: String, ms: Long): TimingTrace {
        if (stages.size < AutoTiming.MAX_STAGES) {
            stages += AutoTiming.sanitize(name) to ms.coerceAtLeast(0L)
        }
        return this
    }

    /** 同步段计时：异常照常抛出，耗时先记账（finally）。 */
    fun <T> stage(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            add(name, (System.nanoTime() - start) / 1_000_000)
        }
    }

    /** 挂起段计时（LLM/IO 段用）。 */
    suspend fun <T> stageSuspend(name: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            add(name, (System.nanoTime() - start) / 1_000_000)
        }
    }

    /** `auto_timing:...` 一行（空账单为空串，调用方跳过落库）。 */
    fun render(): String = AutoTiming.render(stages)

    /** 只读快照（单测断言用）。 */
    fun snapshot(): List<Pair<String, Long>> = stages.toList()
}
