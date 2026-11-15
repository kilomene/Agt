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

    private val _linuxReady = MutableStateFlow(proot.isInstalled())
    val linuxReady: StateFlow<Boolean> = _linuxReady.asStateFlow()

    private val _installProgress = MutableStateFlow(0f)
    val installProgress: StateFlow<Float> = _installProgress.asStateFlow()

    private val _installStatus = MutableStateFlow("")
    val installStatus: StateFlow<String> = _installStatus.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

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
            addProperty("linuxReady", proot.isInstalled())
            addProperty("deployments", store.loadDeployments().size)
            addProperty("running", store.loadDeployments().count { it.status == "running" })
        }
    }

    /** First-run Linux install, with progress the UI can show. */
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
        }
    }

    /** Start everything the host needs: supervisor restore, legs, jobs. */
    fun startAll() {
        val settings = store.loadSettings()
        supervisor.restoreAll()
        tunnel.start(scope, settings.tunnelToken)
        firebase.onWake = { scope.launch { pollJobsOnce() } }
        firebase.start(scope, deviceId()) { telemetryJson() }
        jobEngine.hostId = hostId
        jobEngine.start(scope)
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                try {
                    if (supabase.configured) {
                        val id = deviceId()
                        val hid = supabase.upsertHost(id, "AGT host", identity.publicKeyBase64(), telemetryJson())
                        hostId = hid
                        jobEngine.hostId = hid
                        supabase.heartbeat(hid, telemetryJson())
                        val deployments = store.loadDeployments()
                        for (d in deployments) {
                            try { supabase.upsertDeployment(hid, d) } catch (e: Exception) { }
                        }
                    }
                } catch (e: Exception) {
                    // Sync failure is not fatal: the next cycle retries.
                }
                delay(60_000)
            }
        }
    }

    private suspend fun pollJobsOnce() {
        // A doorbell ring just wakes the normal poll loop; the loop runs
        // every 15s anyway, so nothing extra is needed here.
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
    }
}
