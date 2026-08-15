package com.alara.hermes.notify

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.alara.hermes.HermesApp
import com.alara.hermes.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Native replacement for an external push app: while enabled, keeps a quiet
 * foreground service that polls the session list once a minute over the
 * existing authenticated connection and raises a notification when a
 * conversation driven from ANOTHER surface (matrix, discord, desktop, cron)
 * finishes or advances. Turns run from this phone already notify through
 * the gateway's own turn events, so those are skipped here.
 */
class RemoteWatchService : Service() {

    companion object {
        private const val NOTIF_ID = 41
        private const val POLL_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteWatchService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteWatchService::class.java))
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildOngoing())
        scope.launch { watchLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun buildOngoing(): Notification =
        NotificationCompat.Builder(this, NotificationCenter.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watching Hermes")
            .setContentText("Notifies when tasks finish on other devices")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private suspend fun watchLoop() {
        val container = (application as HermesApp).container
        // key -> (updatedAtMs, running) from the previous poll
        val seen = HashMap<String, Pair<Long, Boolean>>()
        val notifiedAt = HashMap<String, Long>()
        var baseline = true
        while (true) {
            runCatching {
                val server = container.settings.currentServer()
                if (!server.isConfigured) return@runCatching
                val gw = container.gatewayFor(server.url, server.token, server.mode)
                    ?: return@runCatching
                val sessions = gw.listSessions(null)
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
                val firstPass = baseline
                sessions.forEach { s ->
                    val prev = seen[s.key]
                    seen[s.key] = s.updatedAtMs to s.running
                    // The app in the foreground shows activity itself; the
                    // first pass only records a baseline so re-enabling the
                    // watcher never replays history as notifications.
                    if (firstPass || prev == null || foreground) return@forEach
                    if (s.source == "android") return@forEach
                    val advanced = s.updatedAtMs > prev.first
                    val finished = prev.second && !s.running
                    if ((advanced || finished) && !s.running &&
                        (notifiedAt[s.key] ?: 0L) < s.updatedAtMs
                    ) {
                        notifiedAt[s.key] = s.updatedAtMs
                        container.notifications.postRemoteActivity(
                            sessionKey = s.key,
                            title = s.title.ifBlank { "Hermes" },
                            text = s.preview.ifBlank { "New activity in this conversation" },
                        )
                    }
                }
                baseline = false
            }
            delay(POLL_MS)
        }
    }
}
