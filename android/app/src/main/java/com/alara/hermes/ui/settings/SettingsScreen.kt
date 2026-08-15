package com.alara.hermes.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alara.hermes.AppContainer
import com.alara.hermes.data.GatewayMode
import com.alara.hermes.protocol.ConnectionState
import com.alara.hermes.ui.theme.HermesColors
import com.alara.hermes.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    connection: ConnectionState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val server by container.settings.serverSettings.collectAsState(initial = null)
    val appearance by container.settings.appearance.collectAsState(initial = null)
    var signOutConfirm by remember { mutableStateOf(false) }
    var draftsCleared by remember { mutableStateOf(false) }

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
                    Text("Settings", style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            SectionLabel("Appearance")
            val current = appearance?.themeMode ?: ThemeMode.AMOLED
            ThemeMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { container.settings.setThemeMode(mode) } }
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    RadioButton(
                        selected = current == mode,
                        onClick = { scope.launch { container.settings.setThemeMode(mode) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (mode) {
                            ThemeMode.AMOLED -> "AMOLED dark"
                            ThemeMode.DARK -> "Dark"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.SYSTEM -> "Follow system"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            SectionDivider()
            SectionLabel("Connection")
            SettingRow(
                title = server?.url?.takeIf { it.isNotBlank() } ?: "Not configured",
                subtitle = when (server?.mode) {
                    GatewayMode.API_SERVER -> "Hermes API server · Bearer key"
                    GatewayMode.DASHBOARD -> "Hermes dashboard gateway"
                    null -> ""
                },
            )
            SettingRow(
                title = "Status",
                subtitle = when (connection) {
                    is ConnectionState.Connected -> connection.serverVersion ?: "Connected"
                    ConnectionState.Connecting -> "Connecting…"
                    is ConnectionState.Failed -> "Failed"
                    ConnectionState.Disconnected -> "Disconnected"
                },
                subtitleColor = when (connection) {
                    is ConnectionState.Connected -> HermesColors.Positive
                    ConnectionState.Connecting -> HermesColors.Caution
                    else -> MaterialTheme.colorScheme.error
                },
            )
            if (server?.mode == GatewayMode.API_SERVER) {
                val profileNames by container.settings.profileNames.collectAsState(initial = null)
                var profilesText by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(profileNames) {
                    if (profilesText == null && profileNames != null) {
                        profilesText = profileNames!!.joinToString(", ")
                    }
                }
                OutlinedTextField(
                    value = profilesText ?: "",
                    onValueChange = { text ->
                        profilesText = text
                        scope.launch { container.settings.setProfileNames(text.split(',')) }
                    },
                    label = { Text("Profiles") },
                    supportingText = {
                        Text(
                            "Named profiles from desktop, comma-separated (e.g. kimi, grok). " +
                                "The switcher appears in the menu once any are set. " +
                                "Needs profile multiplexing + a per-profile API key on the server.",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            SectionDivider()
            SectionLabel("Conversations")
            val chatSettings by container.settings.chatSettings.collectAsState(initial = null)
            SwitchRow(
                title = "Active conversations first",
                subtitle = "Pin running work to the top of the list",
                checked = chatSettings?.activeFirst == true,
                onChecked = { scope.launch { container.settings.setActiveFirst(it) } },
            )
            SwitchRow(
                title = "Show tool activity",
                subtitle = "Terminal, file and search steps inside conversations",
                checked = chatSettings?.showToolActivity != false,
                onChecked = { scope.launch { container.settings.setShowToolActivity(it) } },
            )
            SwitchRow(
                title = "Stream replies live",
                subtitle = "Off shows a typing indicator and the reply appears in one go",
                checked = chatSettings?.streamLive != false,
                onChecked = { scope.launch { container.settings.setStreamLive(it) } },
            )

            SectionDivider()
            SectionLabel("Notifications")
            val notifContent by container.settings.notificationContentEnabled.collectAsState(initial = false)
            SwitchRow(
                title = "Show message content",
                subtitle = "Off keeps task details away from the lock screen",
                checked = notifContent,
                onChecked = { scope.launch { container.settings.setNotificationContentEnabled(it) } },
            )
            val backgroundWatch by container.settings.backgroundWatchEnabled.collectAsState(initial = false)
            SwitchRow(
                title = "Watch server in background",
                subtitle = "Checks once a minute and notifies when Matrix, Discord or desktop turns finish. Keeps a quiet ongoing notification.",
                checked = backgroundWatch,
                onChecked = { scope.launch { container.settings.setBackgroundWatchEnabled(it) } },
            )
            SettingRow(
                title = "Per-type control",
                subtitle = "Completions, approvals and failures use separate Android channels — long-press a notification to tune them",
            )

            SectionDivider()
            SectionLabel("Storage")
            SettingRow(
                title = "Clear drafts",
                subtitle = if (draftsCleared) "Cleared" else "Remove unsent drafts for every conversation",
                onClick = {
                    scope.launch {
                        container.drafts.clearAll()
                        draftsCleared = true
                    }
                },
            )

            SectionDivider()
            SectionLabel("Account")
            SettingRow(
                title = "Sign out",
                subtitle = "Remove the server and its stored key from this device",
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { signOutConfirm = true },
            )

            SectionDivider()
            SectionLabel("About")
            val version = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "unknown"
            }
            SettingRow(title = "Hermes Mobile", subtitle = "Version $version")
        }
    }
    }

    if (signOutConfirm) {
        AlertDialog(
            onDismissRequest = { signOutConfirm = false },
            title = { Text("Sign out?") },
            text = { Text("The saved server, encrypted key, and local drafts will be removed from this device. Nothing on the Hermes server is affected.") },
            confirmButton = {
                TextButton(onClick = {
                    signOutConfirm = false
                    scope.launch {
                        container.dropGateway()
                        container.drafts.clearAll()
                        container.settings.signOut()
                    }
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { signOutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
            )
        }
    }
}
