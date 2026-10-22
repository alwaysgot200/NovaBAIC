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

package com.verlintas.baic2.floatball

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.device.impl.run.RunService
import com.verlintas.baic2.floatball.advisor.data.SCENE_NON_CHAT
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 画像页「参谋会话」Tab（2026-10-22 重构，§1.5 术语铁律）：该联系人名下
 * 全部参谋会话（主人与 LLM 的对话，内容落上游 conversations/messages，归属
 * 在 session_bindings）。搜索过滤、点击深链打开主应用；底部「新建参谋会话」
 * （关联同档案）与「关联已有会话」（把既有对话改绑到本联系人）。
 *
 * 关系口径：1 个参谋会话只绑 1 个联系人；1 个联系人可绑多个参谋会话。
 * 原 AdvisorConversationsCard（GoalTab 内嵌区块）逻辑并入本文件。
 */
@Composable
internal fun AdvisorSessionsTab(
    contactId: Long,
    scroll: androidx.compose.foundation.ScrollState?,
) {
    // scroll 仅用于滚动定位（同 GoalsTab 约定）；滚动由 PageScaffold 外层
    // verticalScroll(tabScroll) 统一负责——此处再套 verticalScroll 会与
    // 外层共享同一 ScrollState，高度塌陷为 0 导致整个列表不显示。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = advisorActivity()
    val contacts = rememberContactRepository()
    val conversations = rememberConversationRepository()

    data class SessionRow(
        val conversationId: Long,
        val title: String,
        val updatedAt: Long,
        val scene: String,
    )

    var rows by remember(contactId) { mutableStateOf<List<SessionRow>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember(contactId) { mutableStateOf("") }
    var pickOpen by remember { mutableStateOf(false) }

    LaunchedEffect(contactId, reload) {
        rows = runCatching {
            contacts.bindingsForContact(contactId).mapNotNull { binding ->
                conversations.get(binding.conversationId)?.let { conversation ->
                    SessionRow(
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

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.float_sessions_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // 操作按钮置顶（2026-10-22）：新建/关联等宽并排，列表在下。
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        // 手动新建：新会话关联同档案（agentId/标题取档案值）。
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
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.float_conversations_new))
            }
            OutlinedButton(
                onClick = { pickOpen = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.float_sessions_link_btn))
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                val keyword = query.trim()
                val visible = rows.filter { keyword.isEmpty() || it.title.contains(keyword, ignoreCase = true) }
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.float_conversations_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    visible.forEach { row ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clickable {
                                    // 主应用深链打开：FROM_FLOAT 免冷启动自动隐藏。
                                    // MainActivity=singleTop 且画像页盖在栈顶：必须
                                    // CLEAR_TOP+SINGLE_TOP 让 MainActivity 回栈顶收
                                    // onNewIntent，否则 Intent 只投递不切界面
                                    // （「点了没反应」，2026-10-21 真机实测教训）。
                                    context.startActivity(
                                        Intent(context, MainActivity::class.java).apply {
                                            addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                            )
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
                                    text = formatSessionTime(row.updatedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // scene 标识（方案 §1.2）：non_chat 场景条件展示。
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
            }
        }
    }

    if (pickOpen) {
        PickConversationDialog(
            contactId = contactId,
            onDismiss = { pickOpen = false },
            onLinked = {
                pickOpen = false
                reload++
            },
        )
    }
}

/**
 * 「关联已有会话」挑选器：全量会话（更新时间倒序）+ 搜索过滤；点击把该会话
 * 改绑到本联系人（rebind：一参谋会话一联系人，原绑定被接管）。
 */
@Composable
private fun PickConversationDialog(
    contactId: Long,
    onDismiss: () -> Unit,
    onLinked: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts = rememberContactRepository()
    val conversations = rememberConversationRepository()
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<Triple<Long, String, Long>>>(emptyList()) }

    LaunchedEffect(Unit) {
        all = runCatching {
            conversations.observeConversations().first()
                .map { Triple(it.conversation.id, it.conversation.title, it.conversation.updatedAt) }
                .sortedByDescending { it.third }
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_sessions_link_existing)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.float_sessions_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val keyword = query.trim()
                val visible = all.filter { keyword.isEmpty() || it.second.contains(keyword, ignoreCase = true) }
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.float_conversations_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        visible.take(50).forEach { (id, title, updatedAt) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable {
                                        scope.launch {
                                            runCatching { contacts.rebindConversation(id, contactId) }
                                            onLinked()
                                        }
                                    },
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title.ifBlank {
                                            context.getString(R.string.float_conversations_untitled)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = formatSessionTime(updatedAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_profile_create_cancel))
            }
        },
    )
}

/** 时间格式器静态化（F11）：今天给时分，今年给月日，更早带年份。 */
private val TIME_FORMAT_TODAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val TIME_FORMAT_THIS_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private val TIME_FORMAT_WITH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

private fun formatSessionTime(at: Long): String {
    if (at <= 0L) return ""
    val zoned = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val date = zoned.toLocalDate()
    val today = LocalDate.now(ZoneId.systemDefault())
    return when {
        date == today -> TIME_FORMAT_TODAY.format(zoned)
        date.year == today.year -> TIME_FORMAT_THIS_YEAR.format(date)
        else -> TIME_FORMAT_WITH_YEAR.format(date)
    }
}
