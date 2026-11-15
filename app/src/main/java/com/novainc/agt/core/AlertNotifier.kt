package com.novainc.agt.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.novainc.agt.MainActivity
import com.novainc.agt.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instant on-phone alerts, organized by the NotifyKind catalogue: the
 * moment the supervisor sees a deployment die or come back, the tunnel
 * drops or reconnects, a job finishes or fails, or Phoenix pushes an
 * alert through FCM or the Firebase doorbell, the owner hears about it
 * on the lock screen without opening the app, on the channel that
 * matches the event's weight.
 *
 * This is deliberately local-first: the phone IS the server, so the
 * phone is the first to know when something changes, and a local
 * notification is the fastest possible alert on the device.
 */
object AlertNotifier {
    const val CHANNEL_ID = NotifyKind.CHANNEL_ALERTS
    private val nextId = AtomicInteger(2000)

    private data class ChannelSpec(val id: String, val name: String, val importance: Int, val description: String)

    private val CHANNELS = listOf(
        ChannelSpec(NotifyKind.CHANNEL_CRITICAL, "AGT critical", NotificationManager.IMPORTANCE_HIGH,
            "The host is hurt: crash loops, tunnel down"),
        ChannelSpec(NotifyKind.CHANNEL_ALERTS, "AGT alerts", NotificationManager.IMPORTANCE_HIGH,
            "Phoenix alerts and job failures"),
        ChannelSpec(NotifyKind.CHANNEL_SECURITY, "AGT security", NotificationManager.IMPORTANCE_HIGH,
            "Pairing events and rejected signatures"),
        ChannelSpec(NotifyKind.CHANNEL_JOBS, "AGT jobs", NotificationManager.IMPORTANCE_DEFAULT,
            "Agent job completions"),
        ChannelSpec(NotifyKind.CHANNEL_STATUS, "AGT status", NotificationManager.IMPORTANCE_LOW,
            "Recoveries and quiet state changes")
    )

    fun ensureChannel(context: Context) = ensureChannels(context)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            for (spec in CHANNELS) {
                if (nm.getNotificationChannel(spec.id) == null) {
                    val channel = NotificationChannel(spec.id, spec.name, spec.importance).apply {
                        description = spec.description
                        enableVibration(spec.importance >= NotificationManager.IMPORTANCE_HIGH)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }
    }

    fun notify(
        context: Context, title: String, text: String, kind: String? = NotifyKind.ALERT,
        stableKey: String? = null
    ) {
        ensureChannels(context)
        val channelId = NotifyKind.channelFor(kind)
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val priority = if (NotifyKind.isHigh(kind)) NotificationCompat.PRIORITY_HIGH
            else if (channelId == NotifyKind.CHANNEL_STATUS) NotificationCompat.PRIORITY_LOW
            else NotificationCompat.PRIORITY_DEFAULT
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_agt)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stat_agt, "Open AGT", openIntent)
            .setAutoCancel(true)
            .setPriority(priority)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // A stable key pins the notification id, so a repeated alert
        // (the tunnel dropping again inside the same outage) replaces
        // the one already on screen instead of stacking a new card for
        // every retry. One-off events keep unique ids.
        val notificationId = if (stableKey != null) {
            3000 + (stableKey.hashCode() and 0x3FFF)
        } else {
            nextId.getAndIncrement()
        }
        try {
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted yet: the in-app job feed and
            // service log still carry the event, nothing crashes.
        }
    }
}

/**
 * Pure status logic for the home screen widget, kept Android-free so it
 * is unit tested on the JVM.
 */
object WidgetStatus {
    /** One line summary, for example "Online, 2 of 3 running". */
    fun statusLine(serviceRunning: Boolean, running: Int, total: Int): String {
        val head = if (serviceRunning) "Online" else "Stopped"
        return if (total == 0) "$head, no deployments yet"
        else "$head, $running of $total running"
    }

    /** Which status dot to draw: "emerald", "danger", or "dim". */
    fun dotFor(serviceRunning: Boolean, running: Int, total: Int): String {
        return when {
            !serviceRunning -> "dim"
            total > 0 && running == 0 -> "danger"
            else -> "emerald"
        }
    }
}
