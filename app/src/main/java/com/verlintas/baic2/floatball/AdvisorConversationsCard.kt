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
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.EXTRA_FROM_FLOAT
import com.verlintas.baic2.MainActivity
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.device.impl.run.RunService
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.data.SCENE_NON_CHAT
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 画像页（GoalTab）的「该联系人的会话列表」区块（V-B，UltraAdvisor v2.2 方案
 * §2.3/§3）：列出该档案名下全部绑定会话（标题 + 最后活跃时间 + scene 标识），
 * 点击在主应用打开该会话（MainActivity 的 `open_conversation_id` 深链，带
 * [EXTRA_FROM_FLOAT] 免触发冷启动自动隐藏）；方案 §3「手动新建」行的入口也
 * 在此——新会话经 [ContactRepository.bindConversation] 关联同档案。
 *
 * 独立成文件而非内嵌 GoalTab：既有大文件只做一行挂载，保持追加式 diff
 * （与 [AdvisorPersonaCard] 同款做法）。分屏内的会话切换器现状不存在
 * （V-B 核实结论，方案 §2.3 第二点留待后续），列表点击统一走主应用深链
 * 这一最简可用交互；分屏上下文切换留给 B5 后续。
 */
@Composable
internal fun AdvisorConversationsCard(
    contactId: Long,
    contacts: ContactRepository,
    conversations: ConversationRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    data class ConversationRow(
        val conversationId: Long,
        val title: String,
        val updatedAt: Long,
        val scene: String,
    )

    var rows by remember(contactId) { mutableStateOf<List<ConversationRow>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(contactId, reload) {
        rows = runCatching {
            contacts.bindingsForContact(contactId).mapNotNull { binding ->
                conversations.get(binding.conversationId)?.let { conversation ->
                    ConversationRow(
                        conversationId = conversation.id,
                        title = conversation.title.ifBlank {
                            context.getString(R.string.float_conversations_untitled)
                        },
                        updatedAt = conversation.updatedAt,
                        scene = binding.scene,
                    )
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.float_conversations_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val list = rows
            if (list == null) {
                // 首次装载：不打扰布局，留空即可（下一帧 LaunchedEffect 回填）。
            } else if (list.isEmpty()) {
                Text(
                    text = stringResource(R.string.float_conversations_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                list.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable {
                                // 主应用深链打开（最简可用交互）：FROM_FLOAT 免冷启动
                                // 自动隐藏；分屏内切换上下文留待后续（B5 核实结论）。
                                context.startActivity(
                                    Intent(context, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        putExtra(EXTRA_FROM_FLOAT, true)
                                        putExtra(RunService.EXTRA_OPEN_CONVERSATION_ID, row.conversationId)
                                    },
                                )
                            },
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = formatConversationTime(row.updatedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // scene 标识（方案 §1.2）：联系人绑定行恒 chat（markScene 守卫），
                        // 此处按 non_chat 场景条件展示，覆盖孤立会话被手动关联前的边界。
                        if (row.scene == SCENE_NON_CHAT) {
                            Text(
                                text = stringResource(R.string.float_conversations_scene_non_chat),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    scope.launch {
                        // 方案 §3「手动新建」行：新会话关联同档案（agentId/标题取档案值）。
                        runCatching {
                            val profile = contacts.profile(contactId) ?: error("contact_gone")
                            val id = conversations.create(profile.agentId, AppMode.CHAT, profile.displayName)
                            contacts.bindConversation(id, contactId)
                        }.onSuccess { reload++ }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    R.string.float_conversations_fail,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.float_conversations_new))
            }
        }
    }
}

/** 最后活跃时间：今天给时分，今年给月日，更早带年份。 */
private fun formatConversationTime(at: Long): String {
    if (at <= 0L) return ""
    val zoned = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val date = zoned.toLocalDate()
    val today = LocalDate.now(ZoneId.systemDefault())
    return when {
        date == today -> DateTimeFormatter.ofPattern("HH:mm").format(zoned)
        date.year == today.year -> DateTimeFormatter.ofPattern("M月d日").format(date)
        else -> DateTimeFormatter.ofPattern("yyyy年M月d日").format(date)
    }
}
