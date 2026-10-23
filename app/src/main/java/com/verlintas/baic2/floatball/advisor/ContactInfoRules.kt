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

package com.verlintas.baic2.floatball.advisor

import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_ADDRESS
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_EMAIL
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_ID_DOC
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_IM
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_KINDS
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_OTHER
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_PHONE

/**
 * 联系方式取值规则（2026-10-23，纯函数）：kind 归一 + 值校验。
 *
 * 纪律：手动录入走 [validManual]（宽松，不拦用户的记法）；AI 萃取走
 * [validExtracted]（严格，电话/邮件/证件过正则，杜绝把聊天里的数字乱记成
 * 联系方式）。校验不过的萃取值直接丢弃，绝不落草稿。
 */
object ContactInfoRules {

    /** 单值长度上限（与画像字段 VALUE_MAX_CHARS 同口径）。 */
    const val VALUE_MAX_CHARS = 80

    /** kind 归一：词表外返回 null（调用方丢弃或回退 other）。 */
    fun normalizeKind(raw: String?): String? {
        val kind = raw?.trim()?.lowercase() ?: return null
        return CONTACT_INFO_KINDS.firstOrNull { it == kind }
    }

    /** 手动录入宽松校验：非空即可。 */
    fun validManual(value: String): Boolean =
        value.trim().isNotEmpty() && value.trim().length <= VALUE_MAX_CHARS

    /** AI 萃取严格校验：电话/邮件/证件过正则；其余非空即可。 */
    fun validExtracted(kind: String, value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.length > VALUE_MAX_CHARS) return false
        return when (kind) {
            CONTACT_INFO_PHONE -> PHONE_REGEX.containsMatchIn(v)
            CONTACT_INFO_EMAIL -> EMAIL_REGEX.matches(v)
            CONTACT_INFO_ID_DOC -> ID_DOC_REGEX.matches(v)
            else -> true
        }
    }

    /** 记忆/提示词里的非 UI 中文口径（UI 文案走 strings.xml）。 */
    fun kindWord(kind: String): String = when (kind) {
        CONTACT_INFO_PHONE -> "电话"
        CONTACT_INFO_EMAIL -> "邮箱"
        CONTACT_INFO_ADDRESS -> "地址"
        CONTACT_INFO_ID_DOC -> "证件"
        CONTACT_INFO_IM -> "IM 账号"
        else -> "联系方式"
    }

    private val PHONE_REGEX = Regex("(?<!\\d)(1[3-9]\\d{9}|\\d{3,4}-?\\d{7,8})(?!\\d)")
    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val ID_DOC_REGEX = Regex("\\d{15}(\\d{2}[0-9Xx])?")
}
