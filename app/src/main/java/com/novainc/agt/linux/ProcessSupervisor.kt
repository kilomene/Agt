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
 * deployment. A deployment is a supervised PRoot guest process with its
 * own work directory and log file. On service start it restores every
 * deployment that was running, so the host comes back by itself.
 */
class ProcessSupervisor(
    private val context: Context,
    private val store: AgtStore,
    private val proot: Proot
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val running = ConcurrentHashMap<String, Process>()
    private val watchers = ConcurrentHashMap<String, Job>()

    /** Start a deployment (or restart it if it is already running). */
    @Synchronized
    fun start(deployment: Deployment): ProcessRecord {
        stop(deployment.id)
        val workdir = deployment.workdir.ifEmpty { "/root" }
        val logFile = store.logFileFor(deployment.id)
        val hostDir = store.deploymentDir(deployment.id)
        val proc = proot.startBackground(
            deployment.command, workdir, deployment.env, logFile,
            binds = listOf(hostDir.absolutePath to workdir)
        )
        val record = ProcessRecord(
            id = deployment.id,
            deploymentId = deployment.id,
            name = deployment.name,
            command = deployment.command,
            pid = pidOf(proc),
            startedAt = System.currentTimeMillis(),
            restarts = 0,
            status = "running",
            logPath = logFile.absolutePath
        )
        running[deployment.id] = proc
        upsertProcess(record)
        setDeploymentStatus(deployment.id, "running")
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

    fun processFor(deploymentId: String): Process? = running[deploymentId]

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
                if (!proc.isAlive) {
                    val exit = try { proc.exitValue() } catch (e: Exception) { -1 }
                    appendLog(deployment.id, "\n[agt] process exited with code $exit\n")
                    if (deployment.autoRestart) {
                        appendLog(deployment.id, "[agt] restarting in ${backoffMs / 1000}s\n")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                        val old = store.loadProcesses().firstOrNull { it.deploymentId == deployment.id }
                        val record = start(deployment)
                        upsertProcess(record.copy(restarts = (old?.restarts ?: 0) + 1))
                        backoffMs = 1000L
                    } else {
                        updateProcessStatus(deployment.id, "stopped")
                        setDeploymentStatus(deployment.id, "stopped")
                        break
                    }
                }
            }
        }
    }

    /** Restore every deployment that was running when the service last ran. */
    fun restoreAll() {
        if (!proot.isInstalled()) return
        val deployments = store.loadDeployments()
        for (d in deployments) {
            if (d.status == "running") {
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
