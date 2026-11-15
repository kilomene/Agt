package com.novainc.agt.agent

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.novainc.agt.core.AgtStore
import com.novainc.agt.core.JobFeedEntry
import com.novainc.agt.deploy.DeploymentEngine
import com.novainc.agt.deploy.Templates
import com.novainc.agt.legs.SupabaseLeg
import com.novainc.agt.linux.ProcessSupervisor
import com.novainc.agt.linux.Proot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The agent connection: Phoenix writes signed jobs to Supabase, the phone
 * polls them, verifies the Ed25519 signature against the paired Phoenix
 * public key, executes, and writes the result back. No inbound port, no
 * SSH: everything rides the outbound Supabase channel. A job with a bad
 * or missing signature is refused and marked failed, never run.
 */
class JobEngine(
    private val store: AgtStore,
    private val supabase: SupabaseLeg,
    private val proot: Proot,
    private val supervisor: ProcessSupervisor,
    private val deployEngine: DeploymentEngine,
    private val identity: DeviceIdentity
) {
    private val gson = Gson()
    private var loop: Job? = null
    var hostId: String? = null

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
                delay(15_000)
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
            val result = when {
                kind == "pair" -> handlePair(payload, settings.pairingCode)
                signature.isNullOrEmpty() -> fail("missing signature")
                settings.phoenixPublicKey.isEmpty() -> fail("phone is not paired with Phoenix")
                !verify(payload, signature, settings.phoenixPublicKey) -> fail("signature does not verify")
                else -> execute(kind, payload)
            }
            supabase.completeJob(jobId, result)
            store.addJob(JobFeedEntry(UUID.randomUUID().toString(), kind, "done", summarize(kind, result)))
        } catch (e: Exception) {
            try {
                supabase.failJob(jobId, e.message ?: "job failed")
            } catch (ignored: Exception) {
            }
            store.addJob(JobFeedEntry(UUID.randomUUID().toString(), kind, "failed", e.message ?: "job failed"))
        }
    }

    private fun fail(message: String): JsonObject =
        JsonObject().apply { addProperty("error", message) }

    private fun verify(payload: JsonObject, signatureB64: String, publicKeyB64: String): Boolean {
        val public = DeviceIdentity.publicKeyFromBase64(publicKeyB64) ?: return false
        val sig = DeviceIdentity.signatureFromBase64(signatureB64) ?: return false
        return DeviceIdentity.verify(public, canonicalJson(payload).toByteArray(Charsets.UTF_8), sig)
    }

    private fun handlePair(payload: JsonObject, expectedCode: String): JsonObject {
        val code = payload.get("code")?.asString ?: ""
        val key = payload.get("phoenixPublicKey")?.asString ?: ""
        if (expectedCode.isEmpty() || code != expectedCode) {
            return fail("pairing code does not match")
        }
        if (DeviceIdentity.publicKeyFromBase64(key) == null) {
            return fail("bad Phoenix public key")
        }
        val settings = store.loadSettings()
        store.saveSettings(settings.copy(phoenixPublicKey = key, pairingCode = ""))
        return JsonObject().apply {
            addProperty("paired", true)
            addProperty("devicePublicKey", identity.publicKeyBase64())
        }
    }

    private suspend fun execute(kind: String, payload: JsonObject): JsonObject {
        return when (kind) {
            "command" -> {
                val command = payload.get("command")?.asString ?: ""
                val workdir = payload.get("workdir")?.asString ?: "/root"
                val run = proot.runOneShot(command, workdir, emptyMap(), 120)
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
                    addProperty("status", "running")
                }
            }
            "restart" -> {
                val id = payload.get("deploymentId")?.asString ?: return fail("deploymentId missing")
                val d = store.loadDeployments().firstOrNull { it.id == id } ?: return fail("no such deployment")
                supervisor.restart(d)
                JsonObject().apply { addProperty("status", "running") }
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
                JsonObject().apply { addProperty("saved", true) }
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
