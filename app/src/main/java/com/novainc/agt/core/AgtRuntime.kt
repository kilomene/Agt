package com.novainc.agt.core

import android.content.Context
import com.google.gson.JsonObject
import com.novainc.agt.agent.DeviceIdentity
import com.novainc.agt.agent.JobEngine
import com.novainc.agt.deploy.DeploymentEngine
import com.novainc.agt.legs.FirebaseLeg
import com.novainc.agt.legs.SupabaseLeg
import com.novainc.agt.legs.TunnelLeg
import com.novainc.agt.linux.NativeLinux
import com.novainc.agt.linux.ProcessSupervisor
import com.novainc.agt.linux.ShellSession
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
import java.util.concurrent.ConcurrentHashMap

/**
 * The process-wide runtime: every component the app owns, built once and
 * shared by the service and the UI. The service starts it; the UI only
 * reads it. Nothing here is a placeholder: each leg reports its real
 * state, and a leg that is not configured says so instead of faking up.
 */
class AgtRuntime private constructor(private val context: Context) {
    val store = AgtStore(context.applicationContext)
    val linux = NativeLinux(context.applicationContext, store)
    val supervisor = ProcessSupervisor(context.applicationContext, store, linux)
    val deployEngine = DeploymentEngine(context.applicationContext, store, supervisor)
    val supabase = SupabaseLeg(store)
    val tunnel = TunnelLeg(context.applicationContext)
    val firebase = FirebaseLeg(store)
    val identity = DeviceIdentity(context.applicationContext, store)
    val jobEngine = JobEngine(store, supabase, linux, supervisor, deployEngine, identity)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncJob: Job? = null
    private var hostId: String? = null
    private var alertsWired = false

    /** Linux readiness, and it means exactly one thing: a real
     * native shell answered its echo probe. It starts false and is
     * set only by the self-test's verdict, never from file checks:
     * the file-based reading this replaces reported ready through
     * shipped builds whose shell could not start at all (the
     * false-alive reading). Whether the bootstrap is installed is
     * a separate question, and linux.isInstalled() answers it
     * where that is what is meant (the install flow). */
    private val _linuxReady = MutableStateFlow(false)
    val linuxReady: StateFlow<Boolean> = _linuxReady.asStateFlow()

    /** The self-test verdict: whether a real shell answered its
     * echo, proven once per service start and again after a fresh
     * install completes. Never inferred from file existence:
     * three shipped builds reported a ready tree whose bash could
     * not start. */
    private val _linuxConsoleOk = MutableStateFlow(false)
    val linuxConsoleOk: StateFlow<Boolean> = _linuxConsoleOk.asStateFlow()

    private val _linuxDetail = MutableStateFlow("")
    val linuxDetail: StateFlow<String> = _linuxDetail.asStateFlow()

    /** The native diagnostic report (NativeLinux.diagnose): the
     * bootstrap version, content hashes of the binaries the
     * shell's start depends on, and the probe's own words. Rides
     * telemetry as linuxLadder, the field name kept from the
     * guest era so existing readers keep working, so a shell that
     * cannot start explains itself from the phone. */
    private val _linuxLadder = MutableStateFlow("")
    val linuxLadder: StateFlow<String> = _linuxLadder.asStateFlow()

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

    /** Per-deployment log upload offsets (chars already shipped to
     * Supabase), in memory only. See uploadNewLogLines. */
    private val logUploadOffsets = mutableMapOf<String, Int>()

    /** Ensure a device id exists; the id is stable per phone, forever. */
    fun deviceId(): String =
        store.updateSettings { s ->
            if (s.deviceId.isNotEmpty()) s
            else s.copy(deviceId = "agt-" + UUID.randomUUID().toString().take(8))
        }.deviceId

    fun pairingCode(): String =
        store.updateSettings { s ->
            if (s.pairingCode.isNotEmpty()) s
            else s.copy(
                // A freshly minted code here is a main-agent
                // bootstrap code: it carries no agent label.
                pairingCode = (100000..999999).random().toString(),
                pairingAgentId = ""
            )
        }.pairingCode

    /** Mint the one outstanding pairing code for one agentId: a
     * pair job presenting this code plus that agent's public key
     * adds an AGENT entry for the id, leaving the main agent's key
     * untouched. Minting replaces any code already outstanding, the
     * same one-slot rule the pairing code has always had. */
    fun mintAgentPairingCode(agentId: String): String {
        val code = (100000..999999).random().toString()
        store.updateSettings { s -> s.copy(pairingCode = code, pairingAgentId = agentId.trim()) }
        return code
    }

    /** Revoke one admitted agent from the phone itself (the Agent
     * screen's revoke button). The main agent cannot be revoked;
     * AgentTrust refuses and this returns false. */
    fun revokeAgent(agentId: String): Boolean {
        var change: com.novainc.agt.agent.AgentTrust.Change? = null
        store.updateSettings { s ->
            val ch = com.novainc.agt.agent.AgentTrust.revokeAgent(s, agentId)
            change = ch
            ch.settings ?: s
        }
        return change?.settings != null
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
            addProperty("linuxReady", _linuxReady.value)
            addProperty("linuxConsoleOk", _linuxConsoleOk.value)
            addProperty("linuxDetail", _linuxDetail.value.take(160))
            addProperty("linuxLadder", _linuxLadder.value.take(1800))
            addProperty("appVersion", com.novainc.agt.BuildConfig.VERSION_NAME)
            addProperty("firebaseKeySet", firebase.hasUsableKey())
            addProperty("tunnelState", tunnel.state.value.name)
            addProperty("tunnelDetail", tunnel.detail.value.take(160))
            addProperty("tunnelLog", tunnel.recentOutput().joinToString("\n").takeLast(3500))
            addProperty("syncError", _syncError.value ?: "")
            addProperty("installStatus", _installStatus.value.take(120))
            addProperty("deployments", store.loadDeployments().size)
            addProperty("running", store.loadDeployments().count { supervisor.displayStatus(it) == "running" })
        }
    }

    /** First-run Linux install, with progress the UI can show. A
     * failed or interrupted install retries on its own after a
     * minute: the promise is that Linux is part of the app, so a
     * dead install never just sits there waiting for a human. */
    fun installLinux() {
        if (_installing.value) return
        _installing.value = true
        scope.launch {
            val result = linux.install { p, s ->
                _installProgress.value = p / 100f
                _installStatus.value = s
            }
            result.onFailure { e ->
                _installStatus.value = "Install failed: ${e.message}"
            }
            _installing.value = false
            // An installed bootstrap is not a ready Linux:
            // readiness is the self-test's verdict (a shell
            // actually answered), so a finished install goes
            // straight to the probe. A bootstrap that did not
            // install retries on its own.
            if (linux.isInstalled()) {
                runLinuxSelfTest()
            } else {
                delay(60_000)
                if (!linux.isInstalled()) installLinux()
            }
        }
    }

    /** True while the self-test retry loop is running. The loop is
     * the difference between a shell that failed one probe at
     * service start staying dead until the next start, and the
     * shell raising itself once whatever blocked the probe clears. */
    @Volatile
    private var selfTestRetrying: Boolean = false

    /** Run the Linux self-test in the background and cache its
     * verdict for telemetry. Async on purpose: service start never
     * waits on it, and a shell that cannot run reports itself
     * within seconds instead of being discovered by the owner. A
     * failed verdict is not final: the retry loop below re-runs
     * the probe every 30 seconds until the shell answers, because
     * a shell that is not raised is a shell that does not exist,
     * and nothing else in the app re-asks the question. */
    private fun runLinuxSelfTest() {
        scope.launch {
            runLinuxSelfTestAttempt()
            if (!_linuxConsoleOk.value && !selfTestRetrying) {
                selfTestRetrying = true
                try {
                    while (!_linuxConsoleOk.value) {
                        delay(30_000)
                        runLinuxSelfTestAttempt()
                    }
                } finally {
                    selfTestRetrying = false
                }
            }
        }
    }

    /** One self-test attempt: probe, cache the verdict, and raise
     * the primary shell on success. */
    private suspend fun runLinuxSelfTestAttempt() {
        if (!linux.isInstalled()) return
        try {
            val d = linux.diagnose()
            _linuxConsoleOk.value = d.consoleOk
            // Readiness is this same verdict: the probe
            // answered. The native shell has no start shapes:
            // it starts as a process of the app or the
            // failure is printed in the open.
            _linuxReady.value = d.consoleOk
            _linuxLadder.value = d.report
            _linuxDetail.value = d.detailLine.take(160)
            // Startup is not finished until the shell itself
            // is raised. Every road back to life (sticky
            // restart, the watchdog chain, boot, the comeback
            // alarm) funnels into startAll and therefore into
            // this self-test, so a shell that answers its
            // probe raises its primary shell here, with no
            // user action: the session takes its place in the
            // registry under the Console tab's own key
            // (host-0) and simply lives there, owned by this
            // runtime. The raise is gated on the service
            // running, because the shell's keeper lives and
            // dies with the foreground service: a shell is
            // never raised into a runtime whose service is
            // down (a settings-screen install retry can reach
            // this self-test with the service stopped, and
            // there the probe verdict is all that runs).
            if (d.consoleOk && AgtService.running.value) {
                try { shellSession("host-0", "/root") } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            _linuxConsoleOk.value = false
            _linuxReady.value = false
            _linuxDetail.value = (e.message ?: "Linux self-test failed").take(160)
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
                try { supabase.upsertDeployment(hid, d.copy(status = supervisor.displayStatus(d))) } catch (e: Exception) { }
                uploadNewLogLines(d.id)
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

    /** Ship the deployment log lines that are new since the last
     * sync to Supabase, so uploadLogs is a live path and the
     * deployment_logs table is actually fed. The offset advances
     * only after a successful upload, so a failed cycle retries the
     * same lines instead of losing them. A first sync ships only
     * the recent tail, never the whole log. A log failure never
     * fails the sync cycle itself. */
    private suspend fun uploadNewLogLines(deploymentId: String) {
        try {
            val text = supervisor.readLog(deploymentId)
            val from = logUploadOffsets[deploymentId] ?: 0
            if (text.length < from) {
                logUploadOffsets[deploymentId] = 0
                return
            }
            if (text.length == from) return
            val segment = if (from == 0 && text.length > 4000) {
                text.takeLast(4000)
            } else {
                text.substring(from)
            }
            val lines = segment.lines().filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                logUploadOffsets[deploymentId] = text.length
                return
            }
            supabase.uploadLogs(deploymentId, lines)
            logUploadOffsets[deploymentId] = text.length
        } catch (e: Exception) {
            // Offset untouched: the same lines retry next cycle.
        }
    }

    /** Quick action: sync to Supabase right now instead of waiting
     * out the 30 second cycle. */
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
        // Unique subdomains: deployments stored by older builds
        // carry no slug and would share the name-derived address
        // with any same-named deployment. Fill the slugs in before
        // the first sync publishes them.
        try { deployEngine.backfillSlugs() } catch (e: Exception) { }
        supervisor.restoreAll()
        // The tunnel token is baked into the build from the CI secret.
        // No Settings entry exists and none is needed.
        tunnel.start(scope, AgtConfig.TUNNEL_TOKEN)
        // Linux is part of the app, not an optional extra the owner
        // installs by hand: the bootstrap ships inside the APK, so
        // first start extracts it automatically in the background.
        if (!linux.isInstalled() && !_installing.value) {
            installLinux()
        }
        // Prove the console on every service start, in the
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
                delay(30_000)
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
                    if (!token.isNullOrBlank()) {
                        store.updateSettings { s ->
                            if (s.fcmToken == token) s else s.copy(fcmToken = token)
                        }
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

    /** Console shell sessions, owned here: by the runtime, which
     * the foreground service starts and stops, and never by any
     * screen. A shell is a long-lived process, the way a Termux
     * session is: it is created the first time a console tab asks
     * for it and then it simply lives, at its prompt, across tab
     * switches, screen exits, sync cycles and idle days. Opening
     * the Console tab attaches the view to the session that is
     * already running; leaving it detaches the view and nothing
     * else. No screen lifecycle event, timeout or sync cycle
     * starts, stops or restarts a shell; the session's own keeper
     * is the only restart path, and it fires only if the shell
     * process itself exits. The one legitimate end of a shell's
     * life is the service's own teardown, in stopAll() below. */
    private val shellSessions = ConcurrentHashMap<String, ShellSession>()
    private val shellSessionsLock = Any()

    /** The living shell session for a console tab key, started on
     * first ask and returned as-is on every later ask. The
     * workdir/binds arguments are the console's proot-era calling
     * shape, translated here: a deployment tab's bind named the
     * host directory its guest session was mounted at, and that
     * directory is simply the native session's working directory
     * now; the host tab's "/root" is the home directory. */
    fun shellSession(
        key: String,
        workdir: String = "/root",
        binds: List<Pair<String, String>> = emptyList()
    ): ShellSession {
        shellSessions[key]?.let { return it }
        return synchronized(shellSessionsLock) {
            shellSessions.getOrPut(key) {
                val dir = binds.firstOrNull()
                    ?.let { java.io.File(it.first) }
                    ?.takeIf { it.isDirectory }
                    ?: linux.resolveWorkdir(workdir)
                linux.startShell(dir)
            }
        }
    }

    fun stopAll() {
        syncJob?.cancel()
        jobEngine.stop()
        firebase.stop()
        tunnel.stop()
        supervisor.stopAll()
        // The shells end with the service that owns them, and only
        // there: this is the foreground service's onDestroy path.
        synchronized(shellSessionsLock) {
            shellSessions.values.forEach { it.close() }
            shellSessions.clear()
        }
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
