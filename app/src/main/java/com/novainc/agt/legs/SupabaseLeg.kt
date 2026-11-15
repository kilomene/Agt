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

    /** The effective anon key: a build-time constant, or the key entered
     * in Settings (or delivered by a Phoenix job). Empty means the leg is
     * not configured and every call fails closed. */
    fun effectiveKey(): String {
        val fromSettings = store.loadSettings().supabaseAnonKey
        if (fromSettings.isNotEmpty()) return fromSettings
        return AgtConfig.SUPABASE_ANON_KEY
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

    suspend fun hostUuid(deviceId: String): String? = withContext(Dispatchers.IO) {
        val text = call(request("/rest/v1/hosts?device_id=eq.$deviceId&select=id", "GET"))
        val arr = gson.fromJson(text, Array<JsonObject>::class.java)
        if (arr.isEmpty()) null else arr[0].get("id").asString
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

    suspend fun claimJob(jobId: String) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("status", "claimed")
            addProperty("claimed_at", java.time.Instant.now().toString())
        }
        call(request("/rest/v1/jobs?id=eq.$jobId", "PATCH", gson.toJson(body)))
        Unit
    }

    suspend fun completeJob(jobId: String, result: JsonObject) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("status", "done")
            addProperty("completed_at", java.time.Instant.now().toString())
            add("result", result)
        }
        call(request("/rest/v1/jobs?id=eq.$jobId", "PATCH", gson.toJson(body)))
        Unit
    }

    suspend fun failJob(jobId: String, error: String) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("status", "failed")
            addProperty("completed_at", java.time.Instant.now().toString())
            add("result", JsonObject().apply { addProperty("error", error) })
        }
        call(request("/rest/v1/jobs?id=eq.$jobId", "PATCH", gson.toJson(body)))
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
            add("config", JsonObject().apply { addProperty("command", deployment.command) })
        }
        val req = request("/rest/v1/deployments?on_conflict=id", "POST", gson.toJson(body))
            .newBuilder().header("Prefer", "resolution=merge-duplicates").build()
        call(req)
        Unit
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
