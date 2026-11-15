package com.novainc.agt.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * All AGT state that must survive a restart, kept as plain JSON files in
 * the app-private directory. No database, no placeholders: every record
 * here is written by real code and read back by real code.
 */
data class Deployment(
    val id: String,
    val name: String,
    val kind: String,
    val source: String,
    val command: String,
    val workdir: String,
    val env: Map<String, String> = emptyMap(),
    val port: Int = 0,
    val autoRestart: Boolean = true,
    val status: String = "stopped",
    val createdAt: Long = System.currentTimeMillis()
)

data class ProcessRecord(
    val id: String,
    val deploymentId: String,
    val name: String,
    val command: String,
    val pid: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val restarts: Int = 0,
    val status: String = "running",
    val logPath: String = ""
)

data class JobFeedEntry(
    val id: String,
    val kind: String,
    val status: String,
    val summary: String,
    val at: Long = System.currentTimeMillis()
)

data class AgtSettings(
    val deviceId: String = "",
    val devicePublicKey: String = "",
    val phoenixPublicKey: String = "",
    val pairingCode: String = "",
    val tunnelToken: String = "",
    val firebaseToken: String = "",
    val supabaseAnonKey: String = "",
    val fcmToken: String = "",
    val rootfsVersion: String = "",
    val linuxReady: Boolean = false
)

class AgtStore(context: Context) {
    private val gson = Gson()
    private val dir = File(context.filesDir, "agt").apply { mkdirs() }
    private val settingsFile = File(dir, "settings.json")
    private val deploymentsFile = File(dir, "deployments.json")
    private val processesFile = File(dir, "processes.json")
    private val jobsFile = File(dir, "jobs.json")

    @Synchronized
    fun loadSettings(): AgtSettings {
        if (!settingsFile.exists()) return AgtSettings()
        return try {
            gson.fromJson(settingsFile.readText(), AgtSettings::class.java) ?: AgtSettings()
        } catch (e: Exception) {
            AgtSettings()
        }
    }

    @Synchronized
    fun saveSettings(s: AgtSettings) {
        settingsFile.writeText(gson.toJson(s))
    }

    @Synchronized
    fun loadDeployments(): MutableList<Deployment> {
        if (!deploymentsFile.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Deployment>>() {}.type
            gson.fromJson(deploymentsFile.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun saveDeployments(list: List<Deployment>) {
        deploymentsFile.writeText(gson.toJson(list))
    }

    @Synchronized
    fun loadProcesses(): MutableList<ProcessRecord> {
        if (!processesFile.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ProcessRecord>>() {}.type
            gson.fromJson(processesFile.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun saveProcesses(list: List<ProcessRecord>) {
        processesFile.writeText(gson.toJson(list))
    }

    @Synchronized
    fun loadJobs(): MutableList<JobFeedEntry> {
        if (!jobsFile.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<JobFeedEntry>>() {}.type
            gson.fromJson(jobsFile.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun addJob(entry: JobFeedEntry) {
        val list = loadJobs()
        list.add(0, entry)
        while (list.size > 100) list.removeAt(list.lastIndex)
        jobsFile.writeText(gson.toJson(list))
    }

    fun logFileFor(processId: String): File =
        File(dir, "log-$processId.txt").apply { parentFile?.mkdirs() }

    fun deploymentDir(id: String): File =
        File(dir, "deployments/$id").apply { mkdirs() }

    fun snapshotsDir(): File = File(dir, "snapshots").apply { mkdirs() }

    fun linuxDir(): File = File(dir, "linux").apply { mkdirs() }
}
