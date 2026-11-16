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
    val batteryGuard: Boolean = false,
    val status: String = "stopped",
    val createdAt: Long = System.currentTimeMillis(),
    // The deployment's unique subdomain slug under
    // agt.novamail.store, assigned once at creation and never
    // shared: two deployments with the same name still get two
    // different addresses. Empty on rows written by older builds;
    // DeploymentEngine.backfillSlugs() fills those in at start.
    val slug: String = ""
)

data class ProcessRecord(
    val id: String,
    val deploymentId: String,
    val name: String,
    val command: String,
    val pid: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val restarts: Int = 0,
    // Records written before the status was derived from uptime have
    // no honest default to inherit: a record that does not say is
    // starting, never running.
    val status: String = "starting",
    val logPath: String = ""
)

data class JobFeedEntry(
    val id: String,
    val kind: String,
    val status: String,
    val summary: String,
    val at: Long = System.currentTimeMillis(),
    // The trusted agent whose verified signature sent this job
    // (multi-agent trust: "phoenix" for the main agent, the fleet
    // agent's id for an agent-signed job). Empty on entries written
    // before attribution existed and on jobs that never verified;
    // every read goes through AgentTrust.feedAgentOf for the same
    // Gson reason as DeploymentEngine.storedSlug.
    val agent: String = ""
)

/**
 * One trusted signing key on this host. The MAIN entry is the main
 * agent (Phoenix); AGENT entries are the other agents the owner has
 * admitted, each with its own Ed25519 key, each able to deploy and
 * run jobs that are attributed to it, and each revocable without
 * touching the main agent's trust.
 */
data class AgentKey(
    val agentId: String = "",
    val publicKey: String = "",
    val role: String = ROLE_AGENT,
    val addedAt: Long = 0L
) {
    companion object {
        const val ROLE_MAIN = "MAIN"
        const val ROLE_AGENT = "AGENT"
    }
}

data class AgtSettings(
    val deviceId: String = "",
    val devicePublicKey: String = "",
    val phoenixPublicKey: String = "",
    // The trusted agents admitted to this host. Null in settings
    // files written before multi-agent trust existed: Gson does not
    // apply the Kotlin default on those files, so the field is
    // declared nullable and every read goes through
    // AgentTrust.storedAgents, which also performs the first-load
    // migration of the legacy phoenixPublicKey into a MAIN entry.
    // The phoenixPublicKey field itself stays, kept in sync with
    // the MAIN entry by AgentTrust.synced on every save.
    val agents: List<AgentKey>? = null,
    val pairingCode: String = "",
    // The agentId the outstanding pairing code was minted for, or
    // empty when the code is a main-agent (Phoenix) bootstrap code.
    // One outstanding code at a time, as before: minting a code for
    // an agent replaces any code already showing.
    val pairingAgentId: String = "",
    val tunnelToken: String = "",
    val firebaseToken: String = "",
    // The last firebaseToken value that completed a full presence
    // cycle, persisted by FirebaseLeg the moment it is proven. The
    // in-memory proof alone died with the process, so a restart
    // after a network drop read the key as unproven and the
    // firebaseKeySet flag latched false; the persisted copy lets
    // hasUsableKey() stay honest across restarts. Empty until a
    // token first proves itself.
    val provenFirebaseToken: String = "",
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
            val raw = gson.fromJson(settingsFile.readText(), AgtSettings::class.java)
                ?: return AgtSettings()
            // First-load migration for multi-agent trust: a legacy
            // phoenixPublicKey becomes the MAIN agent entry, and the
            // two views of the main key are kept in sync. Persisted
            // once, here, so the file on disk carries the agents
            // list from then on; later loads find it already synced
            // and write nothing.
            val synced = com.novainc.agt.agent.AgentTrust.synced(raw)
            if (synced != raw) saveSettings(synced)
            synced
        } catch (e: Exception) {
            AgtSettings()
        }
    }

    @Synchronized
    fun saveSettings(s: AgtSettings) {
        // Every write passes through the trust sync, so no caller
        // can persist a settings copy whose agents list and legacy
        // phoenixPublicKey disagree about the main agent's key.
        settingsFile.writeText(gson.toJson(com.novainc.agt.agent.AgentTrust.synced(s)))
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
