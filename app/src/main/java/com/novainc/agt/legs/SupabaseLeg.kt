package com.novainc.agt.legs

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.novainc.agt.core.AgtConfig
import com.novainc.agt.core.AgtStore
import com.novainc.agt.core.Deployment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The Supabase leg: Postgres is the truth. Host rows, signed jobs,
 * deployment sync and log upload all go through PostgREST with the anon
 * key; RLS policies on the tables scope what the anon key can do.
 * When the key is not configured the leg reports not-configured and
 * every call fails closed with a plain message.
 */
class SupabaseLeg(private val store: AgtStore) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The anon key baked into the build from the CI secret. There is
     * no Settings entry and no override: the owner ordered the key into
     * GitHub, baked at build time, never pasted by hand. Empty means a
     * local build without the secret, and every call fails closed. */
    fun effectiveKey(): String = AgtConfig.SUPABASE_ANON_KEY

    /** Last measured PostgREST round trip in milliseconds, set by the
     * runtime around its sync calls. Null until the first sync lands,
     * so the UI never prints an invented number. */
    private val _latencyMs = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val latencyMs: kotlinx.coroutines.flow.StateFlow<Long?> = _latencyMs

    fun noteLatency(ms: Long) {
        _latencyMs.value = ms
    }

    val configured: Boolean
        get() = effectiveKey().isNotEmpty()

    private fun headers(): Map<String, String> {
        val key = effectiveKey()
        return mapOf(
            "apikey" to key,
            "Authorization" to "Bearer $key",
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
    }

    private fun request(path: String, method: String, bodyJson: String? = null): Request {
        val builder = Request.Builder().url(AgtConfig.SUPABASE_URL + path)
        headers().forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "POST" -> builder.post((bodyJson ?: "{}").toRequestBody("application/json".toMediaType()))
            "PATCH" -> builder.patch((bodyJson ?: "{}").toRequestBody("application/json".toMediaType()))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        return builder.build()
    }

    private fun call(req: Request): String {
        if (!configured) throw IllegalStateException("Supabase not configured")
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IllegalStateException("Supabase HTTP ${resp.code}: ${text.take(200)}")
            return text
        }
    }

    suspend fun upsertHost(deviceId: String, name: String, publicKey: String, telemetry: JsonObject): String =
        withContext(Dispatchers.IO) {
            val body = JsonObject().apply {
                addProperty("device_id", deviceId)
                addProperty("name", name)
                addProperty("public_key", publicKey)
                addProperty("online", true)
                add("telemetry", telemetry)
            }
            val req = request("/rest/v1/hosts?on_conflict=device_id", "POST", gson.toJson(body))
                .newBuilder().header("Prefer", "resolution=merge-duplicates,return=representation").build()
            val text = call(req)
            val arr = gson.fromJson(text, Array<JsonObject>::class.java)
            if (arr.isEmpty()) throw IllegalStateException("Host upsert returned no row")
            arr[0].get("id").asString
        }

    suspend fun heartbeat(hostId: String, telemetry: JsonObject) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("online", true)
            addProperty("last_seen", java.time.Instant.now().toString())
            add("telemetry", telemetry)
        }
        call(request("/rest/v1/hosts?id=eq.$hostId", "PATCH", gson.toJson(body)))
        Unit
    }

    suspend fun pollJobs(hostId: String): List<JsonObject> = withContext(Dispatchers.IO) {
        val text = call(
            request(
                "/rest/v1/jobs?host_id=eq.$hostId&status=eq.queued&order=created_at.asc&limit=10",
                "GET"
            )
        )
        gson.fromJson(text, Array<JsonObject>::class.java).toList()
    }

    /**
     * Claim a job atomically: the PATCH only matches while the row
     * is still queued, and return=representation shows whether this
     * caller won the row. Two poll loops racing the same job (the
     * loop restarts on every leg reconnect, and cancellation is
     * cooperative, so an old loop can still be holding a fetched
     * job when the new one polls) can no longer both claim it: the
     * loser's PATCH matches nothing and returns an empty array.
     * Returns true only when this caller owns the job.
     */
    suspend fun claimJob(jobId: String): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("status", "claimed")
            addProperty("claimed_at", java.time.Instant.now().toString())
        }
        val req = request("/rest/v1/jobs?id=eq.$jobId&status=eq.queued", "PATCH", gson.toJson(body))
            .newBuilder().header("Prefer", "return=representation").build()
        val text = call(req)
        val arr = gson.fromJson(text, Array<JsonObject>::class.java)
        arr.isNotEmpty()
    }

    /**
     * Completion only lands from the claimed state this engine
     * owns. If the row already reached a terminal state, the PATCH
     * matches nothing and the first result stands: a finished job's
     * result is never overwritten by a late second execution.
     */
    suspend fun completeJob(jobId: String, result: JsonObject) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("status", "done")
            addProperty("completed_at", java.time.Instant.now().toString())
            add("result", result)
        }
        call(request("/rest/v1/jobs?id=eq.$jobId&status=eq.claimed", "PATCH", gson.toJson(body)))
        Unit
    }

    suspend fun failJob(jobId: String, error: String) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("status", "failed")
            addProperty("completed_at", java.time.Instant.now().toString())
            add("result", JsonObject().apply { addProperty("error", error) })
        }
        call(request("/rest/v1/jobs?id=eq.$jobId&status=eq.claimed", "PATCH", gson.toJson(body)))
        Unit
    }

    suspend fun upsertDeployment(hostId: String, deployment: Deployment) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("id", deployment.id)
            addProperty("host_id", hostId)
            addProperty("name", deployment.name)
            addProperty("kind", deployment.kind)
            addProperty("source", deployment.source)
            addProperty("status", deployment.status)
            addProperty("port", deployment.port)
            add("config", JsonObject().apply {
                addProperty("command", deployment.command)
                // The deployment's unique subdomain slug rides in
                // config so the domain reconciler wires the stored
                // address, the same one the app shows, instead of
                // re-deriving it from a name another deployment
                // may share.
                addProperty(
                    "slug",
                    com.novainc.agt.deploy.DeploymentEngine.effectiveSlug(deployment)
                )
            })
        }
        val req = request("/rest/v1/deployments?on_conflict=id", "POST", gson.toJson(body))
            .newBuilder().header("Prefer", "resolution=merge-duplicates").build()
        call(req)
        Unit
    }

    /**
     * Delete one deployment row, scoped to this host. The DELETE
     * returns the deleted row with return=representation, so the
     * caller can see whether anything was actually removed: a
     * no-op (already gone) returns an empty array, not an error.
     * This is the missing half of upsertDeployment: without it a
     * removed deployment's row froze at its last status forever
     * (ghost rows, 2026-11-19), and the domain reconciler kept
     * publishing its address.
     */
    suspend fun deleteDeployment(hostId: String, deploymentId: String): Boolean = withContext(Dispatchers.IO) {
        val req = request("/rest/v1/deployments?id=eq.$deploymentId&host_id=eq.$hostId", "DELETE")
            .newBuilder().header("Prefer", "return=representation").build()
        val text = call(req)
        val arr = gson.fromJson(text, Array<JsonObject>::class.java)
        arr.isNotEmpty()
    }

    /** The deployment ids Supabase currently holds for this host,
     * used by the sync reconciliation to find ghost rows. */
    suspend fun listDeploymentIds(hostId: String): Set<String> = withContext(Dispatchers.IO) {
        val text = call(request("/rest/v1/deployments?host_id=eq.$hostId&select=id", "GET"))
        gson.fromJson(text, Array<JsonObject>::class.java)
            .mapNotNull { it.get("id")?.takeIf { v -> !v.isJsonNull }?.asString }
            .toSet()
    }

    suspend fun uploadLogs(deploymentId: String, lines: List<String>) = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext
        val rows = lines.map { line ->
            JsonObject().apply {
                addProperty("deployment_id", deploymentId)
                addProperty("line", line)
            }
        }
        call(request("/rest/v1/deployment_logs", "POST", gson.toJson(rows)))
        Unit
    }
}
