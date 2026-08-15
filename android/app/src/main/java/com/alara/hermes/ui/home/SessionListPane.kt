package com.alara.hermes.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alara.hermes.protocol.SessionSummary
import com.alara.hermes.ui.HomeUiState
import com.alara.hermes.util.formatRelativeTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListPane(
    state: HomeUiState,
    onOpenSession: (String?) -> Unit,
    onSwitchProfile: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onToggleArchivedView: () -> Unit,
    visibleSessions: List<com.alara.hermes.protocol.SessionSummary>,
    onRefresh: () -> Unit,
    onOpenMenu: () -> Unit,
    sourceOptions: List<String> = emptyList(),
    onToggleSource: (String) -> Unit = {},
    onFork: (String) -> Unit = {},
) {
    var profileMenuOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var contextSession by remember { mutableStateOf<SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // Header: profile switcher + connection + search toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "Menu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onOpenMenu)
                        .padding(vertical = 4.dp),
                )
                Spacer(Modifier.width(16.dp))
                val canSwitchProfile = state.features.profiles && state.profiles.isNotEmpty()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(enabled = canSwitchProfile) { profileMenuOpen = true }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        state.activeProfile ?: "Hermes",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (canSwitchProfile) {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "Switch profile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = profileMenuOpen, onDismissRequest = { profileMenuOpen = false }) {
                        state.profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(profile.displayName)
                                        if (profile.isDefault) {
                                            Text(
                                                "default",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    profileMenuOpen = false
                                    onSwitchProfile(profile.id)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                ConnectionDot(state.connection)
                Spacer(Modifier.width(14.dp))
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable {
                            searchOpen = !searchOpen
                            if (!searchOpen) onSearch("")
                        },
                )
                Spacer(Modifier.width(14.dp))
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = "Filter groups",
                    tint = if (filtersOpen || state.hiddenSources.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.clickable { filtersOpen = !filtersOpen },
                )
                Spacer(Modifier.width(14.dp))
                Icon(
                    if (state.showArchived) Icons.Filled.Unarchive else Icons.Outlined.Archive,
                    contentDescription = if (state.showArchived) "Show active" else "Show archived",
                    tint = if (state.showArchived) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.clickable(onClick = onToggleArchivedView),
                )
            }

            if (searchOpen) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearch,
                    placeholder = { Text("Search conversations") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                )
            }

            // Source-group filter: chips stay selected while visible; unselect
            // to hide that group (cron, matrix, discord, …) from the list.
            if (filtersOpen && sourceOptions.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sourceOptions.forEach { source ->
                        FilterChip(
                            selected = source !in state.hiddenSources,
                            onClick = { onToggleSource(source) },
                            label = { Text(source) },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (state.sessionsLoading && visibleSessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (visibleSessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            state.hiddenSources.isNotEmpty() && state.searchQuery.isBlank() ->
                                "Nothing here — some groups are filtered out"
                            state.showArchived -> "No archived conversations"
                            state.searchQuery.isBlank() -> "No conversations yet"
                            else -> "No matches"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = state.sessionsLoading,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visibleSessions, key = { it.key }) { session ->
                            SwipeableSessionRow(
                                session = session,
                                selected = session.key == state.chat.sessionKey,
                                gesturesEnabled = state.features.sessionFlags,
                                onClick = { onOpenSession(session.key) },
                                onLongClick = { contextSession = session },
                                onPin = { onPin(session.key, !session.pinned) },
                                onArchive = { onArchive(session.key, !session.archived) },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { onOpenSession(null) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New conversation")
        }
    }

    // Long-press context sheet
    contextSession?.let { session ->
        ModalBottomSheet(onDismissRequest = { contextSession = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (state.features.sessionFlags) {
                    SheetAction(if (session.pinned) "Unpin" else "Pin") {
                        onPin(session.key, !session.pinned)
                        contextSession = null
                    }
                    SheetAction(if (session.archived) "Unarchive" else "Archive") {
                        onArchive(session.key, !session.archived)
                        contextSession = null
                    }
                }
                if (state.features.rename) {
                    SheetAction("Rename") {
                        renameTarget = session
                        contextSession = null
                    }
                }
                SheetAction("Fork") {
                    onFork(session.key)
                    contextSession = null
                }
                SheetAction("Delete") {
                    deleteTarget = session
                    contextSession = null
                }
            }
        }
    }

    renameTarget?.let { session ->
        var title by remember(session.key) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(session.key, title.trim())
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?") },
            text = { Text("\"${session.title}\" will be deleted on the Hermes server for every device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session.key)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

@Composable
private fun ConnectionDot(connection: com.alara.hermes.protocol.ConnectionState) {
    val color = when (connection) {
        is com.alara.hermes.protocol.ConnectionState.Connected -> com.alara.hermes.ui.theme.HermesColors.Positive
        com.alara.hermes.protocol.ConnectionState.Connecting -> com.alara.hermes.ui.theme.HermesColors.Caution
        else -> com.alara.hermes.ui.theme.HermesColors.Danger
    }
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/**
 * Swipe right -> pin/unpin, swipe left -> archive/unarchive. The row snaps
 * back after the action; the server flags are the durable state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSessionRow(
    session: SessionSummary,
    selected: Boolean,
    gesturesEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onPin()
                SwipeToDismissBoxValue.EndToStart -> onArchive()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // always snap back; the refreshed list reflects the flag
        },
        // Default threshold demands a near-full swipe; ~30% feels like Telegram.
        positionalThreshold = { totalDistance -> totalDistance * 0.30f },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = gesturesEnabled,
        enableDismissFromEndToStart = gesturesEnabled,
        backgroundContent = {
            val target = dismissState.dismissDirection
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = when (target) {
                    SwipeToDismissBoxValue.StartToEnd -> Arrangement.Start
                    else -> Arrangement.End
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        when (target) {
                            SwipeToDismissBoxValue.StartToEnd ->
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            SwipeToDismissBoxValue.EndToStart ->
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.background
                        },
                    )
                    .padding(horizontal = 24.dp),
            ) {
                when (target) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (session.pinned) "Unpin" else "Pin",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        Text(
                            if (session.archived) "Unarchive" else "Archive",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (session.archived) Icons.Filled.Unarchive else Icons.Outlined.Archive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Unit
                }
            }
        },
    ) {
        SessionRow(
            session = session,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.background
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (session.pinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                session.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (session.running) {
                RunningIndicator()
            } else {
                Text(
                    formatRelativeTime(session.updatedAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            session.source?.takeIf { it.isNotBlank() && it != "android" }?.let { source ->
                // Colored glyph instead of a text pill: rows scan by shape and
                // colour without adding more words to the list.
                val (icon, tint) = sourceGlyph(source)
                Icon(
                    icon,
                    contentDescription = source,
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                session.preview.ifBlank { "No messages yet" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Stable icon + accent per source group; unknown sources hash to a hue. */
private fun sourceGlyph(source: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.Color> =
    when (source.trim().lowercase()) {
        "matrix" -> Icons.Outlined.ChatBubbleOutline to androidx.compose.ui.graphics.Color(0xFF6BBF8A)
        "discord" -> Icons.Outlined.SportsEsports to androidx.compose.ui.graphics.Color(0xFF7289DA)
        "telegram" -> Icons.Outlined.NearMe to androidx.compose.ui.graphics.Color(0xFF4FA8D8)
        "cli", "tui" -> Icons.Outlined.Terminal to androidx.compose.ui.graphics.Color(0xFF9E9E9E)
        "api_server", "api" -> Icons.Outlined.Smartphone to androidx.compose.ui.graphics.Color(0xFF9A86E8)
        "cron" -> Icons.Outlined.Schedule to androidx.compose.ui.graphics.Color(0xFFD8A04D)
        "kanban" -> Icons.Outlined.ViewKanban to androidx.compose.ui.graphics.Color(0xFF57B8C4)
        "slack" -> Icons.Outlined.Tag to androidx.compose.ui.graphics.Color(0xFFE0AA4E)
        "webui", "dashboard", "desktop" -> Icons.Outlined.Monitor to androidx.compose.ui.graphics.Color(0xFF8AB4F8)
        else -> Icons.Outlined.Tag to androidx.compose.ui.graphics.Color.hsl(
            hue = (source.hashCode().toUInt() % 360u).toFloat(),
            saturation = 0.45f,
            lightness = 0.65f,
        )
    }

@Composable
fun RunningIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "working",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
