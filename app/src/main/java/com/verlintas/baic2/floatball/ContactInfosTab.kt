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

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.floatball.advisor.ContactInfoRules
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_ADDRESS
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_EMAIL
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_ID_DOC
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_IM
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_OTHER
import com.verlintas.baic2.floatball.advisor.data.CONTACT_INFO_PHONE
import com.verlintas.baic2.floatball.advisor.data.ContactInfoEntity
import kotlinx.coroutines.launch

/** 联系方式分类（UI 中文口径；顺序即分组展示顺序）。 */
private data class InfoKindDef(val value: String, val labelRes: Int)

private val INFO_KINDS = listOf(
    InfoKindDef(CONTACT_INFO_PHONE, R.string.float_info_kind_phone),
    InfoKindDef(CONTACT_INFO_EMAIL, R.string.float_info_kind_email),
    InfoKindDef(CONTACT_INFO_ADDRESS, R.string.float_info_kind_address),
    InfoKindDef(CONTACT_INFO_ID_DOC, R.string.float_info_kind_id_doc),
    InfoKindDef(CONTACT_INFO_IM, R.string.float_info_kind_im),
    InfoKindDef(CONTACT_INFO_OTHER, R.string.float_info_kind_other),
)

/**
 * 画像页「联系方式」Tab（2026-10-23 需求 3，方案 §3.3）：电话/邮箱/地点/证件/
 * IM 账号/其他，一个联系人 N 条（contact_infos 1:N）。手动添加/编辑/删除即确认；
 * 聊天中 AI 萃取的联系方式落「待确认」草稿（source=llm），人工确认后固化并
 * 双写上游长期记忆（[com.verlintas.baic2.floatball.advisor.ProfileMemorySync]），
 * 丢弃即删草稿——AI 只提案，人工确认才生效。UI 不直连 DAO，写路径全经
 * [com.verlintas.baic2.floatball.advisor.ContactRepository]。
 */
@Composable
internal fun ContactInfosTab(
    contactId: Long,
    scroll: androidx.compose.foundation.ScrollState?,
) {
    // 同 AdvisorSessionsTab：滚动由 PageScaffold 外层负责，勿再套 verticalScroll。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts = rememberContactRepository()
    val profileSync = rememberProfileSync()
    val infos by contacts.observeInfos(contactId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ContactInfoEntity?>(null) }
    var deleting by remember { mutableStateOf<ContactInfoEntity?>(null) }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.float_info_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { addOpen = true }) {
                Text(stringResource(R.string.float_info_add))
            }
        }

        // AI 待确认草稿区（来源徽章 + 确认/丢弃）：确认走双写，丢弃只删草稿。
        val drafts = infos.filter { !it.confirmed }
        if (drafts.isNotEmpty()) {
            InfoGroupHeader(stringResource(R.string.float_info_draft_badge), drafts.size)
            drafts.forEach { row ->
                InfoCard(
                    info = row,
                    onConfirm = {
                        scope.launch {
                            // 确认即固化 + 双写上游长期记忆（PROFILE 稳定事实）。
                            profileSync.confirmInfoAndSync(contactId, row.id)
                            Toast.makeText(
                                context,
                                context.getString(R.string.float_info_confirmed_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onDiscard = {
                        scope.launch { contacts.rejectInfoDraft(row.id) }
                    },
                    onEdit = { editing = row },
                    onDelete = { deleting = row },
                )
            }
        }

        if (infos.isEmpty()) {
            Text(
                text = stringResource(R.string.float_info_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        // kind 分组卡片（词表顺序）；词表外历史 kind 兜底进「其他」组，不静默丢。
        val groups = infos.filter { it.confirmed }.groupBy { it.kind }
        INFO_KINDS.forEach { kind ->
            val rows = groups[kind.value].orEmpty()
            if (rows.isNotEmpty()) {
                InfoGroupHeader(stringResource(kind.labelRes), rows.size)
                rows.forEach { row ->
                    InfoCard(
                        info = row,
                        onConfirm = null,
                        onDiscard = null,
                        onEdit = { editing = row },
                        onDelete = { deleting = row },
                    )
                }
            }
        }
        val leftovers = groups.filterKeys { key -> INFO_KINDS.none { it.value == key } }
            .values.flatten()
        if (leftovers.isNotEmpty()) {
            InfoGroupHeader(stringResource(R.string.float_info_kind_other), leftovers.size)
            leftovers.forEach { row ->
                InfoCard(
                    info = row,
                    onConfirm = null,
                    onDiscard = null,
                    onEdit = { editing = row },
                    onDelete = { deleting = row },
                )
            }
        }
    }

    if (addOpen) {
        InfoEditDialog(
            contactId = contactId,
            existing = null,
            onDismiss = { addOpen = false },
        )
    }
    editing?.let { target ->
        InfoEditDialog(
            contactId = contactId,
            existing = target,
            onDismiss = { editing = null },
        )
    }
    deleting?.let { target ->
        DeleteConfirmDialog(
            title = stringResource(R.string.float_info_delete_title),
            message = stringResource(R.string.float_info_delete_hint),
            confirmText = stringResource(R.string.float_delete),
            onConfirm = {
                deleting = null
                scope.launch { contacts.deleteInfo(target.id) }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun InfoGroupHeader(text: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 单条联系方式卡片：值 + 标签/备注 + 来源徽章（「AI 待确认」出确认/丢弃，
 * 「已确认」出编辑）+ 行内删除。草稿卡淡色底衬与确认卡区分。
 */
@Composable
private fun InfoCard(
    info: ContactInfoEntity,
    onConfirm: (() -> Unit)?,
    onDiscard: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (info.confirmed) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info.value,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (info.confirmed) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (info.confirmed) {
                                R.string.float_info_confirmed_badge
                            } else {
                                R.string.float_info_draft_badge
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (info.confirmed) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            val meta = listOf(info.label, info.note)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!info.confirmed && !info.evidence.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.float_info_evidence, info.evidence),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!info.confirmed && onConfirm != null && onDiscard != null) {
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(R.string.float_info_confirm))
                    }
                    TextButton(onClick = onDiscard) {
                        Text(stringResource(R.string.float_info_discard))
                    }
                } else {
                    TextButton(onClick = onEdit) {
                        Text(stringResource(R.string.float_info_edit))
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.float_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 添加/编辑联系方式弹窗（kind 芯片 + 标签 + 值 + 备注）：手动保存即
 * source=user、confirmed；成功后双写上游记忆（syncInfo）。值不合法/撞
 * 唯一键分别报因，不静默吞错。
 */
@Composable
private fun InfoEditDialog(
    contactId: Long,
    existing: ContactInfoEntity?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts = rememberContactRepository()
    val profileSync = rememberProfileSync()
    var kind by remember(existing) { mutableStateOf(existing?.kind ?: CONTACT_INFO_PHONE) }
    var label by remember(existing) { mutableStateOf(existing?.label.orEmpty()) }
    var value by remember(existing) { mutableStateOf(existing?.value.orEmpty()) }
    var note by remember(existing) { mutableStateOf(existing?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.float_info_add_title else R.string.float_info_edit_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.float_info_kind_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowChips(
                    options = INFO_KINDS.map { it.value to it.labelRes },
                    selected = kind,
                    onSelect = { kind = it },
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.float_info_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.float_info_value_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.float_info_note_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ContactInfoRules.validManual(value.trim()),
                onClick = {
                    scope.launch {
                        val result = runCatching {
                            if (existing == null) {
                                contacts.addInfo(contactId, kind, label, value, note)
                            } else {
                                contacts.updateInfo(existing.id, kind, label, value, note)
                            }
                        }
                        val saved = result.getOrNull()
                        when {
                            saved != null -> {
                                runCatching { profileSync.syncInfo(contactId, saved) }
                                onDismiss()
                            }
                            // 撞唯一键（同分类同值）→ 约束异常上抛到这里报因。
                            result.exceptionOrNull() != null ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.float_info_dup),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            else ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.float_info_invalid),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                },
            ) { Text(stringResource(R.string.float_profile_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.float_profile_cancel))
            }
        },
    )
}
