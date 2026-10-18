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

import com.verlintas.baic2.floatball.director.NotificationGate

/**
 * 场景分类门（v10，UltraAdvisor v2.2 方案 §1）：感知产物流水线第一步，
 * 先于联系人解析回答「这块屏幕是不是聊天界面」——非聊天屏零人设管线
 * （不查档案、不建卡、不进保温/目标），杜绝桌面/新闻屏上「未绑定联系人」
 * 的人设暗示。
 *
 * 输入口径与感知阶梯同序（方案 §1.1）：a11y 文本优先，a11y 提取不到时
 * OCR 兜底——判定逻辑不区分文本来自哪一层，调用方把前台包名 + 屏幕全文
 * 交给 [classify] 即可。纯函数、无 Android 依赖，JVM 可直接单测。
 *
 * 判定规则（确定性优先，方案 §1.3）：
 * - 前台包名 ∈ IM 白名单（与 M2 NotificationGate 同源常量）且
 *   [IncomingCue.latestIncoming] 命中聊天结构 → [Scene.CHAT]；
 * - 包名 ∉ 白名单 → [Scene.NON_CHAT]（桌面/新闻/浏览器/设置页一票否决，
 *   宁可漏一次关联，不可错建一次人设）；
 * - IM 包名命中但结构识别失败（截图时机不佳/a11y 噪声）→ [Scene.UNCERTAIN]，
 *   调用方走轻 LLM 一问兜底（≤80tok「这是聊天界面吗」，放 ScreenAdvisor 接线层）；
 *   LLM 失败/超时保守判 NON_CHAT。
 */
internal object SceneGate {

    /** 分类结果三态。 */
    sealed interface Scene {
        /** 聊天屏：进实体判别与联系人管线。 */
        data object Chat : Scene

        /** 非聊天屏：孤立参谋会话，零人设管线。 */
        data object NonChat : Scene

        /** 模糊：IM 包名命中但无对话结构，交轻 LLM 一问兜底。 */
        data object Uncertain : Scene
    }

    fun classify(foregroundPackage: String?, screenBody: String?): Scene {
        if (foregroundPackage.isNullOrBlank()) {
            // 包名缺失（a11y/感知降级）时无法做白名单裁决：有聊天结构保守
            // 给 UNCERTAIN（走轻 LLM 兜底），否则 NON_CHAT——绝不据此进人设管线。
            return if (hasChatStructure(screenBody)) Scene.Uncertain else Scene.NonChat
        }
        if (foregroundPackage !in NotificationGate.LISTENED_PACKAGES) return Scene.NonChat
        return if (hasChatStructure(screenBody)) Scene.Chat else Scene.Uncertain
    }

    /** 聊天结构判定：复用 IncomingCue 的来信候选（时间分割/结构占位/界面词强标记）。 */
    private fun hasChatStructure(screenBody: String?): Boolean =
        !screenBody.isNullOrBlank() && IncomingCue.latestIncoming(screenBody) != null
}

/**
 * UNCERTAIN 的轻 LLM 一问兜底（v10 方案 §1.3，F10 接线在 ScreenAdvisor 主链路）：
 * IM 白名单命中但结构识别失败（截图时机不佳/a11y 噪声）时，以 ≤[MAX_TOKENS] tok
 * 的一次判定问答补全场景分类——明确 yes 才按 CHAT 进联系人管线，no/超时/畸形
 * 一律保守判 NON_CHAT（新版微信改版不致漏关联）。提示词与解析纯函数、JVM 可单测。
 */
internal object UncertainPrompts {

    internal const val MAX_TOKENS = 100

    /** 屏幕正文进 prompt 的长度上限（超长截断，判定界面形态足够）。 */
    internal const val BODY_MAX_CHARS = 800

    fun systemPrompt(): String =
        "你是手机屏幕场景判定器。判断给定文本是否像一对一聊天界面（有对话双方的消息）。" +
            "只回答 yes 或 no，不要输出任何其他字符。"

    fun userPrompt(screenBody: String): String =
        "以下是手机屏幕的全部文本：\n" +
            screenBody.trim().take(BODY_MAX_CHARS) +
            "\n该屏文本是否像一对一聊天界面？只回答 yes 或 no。"

    /**
     * 宽松但保守的解析：trim/小写后以 yes 开头（或恰为「是」）才判聊天；
     * no/空白/超时截断/畸形一律 false（保守降级 NON_CHAT，绝不误进人设管线）。
     */
    fun isChat(raw: String?): Boolean {
        val text = raw?.trim()?.lowercase() ?: return false
        return text.startsWith("yes") || text == "是"
    }
}
