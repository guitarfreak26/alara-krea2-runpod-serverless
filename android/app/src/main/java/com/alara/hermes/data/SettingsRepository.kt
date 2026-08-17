package com.alara.hermes.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alara.hermes.security.CryptoBox
import com.alara.hermes.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hermes_settings")

/** Which Hermes surface the saved server speaks. */
enum class GatewayMode { DASHBOARD, API_SERVER }

data class ServerSettings(
    val url: String,
    val token: String,
    val activeProfile: String?,
    val mode: GatewayMode = GatewayMode.DASHBOARD,
) {
    val isConfigured: Boolean get() = url.isNotBlank() && token.isNotBlank()
}

data class AppearanceSettings(
    val themeMode: ThemeMode,
)

data class ChatSettings(
    /** Running conversations sort to the top of the list (Codex-style). */
    val activeFirst: Boolean,
    /** Render tool-activity rows inside conversations. */
    val showToolActivity: Boolean,
    /** Show replies token-by-token; off shows a typing dot until complete. */
    val streamLive: Boolean = true,
)

/**
 * App settings + credential storage. The gateway token is encrypted with a
 * Keystore-held AES key before persisting; everything else is plain preferences.
 * Never log values read from here.
 */
class SettingsRepository(
    private val context: Context,
    private val cryptoBox: CryptoBox = CryptoBox(),
) {
    private object Keys {
        val ServerUrl = stringPreferencesKey("server_url")
        val TokenCiphertext = stringPreferencesKey("token_ciphertext")
        val ActiveProfile = stringPreferencesKey("active_profile")
        val GatewayModeKey = stringPreferencesKey("gateway_mode")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val Onboarded = booleanPreferencesKey("onboarded")
        val ActiveFirst = booleanPreferencesKey("sessions_active_first")
        val ShowToolActivity = booleanPreferencesKey("show_tool_activity")
        val StreamLive = booleanPreferencesKey("stream_live")
        val BackgroundWatch = booleanPreferencesKey("background_watch")
        val NotificationContent = booleanPreferencesKey("notification_content")
        val LastOpenSession = stringPreferencesKey("last_open_session")
        val HiddenSources = stringSetPreferencesKey("hidden_session_sources")
        val ProfileNames = stringPreferencesKey("profile_names")
        val PinnedBots = stringSetPreferencesKey("pinned_bots")
        val BotChatIds = stringSetPreferencesKey("bot_chat_ids")
    }

    val serverSettings: Flow<ServerSettings> = context.dataStore.data.map { prefs ->
        ServerSettings(
            url = prefs[Keys.ServerUrl].orEmpty(),
            token = prefs[Keys.TokenCiphertext]?.let { cryptoBox.decrypt(it) }.orEmpty(),
            activeProfile = prefs[Keys.ActiveProfile],
            mode = prefs[Keys.GatewayModeKey]
                ?.let { runCatching { GatewayMode.valueOf(it) }.getOrNull() }
                ?: GatewayMode.DASHBOARD,
        )
    }

    val appearance: Flow<AppearanceSettings> = context.dataStore.data.map { prefs ->
        AppearanceSettings(
            themeMode = prefs[Keys.ThemeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.AMOLED,
        )
    }

    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[Keys.Onboarded] ?: false }

    val chatSettings: Flow<ChatSettings> = context.dataStore.data.map { prefs ->
        ChatSettings(
            activeFirst = prefs[Keys.ActiveFirst] ?: false,
            showToolActivity = prefs[Keys.ShowToolActivity] ?: true,
            streamLive = prefs[Keys.StreamLive] ?: true,
        )
    }

    suspend fun setActiveFirst(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ActiveFirst] = enabled }
    }

    suspend fun setStreamLive(enabled: Boolean) {
        context.dataStore.edit { it[Keys.StreamLive] = enabled }
    }

    suspend fun setShowToolActivity(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ShowToolActivity] = enabled }
    }

    /** Background watcher: notify about turns driven from other devices. */
    val backgroundWatchEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.BackgroundWatch] ?: false }

    suspend fun setBackgroundWatchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BackgroundWatch] = enabled }
    }

    /** Message content in notifications — OFF by default (lock-screen privacy). */
    val notificationContentEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.NotificationContent] ?: false }

    suspend fun setNotificationContentEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NotificationContent] = enabled }
    }

    /** Source groups (cron, matrix, discord, …) hidden from the session list. */
    val hiddenSources: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.HiddenSources] ?: emptySet() }

    suspend fun toggleHiddenSource(source: String) {
        val key = source.trim().lowercase()
        if (key.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HiddenSources] ?: emptySet()
            prefs[Keys.HiddenSources] = if (key in current) current - key else current + key
        }
    }

    /**
     * Profile names for the API-server surface (kimi, grok, …), user-entered
     * because that surface has no profile discovery endpoint. Order preserved.
     */
    val profileNames: Flow<List<String>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.ProfileNames] ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    suspend fun setProfileNames(names: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ProfileNames] = names.map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(",")
        }
    }

    /** Bots pinned to the top of the agent roster (under rooms). Local pref. */
    val pinnedBots: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.PinnedBots] ?: emptySet() }

    suspend fun togglePinnedBot(profileId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.PinnedBots] ?: emptySet()
            prefs[Keys.PinnedBots] =
                if (profileId in current) current - profileId else current + profileId
        }
    }

    /**
     * Canonical Bot Chat session per profile ("profile|sessionKey"). Keeps
     * exactly ONE Bot Chat per bot even when the session-list window misses
     * the canonical row.
     */
    val botChatIds: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.BotChatIds] ?: emptySet()).mapNotNull { entry ->
            entry.split("|", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
    }

    suspend fun setBotChatId(profileId: String, sessionKey: String) {
        context.dataStore.edit { prefs ->
            val kept = (prefs[Keys.BotChatIds] ?: emptySet())
                .filterNot { it.startsWith("$profileId|") }
            prefs[Keys.BotChatIds] = (kept + "$profileId|$sessionKey").toSet()
        }
    }

    /** Canonical id of the conversation that was open — resumed after relaunch. */
    val lastOpenSession: Flow<String?> = context.dataStore.data.map { it[Keys.LastOpenSession] }

    suspend fun setLastOpenSession(sessionKey: String?) {
        context.dataStore.edit { prefs ->
            if (sessionKey == null) prefs.remove(Keys.LastOpenSession) else prefs[Keys.LastOpenSession] = sessionKey
        }
    }

    suspend fun currentServer(): ServerSettings = serverSettings.first()

    suspend fun saveConnection(url: String, token: String, mode: GatewayMode) {
        val ciphertext = cryptoBox.encrypt(token)
        context.dataStore.edit { prefs ->
            prefs[Keys.ServerUrl] = url.trim().trimEnd('/')
            prefs[Keys.TokenCiphertext] = ciphertext
            prefs[Keys.GatewayModeKey] = mode.name
            prefs[Keys.Onboarded] = true
        }
    }

    suspend fun setActiveProfile(profile: String) {
        context.dataStore.edit { it[Keys.ActiveProfile] = profile }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.ThemeMode] = mode.name }
    }

    suspend fun signOut() {
        context.dataStore.edit { it.clear() }
        cryptoBox.reset()
    }
}
