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

import com.verlintas.baic2.floatball.advisor.data.ENGAGEMENT_REACTIVE

/**
 * 当前前台聊天页所绑定的联系人（v3 联系人档案的参谋侧投影）。
 *
 * @param contactId v3 AdvisorDatabase contact_profiles 主键。
 * @param displayName 主显示名（记忆 entities 召回的首选线索）。
 * @param aliases 全部别名（旧名也必须参与召回，改名前写下的笔记挂的是旧标签）。
 * @param goal 业务目标提示词，可空（Stub 档案）。
 * @param style 按对象覆盖的语言风格，可空。
 * @param engagement 维持度三档（v10，方案 §2.6）：通知链 mute 静默、PROACTIVE 检点
 *   只对 proactive 档执行的裁决输入；未知/历史投影缺省 reactive。
 */
data class ContactRef(
    val contactId: Long,
    val displayName: String,
    val aliases: List<String>,
    val goal: String?,
    val style: String?,
    val engagement: String = ENGAGEMENT_REACTIVE,
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
 * 一条联系人↔会话绑定（V-B，UltraAdvisor v2.2 方案 §2.3/§3）：相似度回看
 * 与画像页「该联系人的会话列表」共用的最小投影。
 *
 * @param conversationId 上游会话 id。
 * @param scene 会话归属场景（v10 §1.2）；联系人绑定行恒为 chat（markScene 守卫）。
 * @param boundAt 绑定创建时间（列表新→旧排序用）。
 */
data class BoundConversation(
    val conversationId: Long,
    val scene: String,
    val boundAt: Long,
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
     * [allowStub]=false 时只认既有键/别名（内容级兜底），绝不懒建新档案。
     */
    suspend fun resolve(imPackage: String?, rawTitle: String?, allowStub: Boolean = true): ContactRef?

    /**
     * 群档案解析（v10，方案 §2.1 GROUP 态）：键匹配/去重与 [resolve] 同径；
     * 懒建时新档案直接落群壳（isGroup=true + contactType=group，群名即档案名，
     * 不做成员级画像），命中既有档案则原样返回（不改其群属性）。重名/模糊
     * 近邻不自动建群，返回 null。
     */
    suspend fun resolveGroup(imPackage: String?, rawTitle: String?): ContactRef?

    /**
     * 会话归属场景落行（v10，方案 §1.2）：非聊天屏孤立会话标记 non_chat；
     * 绑定联系人的会话保持/复位 chat。任何失败由调用方静默降级。
     */
    suspend fun markScene(conversationId: Long, scene: String)

    /** 该联系人的专属老参谋会话 id；不存在则创建并完成绑定。 */
    suspend fun ensureAdvisorConversation(contact: ContactRef): Long

    /**
     * 显式创建一条新会话并关联同档案（V-B，方案 §3「手动新建/断崖新开」行）：
     * 与 [ensureAdvisorConversation] 的差异是**无视既有绑定**直接新建——
     * 断崖新开与画像页手动新建都走这里，新会话 agentId/标题取档案值。
     * 档案缺失（被合并/删除）抛错，由调用方按失败处理，不新建孤儿会话。
     */
    suspend fun createConversationFor(contact: ContactRef): Long

    /** 该联系人名下全部老参谋会话 id（含联系人合并带来的多个）。 */
    suspend fun conversationIds(contactId: Long): List<Long>

    /**
     * 该联系人名下全部绑定会话（新→旧；V-B 相似度回看与画像页会话列表共用）。
     * 含联系人合并带来的多个会话；绑定行存在但上游会话可能已被删除，由调用方
     * 按需校验。
     */
    suspend fun boundConversations(contactId: Long): List<BoundConversation>

    /** 会话归属场景读（v10 §1.2 / V-B 池隔离）：无绑定行按 chat 口径（不臆测）。 */
    suspend fun sceneFor(conversationId: Long): String

    /** 取该联系人的滚动摘要；从未生成返回 null。 */
    suspend fun digest(contactId: Long): ContactDigest?

    /** 存该联系人的滚动摘要（回合配对数由实现侧或调用方维护均可）。 */
    suspend fun saveDigest(contactId: Long, digest: String, coveredTurns: Int)

    /**
     * P3 §12.2：帧感知确认「对方新来信」（文本指纹确已变化）后写来信时间戳，
     * 供 24h 中性时序信号消费。仅绑定会话生效；任何失败由调用方静默降级。
     */
    suspend fun markIncomingAt(conversationId: Long, at: Long)

    /**
     * 读对方最后来信时间（V-C §4.3 heatScore 时延信号）：无绑定行/从未更新
     * 返回 null（无信号，调用方跳过不臆测）。
     */
    suspend fun lastIncomingAt(conversationId: Long): Long?

    /**
     * 反馈热度增量落分（V-C §4.3）：调用方先用 [HeatRules] 算出增量再传入；
     * 无档案静默忽略。两路信号源（连续模式差分 / 通知链）经此统一落点。
     */
    suspend fun recordHeatDelta(contactId: Long, delta: Int)
}
