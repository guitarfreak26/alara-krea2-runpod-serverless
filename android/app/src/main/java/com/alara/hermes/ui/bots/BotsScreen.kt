package com.alara.hermes.ui.bots

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alara.hermes.protocol.ConnectionState
import com.alara.hermes.protocol.BotRoom
import com.alara.hermes.protocol.HermesProfile
import com.alara.hermes.ui.HomeUiState
import com.alara.hermes.ui.HomeViewModel
import com.alara.hermes.ui.chat.MediaAuth
import com.alara.hermes.ui.theme.HermesColors
import com.alara.hermes.util.formatRelativeTime

/**
 * Bot Mode roster: the live profiles from /v1/profiles, one row per bot.
 * Tapping opens that bot's canonical "Bot Chat" conversation. The roster is
 * fully dynamic — nothing here hardcodes today's profile names — and the
 * app only OPERATES the roster (no create/delete/edit of production
 * profiles from mobile).
 *
 * Design follows the native Bot Mode plugin (see
 * docs/BOT_MODE_REFERENCE.md): roster rows are avatar + name + latest
 * preview + timestamp, presence is per-bot (busy flag or activity within
 * the plugin's 90s window), and the header dot is the OVERALL gateway
 * connection — the two are deliberately separate signals.
 */
@Composable
fun BotsScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    onOpenBot: (HermesProfile) -> Unit,
    onOpenRoom: (BotRoom) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val mediaAuth by viewModel.mediaAuth.collectAsState()

    // The roster re-fetches on open so profiles created on the VPS appear.
    LaunchedEffect(Unit) {
        viewModel.reloadProfiles()
        viewModel.loadRooms()
    }

    BackHandler(onBack = onBack)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 4.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text("Bots", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(10.dp))
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
                }
            }

            // Two views: server-backed Rooms, and the individual bots.
            var tab by rememberSaveable { mutableStateOf(1) }
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Rooms") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Bots") })
            }
            if (tab == 0) {
                RoomsTab(state, mediaAuth, onOpenRoom)
            } else when {
                state.profiles.isEmpty() && state.sessionsLoading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                state.profiles.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No profiles reported by the server.\nCheck that /v1/profiles is available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.profiles, key = { it.id }) { profile ->
                        BotRow(
                            profile = profile,
                            selected = profile.id == state.activeProfile,
                            mediaAuth = mediaAuth,
                            onClick = { onOpenBot(profile) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Server-backed rooms (docs/BOT_ROOMS_API.md). Nothing here is faked: when
 * the capability is absent a quiet unsupported note shows instead of broken
 * controls, and the room list renders whatever the server defines.
 */
@Composable
internal fun RoomsTab(
    state: HomeUiState,
    mediaAuth: MediaAuth?,
    onOpenRoom: (BotRoom) -> Unit,
) {
    when {
        !state.features.botRooms -> CenteredNote(
            "Rooms require a newer ALARA server.\nIndividual bots keep working meanwhile.",
        )
        state.rooms.isEmpty() -> CenteredNote("No rooms configured on the server")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(state.rooms, key = { it.id }) { room ->
                RoomRow(room, mediaAuth) { onOpenRoom(room) }
            }
        }
    }
}

@Composable
internal fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun RoomRow(room: BotRoom, mediaAuth: MediaAuth?, onClick: () -> Unit) {
    val manager = room.members.firstOrNull { it.profileId == room.managerProfileId }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BotAvatar(
            HermesProfile(
                id = room.managerProfileId,
                displayName = manager?.displayName ?: room.displayName,
                avatarShape = manager?.avatarShape,
                avatarColor = manager?.avatarColor,
                avatarUrl = manager?.avatarUrl,
            ),
            44.dp,
            mediaAuth,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (room.unread == true) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
            Text(
                room.members.joinToString("  ·  ") { it.displayName },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            room.preview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (room.busy == true) {
                com.alara.hermes.ui.home.RunningIndicator()
            } else {
                room.lastActiveMs?.let {
                    Text(
                        formatRelativeTime(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 74.dp),
    )
}

/*
 * Avatar identity mirrors the native Bot Mode plugin
 * (NousResearch/Hermes-Bot-Mode): a flat geometric body with two eyes.
 * Shape comes from the same 31-multiplier name hash over the same shape
 * list, and the default body colour is the plugin's default orange. The
 * installed ALARA plugin syncs custom avatars through the backend; when
 * /v1/profiles advertises avatar metadata it wins over this default.
 */
internal val AVATAR_SHAPES = listOf("circle", "squircle", "pill", "triangle", "hexagon", "cloud", "drop")
private val AVATAR_BODY = Color(0xFFF97316)
private val AVATAR_INK = Color(0xFF1C1917)

internal fun defaultShapeFor(name: String): String {
    var hash = 0u
    for (ch in name) hash = hash * 31u + ch.code.toUInt()
    return AVATAR_SHAPES[(hash % AVATAR_SHAPES.size.toUInt()).toInt()]
}

/**
 * Avatar precedence: server-synced image (ALARA plugin backend sync) →
 * server-synced shape/colour → upstream geometric default. Auth headers ride
 * only to the gateway host, mirroring MediaGallery's rule.
 */
@Composable
internal fun BotAvatar(
    profile: HermesProfile,
    sizeDp: androidx.compose.ui.unit.Dp,
    mediaAuth: com.alara.hermes.ui.chat.MediaAuth?,
) {
    val url = profile.avatarUrl
    if (!url.isNullOrBlank()) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val request = androidx.compose.runtime.remember(url) {
            coil.request.ImageRequest.Builder(context)
                .data(url)
                .apply {
                    val host = runCatching { java.net.URI(url).host }.getOrNull()
                    if (mediaAuth != null && host != null &&
                        host.equals(mediaAuth.host, ignoreCase = true)
                    ) {
                        setHeader("Authorization", mediaAuth.header)
                    }
                }
                .crossfade(true)
                .build()
        }
        coil.compose.AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp)
                .clip(CircleShape),
        )
        return
    }
    GeometricAvatar(
        name = profile.id,
        shapeOverride = profile.avatarShape,
        colorOverride = profile.avatarColor,
        sizeDp = sizeDp,
    )
}

@Composable
internal fun GeometricAvatar(
    name: String,
    shapeOverride: String?,
    colorOverride: String?,
    sizeDp: androidx.compose.ui.unit.Dp,
) {
    val shape = shapeOverride?.takeIf { it in AVATAR_SHAPES } ?: defaultShapeFor(name)
    val body = colorOverride?.let { hex ->
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    } ?: AVATAR_BODY
    androidx.compose.foundation.Canvas(Modifier.size(sizeDp)) {
        // Perceptual luminance: eyes flip light on dark bodies (upstream rule).
        val eyeColor = if (0.2126f * body.red + 0.7152f * body.green + 0.0722f * body.blue < 0.43f) {
            Color(0xFFF5F5F4)
        } else {
            AVATAR_INK
        }
        val w = size.width
        val h = size.height
        when (shape) {
            "circle" -> drawCircle(body, radius = w / 2)
            "squircle" -> drawRoundRect(
                body,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.3f),
            )
            "pill" -> drawRoundRect(
                body,
                topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.1f),
                size = androidx.compose.ui.geometry.Size(w, h * 0.8f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.4f),
            )
            "triangle" -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2, 0f); lineTo(w, h); lineTo(0f, h); close()
                },
                body,
            )
            "hexagon" -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.5f, 0f); lineTo(w * 0.93f, h * 0.25f)
                    lineTo(w * 0.93f, h * 0.75f); lineTo(w * 0.5f, h)
                    lineTo(w * 0.07f, h * 0.75f); lineTo(w * 0.07f, h * 0.25f); close()
                },
                body,
            )
            "cloud" -> {
                drawCircle(body, radius = w * 0.26f, center = androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.62f))
                drawCircle(body, radius = w * 0.26f, center = androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.62f))
                drawCircle(body, radius = w * 0.32f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.42f))
                drawRoundRect(
                    body,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.5f),
                    size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.38f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.19f),
                )
            }
            "drop" -> {
                drawCircle(body, radius = w * 0.38f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.6f))
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.5f, 0f); lineTo(w * 0.79f, h * 0.5f)
                        lineTo(w * 0.21f, h * 0.5f); close()
                    },
                    body,
                )
            }
        }
        // Eyes: same flat two-dot face as the desktop shapes; low-set on
        // bottom-heavy bodies.
        val eyeY = when (shape) {
            "triangle" -> h * 0.7f
            "drop" -> h * 0.62f
            "cloud" -> h * 0.55f
            else -> h * 0.5f
        }
        val eyeR = w * 0.065f
        drawCircle(eyeColor, radius = eyeR, center = androidx.compose.ui.geometry.Offset(w * 0.38f, eyeY))
        drawCircle(eyeColor, radius = eyeR, center = androidx.compose.ui.geometry.Offset(w * 0.62f, eyeY))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun BotRow(
    profile: HermesProfile,
    selected: Boolean,
    mediaAuth: MediaAuth?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // Per-bot presence, native-plugin semantics: busy now, or wrote within
    // the last 90 seconds. Null when the server sent no summary fields —
    // then no dot is shown rather than a misleading one.
    val presence: Boolean? = when {
        profile.busy == true -> true
        profile.lastActiveMs != null ->
            System.currentTimeMillis() - profile.lastActiveMs!! < 90_000L
        else -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerLow
                else MaterialTheme.colorScheme.background,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box {
            BotAvatar(profile, 44.dp, mediaAuth)
            if (presence != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                        .padding(2.dp)
                        .background(
                            if (presence) HermesColors.Positive else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (profile.isDefault) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "default",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Latest Bot Chat preview when the server provides roster
            // summaries; role/model otherwise.
            val subtitle = profile.preview?.takeIf { it.isNotBlank() }
                ?: profile.description ?: profile.model
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            profile.lastActiveMs?.let {
                Text(
                    formatRelativeTime(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text(
                    "active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 74.dp),
    )
}
