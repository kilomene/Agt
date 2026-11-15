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
 * Instant on-phone alerts: the moment the supervisor sees a deployment
 * die or come back, or the tunnel drops or reconnects, the owner hears
 * about it on the lock screen without opening the app. Phoenix can also
 * push an alert through the Firebase doorbell node and it lands here.
 *
 * This is deliberately local-first: the phone IS the server, so the
 * phone is the first to know when something changes, and a local high
 * importance notification is the fastest possible alert on the device.
 */
object AlertNotifier {
    const val CHANNEL_ID = "agt_alerts"
    private val nextId = AtomicInteger(2000)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "AGT alerts", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Server down, back up, and deployment events"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun notify(context: Context, title: String, text: String) {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_agt)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(nextId.getAndIncrement(), notification)
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
