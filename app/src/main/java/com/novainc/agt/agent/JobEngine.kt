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
import com.novainc.agt.vault.SecretRedactor
import com.novainc.agt.vault.VaultNames
import com.novainc.agt.vault.VaultStore
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
    private val identity: DeviceIdentity,
    private val vault: VaultStore
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

    /**
     * Job ids this process has already taken ownership of. The
     * atomic Supabase claim is the real guard; this set is the
     * in-process second lock, so a job id handed to handle() twice
     * in one process lifetime (overlapping poll loops around a leg
     * restart) is executed exactly once here as well. Bounded: the
     * oldest ids fall out past the cap, and by then the row's
     * terminal state in Supabase is the standing guard.
     */
    private val ownedJobIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(
            object : java.util.LinkedHashSet<String>() {
                override fun add(element: String): Boolean {
                    val added = super.add(element)
                    if (added) {
                        while (size > 512) remove(iterator().next())
                    }
                    return added
                }
            }
        )

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
        // Idempotency gate: a job executes exactly once. If this
        // process already owns the id, or the atomic claim finds
        // the row no longer queued (another loop claimed it, or it
        // already finished), skip it silently: never re-execute,
        // never write a second result over the first one. (Field
        // proof 2026-11-17: an agent_revoke executed twice this
        // way, and the second pass overwrote {revoked: true} with
        // "no such agent" even though the revoke had landed.)
        if (!ownedJobIds.add(jobId)) return
        try {
            if (!supabase.claimJob(jobId)) return
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
            // Release the in-process guard so a failure at the claim
            // step (row still queued) can be retried by a later poll.
            // A failure after the claim is safe either way: the row
            // is claimed or terminal by then, never queued again, so
            // the atomic claim refuses any second execution.
            ownedJobIds.remove(jobId)
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
        return if (agentId.isEmpty()) {
            store.updateSettings { current -> AgentTrust.pairAsMain(current, key) }
            JsonObject().apply {
                addProperty("paired", true)
                addProperty("agent", AgentTrust.MAIN_AGENT_ID)
                addProperty("devicePublicKey", identity.publicKeyBase64())
            }
        } else {
            var change: AgentTrust.Change? = null
            store.updateSettings { current ->
                val ch = AgentTrust.pairAsAgent(current, agentId, key)
                change = ch
                ch.settings ?: current
            }
            if (change?.settings == null) return fail(change?.error ?: "pairing refused")
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
                // Command output is a job result, so it passes
                // the vault redactor before it is written back
                // (per-agent Vault rule 5): a command that prints
                // a secret cannot leak it through its own result.
                val values = vault.allValues()
                JsonObject().apply {
                    addProperty("exit", run.exitCode)
                    addProperty("stdout", SecretRedactor.redact(run.stdout, values).take(4000))
                    addProperty("stderr", SecretRedactor.redact(run.stderr, values).take(4000))
                }
            }
            "deploy" -> {
                // The deployment's owner is the verified signer,
                // never a payload field: ownership decides whose
                // vault its declared secrets come from. The
                // payload declares secret NAMES only; values are
                // never in a job, they are injected by the
                // supervisor from the owner's vault at start.
                val owner = AgentTrust.agentIdOf(signer)
                val secretNames = mutableListOf<String>()
                val secretsEl = payload.get("secrets")
                if (secretsEl != null && secretsEl.isJsonArray) {
                    for (el in secretsEl.asJsonArray) {
                        if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                            val n = el.asString
                            if (VaultNames.isValidName(n)) secretNames.add(n)
                        }
                    }
                }
                val templateId = payload.get("template")?.asString
                val deployment = if (templateId != null) {
                    val template = Templates.byId(templateId)
                        ?: return fail("unknown template $templateId")
                    val name = payload.get("name")?.asString ?: template.name
                    deployEngine.createFromTemplate(
                        template, name, owner = owner, secrets = secretNames
                    )
                } else {
                    val git = payload.get("git")?.asString ?: return fail("deploy needs template or git")
                    val name = payload.get("name")?.asString ?: git.substringAfterLast("/").removeSuffix(".git")
                    val command = payload.get("command")?.asString ?: "python3 -m http.server 8080"
                    val port = payload.get("port")?.asInt ?: 0
                    deployEngine.createFromGit(
                        git, name, command, port, owner = owner, secrets = secretNames
                    )
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
                // Instant path (v1.6.20, ghost-row root fix): the
                // remote row is deleted in the same job, per the
                // instant-realtime law, so the address stops
                // publishing at once instead of waiting for the
                // next sync cycle. Best effort only: a failure
                // here never fails the remove, the sync
                // reconciliation is the guaranteed net beneath it.
                try {
                    val hid = hostId
                    if (hid != null && supabase.configured) {
                        supabase.deleteDeployment(hid, id)
                    }
                } catch (e: Exception) { }
                JsonObject().apply { addProperty("removed", true) }
            }
            "firebase_token" -> {
                val token = payload.get("token")?.asString ?: return fail("token missing")
                store.updateSettings { s -> s.copy(firebaseToken = token) }
                onFirebaseTokenSaved?.invoke()
                JsonObject().apply { addProperty("saved", true) }
            }
            "agent_add" -> {
                // Admitting a new trusted agent is the main agent's
                // act alone: AgentTrust refuses any other signer,
                // so an admitted agent can never admit another.
                val agentId = payload.get("agentId")?.asString ?: return fail("agentId missing")
                val key = payload.get("publicKey")?.asString ?: return fail("publicKey missing")
                var change: AgentTrust.Change? = null
                store.updateSettings { current ->
                    val ch = AgentTrust.applyAgentAdd(current, signer, agentId, key)
                    change = ch
                    ch.settings ?: current
                }
                if (change?.settings == null) return fail(change?.error ?: "agent_add refused")
                JsonObject().apply {
                    addProperty("added", true)
                    addProperty("addedAgent", agentId)
                }
            }
            "agent_revoke" -> {
                // Same gate as agent_add, and the main agent's own
                // entry is unrevocable inside AgentTrust.
                val agentId = payload.get("agentId")?.asString ?: return fail("agentId missing")
                var change: AgentTrust.Change? = null
                store.updateSettings { current ->
                    val ch = AgentTrust.applyAgentRevoke(current, signer, agentId)
                    change = ch
                    ch.settings ?: current
                }
                if (change?.settings == null) return fail(change?.error ?: "agent_revoke refused")
                // Revocation ends the agent's access whole: its
                // vault is wiped with its trust (per-agent Vault
                // rule 0), so a revoked agent leaves no secrets
                // behind on the phone.
                vault.wipe(agentId)
                JsonObject().apply {
                    addProperty("revoked", true)
                    addProperty("revokedAgent", agentId)
                }
            }
            "secret_set" -> {
                // Write one secret into the SIGNER's own vault.
                // The vault is chosen by the verified signature,
                // never by the payload: an agent can only ever
                // write its own vault, and the result carries the
                // name and a stored flag, never the value.
                val name = payload.get("name")?.asString ?: return fail("name missing")
                val value = payload.get("value")?.asString ?: return fail("value missing")
                if (!VaultNames.isValidName(name)) return fail("bad secret name")
                val me = AgentTrust.agentIdOf(signer)
                if (!vault.set(me, name, value)) return fail("secret not stored")
                JsonObject().apply {
                    addProperty("stored", true)
                    addProperty("name", name)
                }
            }
            "secret_list" -> {
                // The NAMES in the signer's own vault. There is
                // deliberately no variant that names another
                // agent: an agent cannot even learn what names
                // live in someone else's vault.
                val me = AgentTrust.agentIdOf(signer)
                val arr = com.google.gson.JsonArray()
                for (n in vault.names(me)) arr.add(n)
                JsonObject().apply { add("names", arr) }
            }
            "secret_delete" -> {
                // Delete one secret from the signer's own vault.
                val name = payload.get("name")?.asString ?: return fail("name missing")
                val me = AgentTrust.agentIdOf(signer)
                if (!vault.delete(me, name)) return fail("no such secret")
                JsonObject().apply {
                    addProperty("deleted", true)
                    addProperty("name", name)
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
            "secret_set" -> "secret stored"
            "secret_list" -> "secret names listed"
            "secret_delete" -> "secret deleted"
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
