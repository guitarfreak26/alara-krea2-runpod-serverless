package com.alara.hermes.ui.bots

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alara.hermes.protocol.ConnectionState
import com.alara.hermes.protocol.HermesProfile
import com.alara.hermes.ui.HomeViewModel
import com.alara.hermes.ui.theme.HermesColors

/**
 * Bot Mode roster: the live profiles from /v1/profiles, one row per bot.
 * Tapping opens that bot's canonical "Bot Chat" conversation. The roster is
 * fully dynamic — nothing here hardcodes today's profile names — and the
 * app only OPERATES the roster (no create/delete/edit of production
 * profiles from mobile).
 *
 * Visual note: desktop Bot Mode reference material (screenshots, plugin
 * source) is not reachable from this workspace; see
 * docs/BOT_MODE_REFERENCE.md. The layout keeps the app's design language
 * until the desktop reference lands.
 */
@Composable
fun BotsScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    onOpenBot: (HermesProfile) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // The roster re-fetches on open so profiles created on the VPS appear.
    LaunchedEffect(Unit) { viewModel.reloadProfiles() }

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
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            when {
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
                            online = state.connection is ConnectionState.Connected,
                            onClick = { onOpenBot(profile) },
                        )
                    }
                }
            }
        }
    }
}

/*
 * Avatar identity mirrors the native Bot Mode plugin
 * (NousResearch/Hermes-Bot-Mode): a flat geometric body with two eyes.
 * Shape comes from the same 31-multiplier name hash over the same shape
 * list, and the default body colour is the plugin's default orange, so an
 * uncustomized bot looks the same here as on desktop. (Custom colours,
 * uploaded images and pets live in desktop plugin storage the mobile app
 * cannot read — those bots fall back to their default look here.)
 */
private val AVATAR_SHAPES = listOf("circle", "squircle", "pill", "triangle", "hexagon", "cloud", "drop")
private val AVATAR_BODY = Color(0xFFF97316)
private val AVATAR_INK = Color(0xFF1C1917)

private fun defaultShapeFor(name: String): String {
    var hash = 0u
    for (ch in name) hash = hash * 31u + ch.code.toUInt()
    return AVATAR_SHAPES[(hash % AVATAR_SHAPES.size.toUInt()).toInt()]
}

@Composable
private fun BotAvatar(name: String, sizeDp: androidx.compose.ui.unit.Dp) {
    val shape = defaultShapeFor(name)
    androidx.compose.foundation.Canvas(Modifier.size(sizeDp)) {
        val w = size.width
        val h = size.height
        when (shape) {
            "circle" -> drawCircle(AVATAR_BODY, radius = w / 2)
            "squircle" -> drawRoundRect(
                AVATAR_BODY,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.3f),
            )
            "pill" -> drawRoundRect(
                AVATAR_BODY,
                topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.1f),
                size = androidx.compose.ui.geometry.Size(w, h * 0.8f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.4f),
            )
            "triangle" -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2, 0f); lineTo(w, h); lineTo(0f, h); close()
                },
                AVATAR_BODY,
            )
            "hexagon" -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.5f, 0f); lineTo(w * 0.93f, h * 0.25f)
                    lineTo(w * 0.93f, h * 0.75f); lineTo(w * 0.5f, h)
                    lineTo(w * 0.07f, h * 0.75f); lineTo(w * 0.07f, h * 0.25f); close()
                },
                AVATAR_BODY,
            )
            "cloud" -> {
                drawCircle(AVATAR_BODY, radius = w * 0.26f, center = androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.62f))
                drawCircle(AVATAR_BODY, radius = w * 0.26f, center = androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.62f))
                drawCircle(AVATAR_BODY, radius = w * 0.32f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.42f))
                drawRoundRect(
                    AVATAR_BODY,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.5f),
                    size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.38f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.19f),
                )
            }
            "drop" -> {
                drawCircle(AVATAR_BODY, radius = w * 0.38f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.6f))
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.5f, 0f); lineTo(w * 0.79f, h * 0.5f)
                        lineTo(w * 0.21f, h * 0.5f); close()
                    },
                    AVATAR_BODY,
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
        drawCircle(AVATAR_INK, radius = eyeR, center = androidx.compose.ui.geometry.Offset(w * 0.38f, eyeY))
        drawCircle(AVATAR_INK, radius = eyeR, center = androidx.compose.ui.geometry.Offset(w * 0.62f, eyeY))
    }
}

@Composable
private fun BotRow(
    profile: HermesProfile,
    selected: Boolean,
    online: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerLow
                else MaterialTheme.colorScheme.background,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box {
            BotAvatar(profile.id, 44.dp)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(2.dp)
                    .background(
                        if (online) HermesColors.Positive else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
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
            val subtitle = profile.description ?: profile.model
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
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(
                "active",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 74.dp),
    )
}
