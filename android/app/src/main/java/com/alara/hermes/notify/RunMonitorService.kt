package com.alara.hermes.notify

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alara.hermes.MainActivity
import com.alara.hermes.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service held while a turn this device submitted is running.
 * It keeps the process (and therefore the SSE stream) alive when the app is
 * backgrounded, so long VPS tasks finish streaming and can notify — without
 * a permanent battery-hungry connection when idle.
 */
class RunMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NotificationCenter.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hermes is working…")
            .setContentText("The task keeps running if you leave the app")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        // The service is a lifecycle anchor only; if the system recreates it
        // without an active turn, NotificationCenter stops it again promptly.
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.alara.hermes.notify.STOP"

        /** Balanced start/stop: the service stays up while any turn is active. */
        private val activeTurns = AtomicInteger(0)

        fun start(context: Context) {
            activeTurns.incrementAndGet()
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RunMonitorService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            if (activeTurns.updateAndGet { if (it > 0) it - 1 else 0 } == 0) {
                runCatching {
                    context.startService(
                        Intent(context, RunMonitorService::class.java).setAction(ACTION_STOP),
                    )
                }
            }
        }
    }
}
