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

/** Stable identity colour per bot, derived from the profile name. */
private fun identityColor(id: String): Color =
    Color.hsl(
        hue = (id.hashCode().toUInt() % 360u).toFloat(),
        saturation = 0.45f,
        lightness = 0.55f,
    )

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
            Box(
                Modifier
                    .size(44.dp)
                    .background(identityColor(profile.id), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    profile.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
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
