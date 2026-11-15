package com.novainc.agt.legs

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.JsonObject
import com.novainc.agt.core.AgtService
import com.novainc.agt.core.AgtStore
import com.novainc.agt.core.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Real Firebase Cloud Messaging: the push leg of AGT.
 *
 * The app is registered in Firebase project agentlink-84609 (Android app
 * com.novainc.agt). Phoenix sends high priority FCM messages with the
 * project service account (FCM HTTP v1). Two payload shapes:
 *
 * 1. Notification plus data {type: "alert", title, message}: lands as a
 *    high importance phone notification through AlertNotifier, even when
 *    the app process was dead: FCM wakes it to deliver here.
 * 2. Data only {type: "wake"}: silently starts the foreground host
 *    service so the job engine polls Supabase at once instead of waiting
 *    for the next cycle.
 *
 * Token lifecycle: onNewToken stores the registration token in settings;
 * it rides every Supabase host heartbeat inside telemetry (fcmToken),
 * which is the address Phoenix pushes to. Nothing about the token is a
 * secret: it is a device address, scoped to this app and project.
 */
class AgtMessagingService : FirebaseMessagingService() {

    private val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val store = AgtStore(applicationContext)
        val settings = store.loadSettings()
        if (settings.fcmToken == token) return
        store.saveSettings(settings.copy(fcmToken = token))
        // Publish the new address at once instead of waiting for the next
        // 60 second heartbeat: upsert the host row with fresh telemetry.
        svcScope.launch {
            try {
                val telemetry = JsonObject().apply {
                    addProperty("fcmToken", token)
                    addProperty("fcmRotatedAt", System.currentTimeMillis())
                }
                SupabaseLeg(store).upsertHost(
                    settings.deviceId, "AGT host", settings.devicePublicKey, telemetry
                )
            } catch (e: Exception) {
                // Not configured yet or offline: the regular heartbeat
                // carries the token as soon as the leg is live.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data["type"] == "wake" && message.notification == null) {
            // Silent wake: bring the host service up now.
            AgtService.start(applicationContext)
            return
        }
        val (title, body) = FcmPayload.resolve(
            data, message.notification?.title, message.notification?.body
        )
        AlertNotifier.notify(applicationContext, title, body, data["kind"] ?: "alert")
        if (data["type"] == "wake") {
            AgtService.start(applicationContext)
        }
    }
}

/** Pure payload resolution, unit tested: notification fields win, then
 * data fields, then plain defaults. A malformed push must still land as
 * a readable notification, never a crash. */
object FcmPayload {
    fun resolve(
        data: Map<String, String>,
        notifTitle: String?,
        notifBody: String?
    ): Pair<String, String> {
        val title = notifTitle?.takeIf { it.isNotBlank() }
            ?: data["title"]?.takeIf { it.isNotBlank() }
            ?: "AGT alert"
        val body = notifBody?.takeIf { it.isNotBlank() }
            ?: data["message"]?.takeIf { it.isNotBlank() }
            ?: data["body"]?.takeIf { it.isNotBlank() }
            ?: ""
        return title to body
    }
}
