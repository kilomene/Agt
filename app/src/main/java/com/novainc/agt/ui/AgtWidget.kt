package com.novainc.agt.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.novainc.agt.MainActivity
import com.novainc.agt.R
import com.novainc.agt.core.AgtConfig
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.core.AgtService
import com.novainc.agt.core.AgtStore
import com.novainc.agt.core.WidgetStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The AGT home screen widget: the host at a glance without opening the
 * app. Matte black, gold wordmark, emerald status light, in the same
 * design language as the app. It reads the app store directly, so it
 * tells the truth even when the service is stopped, and the service
 * refreshes it on every heartbeat (30s) while it runs.
 */
class AgtWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            try {
                val appContext = context.applicationContext
                val manager = AppWidgetManager.getInstance(appContext)
                val component = ComponentName(appContext, AgtWidget::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isEmpty()) return
                val views = buildViews(appContext)
                for (id in ids) manager.updateAppWidget(id, views)
            } catch (e: Exception) {
                // A widget refresh must never take the host down.
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val store = AgtStore(context)
            val deployments = try {
                store.loadDeployments()
            } catch (e: Exception) {
                emptyList()
            }
            val runningCount = deployments.count { it.status == "running" }
            val total = deployments.size
            val serviceRunning = AgtService.running.value
            val tunnelState = AgtRuntime.peek()?.tunnel?.state?.value?.name ?: "OFF"
            val wake = if (AgtService.wakeLockHeld.value) "Wake lock held" else "Wake lock off"

            val views = RemoteViews(context.packageName, R.layout.agt_widget)
            views.setTextViewText(
                R.id.widget_status,
                WidgetStatus.statusLine(serviceRunning, runningCount, total)
            )
            views.setTextViewText(
                R.id.widget_detail,
                "Tunnel $tunnelState · ${AgtConfig.TUNNEL_HOSTNAME}"
            )
            views.setTextViewText(R.id.widget_sub, wake)
            views.setTextViewText(
                R.id.widget_updated,
                "Updated " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            )
            val dot = when (WidgetStatus.dotFor(serviceRunning, runningCount, total)) {
                "emerald" -> R.drawable.dot_emerald
                "danger" -> R.drawable.dot_danger
                else -> R.drawable.dot_dim
            }
            views.setImageViewResource(R.id.widget_dot, dot)
            val openIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, openIntent)
            return views
        }
    }
}
