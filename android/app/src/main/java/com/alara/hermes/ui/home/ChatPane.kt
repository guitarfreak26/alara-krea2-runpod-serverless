package com.alara.hermes.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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

    // reverseLayout: index 0 sits at the bottom, so opening a conversation
    // starts at the newest message, streaming growth stays anchored, and the
    // keyboard never pushes content out from under the reader.
    val rows = remember(entries) { buildTranscriptRows(entries).asReversed() }

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
        )

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            ) {
                chat.timeline?.statusText?.let { status ->
                    item(key = "status-row") { StatusRow(status) }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is TranscriptRow.TimeMarker -> TimeMarkerRow(row.timestampMs)
                        is TranscriptRow.Entry -> when (val entry = row.entry) {
                            is ChatEntry.Message -> MessageBubble(entry)
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
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Composer(
            sessionKey = chat.sessionKey,
            sending = chat.sending,
            running = chat.timeline?.running == true,
            offline = state.connection !is com.alara.hermes.protocol.ConnectionState.Connected,
            onSend = viewModel::send,
            onStop = viewModel::interrupt,
            draftLoader = { key -> viewModel.draftFor(key) },
            onDraftChange = viewModel::saveDraft,
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
) {
    val chat = state.chat
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
                Column(Modifier.weight(1f)) {
                    Text(
                        chat.title.ifBlank { "New conversation" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append(state.activeProfile ?: "default")
                                chat.config.model?.let { append("  ·  ").append(it.substringAfterLast('/')) }
                                chat.config.thinkingLevel?.takeIf { it.isNotBlank() && it != "none" }
                                    ?.let { append("  ·  ").append(it) }
                                if (chat.config.fastMode == true) append("  ·  fast")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chat.timeline?.running == true) {
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
