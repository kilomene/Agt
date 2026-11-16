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
 *
 * A custom token dies 1 hour after it is minted. Phoenix runs an
 * always-on keeper that sends a fresh token well before that, and
 * JobEngine pokes refreshNow() the moment a new token is saved, so
 * the leg applies it instantly. A stored token whose own expiry has
 * passed is treated as no token at all: the leg waits for the fresh
 * one instead of failing against RTDB with a dead key.
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

    // Start parameters, kept so refreshNow() can restart the loop the
    // instant a fresh token is saved by JobEngine.
    private var startScope: CoroutineScope? = null
    private var startDeviceId: String = ""
    private var startTelemetry: (() -> JsonObject)? = null
    @Volatile private var lastCustomToken: String = ""

    /** The last custom token that completed a full presence cycle.
     * Once a token has connected successfully it is proven usable;
     * later transient state changes (a failed cycle, or the restart
     * that every fresh key triggers) must not un-prove it. */
    @Volatile private var provenCustomToken: String = ""

    /** Consecutive failed cycles in the current loop. A proven key
     * tolerates the first failures without flipping the leg to
     * ERROR: one network drop must not read as a dead key. Reset to
     * zero by every successful cycle. */
    @Volatile private var consecutiveFailures: Int = 0

    /** Record a token as proven: in memory for this process, and
     * persisted in settings so the proof survives a restart (the
     * process can die mid-outage, and on the way back the flag must
     * still know this key worked). Persisted only when it changes,
     * so the presence cadence does not rewrite settings forever. */
    private fun markProven(custom: String) {
        provenCustomToken = custom
        try {
            store.updateSettings { s ->
                if (s.provenFirebaseToken == custom) s else s.copy(provenFirebaseToken = custom)
            }
        } catch (e: Exception) {
            // Persistence is a hardening copy; the in-memory proof
            // above still holds for this process.
        }
    }

    /** Whether the current custom token has a proof anywhere:
     * in memory, persisted from an earlier process, or connected
     * right now. */
    private fun hasProof(custom: String, persistedProven: String): Boolean =
        _state.value == State.CONNECTED || provenCustomToken == custom || persistedProven == custom

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
        startScope = scope
        startDeviceId = deviceId
        startTelemetry = telemetry
        // Restarting the loop must not blank the state: every fresh
        // key delivery lands here via refreshNow(), and a restart
        // that passes through OFF is exactly what made the
        // firebaseKeySet flag flap on deliveries that worked.
        cancelLoop()
        loop = scope.launch(Dispatchers.IO) {
            var cycle = 0
            while (isActive) {
                val custom = store.loadSettings().firebaseToken
                if (custom != lastCustomToken) {
                    // A fresh key landed: drop the cached ID token so
                    // the next exchange uses the new key, not the old.
                    lastCustomToken = custom
                    idToken = ""
                    idTokenExpiryMs = 0
                }
                val expiry = if (custom.isBlank()) null else tokenExpiryMs(custom)
                if (custom.isBlank() || (expiry != null && System.currentTimeMillis() > expiry)) {
                    _state.value = State.NOT_CONFIGURED
                    _detail.value = "Key on its way from Phoenix. It lands on its own, nothing to set up."
                    delay(2_000)
                    continue
                }
                try {
                    val token = authToken(custom)
                    // Presence is written on a slow cadence (about
                    // every 10s); the doorbell and alert nodes are
                    // checked every cycle (about every 2s), so a ring
                    // wakes the job engine near-instantly instead of
                    // waiting out the presence interval.
                    val presenceCycle = cycle % 5 == 0
                    if (presenceCycle) {
                        val t0 = System.currentTimeMillis()
                        writePresence(deviceId, token, telemetry())
                        _latencyMs.value = System.currentTimeMillis() - t0
                        // The write landed: the key is proven right
                        // now, before the doorbell and alert reads
                        // below get any chance to fail the cycle.
                        markProven(custom)
                    }
                    cycle++
                    // The doorbell and alert reads are best-effort
                    // extras on top of the presence proof: a failed
                    // read must never fail the cycle, block the
                    // proof, or flip the state.
                    try { checkWake(deviceId, token) } catch (e: Exception) { }
                    try { checkAlert(deviceId, token) } catch (e: Exception) { }
                    if (!presenceCycle) markProven(custom)
                    consecutiveFailures = 0
                    _state.value = State.CONNECTED
                    _detail.value = "Presence live, doorbell listening"
                } catch (e: Exception) {
                    consecutiveFailures++
                    val persisted = try {
                        store.loadSettings().provenFirebaseToken
                    } catch (loadError: Exception) {
                        ""
                    }
                    val proven = hasProof(custom, persisted)
                    if (shouldReportError(consecutiveFailures, proven)) {
                        _state.value = State.ERROR
                    }
                    // Below the tolerance threshold with a proven
                    // key, the state stays CONNECTED: the error is
                    // recorded in the detail, not promoted to a
                    // dead-leg reading.
                    _detail.value = e.message ?: "Firebase error"
                }
                delay(2_000)
            }
        }
    }

    /** Cancel the loop job only, leaving state and detail exactly
     * as they are. start() uses this on every restart; stop() is
     * the deliberate teardown below. */
    private fun cancelLoop() {
        loop?.cancel()
        loop = null
    }

    fun stop() {
        cancelLoop()
        _state.value = State.OFF
        _detail.value = ""
    }

    /** Restart the loop right now. Called by JobEngine the moment a
     * fresh firebase_token job saves a new key, so the leg connects
     * with it instantly instead of waiting out a poll interval. */
    fun refreshNow() {
        val scope = startScope ?: return
        val telemetry = startTelemetry ?: return
        if (startDeviceId.isEmpty()) return
        start(scope, startDeviceId, telemetry)
    }

    /** True while the leg holds a key that is actually usable:
     * a token is stored, its own expiry has not passed, and the
     * token has proven itself by completing a full presence cycle
     * (or is connected right now). Telemetry reports this as
     * firebaseKeySet, so Phoenix's keeper mints the moment it turns
     * false instead of trusting that a token string merely exists.
     *
     * The proof is deliberately sticky. An earlier version also
     * required the state at the sampling instant to be CONNECTED,
     * which made the flag flap false on any single failed cycle and
     * on every key delivery (each fresh key restarts the leg, and
     * the restart passes through OFF). The keeper read those flaps
     * as a missing key and re-minted against a key that worked,
     * and phase gates failed on a two-second network hiccup. A
     * token that has never completed a cycle still reads false, so
     * a stored-but-dead key is still caught. The proof is read from
     * memory and from the persisted copy in settings, so it also
     * survives the process dying mid-outage and restarting. */
    fun hasUsableKey(): Boolean {
        val settings = store.loadSettings()
        val custom = settings.firebaseToken
        if (custom.isBlank()) return false
        val expiry = tokenExpiryMs(custom)
        if (expiry != null && System.currentTimeMillis() > expiry) return false
        return hasProof(custom, settings.provenFirebaseToken)
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
        /** The failure-tolerance decision for one failed cycle:
         * report ERROR only once failures reach the threshold, or
         * immediately when the current token has no proof at all
         * (a never-proven key failing is a dead key, not a hiccup).
         * Pure and unit tested. */
        fun shouldReportError(failures: Int, hasProven: Boolean): Boolean =
            failures >= 3 || !hasProven

        /** The expiry instant (epoch millis) carried inside a Firebase
         * custom token, read from its JWT payload without verifying
         * the signature: the leg only needs to know when the key dies,
         * authenticity is JobEngine's job. Null for anything that is
         * not a JWT with an exp claim. Pure and unit tested. */
        fun tokenExpiryMs(jwt: String): Long? {
            return try {
                val parts = jwt.split(".")
                if (parts.size != 3) return null
                var seg = parts[1].replace('-', '+').replace('_', '/')
                while (seg.length % 4 != 0) seg += "="
                val payload = String(java.util.Base64.getDecoder().decode(seg), Charsets.UTF_8)
                val obj = com.google.gson.JsonParser.parseString(payload).asJsonObject
                val exp = obj.get("exp")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
                exp * 1000
            } catch (e: Exception) {
                null
            }
        }

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
