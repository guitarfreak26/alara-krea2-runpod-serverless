package com.alara.hermes.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alara.hermes.protocol.BotRoom
import com.alara.hermes.protocol.ConnectionState
import com.alara.hermes.protocol.HermesProfile
import com.alara.hermes.ui.HomeViewModel
import com.alara.hermes.ui.theme.HermesColors

/**
 * The chat-first HOME: one messenger-style roster of your agents — rooms
 * first, then every profile — like the desktop Bot Mode rail. Tapping opens
 * the agent's canonical Bot Chat (or the room transcript). Threads live in
 * their own tab; this list is the front door.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun AgentsPane(
    viewModel: HomeViewModel,
    onOpenMenu: () -> Unit,
    onOpenBot: (HermesProfile) -> Unit,
    onOpenRoom: (BotRoom) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val mediaAuth by viewModel.mediaAuth.collectAsState()
    var editTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<HermesProfile?>(null)
    }
    var menuTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<HermesProfile?>(null)
    }

    LaunchedEffect(Unit) {
        viewModel.reloadProfiles()
        viewModel.loadRooms()
    }

    editTarget?.let { profile ->
        AvatarEditorSheet(profile, viewModel, onDismiss = { editTarget = null })
    }

    menuTarget?.let { profile ->
        val pinned = profile.id in state.pinnedBots
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { menuTarget = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    profile.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    if (pinned) "Unpin" else "Pin to top",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.togglePinnedBot(profile.id)
                            menuTarget = null
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
                Text(
                    "Edit look",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            menuTarget = null
                            viewModel.ensureAvatarEditing { supported ->
                                if (supported) editTarget = profile
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Text("Hermes", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        when (state.connection) {
                            is ConnectionState.Connected -> HermesColors.Positive
                            ConnectionState.Connecting -> HermesColors.Caution
                            else -> HermesColors.Danger
                        },
                        CircleShape,
                    ),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.profiles.isEmpty() && state.rooms.isEmpty()) {
            CenteredNote(
                if (state.connection is ConnectionState.Connected) {
                    "No agents reported by the server yet"
                } else {
                    "Connecting to your agents…"
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.rooms.isNotEmpty()) {
                    items(state.rooms, key = { "room:${it.id}" }) { room ->
                        RoomRow(room, mediaAuth) { onOpenRoom(room) }
                    }
                }
                // Pinned bots sit directly under the rooms; order is otherwise
                // the server's roster order, unchanged.
                val ordered = state.profiles.sortedByDescending { it.id in state.pinnedBots }
                items(ordered, key = { "bot:${it.id}" }) { profile ->
                    BotRow(
                        profile = profile,
                        selected = profile.id == state.activeProfile &&
                            state.chat.room == null && state.chat.sessionKey != null,
                        mediaAuth = mediaAuth,
                        pinned = profile.id in state.pinnedBots,
                        onClick = { onOpenBot(profile) },
                        onLongClick = { menuTarget = profile },
                    )
                }
            }
        }
    }
}
