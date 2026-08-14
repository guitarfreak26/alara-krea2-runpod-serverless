package com.alara.hermes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.draftStore by preferencesDataStore(name = "hermes_drafts")

/** Per-session unsent composer drafts. Survives navigation and process death. */
class DraftsRepository(private val context: Context) {

    private fun key(sessionKey: String) = stringPreferencesKey("draft:$sessionKey")

    fun draft(sessionKey: String): Flow<String> =
        context.draftStore.data.map { it[key(sessionKey)].orEmpty() }

    suspend fun save(sessionKey: String, text: String) {
        context.draftStore.edit { prefs ->
            if (text.isBlank()) prefs.remove(key(sessionKey)) else prefs[key(sessionKey)] = text
        }
    }

    suspend fun clearAll() {
        context.draftStore.edit { it.clear() }
    }
}
