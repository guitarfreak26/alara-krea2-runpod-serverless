package com.alara.hermes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.archivedStore by preferencesDataStore(name = "hermes_archived")

@Serializable
data class ArchivedEntry(
    val sessionKey: String,
    val title: String,
    val archivedAtMs: Long,
)

/**
 * Display-only registry of conversations archived from this device.
 *
 * The server's archived flag is the durable truth (PATCH persists it), but
 * the API-server session list cannot RETURN archived rows, so without this
 * the archived view would always be empty. Entries here only feed that view;
 * unarchiving goes through the server and removes the entry.
 */
class ArchivedRegistry(private val context: Context) {

    private val key = stringPreferencesKey("archived_v1")
    private val json = Json { ignoreUnknownKeys = true }

    val entries: Flow<List<ArchivedEntry>> = context.archivedStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString<List<ArchivedEntry>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun add(sessionKey: String, title: String) {
        context.archivedStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString<List<ArchivedEntry>>(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            val next = current.filterNot { it.sessionKey == sessionKey } +
                ArchivedEntry(sessionKey, title, System.currentTimeMillis())
            prefs[key] = json.encodeToString(next)
        }
    }

    suspend fun remove(sessionKey: String) {
        context.archivedStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString<List<ArchivedEntry>>(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[key] = json.encodeToString(current.filterNot { it.sessionKey == sessionKey })
        }
    }
}
