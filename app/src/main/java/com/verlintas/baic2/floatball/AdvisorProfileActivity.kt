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

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.R
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import com.verlintas.baic2.floatball.advisor.ContactRepository
import com.verlintas.baic2.floatball.advisor.PersonaFieldDef
import com.verlintas.baic2.floatball.advisor.PersonaFieldType
import com.verlintas.baic2.floatball.advisor.PersonaSchemas
import com.verlintas.baic2.floatball.advisor.PersonaValues
import com.verlintas.baic2.floatball.advisor.ProfileMemorySync
import com.verlintas.baic2.floatball.advisor.data.CONTACT_TYPE_GROUP
import com.verlintas.baic2.floatball.advisor.data.CONTACT_TYPE_PERSON
import com.verlintas.baic2.floatball.advisor.data.ContactProfileEntity
import com.verlintas.baic2.floatball.advisor.data.GroupMemberEntity
import com.verlintas.baic2.floatball.advisor.data.MY_PERSONA_CONTACT_ID
import com.verlintas.baic2.floatball.advisor.data.MergeRecordEntity
import com.verlintas.baic2.floatball.advisor.data.PersonaFieldEntity
import com.verlintas.baic2.floatball.contact.ContactHistoryGateway
import com.verlintas.baic2.floatball.contact.DealFact
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 联系人画像管理页（方案 §7）：联系人列表/搜索 → schema 驱动的画像表单 →
 * 草稿 ✓/✕（✓ 经 [ProfileMemorySync] 双写上游记忆）→ 手动改名/改绑/
 * 合并与 30 天撤销 → 按人检索全部会话；另含「我的人设」编辑。
 */
@AndroidEntryPoint
class AdvisorProfileActivity : ComponentActivity() {

    @Inject
    lateinit var contacts: ContactRepository

    @Inject
    lateinit var schemas: PersonaSchemas

    @Inject
    lateinit var profileSync: ProfileMemorySync

    @Inject
    lateinit var historyGateway: ContactHistoryGateway

    @Inject
    lateinit var conversationRepository: ConversationRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun attachBaseContext(newBase: Context) {
        val language = AppLocaleStore.readFrom(newBase)
        val tag = language.languageTag
        super.attachBaseContext(
            if (tag == null) newBase else newBase.withLocale(Locale.forLanguageTag(tag)),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initialContact = intent.getLongExtra(EXTRA_CONTACT_ID, -1L)
        val initialConversation = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        setContent {
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val accent by settingsRepository.accentColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.BLUE)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            androidx.compose.runtime.SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            Baic2Theme(darkTheme = darkTheme, accent = accent) {
                ProfileNav(
                    initialContactId = initialContact,
                    currentConversationId = initialConversation,
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CONTACT_ID = "extra_contact_id"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    }
}

private enum class ProfileScreen { LIST, DETAIL, MINE }

@Composable
private fun ProfileNav(
    initialContactId: Long,
    currentConversationId: Long,
    onClose: () -> Unit,
) {
    var screen by remember { mutableStateOf(ProfileScreen.LIST) }
    var selectedId by remember { mutableStateOf(0L) }

    LaunchedEffect(initialContactId) {
        if (initialContactId > 0) {
            selectedId = initialContactId
            screen = ProfileScreen.DETAIL
        }
    }

    when (screen) {
        ProfileScreen.LIST -> ContactListScreen(
            onOpen = {
                selectedId = it
                screen = ProfileScreen.DETAIL
            },
            onMine = { screen = ProfileScreen.MINE },
            onClose = onClose,
        )

        ProfileScreen.DETAIL -> ContactDetailScreen(
            contactId = selectedId,
            currentConversationId = currentConversationId,
            onBack = { screen = ProfileScreen.LIST },
        )

        ProfileScreen.MINE -> MyPersonaScreen(onBack = { screen = ProfileScreen.LIST })
    }
}

// ------------------------------------------------------------ list

@Composable
private fun ContactListScreen(
    onOpen: (Long) -> Unit,
    onMine: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts = rememberContactRepository()
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<ContactProfileEntity>>(emptyList()) }
    var merges by remember { mutableStateOf<List<MergeRecordEntity>>(emptyList()) }

    suspend fun refresh() {
        all = contacts.listAlive()
        merges = contacts.recentMerges()
    }
    LaunchedEffect(Unit) { refresh() }

    val filtered = all.filter { profile ->
        query.isBlank() || profile.displayName.contains(query.trim(), ignoreCase = true) ||
            (profile.goal?.contains(query.trim(), ignoreCase = true) == true)
    }

    PageScaffold(title = stringResource(R.string.float_profile_title), onClose = onClose) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.float_profile_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = onMine) {
                Text(stringResource(R.string.float_profile_mine))
            }
            val context = LocalContext.current
            OutlinedButton(onClick = {
                context.startActivity(
                    android.content.Intent(context, AdvisorPlaybookActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) {
                Text(stringResource(R.string.float_playbook_entry))
            }
        }

        merges.forEach { record ->
            MergeUndoBar(record) { scope.launch { runCatching { contacts.undoMerge(record.fromContactId) }; refresh() } }
        }

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.float_profile_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        filtered.forEach { profile ->
            ContactCard(profile) { onOpen(profile.id) }
        }
    }
}

@Composable
private fun ContactCard(profile: ContactProfileEntity, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TypeTag(profile.contactType)
            }
            profile.goal?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun TypeTag(type: String) {
    Text(
        text = stringResource(
            if (type == CONTACT_TYPE_GROUP) R.string.float_profile_type_group else R.string.float_profile_type_person,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MergeUndoBar(record: MergeRecordEntity, onUndo: () -> Unit) {
    val contacts = rememberContactRepository()
    var fromName by remember { mutableStateOf("") }
    var intoName by remember { mutableStateOf("") }
    LaunchedEffect(record.fromContactId) {
        fromName = contacts.profile(record.fromContactId)?.displayName.orEmpty()
        intoName = contacts.profile(record.intoContactId)?.displayName.orEmpty()
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.float_profile_merged_undo, fromName, intoName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.float_profile_undo),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onUndo)
                    .padding(4.dp),
            )
        }
    }
}

// ------------------------------------------------------------ detail

@Composable
private fun ContactDetailScreen(
    contactId: Long,
    currentConversationId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts = rememberContactRepository()
    val schemas = rememberPersonaSchemas()
    val profileSync = rememberProfileSync()
    val conversationRepo = rememberConversationRepository()
    val profile by contacts.observeProfile(contactId).collectAsStateWithLifecycle(initialValue = null)
    val fields by contacts.observeFields(contactId).collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<PersonaFieldDef?>(null) }
    var mergeOpen by remember { mutableStateOf(false) }
    var boundTitles by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }

    suspend fun refreshBindings() {
        boundTitles = contacts.conversationIdsFor(contactId).mapNotNull { id ->
            val title = conversationRepo.get(id)?.title ?: return@mapNotNull null
            id to title
        }
    }
    LaunchedEffect(contactId) { refreshBindings() }

    val current = profile
    PageScaffold(
        title = current?.displayName ?: stringResource(R.string.float_profile_title),
        onClose = null,
        onBack = onBack,
    ) {
        if (current == null) {
            Text(stringResource(R.string.float_profile_gone))
            return@PageScaffold
        }

        // 类型 + 改名
        FlowChips(
            options = listOf(CONTACT_TYPE_PERSON to R.string.float_profile_type_person,
                CONTACT_TYPE_GROUP to R.string.float_profile_type_group),
            selected = current.contactType,
        ) { type ->
            scope.launch { contacts.setContactType(contactId, type) }
        }

        var name by remember(current.id, current.displayName) { mutableStateOf(current.displayName) }
        LabeledField(stringResource(R.string.float_profile_name), name, { name = it })
        var goal by remember(current.id, current.goal) { mutableStateOf(current.goal.orEmpty()) }
        LabeledField(stringResource(R.string.float_profile_goal), goal, { goal = it }, minLines = 2)
        var style by remember(current.id, current.style) { mutableStateOf(current.style.orEmpty()) }
        SectionTitle(stringResource(R.string.float_profile_style))
        FlowChips(
            options = schemas.get().stylePresets.map { it to null },
            selected = style,
        ) { style = it }
        LabeledField(stringResource(R.string.float_profile_style_custom), style, { style = it })
        Button(onClick = {
            scope.launch {
                contacts.renameContact(contactId, name)
                contacts.updateGoalStyle(contactId, goal, style)
                toast(context, R.string.float_profile_saved)
            }
        }) { Text(stringResource(R.string.float_profile_save)) }

        // 当前会话改绑（L3 手动绑定/切人修正）
        if (currentConversationId > 0) {
            val boundHere = boundTitles.any { it.first == currentConversationId }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (boundHere) R.string.float_profile_bound_current else R.string.float_profile_rebind_current,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (!boundHere) {
                        Button(onClick = {
                            scope.launch {
                                contacts.rebindConversation(currentConversationId, contactId)
                                refreshBindings()
                            }
                        }) { Text(stringResource(R.string.float_profile_bind)) }
                    }
                }
            }
        }

        // 关联会话 + 按人搜索
        SectionTitle(stringResource(R.string.float_profile_conversations))
        boundTitles.forEach { (_, title) ->
            Text("· $title", style = MaterialTheme.typography.bodyMedium)
        }
        PersonHistorySearch(contactId)

        // 画像字段（schema 驱动分组）
        val theirDefs = schemas.get().theirFields.groupBy { it.group }
        theirDefs.forEach { (group, defs) ->
            SectionTitle(group)
            defs.forEach { def ->
                FieldRow(
                    def = def,
                    entity = fields.firstOrNull { it.field == def.key },
                    onConfirm = {
                        scope.launch {
                            profileSync.confirmAndSync(
                                contactId = contactId,
                                field = def.key,
                                conversationId = boundTitles.firstOrNull()?.first,
                            )
                        }
                    },
                    onReject = { scope.launch { contacts.rejectDraft(contactId, def.key) } },
                    onEdit = { editing = def },
                )
            }
        }

        // 群成员二级画像（仅群档案）
        if (current.contactType == CONTACT_TYPE_GROUP) {
            GroupMembersSection(contactId)
        }

        // 合并
        SectionTitle(stringResource(R.string.float_profile_merge))
        OutlinedButton(onClick = { mergeOpen = true }) {
            Text(stringResource(R.string.float_profile_merge_pick))
        }
        if (mergeOpen) {
            MergePicker(
                targetId = contactId,
                onDismiss = { mergeOpen = false },
                onMerged = {
                    mergeOpen = false
                    scope.launch { refreshBindings() }
                },
            )
        }

        // 危险区：清除上游相关记忆（软删除） + 删除 advisor 档案
        DangerZone(
            displayName = current.displayName,
            onForget = {
                scope.launch {
                    val count = profileSync.forgetContact(contactId)
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.float_profile_forgot_done, count),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDelete = {
                scope.launch {
                    contacts.deleteContact(contactId)
                    onBack()
                }
            },
        )
    }

    editing?.let { def ->
        val initial = fields.firstOrNull { it.field == def.key }?.let { storedValue(def.type, it) }.orEmpty()
        FieldEditorDialog(
            def = def,
            initial = initial,
            onDismiss = { editing = null },
            onSave = { value ->
                editing = null
                scope.launch { contacts.setUserField(contactId, def.key, value) }
            },
            onDelete = {
                editing = null
                scope.launch { contacts.setUserField(contactId, def.key, "") }
            },
        )
    }
}

@Composable
private fun PersonHistorySearch(contactId: Long) {
    val contacts = rememberContactRepository()
    val gateway = rememberHistoryGateway()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DealFact>?>(null) }
    SectionTitle(stringResource(R.string.float_profile_history_search))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.float_profile_history_search)) },
        )
        Button(
            onClick = {
                scope.launch {
                    val ids = contacts.conversationIdsFor(contactId)
                    results = gateway.search(ids, query)
                }
            },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(stringResource(R.string.float_profile_history_search_btn)) }
    }
    results?.let { list ->
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.float_profile_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        list.take(20).forEach { fact ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = formatTime(fact.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = fact.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MergePicker(
    targetId: Long,
    onDismiss: () -> Unit,
    onMerged: () -> Unit,
) {
    val context = LocalContext.current
    val contacts = rememberContactRepository()
    val scope = rememberCoroutineScope()
    var others by remember { mutableStateOf<List<ContactProfileEntity>>(emptyList()) }
    LaunchedEffect(targetId) {
        others = contacts.listAlive().filter { it.id != targetId }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.float_profile_merge_pick),
                style = MaterialTheme.typography.titleMedium,
            )
            Column {
                others.forEach { candidate ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(candidate.displayName, Modifier.weight(1f))
                        TypeTag(candidate.contactType)
                        Text(
                            text = stringResource(R.string.float_profile_merge_confirm),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .clickable {
                                    scope.launch {
                                        val result = runCatching {
                                            contacts.mergeContacts(candidate.id, targetId)
                                        }
                                        if (result.isSuccess) {
                                            onMerged()
                                        } else {
                                            toast(
                                                context,
                                                when (result.exceptionOrNull()?.message) {
                                                    "type_mismatch" ->
                                                        R.string.float_profile_merge_fail_type
                                                    else -> R.string.float_profile_merge_fail_generic
                                                },
                                            )
                                        }
                                    }
                                },
                        )
                    }
                }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.float_profile_cancel))
            }
        }
    }
}

// ------------------------------------------------------------ group members

@Composable
private fun GroupMembersSection(groupContactId: Long) {
    val contacts = rememberContactRepository()
    val scope = rememberCoroutineScope()
    val members by contacts.observeGroupMembers(groupContactId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var newNickname by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf<GroupMemberEntity?>(null) }

    SectionTitle(stringResource(R.string.float_profile_members))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newNickname,
            onValueChange = { newNickname = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.float_profile_members_add_hint)) },
        )
        Button(
            enabled = newNickname.isNotBlank(),
            onClick = {
                val name = newNickname.trim()
                newNickname = ""
                scope.launch { contacts.upsertGroupMember(groupContactId, name) }
            },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(stringResource(R.string.float_profile_members_add)) }
    }

    if (members.isEmpty()) {
        Text(
            text = stringResource(R.string.float_profile_members_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    members.forEach { member ->
        var linkedName by remember(member.id, member.linkedContactId) { mutableStateOf<String?>(null) }
        LaunchedEffect(member.linkedContactId) {
            linkedName = member.linkedContactId?.let { contacts.profile(it)?.displayName }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = member.displayName + " ×${member.seenCount}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    linkedName?.let {
                        Text(
                            text = stringResource(R.string.float_profile_members_linked, it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (linkedName == null) R.string.float_profile_members_link
                        else R.string.float_profile_members_unlink,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable {
                            linking = if (linkedName == null) member else null.also {
                                scope.launch { contacts.linkGroupMember(member.id, null) }
                            }
                        }
                        .padding(6.dp),
                )
                Text(
                    text = stringResource(R.string.float_profile_members_remove),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable {
                            scope.launch { contacts.removeGroupMember(groupContactId, member.id) }
                        }
                        .padding(6.dp),
                )
            }
        }
    }

    linking?.let { member ->
        MemberLinkDialog(
            groupContactId = groupContactId,
            onDismiss = { linking = null },
            onPicked = { personId ->
                linking = null
                scope.launch { contacts.linkGroupMember(member.id, personId) }
            },
        )
    }
}

@Composable
private fun MemberLinkDialog(
    groupContactId: Long,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit,
) {
    val contacts = rememberContactRepository()
    var people by remember { mutableStateOf<List<ContactProfileEntity>>(emptyList()) }
    LaunchedEffect(groupContactId) {
        people = contacts.listAlive().filter {
            it.contactType == CONTACT_TYPE_PERSON && it.id != groupContactId
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.float_profile_members_link_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (people.isEmpty()) {
                    Text(stringResource(R.string.float_profile_members_link_empty))
                }
                people.forEach { p ->
                    Text(
                        text = p.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPicked(p.id) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.float_profile_cancel)) }
        },
    )
}

// ------------------------------------------------------------ danger zone

@Composable
private fun DangerZone(
    displayName: String,
    onForget: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmForget by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.float_profile_danger))
    OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.float_profile_forget))
    }
    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = stringResource(R.string.float_profile_delete),
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.float_profile_forget_title)) },
            text = { Text(stringResource(R.string.float_profile_forget_confirm, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForget()
                }) {
                    Text(stringResource(R.string.float_profile_forget_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(R.string.float_profile_cancel))
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.float_profile_delete_title)) },
            text = { Text(stringResource(R.string.float_profile_delete_confirm, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(
                        text = stringResource(R.string.float_profile_delete_confirm_btn),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.float_profile_cancel))
                }
            },
        )
    }
}

// ------------------------------------------------------------ my persona

@Composable
private fun MyPersonaScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val contacts = rememberContactRepository()
    val sync = rememberProfileSync()
    val schemas = rememberPersonaSchemas()
    val fields by contacts.observeFields(MY_PERSONA_CONTACT_ID)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<PersonaFieldDef?>(null) }

    PageScaffold(
        title = stringResource(R.string.float_profile_mine),
        onClose = null,
        onBack = onBack,
    ) {
        schemas.get().myFields.groupBy { it.group }.forEach { (group, defs) ->
            SectionTitle(group)
            defs.forEach { def ->
                FieldRow(
                    def = def,
                    entity = fields.firstOrNull { it.field == def.key },
                    onConfirm = {},
                    onReject = {},
                    onEdit = { editing = def },
                    mine = true,
                )
            }
        }
    }

    editing?.let { def ->
        val initial = contacts.let { repo ->
            val entity = fields.firstOrNull { it.field == def.key }
            if (entity == null) "" else storedValue(def.type, entity)
        }
        FieldEditorDialog(
            def = def,
            initial = initial,
            onDismiss = { editing = null },
            onSave = { value ->
                editing = null
                // §6.5：手填我方人设立即确认并双写上游（PROFILE/USER/imp4/entities=我）。
                scope.launch {
                    contacts.setUserField(MY_PERSONA_CONTACT_ID, def.key, value)
                    sync.syncMyPersonaField(def.key, value)
                }
            },
            onDelete = {
                editing = null
                scope.launch {
                    contacts.setUserField(MY_PERSONA_CONTACT_ID, def.key, "")
                }
            },
        )
    }
}

// ------------------------------------------------------------ shared field UI

@Composable
private fun FieldRow(
    def: PersonaFieldDef,
    entity: PersonaFieldEntity?,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    mine: Boolean = false,
) {
    val context = LocalContext.current
    val value = entity?.let { storedValue(def.type, it) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (entity != null && !entity.confirmed && !mine) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = def.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (value.isNullOrBlank()) R.string.float_profile_add_field
                        else R.string.float_profile_edit_field,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable(onClick = onEdit)
                        .padding(4.dp),
                )
            }
            value?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            if (entity != null && !entity.confirmed && !mine) {
                entity.evidence?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.float_profile_evidence, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.float_profile_confirm),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(onClick = onConfirm)
                            .padding(vertical = 4.dp),
                    )
                    Text(
                        text = stringResource(R.string.float_profile_reject),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable(onClick = onReject)
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldEditorDialog(
    def: PersonaFieldDef,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember(def.key) { mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(def.label, style = MaterialTheme.typography.titleMedium)
                when (def.type) {
                    PersonaFieldType.ENUM -> FlowChips(
                        options = (def.options ?: emptyList()).map { it to null },
                        selected = text,
                    ) { text = it }

                    else -> OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        minLines = if (def.type == PersonaFieldType.TEXTAREA) 3 else 1,
                        label = { Text(def.label) },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Button(onClick = { onSave(text) }) {
                        Text(stringResource(R.string.float_profile_save))
                    }
                    if (initial.isNotBlank()) {
                        OutlinedButton(onClick = onDelete) {
                            Text(stringResource(R.string.float_profile_clear))
                        }
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.float_profile_cancel))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ building blocks

@Composable
private fun PageScaffold(
    title: String,
    onClose: (() -> Unit)?,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    onBack?.let {
                        OutlinedButton(onClick = it) {
                            Text(stringResource(R.string.float_profile_back))
                        }
                    } ?: onClose?.let {
                        OutlinedButton(onClick = it) {
                            Text(stringResource(R.string.float_ask_close))
                        }
                    }
                }
                content()
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(
    options: List<Pair<String, Int?>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        options.forEach { (value, labelRes) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(labelRes?.let { stringResource(it) } ?: value) },
            )
        }
    }
}

// ---------------------------------------------------- composition-local singletons

/** LocalContext 可能是 Dialog/主题包装，沿 ContextWrapper 链找回宿主 Activity。 */
@Composable
private fun advisorActivity(): AdvisorProfileActivity =
    generateSequence<android.content.Context>(LocalContext.current) {
        (it as? android.content.ContextWrapper)?.baseContext
    }.filterIsInstance<AdvisorProfileActivity>().firstOrNull()
        ?: error("AdvisorProfileActivity context expected")

@Composable
private fun rememberContactRepository(): ContactRepository = advisorActivity().contacts

@Composable
private fun rememberPersonaSchemas(): PersonaSchemas = advisorActivity().schemas

@Composable
private fun rememberProfileSync(): ProfileMemorySync = advisorActivity().profileSync

@Composable
private fun rememberHistoryGateway(): ContactHistoryGateway = advisorActivity().historyGateway

@Composable
private fun rememberConversationRepository(): ConversationRepository =
    advisorActivity().conversationRepository

/**
 * 字段存储值 → 展示文本（tags 解码顿号拼接）。
 */
private fun storedValue(type: String, entity: PersonaFieldEntity): String =
    if (type == PersonaFieldType.TAGS) {
        PersonaValues.decodeTags(entity.value).joinToString("、")
    } else {
        entity.value
    }

private fun toast(context: android.content.Context, resId: Int) {
    android.widget.Toast.makeText(context, context.getString(resId), android.widget.Toast.LENGTH_SHORT).show()
}

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatTime(epochMs: Long): String =
    TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs))

private fun Context.withLocale(locale: Locale): Context {
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}
