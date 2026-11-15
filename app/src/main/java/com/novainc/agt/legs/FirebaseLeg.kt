package com.novainc.agt.legs

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.novainc.agt.core.AgtConfig
import com.novainc.agt.core.AgtStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The Firebase leg: realtime presence and the wake-up doorbell, over the
 * Realtime Database REST API. Firebase is presence and doorbell only,
 * never the database of record: Supabase Postgres stays the truth.
 *
 * Auth: the app uses a Firebase custom token that Phoenix mints with the
 * service account and delivers as a signed Supabase job (kind
 * firebase_token). The job verifies against the Phoenix public key
 * baked into the build, so the token lands on its own with no pairing
 * and nothing for the owner to set up. Until it arrives the leg
 * reports NOT_CONFIGURED and the doorbell falls back to the poll.
 */
class FirebaseLeg(private val store: AgtStore) {
    enum class State { OFF, NOT_CONFIGURED, CONNECTED, ERROR }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    /** Last measured presence write round trip in milliseconds.
     * Null until the first real write lands. */
    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs

    private var loop: Job? = null
    var onWake: (() -> Unit)? = null

    /** Phoenix can push an instant alert to this phone by writing
     * {"at": <ms>, "title": "...", "message": "..."} to the alert node.
     * It lands as a high importance notification via AlertNotifier. */
    var onAlert: ((title: String, message: String, kind: String) -> Unit)? = null
    private var lastWake: Long = 0
    private var lastAlert: Long = 0

    @Volatile private var idToken: String = ""
    @Volatile private var idTokenExpiryMs: Long = 0

    /** The stored token is a Firebase custom token minted by Phoenix.
     * RTDB REST wants an ID token, so exchange the custom token at the
     * Identity Toolkit endpoint (cached for 50 minutes). If the exchange
     * fails, fall back to the stored token itself so behaviour never
     * regresses below the original direct use. */
    private fun authToken(customToken: String): String {
        val now = System.currentTimeMillis()
        if (idToken.isNotEmpty() && now < idTokenExpiryMs) return idToken
        return try {
            val body = JsonObject().apply {
                addProperty("token", customToken)
                addProperty("returnSecureToken", true)
            }
            val req = Request.Builder()
                .url("https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=${AgtConfig.FIREBASE_API_KEY}")
                .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return customToken
                val obj = com.google.gson.JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
                val id = obj.get("idToken")?.asString ?: return customToken
                idToken = id
                idTokenExpiryMs = now + 50 * 60 * 1000
                id
            }
        } catch (e: Exception) {
            customToken
        }
    }

    fun start(scope: CoroutineScope, deviceId: String, telemetry: () -> JsonObject) {
        stop()
        loop = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val custom = store.loadSettings().firebaseToken
                if (custom.isBlank()) {
                    _state.value = State.NOT_CONFIGURED
                    _detail.value = "Key on its way from Phoenix. It lands on its own, nothing to set up."
                    delay(30_000)
                    continue
                }
                try {
                    val token = authToken(custom)
                    val t0 = System.currentTimeMillis()
                    writePresence(deviceId, token, telemetry())
                    _latencyMs.value = System.currentTimeMillis() - t0
                    checkWake(deviceId, token)
                    checkAlert(deviceId, token)
                    _state.value = State.CONNECTED
                    _detail.value = "Presence live, doorbell listening"
                } catch (e: Exception) {
                    _state.value = State.ERROR
                    _detail.value = e.message ?: "Firebase error"
                }
                delay(10_000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        _state.value = State.OFF
        _detail.value = ""
    }

    private fun writePresence(deviceId: String, token: String, telemetry: JsonObject) {
        val body = JsonObject().apply {
            addProperty("online", true)
            addProperty("lastSeen", System.currentTimeMillis())
            addProperty("hostname", AgtConfig.TUNNEL_HOSTNAME)
            add("telemetry", telemetry)
        }
        val req = Request.Builder()
            .url("${AgtConfig.FIREBASE_RTDB_URL}/agt/hosts/$deviceId.json?auth=$token")
            .put(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("RTDB presence HTTP ${resp.code}")
        }
    }

    private fun checkWake(deviceId: String, token: String) {
        val req = Request.Builder()
            .url("${AgtConfig.FIREBASE_RTDB_URL}/agt/hosts/$deviceId/wake.json?auth=$token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val text = (resp.body?.string() ?: "").trim()
            if (text == "null" || text.isEmpty()) return
            val at = text.toLongOrNull() ?: return
            if (at > lastWake) {
                lastWake = at
                onWake?.invoke()
            }
        }
    }

    private fun checkAlert(deviceId: String, token: String) {
        val req = Request.Builder()
            .url("${AgtConfig.FIREBASE_RTDB_URL}/agt/hosts/$deviceId/alert.json?auth=$token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val text = (resp.body?.string() ?: "").trim()
            val alert = parseAlert(text) ?: return
            if (alert.at > lastAlert) {
                lastAlert = alert.at
                onAlert?.invoke(alert.title, alert.message, alert.kind)
            }
        }
    }

    data class Alert(val at: Long, val title: String, val message: String, val kind: String = "alert")

    companion object {
        /** Parse an alert node body; null for empty or malformed input.
         * Pure and unit tested: a bad node must never crash the leg. */
        fun parseAlert(text: String): Alert? {
            if (text.isEmpty() || text == "null") return null
            return try {
                val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
                val at = obj.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
                val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: "AGT alert"
                val message = obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                val kind = obj.get("kind")?.takeIf { it.isJsonPrimitive }?.asString ?: "alert"
                Alert(at, title, message, kind)
            } catch (e: Exception) {
                null
            }
        }
    }
}
