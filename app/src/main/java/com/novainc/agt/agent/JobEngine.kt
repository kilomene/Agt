package com.novainc.agt.agent

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.novainc.agt.core.AgentKey
import com.novainc.agt.core.AgtSettings
import com.novainc.agt.core.AgtStore
import com.novainc.agt.core.NotifyKind
import com.novainc.agt.core.JobFeedEntry
import com.novainc.agt.deploy.DeploymentEngine
import com.novainc.agt.deploy.Templates
import com.novainc.agt.legs.SupabaseLeg
import com.novainc.agt.linux.ProcessSupervisor
import com.novainc.agt.linux.NativeLinux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The agent connection: Phoenix writes signed jobs to Supabase, the phone
 * polls them, verifies the Ed25519 signature against the trusted agents
 * list (the main agent first, then each admitted agent's own key),
 * executes, and writes the result back. No inbound port, no SSH:
 * everything rides the outbound Supabase channel. A job with a bad
 * or missing signature is refused and marked failed, never run.
 * Every verified job is attributed to the agent whose key signed it:
 * the agentId lands on the job feed entry and in the result JSON.
 */
class JobEngine(
    private val store: AgtStore,
    private val supabase: SupabaseLeg,
    private val linux: NativeLinux,
    private val supervisor: ProcessSupervisor,
    private val deployEngine: DeploymentEngine,
    private val identity: DeviceIdentity
) {
    private val gson = Gson()
    private var loop: Job? = null
    var hostId: String? = null

    /** Job lifecycle events for the notification catalogue, wired by
     * the runtime: (kind, title, text). */
    var onJobEvent: ((kind: String, title: String, text: String) -> Unit)? = null

    /** Fired the moment a firebase_token job has saved the new key,
     * so the Firebase leg applies it instantly instead of noticing
     * it on a later poll. Wired by AgtRuntime. */
    var onFirebaseTokenSaved: (() -> Unit)? = null

    private val pollRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Wake the poll loop NOW instead of waiting out the 15 second
     * cycle: a doorbell ring or an FCM wake lands here and the queued
     * jobs are picked up within a quarter second. */
    fun requestPoll() {
        pollRequested.set(true)
    }

    fun start(scope: CoroutineScope) {
        loop?.cancel()
        loop = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val hid = hostId
                    if (hid != null && supabase.configured) {
                        val jobs = supabase.pollJobs(hid)
                        for (job in jobs) handle(job)
                    }
                } catch (e: Exception) {
                    // Poll failure is not fatal: the next cycle retries.
                }
                // Sleep in short slices so a doorbell ring cuts the
                // wait short: worst case pickup stays 15s, rung pickup
                // is about 250ms.
                var waited = 0L
                while (waited < 15_000 && !pollRequested.get()) {
                    delay(250)
                    waited += 250
                }
                pollRequested.set(false)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    private suspend fun handle(job: JsonObject) {
        val jobId = job.get("id").asString
        val kind = job.get("kind").asString
        val payload = job.getAsJsonObject("payload")
        val signature = job.get("signature")?.takeIf { !it.isJsonNull }?.asString
        try {
            supabase.claimJob(jobId)
            val settings = store.loadSettings()
            // Trust root: the trusted agents list. Verification tries
            // the main agent's key first (the paired key when there is
            // one, otherwise the Phoenix key baked into the build, so
            // setup jobs like firebase_token verify on a fresh install
            // with no pairing step in the way), then each admitted
            // agent's key. The entry that verifies is the job's
            // signer, and its agentId is the attribution stamped on
            // the feed entry and into the result.
            var feedAgent = ""
            val result = when {
                kind == "pair" -> {
                    val pairResult = handlePair(payload, settings)
                    feedAgent = pairResult.get("agent")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    pairResult
                }
                signature.isNullOrEmpty() -> fail("missing signature")
                else -> {
                    val matched = AgentTrust.verifySigner(settings, payload, signature)
                    if (matched == null) {
                        fail("signature does not verify")
                    } else {
                        feedAgent = AgentTrust.agentIdOf(matched)
                        AgentTrust.stampAgent(execute(kind, payload, matched), matched)
                    }
                }
            }
            supabase.completeJob(jobId, result)
            store.addJob(
                JobFeedEntry(
                    UUID.randomUUID().toString(), kind, "done",
                    summarize(kind, result), agent = feedAgent
                )
            )
            val err = result.get("error")?.takeIf { !it.isJsonNull }?.asString
            when {
                err != null && kind == "pair" -> onJobEvent?.invoke(
                    NotifyKind.SECURITY, "Pairing failed", err)
                err != null -> onJobEvent?.invoke(
                    NotifyKind.ALERT, "Job failed: $kind", err)
                kind == "pair" && feedAgent.isNotEmpty() && feedAgent != AgentTrust.MAIN_AGENT_ID ->
                    onJobEvent?.invoke(
                        NotifyKind.SECURITY, "Agent paired",
                        "$feedAgent is paired with this phone.")
                kind == "pair" -> onJobEvent?.invoke(
                    NotifyKind.SECURITY, "Phone paired", "Phoenix is paired with this phone.")
                else -> onJobEvent?.invoke(
                    NotifyKind.JOB, "Job done: $kind", summarize(kind, result))
            }
        } catch (e: Exception) {
            try {
                supabase.failJob(jobId, e.message ?: "job failed")
            } catch (ignored: Exception) {
            }
            store.addJob(JobFeedEntry(UUID.randomUUID().toString(), kind, "failed", e.message ?: "job failed"))
            onJobEvent?.invoke(NotifyKind.ALERT, "Job failed: $kind", e.message ?: "job failed")
        }
    }

    private fun fail(message: String): JsonObject =
        JsonObject().apply { addProperty("error", message) }

    /**
     * Pairing, two doors over the one outstanding code. A code the
     * owner minted for an agentId (on the Agent screen) admits that
     * agent: the presented key becomes its AGENT entry and the main
     * agent's trust is untouched. A code with no label, or a pair
     * payload with an empty agentId, keeps the original bootstrap
     * behaviour: the presented key replaces the main agent's key.
     * A payload that names a different agentId than the code's label
     * is refused, so a code minted for one agent cannot be spent by
     * another. The presented key rides in the payload's
     * phoenixPublicKey field, as it always has; publicKey is
     * accepted as the same value under the agent-neutral name.
     */
    private fun handlePair(payload: JsonObject, settings: AgtSettings): JsonObject {
        val code = payload.get("code")?.asString ?: ""
        val key = payload.get("phoenixPublicKey")?.asString
            ?: payload.get("publicKey")?.asString ?: ""
        val expectedCode = AgentTrust.storedPairingCode(settings)
        if (expectedCode.isEmpty() || code != expectedCode) {
            return fail("pairing code does not match")
        }
        if (DeviceIdentity.publicKeyBytesFromBase64(key) == null) {
            return fail("bad Phoenix public key")
        }
        val labelled = AgentTrust.storedPairingAgentId(settings)
        val named = payload.get("agentId")?.asString ?: ""
        if (labelled.isNotEmpty() && named.isNotEmpty() && named != labelled) {
            return fail("pairing code is for a different agent")
        }
        val agentId = labelled.ifEmpty { named }
        val current = store.loadSettings()
        return if (agentId.isEmpty()) {
            store.saveSettings(AgentTrust.pairAsMain(current, key))
            JsonObject().apply {
                addProperty("paired", true)
                addProperty("agent", AgentTrust.MAIN_AGENT_ID)
                addProperty("devicePublicKey", identity.publicKeyBase64())
            }
        } else {
            val change = AgentTrust.pairAsAgent(current, agentId, key)
            val updated = change.settings ?: return fail(change.error ?: "pairing refused")
            store.saveSettings(updated)
            JsonObject().apply {
                addProperty("paired", true)
                addProperty("agent", agentId)
                addProperty("devicePublicKey", identity.publicKeyBase64())
            }
        }
    }

    private suspend fun execute(kind: String, payload: JsonObject, signer: AgentKey): JsonObject {
        return when (kind) {
            "command" -> {
                val command = payload.get("command")?.asString ?: ""
                val workdir = payload.get("workdir")?.asString ?: "/root"
                val run = linux.runOneShot(command, workdir, emptyMap(), 120)
                JsonObject().apply {
                    addProperty("exit", run.exitCode)
                    addProperty("stdout", run.stdout.take(4000))
                    addProperty("stderr", run.stderr.take(4000))
                }
            }
            "deploy" -> {
                val templateId = payload.get("template")?.asString
                val deployment = if (templateId != null) {
                    val template = Templates.byId(templateId)
                        ?: return fail("unknown template $templateId")
                    val name = payload.get("name")?.asString ?: template.name
                    deployEngine.createFromTemplate(template, name)
                } else {
                    val git = payload.get("git")?.asString ?: return fail("deploy needs template or git")
                    val name = payload.get("name")?.asString ?: git.substringAfterLast("/").removeSuffix(".git")
                    val command = payload.get("command")?.asString ?: "python3 -m http.server 8080"
                    val port = payload.get("port")?.asInt ?: 0
                    deployEngine.createFromGit(git, name, command, port)
                }
                supervisor.start(deployment)
                JsonObject().apply {
                    addProperty("deploymentId", deployment.id)
                    addProperty("name", deployment.name)
                    // The stored status the supervisor derived, not
                    // an assertion: a fresh process is starting
                    // until it earns running by staying up.
                    addProperty(
                        "status",
                        store.loadDeployments()
                            .firstOrNull { it.id == deployment.id }?.status ?: "starting"
                    )
                }
            }
            "restart" -> {
                val id = payload.get("deploymentId")?.asString ?: return fail("deploymentId missing")
                val d = store.loadDeployments().firstOrNull { it.id == id } ?: return fail("no such deployment")
                supervisor.restart(d)
                JsonObject().apply {
                    addProperty(
                        "status",
                        store.loadDeployments()
                            .firstOrNull { it.id == id }?.status ?: "starting"
                    )
                }
            }
            "stop" -> {
                val id = payload.get("deploymentId")?.asString ?: return fail("deploymentId missing")
                supervisor.stop(id)
                JsonObject().apply { addProperty("status", "stopped") }
            }
            "logs" -> {
                val id = payload.get("deploymentId")?.asString ?: return fail("deploymentId missing")
                JsonObject().apply { addProperty("logs", supervisor.readLog(id).takeLast(4000)) }
            }
            "remove" -> {
                val id = payload.get("deploymentId")?.asString ?: return fail("deploymentId missing")
                deployEngine.delete(id)
                JsonObject().apply { addProperty("removed", true) }
            }
            "firebase_token" -> {
                val token = payload.get("token")?.asString ?: return fail("token missing")
                val settings = store.loadSettings()
                store.saveSettings(settings.copy(firebaseToken = token))
                onFirebaseTokenSaved?.invoke()
                JsonObject().apply { addProperty("saved", true) }
            }
            "agent_add" -> {
                // Admitting a new trusted agent is the main agent's
                // act alone: AgentTrust refuses any other signer,
                // so an admitted agent can never admit another.
                val agentId = payload.get("agentId")?.asString ?: return fail("agentId missing")
                val key = payload.get("publicKey")?.asString ?: return fail("publicKey missing")
                val change = AgentTrust.applyAgentAdd(store.loadSettings(), signer, agentId, key)
                val updated = change.settings ?: return fail(change.error ?: "agent_add refused")
                store.saveSettings(updated)
                JsonObject().apply {
                    addProperty("added", true)
                    addProperty("addedAgent", agentId)
                }
            }
            "agent_revoke" -> {
                // Same gate as agent_add, and the main agent's own
                // entry is unrevocable inside AgentTrust.
                val agentId = payload.get("agentId")?.asString ?: return fail("agentId missing")
                val change = AgentTrust.applyAgentRevoke(store.loadSettings(), signer, agentId)
                val updated = change.settings ?: return fail(change.error ?: "agent_revoke refused")
                store.saveSettings(updated)
                JsonObject().apply {
                    addProperty("revoked", true)
                    addProperty("revokedAgent", agentId)
                }
            }
            else -> fail("unknown job kind $kind")
        }
    }

    private fun summarize(kind: String, result: JsonObject): String {
        if (result.has("error")) return result.get("error").asString
        return when (kind) {
            "command" -> "exit ${result.get("exit")?.asInt}"
            "deploy" -> "deployed ${result.get("name")?.asString}"
            "restart" -> "restarted"
            "stop" -> "stopped"
            "logs" -> "logs pulled"
            "remove" -> "removed"
            "firebase_token" -> "token saved"
            "agent_add" -> "agent added"
            "agent_revoke" -> "agent revoked"
            else -> kind
        }
    }

    companion object {
        /**
         * Canonical JSON: keys sorted recursively, no whitespace. Phoenix
         * signs exactly this form (json.dumps sort_keys separators ,:).
         */
        fun canonicalJson(el: JsonElement): String {
            val sb = StringBuilder()
            writeCanonical(el, sb)
            return sb.toString()
        }

        private fun writeCanonical(el: JsonElement, sb: StringBuilder) {
            when {
                el.isJsonObject -> {
                    sb.append('{')
                    val obj = el.asJsonObject
                    val keys = obj.keySet().sorted()
                    keys.forEachIndexed { i, k ->
                        if (i > 0) sb.append(',')
                        sb.append(Gson().toJson(k))
                        sb.append(':')
                        writeCanonical(obj.get(k), sb)
                    }
                    sb.append('}')
                }
                el.isJsonArray -> {
                    sb.append('[')
                    el.asJsonArray.forEachIndexed { i, v ->
                        if (i > 0) sb.append(',')
                        writeCanonical(v, sb)
                    }
                    sb.append(']')
                }
                el.isJsonNull -> sb.append("null")
                el.isJsonPrimitive -> {
                    val p = el.asJsonPrimitive
                    when {
                        p.isString -> sb.append(Gson().toJson(p.asString))
                        p.isBoolean -> sb.append(p.asBoolean.toString())
                        else -> sb.append(p.asNumber.toString())
                    }
                }
            }
        }
    }
}
