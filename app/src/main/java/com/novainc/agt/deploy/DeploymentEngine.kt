package com.novainc.agt.deploy

import android.content.Context
import com.novainc.agt.core.AgtStore
import com.novainc.agt.core.Deployment
import com.novainc.agt.linux.ProcessSupervisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Deployments: create from a template, a git URL, or an uploaded bundle;
 * snapshot and restore; delete. A deployment is a directory under the
 * app-private store plus a record; the supervisor runs it.
 */
class DeploymentEngine(
    private val context: Context,
    private val store: AgtStore,
    private val supervisor: ProcessSupervisor
) {
    fun createFromTemplate(
        template: Template, name: String,
        command: String = template.command,
        port: Int = template.port,
        env: Map<String, String> = template.env,
        owner: String = "",
        secrets: List<String> = emptyList()
    ): Deployment {
        val id = UUID.randomUUID().toString()
        val dir = store.deploymentDir(id)
        val guestWorkdir = "/root/deployments/$id"
        for ((fileName, content) in template.files) {
            val f = File(dir, fileName)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        val list = store.loadDeployments()
        val deployment = Deployment(
            id = id,
            name = name,
            kind = template.id,
            source = "template:${template.id}",
            command = command,
            workdir = guestWorkdir,
            env = env,
            port = port,
            slug = uniqueSlug(name, id, list.map { effectiveSlug(it) }.toSet()),
            owner = owner,
            secrets = secrets
        )
        list.add(deployment)
        store.saveDeployments(list)
        return deployment
    }

    /** Give every stored deployment a unique slug. Rows written by
     * older builds have none and fall back to the name-derived slug,
     * which two same-named deployments share. Idempotent: a row
     * that already holds a slug keeps it. Called at service start,
     * before the first sync publishes the deployments. */
    fun backfillSlugs() {
        val list = store.loadDeployments()
        if (list.none { storedSlug(it).isBlank() }) return
        val taken = list.map { storedSlug(it) }.filter { it.isNotBlank() }.toMutableSet()
        var changed = false
        val updated = list.map { d ->
            if (storedSlug(d).isNotBlank()) {
                d
            } else {
                val s = uniqueSlug(d.name, d.id, taken)
                taken += s
                changed = true
                d.copy(slug = s)
            }
        }
        if (changed) store.saveDeployments(updated)
    }

    /**
     * Create from a git URL. The repo is fetched as a tarball from the
     * host (GitHub codeload) and extracted into the deployment directory.
     * The guest workdir mirrors the same relative path under /root.
     */
    suspend fun createFromGit(
        url: String, name: String, command: String, port: Int,
        env: Map<String, String> = emptyMap(),
        autoRestart: Boolean = true,
        batteryGuard: Boolean = false,
        owner: String = "",
        secrets: List<String> = emptyList()
    ): Deployment =
        withContext(Dispatchers.IO) {
            val tarballUrl = gitTarballUrl(url)
            val id = UUID.randomUUID().toString()
            val dir = store.deploymentDir(id)
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val req = Request.Builder().url(tarballUrl).build()
            val tmp = File.createTempFile("git-", ".tar.gz", context.cacheDir)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Git download HTTP ${resp.code}")
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("Empty git download")
            }
            extractTarball(tmp, dir)
            tmp.delete()
            val list = store.loadDeployments()
            val deployment = Deployment(
                id = id,
                name = name,
                kind = "git",
                source = url,
                command = command,
                workdir = "/root/deployments/$id",
                env = env,
                port = port,
                autoRestart = autoRestart,
                batteryGuard = batteryGuard,
                slug = uniqueSlug(name, id, list.map { effectiveSlug(it) }.toSet()),
                owner = owner,
                secrets = secrets
            )
            list.add(deployment)
            store.saveDeployments(list)
            deployment
        }

    /** Create from a bundle file (tar.gz) the user picked. */
    suspend fun createFromBundle(
        bundle: File, name: String, command: String, port: Int,
        env: Map<String, String> = emptyMap(),
        autoRestart: Boolean = true,
        batteryGuard: Boolean = false
    ): Deployment =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val dir = store.deploymentDir(id)
            extractTarball(bundle, dir)
            val list = store.loadDeployments()
            val deployment = Deployment(
                id = id,
                name = name,
                kind = "bundle",
                source = bundle.name,
                command = command,
                workdir = "/root/deployments/$id",
                env = env,
                port = port,
                autoRestart = autoRestart,
                batteryGuard = batteryGuard,
                slug = uniqueSlug(name, id, list.map { effectiveSlug(it) }.toSet())
            )
            list.add(deployment)
            store.saveDeployments(list)
            deployment
        }

    fun delete(deploymentId: String) {
        supervisor.stop(deploymentId)
        val list = store.loadDeployments()
        list.removeAll { it.id == deploymentId }
        store.saveDeployments(list)
        store.deploymentDir(deploymentId).deleteRecursively()
        store.logFileFor(deploymentId).delete()
    }

    /** Snapshot a deployment directory into a dated tar.gz. Returns the file. */
    fun snapshot(deployment: Deployment): File {
        val dir = store.deploymentDir(deployment.id)
        val out = File(
            store.snapshotsDir(),
            "${deployment.name.replace(Regex("[^A-Za-z0-9_-]"), "-")}-${System.currentTimeMillis()}.tar.gz"
        )
        GzipCompressorOutputStream(FileOutputStream(out)).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                dir.walkTopDown().forEach { file ->
                    if (file == dir) return@forEach
                    val rel = dir.toPath().relativize(file.toPath()).toString()
                    val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(file, rel)
                    tar.putArchiveEntry(entry)
                    if (file.isFile) FileInputStream(file).use { it.copyTo(tar) }
                    tar.closeArchiveEntry()
                }
            }
        }
        return out
    }

    fun listSnapshots(): List<File> =
        store.snapshotsDir().listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    companion object {
        /** The slug actually stored on a row, tolerating rows the
         * JSON loader hydrated without one: Gson does not apply
         * Kotlin default values, so a field added in v1.6.6 reads
         * back null, not "", in files written by older builds.
         * Every slug read goes through here. */
        fun storedSlug(d: Deployment): String {
            val raw: String? = d.slug
            return if (raw.isNullOrBlank()) "" else raw
        }

        /** The owning agent stored on a row, with the same Gson
         * tolerance as storedSlug: rows written before the
         * per-agent Vault existed read back null, which means
         * owner-created on the phone, not any agent. */
        fun storedOwner(d: Deployment): String {
            val raw: String? = d.owner
            return if (raw.isNullOrBlank()) "" else raw
        }

        /** The secret NAMES stored on a row, with the same Gson
         * tolerance: a pre-vault row reads back null, which means
         * the deployment declares no secrets. */
        fun storedSecrets(d: Deployment): List<String> {
            val raw: List<String>? = d.secrets
            if (raw == null) return emptyList()
            return raw.filter { it.isNotBlank() }
        }

        /** The subdomain slug a deployment answers to: its stored
         * slug, or the name-derived slug for rows written before
         * slugs were stored. */
        fun effectiveSlug(d: Deployment): String =
            storedSlug(d).ifBlank { com.novainc.agt.ui.slugify(d.name) }.ifBlank { "deployment" }

        /** A slug no other deployment holds. The first deployment
         * of a name keeps the clean slug; a later one gets the same
         * base plus a unique code from its own id, so two servers
         * can never share one address (the field bug the owner
         * caught: four deployments named pic all answering to
         * pic-agt.novamail.store). */
        fun uniqueSlug(name: String, id: String, taken: Set<String>): String {
            val base = com.novainc.agt.ui.slugify(name).ifBlank { "deployment" }
            if (base !in taken) return base
            val short = "$base-${id.take(4)}"
            if (short !in taken) return short
            return "$base-${id.take(8)}"
        }

        /**
         * Turn a repo URL into its codeload tarball URL. Accepts
         * https://github.com/owner/repo and .../tree/branch forms.
         */
        fun gitTarballUrl(url: String): String {
            val cleaned = url.trim().removeSuffix("/").removeSuffix(".git")
            val parts = cleaned.removePrefix("https://github.com/").split("/")
            require(parts.size >= 2) { "Not a GitHub repo URL: $url" }
            val owner = parts[0]
            val repo = parts[1]
            val branch = if (parts.size >= 4 && parts[2] == "tree") parts[3] else "main"
            return "https://codeload.github.com/$owner/$repo/tar.gz/refs/heads/$branch"
        }

        fun extractTarball(tarball: File, dest: File) {
            dest.mkdirs()
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(tarball))).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry = tar.nextEntry
                    var stripPrefix: String? = null
                    while (entry != null) {
                        if (stripPrefix == null && entry.name.contains("/")) {
                            stripPrefix = entry.name.substringBefore("/") + "/"
                        }
                        val rel = if (stripPrefix != null && entry.name.startsWith(stripPrefix)) {
                            entry.name.removePrefix(stripPrefix)
                        } else {
                            entry.name
                        }
                        if (rel.isNotEmpty()) {
                            val target = File(dest, rel)
                            val canonical = target.canonicalFile
                            if (!canonical.path.startsWith(dest.canonicalPath)) {
                                throw IllegalStateException("Unsafe tar entry: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                canonical.mkdirs()
                            } else {
                                canonical.parentFile?.mkdirs()
                                FileOutputStream(canonical).use { out -> tar.copyTo(out) }
                                if (entry.mode and 0x49 != 0) canonical.setExecutable(true, false)
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }
}
