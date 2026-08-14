package com.alara.hermes.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.alara.hermes.MainActivity
import com.alara.hermes.R
import com.alara.hermes.data.PendingRun
import com.alara.hermes.data.PendingRunsRepository
import com.alara.hermes.data.SettingsRepository
import com.alara.hermes.protocol.ApiServerGateway
import com.alara.hermes.protocol.HermesGateway
import com.alara.hermes.protocol.TurnEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Maps gateway turn events onto Android notifications and the run-monitor
 * foreground service. Channels give the user per-type control; lock-screen
 * privacy defaults to generic text until explicitly enabled.
 */
class NotificationCenter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val pendingRuns: PendingRunsRepository,
    private val scope: CoroutineScope,
) {
    companion object {
        const val CHANNEL_COMPLETIONS = "completions"
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_FAILURES = "failures"
        const val CHANNEL_ONGOING = "ongoing"
        const val EXTRA_OPEN_SESSION = "open_session"
    }

    private var attachJob: Job? = null

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_COMPLETIONS, "Task completions", NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Hermes finished a task" },
                NotificationChannel(
                    CHANNEL_APPROVALS, "Approvals needed", NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Hermes is waiting for your decision" },
                NotificationChannel(
                    CHANNEL_FAILURES, "Failures", NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "A Hermes task failed" },
                NotificationChannel(
                    CHANNEL_ONGOING, "Background work", NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "Shown while Hermes is working on a task" },
            ),
        )
    }

    /** Rebind to a (new) gateway; the previous collector is cancelled. */
    fun attach(gateway: HermesGateway) {
        attachJob?.cancel()
        attachJob = scope.launch {
            gateway.turnEvents.collect { event -> onTurnEvent(event) }
        }
        scope.launch { reconcileOrphans(gateway) }
    }

    fun detach() {
        attachJob?.cancel()
        attachJob = null
        RunMonitorService.stop(context)
    }

    private suspend fun onTurnEvent(event: TurnEvent) {
        when (event) {
            is TurnEvent.Started -> {
                event.runId?.let {
                    pendingRuns.add(PendingRun(it, event.sessionKey, System.currentTimeMillis()))
                }
                RunMonitorService.start(context)
            }
            is TurnEvent.Completed -> {
                pendingRuns.removeBySession(event.sessionKey)
                RunMonitorService.stop(context)
                if (!appInForeground()) {
                    post(
                        channel = CHANNEL_COMPLETIONS,
                        sessionKey = event.sessionKey,
                        title = "Hermes finished a task",
                        privateText = event.preview.ifBlank { "Tap to see the result" },
                    )
                }
            }
            is TurnEvent.Failed -> {
                pendingRuns.removeBySession(event.sessionKey)
                RunMonitorService.stop(context)
                if (!appInForeground()) {
                    post(
                        channel = CHANNEL_FAILURES,
                        sessionKey = event.sessionKey,
                        title = "Hermes task failed",
                        privateText = event.error.take(160),
                    )
                }
            }
            is TurnEvent.ApprovalRequested -> {
                if (!appInForeground()) {
                    post(
                        channel = CHANNEL_APPROVALS,
                        sessionKey = event.sessionKey,
                        title = "Hermes needs your approval",
                        privateText = event.prompt.take(160),
                    )
                }
            }
        }
    }

    /**
     * After process death: ask the server what happened to runs we submitted
     * but never saw finish. The reopened app refreshes transcripts itself, so
     * completed runs are just cleared; still-running ones keep their record.
     */
    private suspend fun reconcileOrphans(gateway: HermesGateway) {
        val api = gateway as? ApiServerGateway ?: run {
            return
        }
        pendingRuns.list().forEach { pending ->
            val outcome = api.reconcileRun(pending.runId)
            when (outcome?.status) {
                null, "completed", "failed", "cancelled" -> pendingRuns.remove(pending.runId)
                else -> Unit // queued/running/waiting_for_approval: keep tracking
            }
        }
    }

    private fun appInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private suspend fun post(
        channel: String,
        sessionKey: String,
        title: String,
        privateText: String,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val showContent = settings.notificationContentEnabled.first()
        val tapIntent = PendingIntent.getActivity(
            context,
            sessionKey.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_SESSION, sessionKey)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (showContent) privateText else "Tap to open")
            .setStyle(
                if (showContent) NotificationCompat.BigTextStyle().bigText(privateText) else null,
            )
            .setVisibility(
                if (showContent) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET,
            )
            .setPublicVersion(publicVersion)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(sessionKey.hashCode() xor channel.hashCode(), notification)
    }
}
