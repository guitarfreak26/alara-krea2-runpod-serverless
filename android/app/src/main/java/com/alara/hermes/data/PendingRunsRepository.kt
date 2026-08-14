package com.alara.hermes.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.pendingRunStore by preferencesDataStore(name = "hermes_pending_runs")

@Serializable
data class PendingRun(
    val runId: String,
    val sessionKey: String,
    val startedAtMs: Long,
)

/**
 * Runs this device submitted that may still be executing on the VPS.
 * Survives process death so the next launch can reconcile via
 * GET /v1/runs/{id} instead of losing track of the task.
 */
class PendingRunsRepository(private val context: Context) {

    private val key = stringPreferencesKey("pending_runs_v1")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(): List<PendingRun> {
        val raw = context.pendingRunStore.data.first()[key] ?: return emptyList()
        return runCatching { json.decodeFromString<List<PendingRun>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun add(run: PendingRun) {
        val current = list().filterNot { it.runId == run.runId }
        write(current + run)
    }

    suspend fun remove(runId: String) {
        write(list().filterNot { it.runId == runId })
    }

    suspend fun removeBySession(sessionKey: String) {
        write(list().filterNot { it.sessionKey == sessionKey })
    }

    private suspend fun write(runs: List<PendingRun>) {
        // Stale records (>24h) are dropped: the server has long since pruned them.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val pruned = runs.filter { it.startedAtMs > cutoff }
        context.pendingRunStore.edit { prefs ->
            prefs[key] = json.encodeToString(pruned)
        }
    }
}
