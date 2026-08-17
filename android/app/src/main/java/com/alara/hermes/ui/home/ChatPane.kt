package com.alara.hermes.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alara.hermes.protocol.ChatEntry
import com.alara.hermes.ui.HomeUiState
import com.alara.hermes.ui.HomeViewModel
import com.alara.hermes.ui.chat.ApprovalCard
import com.alara.hermes.ui.chat.Composer
import com.alara.hermes.ui.chat.MessageBubble
import com.alara.hermes.ui.chat.ReasoningRow
import com.alara.hermes.ui.chat.SessionConfigSheet
import com.alara.hermes.ui.chat.StatusRow
import com.alara.hermes.ui.chat.SystemNoteRow
import com.alara.hermes.ui.chat.ToolRow
import com.alara.hermes.util.formatRelativeTime
import kotlinx.coroutines.launch

/** A row in the transcript: an entry, or a time separator between bursts. */
private sealed interface TranscriptRow {
    val key: String

    data class Entry(val entry: ChatEntry) : TranscriptRow {
        override val key: String get() = entry.id.value
    }

    data class TimeMarker(val timestampMs: Long, override val key: String) : TranscriptRow
}

private const val TIME_MARKER_GAP_MS = 30L * 60 * 1000

private fun buildTranscriptRows(entries: List<ChatEntry>): List<TranscriptRow> {
    if (entries.isEmpty()) return emptyList()
    val rows = ArrayList<TranscriptRow>(entries.size + 8)
    var previousTs = 0L
    entries.forEach { entry ->
        if (entry.timestampMs > 0 && entry.timestampMs - previousTs > TIME_MARKER_GAP_MS) {
            rows += TranscriptRow.TimeMarker(entry.timestampMs, key = "time:${entry.id.value}")
        }
        if (entry.timestampMs > 0) previousTs = entry.timestampMs
        rows += TranscriptRow.Entry(entry)
    }
    return rows
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPane(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onBack: (() -> Unit)?,
    pendingShare: com.alara.hermes.SharePayload? = null,
    onShareConsumed: () -> Unit = {},
) {
    val chat = state.chat
    if (chat.sessionKey == null && chat.timeline == null) {
        EmptyChatPlaceholder()
        return
    }

    var configSheetOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val entries = chat.timeline?.entries.orEmpty()
    val mediaAuth by viewModel.mediaAuth.collectAsState()

    // Bot/room chats are QUIET by default: the manager speaks, activity is
    // one tap away. Ordinary threads keep following the global setting.
    val quietChat = chat.room != null || chat.title == HomeViewModel.BOT_CHAT_TITLE
    var revealQuietTools by remember(chat.sessionKey) { mutableStateOf(false) }

    // reverseLayout: index 0 sits at the bottom, so opening a conversation
    // starts at the newest message, streaming growth stays anchored, and the
    // keyboard never pushes content out from under the reader.
    val showTools = if (quietChat) revealQuietTools else state.chatSettings.showToolActivity
    val rows = remember(entries, showTools) {
        val visible = if (showTools) entries else entries.filterNot { it is ChatEntry.ToolRun }
        buildTranscriptRows(visible).asReversed()
    }
    val hiddenActivityCount = if (quietChat && !revealQuietTools) {
        entries.count { it is ChatEntry.ToolRun }
    } else {
        0
    }

    val pinnedToBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex <= 1 &&
                listState.firstVisibleItemScrollOffset < 220
        }
    }
    // Follow new rows only while the reader is already at the bottom.
    LaunchedEffect(rows.firstOrNull()?.key) {
        if (pinnedToBottom && rows.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
    ) {
        ChatHeader(
            state = state,
            onBack = onBack,
            onOpenConfig = { configSheetOpen = true },
            mediaAuth = mediaAuth,
        )
        // Room context: keep the member roster visible; tap for roles.
        chat.room?.let { room ->
            var membersOpen by remember { mutableStateOf(false) }
            Text(
                room.members.joinToString("  ·  ") { it.displayName },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { membersOpen = true }
                    .padding(horizontal = 18.dp, vertical = 3.dp),
            )
            if (membersOpen) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { membersOpen = false },
                    title = { Text(room.displayName) },
                    text = {
                        Column {
                            room.members.forEach { member ->
                                Text(
                                    "${member.displayName} — ${member.role}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                            room.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { membersOpen = false }) {
                            Text("Close")
                        }
                    },
                )
            }
        }

        // Quiet chats: activity stays backstage until asked for.
        if (quietChat && (hiddenActivityCount > 0 || revealQuietTools)) {
            Text(
                if (revealQuietTools) {
                    "hide background activity"
                } else {
                    "$hiddenActivityCount background step${if (hiddenActivityCount == 1) "" else "s"} · show"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { revealQuietTools = !revealQuietTools }
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            ) {
                chat.timeline?.statusText?.let { status ->
                    item(key = "status-row") { StatusRow(status) }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is TranscriptRow.TimeMarker -> TimeMarkerRow(row.timestampMs)
                        is TranscriptRow.Entry -> when (val entry = row.entry) {
                            is ChatEntry.Message -> MessageBubble(
                                entry,
                                mediaAuth,
                                streamLive = state.chatSettings.streamLive,
                                mediaPathUrl = viewModel.mediaUrlBuilder(),
                            )
                            is ChatEntry.Reasoning -> ReasoningRow(entry)
                            is ChatEntry.ToolRun -> ToolRow(entry)
                            is ChatEntry.Approval -> ApprovalCard(
                                entry = entry,
                                onChoice = { choice -> viewModel.respondApproval(entry.id, choice) },
                            )
                            is ChatEntry.SystemNote -> SystemNoteRow(entry)
                        }
                    }
                }
            }

            // Jump back to the newest message after scrolling up.
            // Fully qualified so the ColumnScope overload isn't resolved here.
            androidx.compose.animation.AnimatedVisibility(
                visible = !pinnedToBottom,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Surface(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 4.dp,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Jump to latest",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(24.dp),
                    )
                }
            }
        }

        chat.error?.let { error ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (chat.loadFailed) {
                    androidx.compose.material3.TextButton(onClick = viewModel::retryLoad) {
                        Text("Retry")
                    }
                }
            }
        }

        Composer(
            sessionKey = chat.sessionKey,
            sending = chat.sending || chat.loadFailed,
            running = chat.timeline?.running == true,
            offline = state.connection !is com.alara.hermes.protocol.ConnectionState.Connected,
            onSend = { text, attachments, mentions -> viewModel.send(text, attachments, mentions) },
            onStop = viewModel::interrupt,
            draftLoader = { key -> viewModel.draftFor(key) },
            onDraftChange = viewModel::saveDraft,
            pendingShare = pendingShare,
            onShareConsumed = onShareConsumed,
            roomMembers = chat.room?.members.orEmpty(),
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    if (configSheetOpen) {
        SessionConfigSheet(
            config = state.chat.config,
            models = state.models,
            showSessionControls = state.features.sessionConfig,
            onDismiss = { configSheetOpen = false },
            onModel = viewModel::setModel,
            onReasoning = viewModel::setReasoning,
            onFastMode = viewModel::setFastMode,
        )
    }
}

@Composable
private fun ChatHeader(
    state: HomeUiState,
    onBack: (() -> Unit)?,
    onOpenConfig: () -> Unit,
    mediaAuth: com.alara.hermes.ui.chat.MediaAuth? = null,
) {
    val chat = state.chat
    // Bot identity: a canonical Bot Chat wears the bot's display name and
    // avatar; a room wears the room name with the manager's avatar.
    val activeProfile = state.profiles.firstOrNull { it.id == state.activeProfile }
    val isBotChat = chat.title == HomeViewModel.BOT_CHAT_TITLE && chat.room == null
    val headerProfile = when {
        chat.room != null -> chat.room.members.firstOrNull { it.profileId == chat.room.managerProfileId }
            ?.let { m ->
                com.alara.hermes.protocol.HermesProfile(
                    id = m.profileId,
                    displayName = m.displayName,
                    avatarShape = m.avatarShape,
                    avatarColor = m.avatarColor,
                    avatarUrl = m.avatarUrl,
                )
            } ?: activeProfile
        isBotChat -> activeProfile
        else -> null
    }
    val headerTitle = when {
        chat.room != null -> chat.room.displayName
        isBotChat && activeProfile != null -> activeProfile.displayName
        else -> chat.title.ifBlank { "New conversation" }
    }
    // "Is she actually doing it?" — working state from ANY signal: this
    // app's live turn, the server's busy flag, activity within the native
    // 90s window, or the session row still marked running.
    val working = chat.timeline?.running == true ||
        chat.room?.busy == true ||
        activeProfile?.busy == true ||
        (activeProfile?.lastActiveMs?.let { System.currentTimeMillis() - it < 90_000L } == true) ||
        state.sessions.firstOrNull { it.key == chat.sessionKey }?.running == true
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                headerProfile?.let { profile ->
                    com.alara.hermes.ui.bots.BotAvatar(profile, 34.dp, mediaAuth)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        headerTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                if (working) {
                                    append("working…")
                                } else if (headerProfile != null) {
                                    append("online")
                                } else {
                                    append(state.activeProfile ?: "default")
                                }
                                val model = chat.config.model
                                    ?: state.models.firstOrNull { it.isCurrent }?.displayName
                                model?.let { append("  ·  ").append(it.substringAfterLast('/')) }
                                chat.config.thinkingLevel?.takeIf { it.isNotBlank() && it != "none" }
                                    ?.let { append("  ·  ").append(it) }
                                if (chat.config.fastMode == true) append("  ·  fast")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (working) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (working) {
                            Spacer(Modifier.width(8.dp))
                            RunningIndicator()
                        }
                    }
                }
                IconButton(onClick = onOpenConfig) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = "Session settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun TimeMarkerRow(timestampMs: Long) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            formatRelativeTime(timestampMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    CircleShape,
                )
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyChatPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Hermes",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Pick a conversation or start a new one",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
