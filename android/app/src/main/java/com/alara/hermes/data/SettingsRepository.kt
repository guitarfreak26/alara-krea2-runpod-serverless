package com.alara.hermes.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
        val NotificationContent = booleanPreferencesKey("notification_content")
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
        )
    }

    suspend fun setActiveFirst(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ActiveFirst] = enabled }
    }

    suspend fun setShowToolActivity(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ShowToolActivity] = enabled }
    }

    /** Message content in notifications — OFF by default (lock-screen privacy). */
    val notificationContentEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.NotificationContent] ?: false }

    suspend fun setNotificationContentEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NotificationContent] = enabled }
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
