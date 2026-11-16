package com.novainc.agt.linux

import android.content.Context
import com.novainc.agt.core.AgtStore
import com.novainc.agt.core.Deployment
import com.novainc.agt.core.ProcessRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The deployment supervisor: starts, stops, restarts and watches every
 * deployment. A deployment is a supervised native process with its
 * own work directory and log file. On service start it restores every
 * deployment that was running, so the host comes back by itself.
 *
 * Status honesty: the stored status is DERIVED from what the process
 * actually did, never asserted at spawn time. A fresh process is
 * "starting"; it earns "running" only by staying alive past
 * EARNED_RUNNING_MS under the watcher's eye; a process that keeps
 * dying fast (CRASH_LOOP_FAST_EXITS consecutive exits, each alive less
 * than FAST_EXIT_MS) is "crash-looping". Every surface reads the same
 * stored status (the deployment screens, the sync upsert to Supabase,
 * the domain reconciler downstream of it), so a crash-looping
 * deployment can no longer read as running anywhere.
 */
class ProcessSupervisor(
    private val context: Context,
    private val store: AgtStore,
    private val linux: NativeLinux
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val running = ConcurrentHashMap<String, Process>()
    private val watchers = ConcurrentHashMap<String, Job>()

    /** Fired on watcher-observed events only (a deployment died, a
     * deployment came back after a crash restart). Manual start and stop
     * from the UI or a job do not fire this: the owner caused those.
     * Wired by AgtRuntime to the phone's alert notifications. */
    var onEvent: ((title: String, text: String) -> Unit)? = null

    /** What one deployment's process actually did, kept by the
     * supervisor (in memory; an app restart starts the account fresh
     * and every status is re-earned). countedStart is the startedAt of
     * the process instance whose exit has already been accounted, so a
     * battery-guard hold re-entering the exit branch cannot count the
     * same death twice. */
    private class DepHealth {
        var fastExits: Int = 0
        var countedStart: Long = 0L
        var promotedStart: Long = 0L
        var awaitingRecovery: Boolean = false
    }

    private val health = ConcurrentHashMap<String, DepHealth>()

    private companion object {
        /** A process earns "running" by staying alive this long. */
        const val EARNED_RUNNING_MS = 15_000L

        /** An exit after less than this alive counts as a fast exit. */
        const val FAST_EXIT_MS = 15_000L

        /** Consecutive fast exits that make a deployment crash-looping. */
        const val CRASH_LOOP_FAST_EXITS = 3
    }

    /** The status a deployment has right now by the supervisor's own
     * account: crash-looping once the fast-exit count reaches the
     * threshold, otherwise starting (running is only ever written by
     * the watcher's promotion, never here). */
    private fun derivedStatus(deploymentId: String): String {
        val h = health[deploymentId]
        return if ((h?.fastExits ?: 0) >= CRASH_LOOP_FAST_EXITS) {
            "crash-looping"
        } else {
            "starting"
        }
    }

    /** Write one status to both stores, so the process record and the
     * deployment row can never disagree. */
    private fun setStatuses(deploymentId: String, status: String) {
        updateProcessStatus(deploymentId, status)
        setDeploymentStatus(deploymentId, status)
    }

    /** Start a deployment (or restart it if it is already running). */
    @Synchronized
    fun start(deployment: Deployment): ProcessRecord {
        stop(deployment.id)
        val logFile = store.logFileFor(deployment.id)
        val hostDir = store.deploymentDir(deployment.id)
        // Native process: the deployment's own directory is its
        // working directory and (unless its env overrides it) its
        // HOME. In the guest era the same directory was bind
        // mounted into the rootfs at the deployment's workdir;
        // natively no mount is needed for the files to be exactly
        // where the process works.
        val proc = linux.startBackground(
            deployment.command, hostDir, deployment.env, logFile
        )
        val record = ProcessRecord(
            id = deployment.id,
            deploymentId = deployment.id,
            name = deployment.name,
            command = deployment.command,
            pid = pidOf(proc),
            startedAt = System.currentTimeMillis(),
            restarts = 0,
            // Spawned is not running: the record carries the derived
            // status (starting, or crash-looping when the fast-exit
            // count says this deployment is in a loop) until the
            // watcher promotes it on earned uptime.
            status = derivedStatus(deployment.id),
            logPath = logFile.absolutePath
        )
        running[deployment.id] = proc
        upsertProcess(record)
        setDeploymentStatus(deployment.id, record.status)
        watch(deployment)
        return record
    }

    @Synchronized
    fun stop(deploymentId: String) {
        watchers.remove(deploymentId)?.cancel()
        running.remove(deploymentId)?.let { proc ->
            try {
                proc.destroy()
                if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
            }
        }
        updateProcessStatus(deploymentId, "stopped")
        setDeploymentStatus(deploymentId, "stopped")
        // A deliberate stop ends any pending recovery: if the owner
        // starts it again later and it earns running, that is a fresh
        // start, not a comeback the watcher should announce.
        health[deploymentId]?.awaitingRecovery = false
    }

    @Synchronized
    fun restart(deployment: Deployment): ProcessRecord {
        val old = store.loadProcesses().firstOrNull { it.deploymentId == deployment.id }
        val record = start(deployment)
        if (old != null) {
            upsertProcess(record.copy(restarts = old.restarts + 1))
        }
        return record
    }

    fun isRunning(deploymentId: String): Boolean =
        running[deploymentId]?.isAlive == true

    /** The host pid of a child process. The Android SDK does not expose
     * Process.pid(), so read it from the runtime implementation
     * reflectively; 0 when it cannot be determined. */
    private fun pidOf(proc: Process): Long {
        return try {
            val field = proc.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            (field.get(proc) as Number).toLong()
        } catch (e: Exception) {
            try {
                val method = proc.javaClass.getMethod("pid")
                (method.invoke(proc) as Number).toLong()
            } catch (e2: Exception) {
                0L
            }
        }
    }

    /** Health: the process is alive, and its port answers when it has one. */
    fun healthy(deployment: Deployment): Boolean {
        if (!isRunning(deployment.id)) return false
        if (deployment.port <= 0) return true
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", deployment.port), 1500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun watch(deployment: Deployment) {
        watchers[deployment.id]?.cancel()
        watchers[deployment.id] = scope.launch {
            var backoffMs = 1000L
            while (isActive) {
                delay(5000)
                val proc = running[deployment.id] ?: break
                if (proc.isAlive) {
                    // Earned, not assumed: a process instance that
                    // stays alive past the threshold promotes its
                    // deployment to running, clears the fast-exit
                    // account, and is the only moment a "back up"
                    // event may fire (for an instance the watcher
                    // saw die). Before this change, start() asserted
                    // running at spawn and the back-up event fired at
                    // respawn, so a deployment dying inside a second,
                    // forever, read as running on every surface.
                    val h = health.getOrPut(deployment.id) { DepHealth() }
                    val rec = store.loadProcesses()
                        .firstOrNull { it.deploymentId == deployment.id }
                    val startedAt = rec?.startedAt ?: 0L
                    if (startedAt != 0L && startedAt != h.promotedStart &&
                        System.currentTimeMillis() - startedAt >= EARNED_RUNNING_MS
                    ) {
                        h.promotedStart = startedAt
                        h.fastExits = 0
                        setStatuses(deployment.id, "running")
                        if (h.awaitingRecovery) {
                            h.awaitingRecovery = false
                            onEvent?.invoke(
                                "Deployment back up: ${deployment.name}",
                                "${deployment.name} restarted and is running again."
                            )
                        }
                    }
                    continue
                }
                val exit = try { proc.exitValue() } catch (e: Exception) { -1 }
                appendLog(deployment.id, "\n[agt] process exited with code $exit\n")
                // Account this death once per process instance: how
                // long it lived decides whether it was a fast exit,
                // and the derived status is written BEFORE any
                // restart, so the deployment never passes through a
                // stored "running" on its way around the loop.
                val h = health.getOrPut(deployment.id) { DepHealth() }
                val rec = store.loadProcesses()
                    .firstOrNull { it.deploymentId == deployment.id }
                val startedAt = rec?.startedAt ?: 0L
                if (startedAt != 0L && startedAt != h.countedStart) {
                    h.countedStart = startedAt
                    val lived = System.currentTimeMillis() - startedAt
                    if (lived < FAST_EXIT_MS) h.fastExits++ else h.fastExits = 0
                    h.awaitingRecovery = true
                    setStatuses(deployment.id, derivedStatus(deployment.id))
                }
                if (deployment.batteryGuard && batteryGuardHolds()) {
                    // The guard the New Deployment form promises:
                    // a low, unplugged battery holds the restart
                    // (silently, no alert spam) until power returns.
                    appendLog(deployment.id, "[agt] battery guard: restart held, battery low and not charging\n")
                    delay(60_000)
                    continue
                }
                if (deployment.autoRestart) {
                    onEvent?.invoke(
                        "Deployment down: ${deployment.name}",
                        "${deployment.name} exited with code $exit. Restarting it now."
                    )
                    appendLog(deployment.id, "[agt] restarting in ${backoffMs / 1000}s\n")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                    val old = store.loadProcesses().firstOrNull { it.deploymentId == deployment.id }
                    val record = start(deployment)
                    upsertProcess(record.copy(restarts = (old?.restarts ?: 0) + 1))
                    backoffMs = 1000L
                } else {
                    onEvent?.invoke(
                        "Deployment stopped: ${deployment.name}",
                        "${deployment.name} exited with code $exit and auto-restart is off."
                    )
                    setStatuses(deployment.id, "stopped")
                    break
                }
            }
        }
    }

    /** The battery guard test: battery under 25 percent and not
     * charging. Read live from the BatteryManager every time. */
    private fun batteryGuardHolds(): Boolean {
        return try {
            val bm = context.getSystemService(android.os.BatteryManager::class.java) ?: return false
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            level in 0 until 25 && !bm.isCharging
        } catch (e: Exception) {
            false
        }
    }

    /** Restore every deployment that was meant to be up when the
     * service last ran: anything not deliberately stopped. Running,
     * starting and crash-looping all restore; each re-earns its
     * status under the watcher like any fresh start. */
    fun restoreAll() {
        if (!linux.isInstalled()) return
        val deployments = store.loadDeployments()
        for (d in deployments) {
            if (d.status != "stopped") {
                try {
                    start(d)
                } catch (e: Exception) {
                    appendLog(d.id, "[agt] restore failed: ${e.message}\n")
                }
            }
        }
    }

    fun readLog(deploymentId: String, maxChars: Int = 200_000): String {
        val file = store.logFileFor(deploymentId)
        if (!file.exists()) return ""
        val text = file.readText()
        return if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    private fun appendLog(deploymentId: String, text: String) {
        try {
            store.logFileFor(deploymentId).appendText(text)
        } catch (e: Exception) {
        }
    }

    private fun upsertProcess(record: ProcessRecord) {
        val list = store.loadProcesses()
        val i = list.indexOfFirst { it.deploymentId == record.deploymentId }
        if (i >= 0) list[i] = record else list.add(record)
        store.saveProcesses(list)
    }

    private fun updateProcessStatus(deploymentId: String, status: String) {
        val list = store.loadProcesses()
        val i = list.indexOfFirst { it.deploymentId == deploymentId }
        if (i >= 0) {
            list[i] = list[i].copy(status = status)
            store.saveProcesses(list)
        }
    }

    private fun setDeploymentStatus(deploymentId: String, status: String) {
        val list = store.loadDeployments()
        val i = list.indexOfFirst { it.id == deploymentId }
        if (i >= 0) {
            list[i] = list[i].copy(status = status)
            store.saveDeployments(list)
        }
    }

    fun stopAll() {
        for (id in running.keys.toList()) stop(id)
        scope.launch { }
    }
}
