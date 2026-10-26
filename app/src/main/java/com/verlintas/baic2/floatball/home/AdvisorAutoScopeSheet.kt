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

package com.verlintas.baic2.floatball.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.verlintas.baic2.R
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.floatball.advisor.data.ChatChannelEntity
import com.verlintas.baic2.floatball.advisor.data.ContactProfileEntity
import com.verlintas.baic2.floatball.auto.AutoSelectionStore

/**
 * 自动托管范围选择弹层（方案 §5.3）：`APP → 联系人会话` 两级多选。
 *
 * 只负责「选了谁」：运行条件在 [AdvisorHomeBar] 的 commit 中复核，执行侧由
 * `AutoSessionScheduler` 承担；本弹层不订阅运行状态、不发起任何 RPA 动作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvisorAutoScopeSheet(
    onDismiss: () -> Unit,
    onStart: (AutoSelectionStore.Selection) -> Unit,
) {
    val context = LocalContext.current
    val entry = rememberAdvisorHomeEntry()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var entries by remember {
        mutableStateOf<List<Pair<ContactProfileEntity, List<ChatChannelEntity>>>?>(null)
    }
    var allContacts by remember { mutableStateOf(false) }
    var selected by remember {
        mutableStateOf(entry.selectionStore().current.value.targets.toSet())
    }
    var appLevel by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var limitHint by remember { mutableStateOf(false) }

    LaunchedEffect(entry) {
        entries = entry.contactRepository().autoScopeEntries()
    }

    // 选中的目标：同一联系人在不同 APP = 多个会话，可同时勾选。
    val channelRows = remember(entries) { entries.orEmpty().flattenRows() }
    val byApp = remember(channelRows) { channelRows.groupBy { it.channel.imPackage } }
    val listenerEnabled = remember(context) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    val sessionCount = selected.size
    val contactCount = selected.map { it.contactId }.distinct().size
    val overLimit = sessionCount > MAX_SESSIONS || contactCount > MAX_CONTACTS

    fun toggle(target: AutoSelectionStore.Target) {
        if (target in selected) {
            selected = selected - target
            limitHint = false
            return
        }
        if (sessionCount + 1 > MAX_SESSIONS || (target.contactId !in selected.map { it.contactId } &&
                contactCount + 1 > MAX_CONTACTS)
        ) {
            limitHint = true
            return
        }
        allContacts = false
        selected = selected + target
        limitHint = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Baic2Spacing.xl),
        ) {
            val currentApp = appLevel
            if (currentApp == null) {
                SheetHeader(
                    title = stringResource(R.string.float_home_scope_title),
                    subtitle = stringResource(R.string.float_home_scope_subtitle),
                )
                if (!listenerEnabled) {
                    Spacer(Modifier.height(Baic2Spacing.md))
                    ListenerWarning(onOpen = { context.openNotificationListenerSettings() })
                }
            } else {
                SecondLevelHeader(
                    appLabel = appLabel(context, currentApp),
                    selected = byApp[currentApp].orEmpty().count { it.target in selected },
                    total = byApp[currentApp].orEmpty().size,
                    onBack = {
                        appLevel = null
                        query = ""
                    },
                )
            }

            Spacer(Modifier.height(Baic2Spacing.md))

            val loading = entries == null
            when {
                loading -> LoadingBlock()
                entries.orEmpty().isEmpty() -> EmptyBlock()
                currentApp == null -> FirstLevelList(
                    byApp = byApp,
                    allContacts = allContacts,
                    selected = selected,
                    sessionCount = sessionCount,
                    appCount = selected.mapNotNull { it.imPackage }.distinct().size,
                    limitHint = limitHint,
                    overLimit = overLimit,
                    onToggleAllContacts = {
                        allContacts = !allContacts
                        if (allContacts) selected = emptySet()
                        limitHint = false
                    },
                    onOpenApp = { pkg ->
                        query = ""
                        appLevel = pkg
                    },
                )
                else -> SecondLevelList(
                    rows = byApp[currentApp].orEmpty(),
                    query = query,
                    selected = selected,
                    onQueryChange = { query = it },
                    onToggle = ::toggle,
                    onSelectAll = {
                        val targets = byApp[currentApp].orEmpty().map { it.target }
                        val allSelected = targets.all { it in selected }
                        if (allSelected) {
                            selected = selected - targets.toSet()
                            limitHint = false
                        } else {
                            val merged = selected + targets
                            val mergedContacts = merged.map { it.contactId }.distinct().size
                            if (merged.size > MAX_SESSIONS || mergedContacts > MAX_CONTACTS) {
                                limitHint = true
                            } else {
                                allContacts = false
                                selected = merged
                                limitHint = false
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.height(Baic2Spacing.md))
            StartBlock(
                allContacts = allContacts,
                sessionCount = sessionCount,
                appCount = selected.mapNotNull { it.imPackage }.distinct().size,
                overLimit = overLimit,
                limitHint = limitHint,
                onStart = {
                    onStart(
                        AutoSelectionStore.Selection(
                            allContacts = allContacts,
                            targets = if (allContacts) emptyList() else selected.toList(),
                        ),
                    )
                },
            )
            Spacer(Modifier.height(Baic2Spacing.md))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

// --------------------------------------------------------------- 一级：APP

@Composable
private fun FirstLevelList(
    byApp: Map<String, List<ChannelRow>>,
    allContacts: Boolean,
    selected: Set<AutoSelectionStore.Target>,
    sessionCount: Int,
    appCount: Int,
    limitHint: Boolean,
    overLimit: Boolean,
    onToggleAllContacts: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        item(key = "all-contacts") {
            CheckRow(
                title = stringResource(R.string.float_home_scope_all_contacts),
                subtitle = stringResource(R.string.float_home_scope_all_contacts_hint),
                checked = allContacts,
                avatar = "全",
                onClick = onToggleAllContacts,
            )
        }
        item(key = "by-app-label") {
            Text(
                text = stringResource(R.string.float_home_scope_by_app),
                modifier = Modifier.padding(top = Baic2Spacing.md, bottom = Baic2Spacing.xs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(byApp.keys.toList(), key = { "app-$it" }) { pkg ->
            val rows = byApp[pkg].orEmpty()
            val count = rows.count { it.target in selected }
            AppRow(
                label = appLabel(LocalContext.current, pkg),
                count = count,
                enabled = !allContacts,
                onClick = { onOpenApp(pkg) },
            )
        }
        item(key = "summary") {
            Spacer(Modifier.height(Baic2Spacing.sm))
            SelectionSummary(appCount = appCount, sessionCount = sessionCount)
        }
        if (limitHint || overLimit) {
            item(key = "limit") {
                Text(
                    text = stringResource(
                        R.string.float_home_scope_limit,
                        MAX_CONTACTS,
                        MAX_SESSIONS,
                    ),
                    modifier = Modifier.padding(top = Baic2Spacing.xs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AppRow(label: String, count: Int, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Baic2Spacing.xxs)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f * alpha))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(label)
        Spacer(Modifier.width(Baic2Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (count > 0) {
                    stringResource(R.string.float_home_scope_selected_count, count)
                } else {
                    stringResource(R.string.float_home_scope_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------- 二级：联系人会话

@Composable
private fun SecondLevelHeader(
    appLabel: String,
    selected: Int,
    total: Int,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.float_home_scope_back),
            )
        }
        Text(
            text = appLabel,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "$selected/$total",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SecondLevelList(
    rows: List<ChannelRow>,
    query: String,
    selected: Set<AutoSelectionStore.Target>,
    onQueryChange: (String) -> Unit,
    onToggle: (AutoSelectionStore.Target) -> Unit,
    onSelectAll: () -> Unit,
) {
    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter {
            it.profile.displayName.contains(query, ignoreCase = true) ||
                it.channel.displayName.contains(query, ignoreCase = true)
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.float_home_scope_select_all))
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text(stringResource(R.string.float_home_scope_search)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Spacer(Modifier.height(Baic2Spacing.sm))
        LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
            items(filtered, key = { "${it.profile.id}|${it.channel.id}" }) { row ->
                CheckRow(
                    title = row.profile.displayName,
                    subtitle = "${appLabel(LocalContext.current, row.channel.imPackage)} · " +
                        row.channel.displayName,
                    checked = row.target in selected,
                    avatar = row.profile.displayName.take(1),
                    onClick = { onToggle(row.target) },
                )
            }
        }
    }
}

// --------------------------------------------------------------- 通用组件

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = Baic2Spacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(Baic2Spacing.xs))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListenerWarning(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.width(Baic2Spacing.sm))
        Text(
            text = stringResource(R.string.float_home_auto_need_notify),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        TextButton(onClick = onOpen) {
            Text(stringResource(R.string.float_home_scope_open_settings))
        }
    }
}

@Composable
private fun CheckRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    avatar: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Baic2Spacing.xxs)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatar)
        Spacer(Modifier.width(Baic2Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Baic2Spacing.sm))
        Checkbox(checked = checked, onCheckedChange = { onClick() })
    }
}

@Composable
private fun Avatar(label: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun SelectionSummary(appCount: Int, sessionCount: Int) {
    Text(
        text = stringResource(R.string.float_home_scope_selected, appCount, sessionCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StartBlock(
    allContacts: Boolean,
    sessionCount: Int,
    appCount: Int,
    overLimit: Boolean,
    limitHint: Boolean,
    onStart: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!allContacts) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                SelectionSummary(appCount = appCount, sessionCount = sessionCount)
            }
            Spacer(Modifier.height(Baic2Spacing.sm))
        }
        if (limitHint || overLimit) {
            Text(
                text = stringResource(R.string.float_home_scope_limit, MAX_CONTACTS, MAX_SESSIONS),
                modifier = Modifier.padding(bottom = Baic2Spacing.xs),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onStart,
            enabled = (allContacts || sessionCount > 0) && !overLimit,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = if (allContacts) {
                    stringResource(R.string.float_home_scope_start_all)
                } else {
                    stringResource(R.string.float_home_scope_start, sessionCount)
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.float_home_scope_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.float_home_scope_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --------------------------------------------------------------- utils

private data class ChannelRow(
    val profile: ContactProfileEntity,
    val channel: ChatChannelEntity,
) {
    val target: AutoSelectionStore.Target
        get() = AutoSelectionStore.Target(
            contactId = profile.id,
            imPackage = channel.imPackage,
            channelName = channel.displayName,
            displayName = profile.displayName,
        )
}

private fun List<Pair<ContactProfileEntity, List<ChatChannelEntity>>>.flattenRows(): List<ChannelRow> =
    flatMap { (profile, channels) -> channels.map { ChannelRow(profile, it) } }

private fun appLabel(context: Context, imPackage: String): String = runCatching {
    context.packageManager
        .getApplicationLabel(context.packageManager.getApplicationInfo(imPackage, 0))
        .toString()
}.getOrDefault(imPackage)

private fun Context.openNotificationListenerSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 联系人 / 会话上限（方案 §5.3.1：联系人 ≤5 沿用白名单口径、会话 ≤10）。 */
private const val MAX_CONTACTS = 5
private const val MAX_SESSIONS = 10
