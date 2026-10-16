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

package com.verlintas.baic2.floatball.contact

/**
 * 当前前台聊天页所绑定的联系人（v3 联系人档案的参谋侧投影）。
 *
 * @param contactId v3 AdvisorDatabase contact_profiles 主键。
 * @param displayName 主显示名（记忆 entities 召回的首选线索）。
 * @param aliases 全部别名（旧名也必须参与召回，改名前写下的笔记挂的是旧标签）。
 * @param goal 业务目标提示词，可空（Stub 档案）。
 * @param style 按对象覆盖的语言风格，可空。
 */
data class ContactRef(
    val contactId: Long,
    val displayName: String,
    val aliases: List<String>,
    val goal: String?,
    val style: String?,
)

/**
 * 按联系人存储的滚动摘要（v3 session_bindings 上的 digest 三列投影）。
 */
data class ContactDigest(
    val digest: String,
    val coveredTurns: Int,
    val updatedAt: Long,
)

/**
 * 联系人档案目录——老参谋管道的**消费端契约**（设计文档 §6.3–6.6）。
 *
 * 唯一实现为 advisor 包的 `AdvisorContactDirectory`（经 AdvisorDirectoryModule
 * 在 Hilt 绑定）；[resolve] 未绑定、无法识别或不是 IM 聊天页时返回 null，
 * 老参谋回退共享会话，行为与未绑定时完全一致。
 *
 * 实现方注意：
 * - [resolve] 的标题规范化（未读角标/群人数/壳后缀）在实现侧完成，
 *   调用方只给前台包名与原始窗口标题；
 * - [ensureAdvisorConversation] 需要创建会话时，标题用联系人主名，
 *   agentId 取档案绑定值，创建后写入 session_bindings；
 * - 合并联系人后 [conversationIds] 必须返回目标档案名下全部会话。
 */
interface ContactDirectory {

    /**
     * 解析当前前台页面对应的已绑定联系人；未绑定、无法识别或不是 IM
     * 聊天页时返回 null（调用方据此回退共享会话，不阻断参谋）。
     */
    suspend fun resolve(imPackage: String?, rawTitle: String?): ContactRef?

    /** 该联系人的专属老参谋会话 id；不存在则创建并完成绑定。 */
    suspend fun ensureAdvisorConversation(contact: ContactRef): Long

    /** 该联系人名下全部老参谋会话 id（含联系人合并带来的多个）。 */
    suspend fun conversationIds(contactId: Long): List<Long>

    /** 取该联系人的滚动摘要；从未生成返回 null。 */
    suspend fun digest(contactId: Long): ContactDigest?

    /** 存该联系人的滚动摘要（回合配对数由实现侧或调用方维护均可）。 */
    suspend fun saveDigest(contactId: Long, digest: String, coveredTurns: Int)

    /**
     * P3 §12.2：帧感知确认「对方新来信」（文本指纹确已变化）后写来信时间戳，
     * 供 24h 中性时序信号消费。仅绑定会话生效；任何失败由调用方静默降级。
     */
    suspend fun markIncomingAt(conversationId: Long, at: Long)
}
