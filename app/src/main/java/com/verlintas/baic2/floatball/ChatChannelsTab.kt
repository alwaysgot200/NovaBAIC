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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.advisor.data.ChatChannelEntity
import kotlinx.coroutines.launch

/**
 * 画像页「聊天会话」Tab（2026-10-22 重构，§1.5 术语铁律）：该联系人在各
 * IM 里的真实聊天归属（chat_channels——只记「哪个 APP 的哪个聊天对象」，
 * 一概不存聊天内容）。搜索过滤；点击由 ImChatNavigator 直达该 APP 的聊天
 * 页（RPA 自动化打开的正确目标；a11y 未开/失败 → toast 报因并指引手动，
 * §4.5 铁律）。一个联系人可关联多个 APP 下的多个聊天会话。
 */
@Composable
internal fun ChatChannelsTab(
    contactId: Long,
    scroll: androidx.compose.foundation.ScrollState?,
) {
    // 同 AdvisorSessionsTab：滚动由 PageScaffold 外层负责，勿再套 verticalScroll。
    val scope = rememberCoroutineScope()
    val activity = advisorActivity()
    val contacts = rememberContactRepository()
    val imNavigator = activity.imNavigator
    var channels by remember(contactId) { mutableStateOf<List<ChatChannelEntity>>(emptyList()) }
    var query by remember(contactId) { mutableStateOf("") }
    var addOpen by remember { mutableStateOf(false) }

    LaunchedEffect(contactId) {
        channels = runCatching { contacts.channelsOf(contactId) }.getOrDefault(emptyList())
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        // 操作按钮置顶（2026-10-22）：搜索框 + 新增同行，列表在下。
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.float_channels_search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { addOpen = true }) {
                Text(stringResource(R.string.float_channels_add_btn))
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
                val visible = channels.filter {
                    keyword.isEmpty() || it.displayName.contains(keyword, ignoreCase = true)
                }
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.float_bind_channels_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    visible.forEach { channel ->
                        val appLabelRes = FloatUi.IM_APPS.firstOrNull { it.first == channel.imPackage }?.second
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text(
                                text = (appLabelRes?.let { stringResource(it) } ?: channel.imPackage) +
                                    "：" + channel.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    // 直达该 APP 的该聊天页；失败 toast 报因（§4.5.2）。
                                    val result = imNavigator.openChat(channel.imPackage, channel.displayName)
                                    imNavigator.toastResult(result, channel.displayName)
                                }
                            }) {
                                Text(stringResource(R.string.float_channels_open))
                            }
                        }
                    }
                }
            }
        }
    }

    if (addOpen) {
        AddChannelDialog(
            contactId = contactId,
            onDone = {
                addOpen = false
                scope.launch {
                    channels = runCatching { contacts.channelsOf(contactId) }.getOrDefault(channels)
                }
            },
        )
    }
}

/**
 * 「新增聊天会话」：选 APP + 输入聊天对象在该 APP 内的显示名 →
 * [com.verlintas.baic2.floatball.advisor.ContactRepository.recordChannel]
 * 落 chat_channels 行（同一联系人 + APP + 对象已存在时由上层 upsert 语义去重）。
 */
@Composable
private fun AddChannelDialog(
    contactId: Long,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val contacts = rememberContactRepository()
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf(FloatUi.IM_APPS.first().first) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.float_channels_add)) },
        text = {
            // text 不自动纵向排列，显式 Column（同新建联系人弹窗修复）。
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.float_channels_add_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowChips(
                    options = FloatUi.IM_APPS,
                    selected = pkg,
                    onSelect = { pkg = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) return@TextButton
                    scope.launch {
                        runCatching { contacts.recordChannel(contactId, pkg, trimmed) }
                        onDone()
                    }
                },
            ) { Text(stringResource(R.string.float_profile_create_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.float_profile_create_cancel))
            }
        },
    )
}
