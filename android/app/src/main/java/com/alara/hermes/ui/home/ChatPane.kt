package com.alara.hermes.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
    val entries = chat.timeline?.entries.orEmpty()

    // Follow the stream only while the user is already at the bottom.
    var pinnedToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= layout.totalItemsCount - 2
        }.collect { atBottom -> pinnedToBottom = atBottom }
    }
    LaunchedEffect(entries.size, (entries.lastOrNull() as? ChatEntry.Message)?.text?.length) {
        if (pinnedToBottom && entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // Compact header: title + profile/model, advanced controls in a sheet.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
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
            IconButton(onClick = { configSheetOpen = true }) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = "Session settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id.value }) { entry ->
                    when (entry) {
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
                chat.timeline?.statusText?.let { status ->
                    item(key = "status-row") { StatusRow(status) }
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
            onDismiss = { configSheetOpen = false },
            onModel = viewModel::setModel,
            onReasoning = viewModel::setReasoning,
            onFastMode = viewModel::setFastMode,
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
