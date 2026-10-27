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

package com.verlintas.baic2.floatball.auto

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动发送的**唯一回复出口**（托管方案 v8 §二十四.5）。
 *
 * 所有会“把文字发出去”的路径（陪伴窗口、多会话调度、起草通知的一键发送）
 * 都从这里取稿，保证纪律只有一条：
 *
 * 1. 先做确定性抽取（`<reply>` 标记 / 编号建议，零成本）；
 * 2. 抽不到 → 一次「代言者修复调用」写一条纯回复；
 * 3. 仍拿不到 → 返回 null，调用方降级起草或跳过并落台账；
 * **任何情况下都不把参谋分析全文当发送正文。**
 */
@Singleton
class ReplyResolver @Inject constructor(
    private val composer: ReplyComposeSource,
) {

    /**
     * @param full 参谋回合落库全文（分析 + 可能的 `<reply>` 块）。
     * @param incoming 对方最新来信（无来信的主动开场传 null）。
     * @param recentSent 近期已发的纯回复（防复读；由调用方先抽取）。
     * @param allowCompose false 时只做零成本抽取（起草通知等不想多付一次调用的路径）。
     */
    suspend fun resolve(
        full: String?,
        answerTargetChars: Int,
        incoming: String? = null,
        recentSent: List<String> = emptyList(),
        allowCompose: Boolean = true,
    ): SendableDraft? {
        SendableDraftExtractor.extract(full, answerTargetChars)?.let { return it }
        if (!allowCompose) return null
        val composed = composer.compose(
            incoming = incoming,
            analysis = full,
            recentSent = recentSent,
            answerTargetChars = answerTargetChars,
        ) ?: return null
        return composed.copy(source = SendableDraftExtractor.SOURCE_COMPOSED)
    }

    /** 零成本预览抽取（分屏/台账展示用；不触发任何 LLM 调用）。 */
    fun preview(full: String?, answerTargetChars: Int): SendableDraft? =
        SendableDraftExtractor.extract(full, answerTargetChars)

    /**
     * 审查拦截后的合规改写（2026-10-27，自动档专属重试入口）：把命中
     * 军师禁令/隐私红线的表述删掉后仍以纯回复形态返回；改写未实现/失败
     * 返回 null，调用方按原拒绝路径降级，绝不擅自发送原稿。
     */
    suspend fun rewriteForGate(
        draft: String,
        incoming: String?,
        constraints: String,
        answerTargetChars: Int,
    ): SendableDraft? = composer.rewrite(
        draft = draft,
        incoming = incoming,
        constraints = constraints,
        answerTargetChars = answerTargetChars,
    )
}
