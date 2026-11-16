package com.novainc.agt.core

import android.content.Context
import com.google.gson.JsonObject
import com.novainc.agt.agent.DeviceIdentity
import com.novainc.agt.agent.JobEngine
import com.novainc.agt.deploy.DeploymentEngine
import com.novainc.agt.legs.FirebaseLeg
import com.novainc.agt.legs.SupabaseLeg
import com.novainc.agt.legs.TunnelLeg
import com.novainc.agt.linux.ProcessSupervisor
import com.novainc.agt.linux.Proot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The process-wide runtime: every component the app owns, built once and
 * shared by the service and the UI. The service starts it; the UI only
 * reads it. Nothing here is a placeholder: each leg reports its real
 * state, and a leg that is not configured says so instead of faking up.
 */
class AgtRuntime private constructor(private val context: Context) {
    val store = AgtStore(context.applicationContext)
    val proot = Proot(context.applicationContext, store)
    val supervisor = ProcessSupervisor(context.applicationContext, store, proot)
    val deployEngine = DeploymentEngine(context.applicationContext, store, proot, supervisor)
    val supabase = SupabaseLeg(store)
    val tunnel = TunnelLeg(context.applicationContext)
    val firebase = FirebaseLeg(store)
    val identity = DeviceIdentity(context.applicationContext, store)
    val jobEngine = JobEngine(store, supabase, proot, supervisor, deployEngine, identity)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncJob: Job? = null
    private var hostId: String? = null
    private var alertsWired = false

    private val _linuxReady = MutableStateFlow(proot.isInstalled())
    val linuxReady: StateFlow<Boolean> = _linuxReady.asStateFlow()

    /** The guest self-test verdict: whether a real guest shell
     * answered its echo, proven once per service start and again
     * after a fresh install completes. Never inferred from file
     * existence: three shipped builds reported a ready tree whose
     * bash could not start. */
    private val _linuxConsoleOk = MutableStateFlow(false)
    val linuxConsoleOk: StateFlow<Boolean> = _linuxConsoleOk.asStateFlow()

    private val _linuxDetail = MutableStateFlow("")
    val linuxDetail: StateFlow<String> = _linuxDetail.asStateFlow()

    private val _installProgress = MutableStateFlow(0f)
    val installProgress: StateFlow<Float> = _installProgress.asStateFlow()

    private val _installStatus = MutableStateFlow("")
    val installStatus: StateFlow<String> = _installStatus.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    /** The Supabase sync truth: the last cycle's error (null when the
     * last cycle landed) and when a cycle last landed. The legs card
     * reads these, so "Live" means a sync actually arrived, never just
     * that a key exists. */
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _lastSyncOkMs = MutableStateFlow(0L)
    val lastSyncOkMs: StateFlow<Long> = _lastSyncOkMs.asStateFlow()

    private var tunnelDownAlerted = false

    /** Ensure a device id exists; the id is stable per phone, forever. */
    fun deviceId(): String {
        val settings = store.loadSettings()
        if (settings.deviceId.isNotEmpty()) return settings.deviceId
        val id = "agt-" + UUID.randomUUID().toString().take(8)
        store.saveSettings(settings.copy(deviceId = id))
        return id
    }

    fun pairingCode(): String {
        val settings = store.loadSettings()
        if (settings.pairingCode.isNotEmpty()) return settings.pairingCode
        val code = (100000..999999).random().toString()
        store.saveSettings(settings.copy(pairingCode = code))
        return code
    }

    fun telemetryJson(): JsonObject {
        val t = Telemetry.read(context)
        return JsonObject().apply {
            addProperty("battery", t?.batteryPercent)
            addProperty("batteryCharging", t?.batteryCharging)
            addProperty("storageFreeGb", t?.storageFreeGb)
            addProperty("memoryAvailGb", t?.memoryAvailGb)
            addProperty("uptimeSec", (t?.deviceUptimeMs ?: 0L) / 1000)
            addProperty("fcmToken", store.loadSettings().fcmToken)
            addProperty("linuxReady", proot.isInstalled())
            addProperty("linuxConsoleOk", _linuxConsoleOk.value)
            addProperty("linuxDetail", _linuxDetail.value.take(160))
            addProperty("appVersion", com.novainc.agt.BuildConfig.VERSION_NAME)
            addProperty("firebaseKeySet", firebase.hasUsableKey())
            addProperty("tunnelState", tunnel.state.value.name)
            addProperty("tunnelDetail", tunnel.detail.value.take(160))
            addProperty("tunnelLog", tunnel.recentOutput().joinToString("\n").takeLast(3500))
            addProperty("syncError", _syncError.value ?: "")
            addProperty("installStatus", _installStatus.value.take(120))
            addProperty("deployments", store.loadDeployments().size)
            addProperty("running", store.loadDeployments().count { it.status == "running" })
        }
    }

    /** First-run Linux install, with progress the UI can show. A
     * failed or interrupted install retries on its own after a
     * minute: the promise is that Linux sets itself up, so a dead
     * install never just sits there waiting for a human. */
    fun installLinux() {
        if (_installing.value) return
        _installing.value = true
        scope.launch {
            val result = proot.install { p, s ->
                _installProgress.value = p
                _installStatus.value = s
            }
            result.onFailure { e ->
                _installStatus.value = "Install failed: ${e.message}"
            }
            _linuxReady.value = proot.isInstalled()
            _installing.value = false
            if (_linuxReady.value) runLinuxSelfTest()
            if (!_linuxReady.value) {
                delay(60_000)
                if (!proot.isInstalled()) installLinux()
            }
        }
    }

    /** Run the guest self-test in the background and cache its
     * verdict for telemetry. Async on purpose: service start never
     * waits on it, and a guest that cannot run reports itself
     * within seconds instead of being discovered by the owner. */
    private fun runLinuxSelfTest() {
        scope.launch {
            if (!proot.isInstalled()) return@launch
            try {
                val r = proot.selfTest()
                val ok = r.exitCode == 0 && r.stdout.contains("AGT_LINUX_OK")
                _linuxConsoleOk.value = ok
                _linuxDetail.value = if (ok) {
                    "guest shell answered AGT_LINUX_OK"
                } else {
                    (r.stderr.ifBlank { r.stdout }).trim().take(160)
                }
            } catch (e: Exception) {
                _linuxConsoleOk.value = false
                _linuxDetail.value = (e.message ?: "guest self-test failed").take(160)
            }
        }
    }

    /** Wire every real-time alert path once: supervisor events, tunnel
     * transitions, and Phoenix alerts pushed through the Firebase
     * doorbell node all land as phone notifications via AlertNotifier. */
    private fun wireAlerts() {
        if (alertsWired) return
        alertsWired = true
        val appContext = context.applicationContext
        supervisor.onEvent = { title, text ->
            val kind = when {
                title.contains("down") -> NotifyKind.CRITICAL
                title.contains("back up") -> NotifyKind.STATUS
                else -> NotifyKind.ALERT
            }
            AlertNotifier.notify(appContext, title, text, kind)
        }
        firebase.onAlert = { title, message, kind -> AlertNotifier.notify(appContext, title, message, kind) }
        jobEngine.onJobEvent = { kind, title, text -> AlertNotifier.notify(appContext, title, text, kind) }
        scope.launch {
            var prev: TunnelLeg.State? = null
            while (isActive) {
                val s = tunnel.state.value
                val p = prev
                prev = s
                if (p != null && p != s) {
                    when {
                        // One alert per outage episode, on a stable
                        // notification id: the tunnel restarting itself
                        // every few seconds must not stack a fresh card
                        // for every attempt.
                        s == TunnelLeg.State.ERROR && !tunnelDownAlerted -> {
                            tunnelDownAlerted = true
                            AlertNotifier.notify(
                                appContext, "Tunnel down",
                                "The Cloudflare tunnel dropped. AGT is reconnecting on its own.",
                                NotifyKind.CRITICAL, stableKey = "tunnel"
                            )
                        }
                        s == TunnelLeg.State.CONNECTED && tunnelDownAlerted -> {
                            tunnelDownAlerted = false
                            AlertNotifier.notify(
                                appContext, "Tunnel back up",
                                "The Cloudflare tunnel reconnected. The host is reachable again.",
                                NotifyKind.STATUS, stableKey = "tunnel"
                            )
                        }
                    }
                }
                delay(1_000)
            }
        }
    }

    /** One Supabase sync cycle: host upsert (timed, it feeds the legs
     * card latency), heartbeat, deployment upserts. */
    private suspend fun runSyncCycle() {
        if (!supabase.configured) return
        try {
            val id = deviceId()
            val t0 = System.currentTimeMillis()
            // The host name carries the device id: hosts.name has a
            // unique constraint, so a fixed name made every fresh
            // install collide with the row an earlier install (or the
            // FCM token service) had already created, and the upsert
            // failed with 409 on every cycle (field failure, v1.6.0).
            val hid = supabase.upsertHost(id, "AGT host $id", identity.publicKeyBase64(), telemetryJson())
            supabase.noteLatency(System.currentTimeMillis() - t0)
            hostId = hid
            jobEngine.hostId = hid
            supabase.heartbeat(hid, telemetryJson())
            val deployments = store.loadDeployments()
            for (d in deployments) {
                try { supabase.upsertDeployment(hid, d) } catch (e: Exception) { }
            }
            _lastSyncOkMs.value = System.currentTimeMillis()
            _syncError.value = null
        } catch (e: Exception) {
            // Record the real error where the owner and Phoenix can see
            // it (legs card detail and telemetry), then let the caller
            // retry on its normal cadence.
            _syncError.value = (e.message ?: e.javaClass.simpleName).take(180)
            throw e
        }
    }

    /** Quick action: sync to Supabase right now instead of waiting
     * out the 60 second cycle. */
    fun syncNow() {
        scope.launch {
            try { runSyncCycle() } catch (e: Exception) { }
        }
    }

    /** Quick action: restart the tunnel leg with the baked token. */
    fun restartTunnel() {
        tunnel.start(scope, AgtConfig.TUNNEL_TOKEN)
    }

    /** Start everything the host needs: supervisor restore, legs, jobs. */
    fun startAll() {
        wireAlerts()
        supervisor.restoreAll()
        // The tunnel token is baked into the build from the CI secret.
        // No Settings entry exists and none is needed.
        tunnel.start(scope, AgtConfig.TUNNEL_TOKEN)
        // Linux is part of the app, not an optional extra the owner
        // installs by hand: the rootfs ships inside the APK, so first
        // start extracts it automatically in the background.
        if (!proot.isInstalled() && !_installing.value) {
            installLinux()
        }
        // Prove the guest console on every service start, in the
        // background: the verdict rides telemetry as linuxConsoleOk.
        runLinuxSelfTest()
        refreshFcmToken()
        // A fresh Firebase key applies the instant its job lands:
        // JobEngine pokes the leg, the leg reconnects with the new
        // key in the same second.
        jobEngine.onFirebaseTokenSaved = { firebase.refreshNow() }
        firebase.onWake = { scope.launch { pollJobsOnce() } }
        firebase.start(scope, deviceId()) { telemetryJson() }
        jobEngine.hostId = hostId
        jobEngine.start(scope)
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                try {
                    runSyncCycle()
                } catch (e: Exception) {
                    // Sync failure is not fatal: the next cycle retries.
                }
                delay(60_000)
            }
        }
    }

    /** Ask FCM for this device's registration token and store it. The
     * token also rides every Supabase heartbeat inside telemetry, which is
     * how Phoenix learns where to push. Rotations are handled by
     * AgtMessagingService.onNewToken. */
    private fun refreshFcmToken() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrBlank() && token != store.loadSettings().fcmToken) {
                        store.saveSettings(store.loadSettings().copy(fcmToken = token))
                    }
                }
        } catch (e: Exception) {
            // Firebase not initialized (local build without
            // google-services.json): the RTDB doorbell and Supabase poll
            // still cover wake-ups, and the state is visible in Settings.
        }
    }

    private suspend fun pollJobsOnce() {
        // A doorbell ring cuts the job poll loop's sleep short: queued
        // jobs are picked up within about 250ms of the ring instead of
        // waiting out the 15 second cycle.
        jobEngine.requestPoll()
    }

    fun stopAll() {
        syncJob?.cancel()
        jobEngine.stop()
        firebase.stop()
        tunnel.stop()
        supervisor.stopAll()
    }

    fun destroy() {
        scope.cancel()
    }

    companion object {
        @Volatile
        private var instance: AgtRuntime? = null

        fun get(context: Context): AgtRuntime {
            return instance ?: synchronized(this) {
                instance ?: AgtRuntime(context.applicationContext).also { instance = it }
            }
        }

        /** The existing instance without creating one (widget reads). */
        fun peek(): AgtRuntime? = instance
    }
}
