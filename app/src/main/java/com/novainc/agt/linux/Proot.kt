package com.novainc.agt.linux

import android.content.Context
import android.os.Build
import com.novainc.agt.core.AgtConfig
import com.novainc.agt.core.AgtStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The Linux layer: a Debian bookworm rootfs running under PRoot, no root.
 * The PRoot binary ships as libproot.so in jniLibs (native library dir is
 * executable on Android, unlike app data). The rootfs is downloaded once
 * from our own release, verified by sha256, and extracted with pure Java.
 * Everything here was proven on the bench first: PRoot 5.3.0 runs this
 * exact rootfs, python3 and node install and serve inside it.
 */
class Proot(private val context: Context, private val store: AgtStore) {

    val prootBin: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    val rootfsDir: File
        get() = File(store.linuxDir(), "rootfs")

    val tmpDir: File
        get() = File(context.cacheDir, "proot-tmp").apply { mkdirs() }

    val supported: Boolean
        get() = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    fun isInstalled(): Boolean {
        val settings = store.loadSettings()
        if (!settings.linuxReady) return false
        if (!File(rootfsDir, "bin/bash").exists()) return false
        if (!File(rootfsDir, "usr/bin/python3").exists()) return false
        if (!File(rootfsDir, "usr/bin/node").exists()) return false
        return settings.rootfsVersion == AgtConfig.ROOTFS_VERSION
    }

    /**
     * First-run install. Downloads the rootfs (about 100 MB, one time),
     * checks its sha256 when one is pinned, extracts it, and writes the
     * guest config (resolv.conf, apt gpgv method). Reports progress 0..1.
     */
    suspend fun install(onProgress: (Float, String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!supported) {
                    return@withContext Result.failure(
                        IllegalStateException("This phone is not arm64; the Linux layer needs arm64-v8a")
                    )
                }
                if (!prootBin.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("PRoot binary missing from the app install")
                    )
                }
                val tarball = File(store.linuxDir(), "rootfs.tar.gz")
                if (!tarball.exists() || tarball.length() < 10_000_000) {
                    if (!copyBundledRootfs(tarball) { p -> onProgress(p * 0.7f, "Unpacking Debian system") }) {
                        onProgress(0.02f, "Downloading Debian system")
                        download(tarball) { p -> onProgress(p * 0.7f, "Downloading Debian system") }
                    }
                }
                if (AgtConfig.ROOTFS_SHA256.isNotEmpty()) {
                    onProgress(0.72f, "Verifying download")
                    val actual = sha256(tarball)
                    if (!actual.equals(AgtConfig.ROOTFS_SHA256, ignoreCase = true)) {
                        tarball.delete()
                        return@withContext Result.failure(
                            IllegalStateException("Rootfs checksum mismatch, download deleted")
                        )
                    }
                }
                onProgress(0.75f, "Extracting Debian system")
                extract(tarball)
                onProgress(0.95f, "Finishing guest setup")
                File(rootfsDir, "etc/resolv.conf").apply {
                    parentFile?.mkdirs()
                    writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
                }
                File(rootfsDir, "etc/apt/apt.conf.d/99agt").apply {
                    parentFile?.mkdirs()
                    writeText("APT::Sandbox::User \"root\";\n")
                }
                val settings = store.loadSettings()
                store.saveSettings(
                    settings.copy(
                        rootfsVersion = AgtConfig.ROOTFS_VERSION,
                        linuxReady = true
                    )
                )
                tarball.delete()
                onProgress(1f, "Linux ready")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Copy the rootfs bundled in the APK assets, if the build included it.
     * Returns false when no bundled copy exists, so the caller downloads
     * from [AgtConfig.ROOTFS_URL] instead. */
    private fun copyBundledRootfs(out: File, onProgress: (Float) -> Unit): Boolean {
        return try {
            context.assets.open("debian-bookworm-agt-arm64.bundle").use { input ->
                out.parentFile?.mkdirs()
                var copied = 0L
                val total = AgtConfig.ROOTFS_SIZE_BYTES
                out.outputStream().use { output ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) onProgress(copied.toFloat() / total)
                    }
                }
            }
            out.exists() && out.length() >= 10_000_000
        } catch (e: Exception) {
            false
        }
    }

    /** Download the rootfs in parts and reassemble it. Each part is
     * fetched from the repo; the sha256 check in install() covers the
     * reassembled file, so a bad part fails closed there. */
    private fun download(out: File, onProgress: (Float) -> Unit) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val parts = AgtConfig.ROOTFS_PART_URLS
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { output ->
            parts.forEachIndexed { index, url ->
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Rootfs part ${index + 1}/${parts.size} HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty rootfs part ${index + 1}")
                    val total = body.contentLength().takeIf { it > 0 } ?: 1L
                    var read = 0L
                    body.byteStream().use { input ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            val overall = (index + read.toFloat() / total) / parts.size
                            onProgress(overall.coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
    }

    private fun extract(tarball: File) {
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()
        val rootCanonical = rootfsDir.canonicalPath
        GzipCompressorInputStream(BufferedInputStream(FileInputStream(tarball))).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val target = File(rootfsDir, entry.name)
                    val canonical = target.canonicalFile
                    if (!canonical.path.startsWith(rootCanonical)) {
                        throw IllegalStateException("Unsafe tar entry: ${entry.name}")
                    }
                    when {
                        entry.isDirectory -> canonical.mkdirs()
                        entry.isSymbolicLink -> {
                            // A Debian rootfs is built on links: bin, lib,
                            // sbin and lib64 are symlinks into usr, and
                            // usr/bin/python3 is a symlink to python3.11.
                            // Writing a link as an empty regular file (the
                            // old behaviour) corrupts the tree and can make
                            // later entries fail outright, so the install
                            // never finished. Create the real link.
                            canonical.parentFile?.mkdirs()
                            removePath(canonical)
                            android.system.Os.symlink(entry.linkName, canonical.path)
                        }
                        entry.isLink -> {
                            // Hardlink: point at the already extracted
                            // target; fall back to copying its bytes when
                            // the filesystem refuses the link.
                            canonical.parentFile?.mkdirs()
                            removePath(canonical)
                            val source = File(rootfsDir, entry.linkName).canonicalFile
                            try {
                                android.system.Os.link(source.path, canonical.path)
                            } catch (e: Exception) {
                                if (source.isFile) {
                                    source.copyTo(canonical, overwrite = true)
                                } else {
                                    throw IllegalStateException(
                                        "Hardlink target missing for ${entry.name}: ${entry.linkName}"
                                    )
                                }
                            }
                        }
                        entry.isFIFO || entry.isCharacterDevice || entry.isBlockDevice -> {
                            // Device nodes and FIFOs cannot be created
                            // without root, and the guest never needs
                            // them: PRoot binds the host /dev over the
                            // rootfs at runtime.
                        }
                        else -> {
                            canonical.parentFile?.mkdirs()
                            if (isSymlink(canonical) || canonical.isDirectory) removePath(canonical)
                            FileOutputStream(canonical).use { out ->
                                tar.copyTo(out)
                            }
                            if (entry.mode and 0x49 != 0) canonical.setExecutable(true, false)
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    /** True when the path itself is a symlink (not its target). */
    private fun isSymlink(file: File): Boolean {
        return try {
            val st = android.system.Os.lstat(file.path)
            (st.st_mode and 0xF000) == 0xA000
        } catch (e: Exception) {
            false
        }
    }

    /** Remove a path without following it: a symlink is unlinked
     * itself, a directory tree is deleted, a file is deleted. */
    private fun removePath(file: File) {
        if (isSymlink(file) || file.isFile) {
            file.delete()
        } else if (file.isDirectory) {
            file.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Build the full PRoot command for a guest shell command. */
    fun commandFor(
        guestCommand: String,
        workdir: String = "/root",
        env: Map<String, String> = emptyMap(),
        binds: List<Pair<String, String>> = emptyList()
    ): List<String> = buildProotCommand(
        prootBin.absolutePath, rootfsDir.absolutePath, guestCommand, workdir, binds
    )

    companion object {
        /** Pure command builder, unit-testable without Android. */
        fun buildProotCommand(
            prootPath: String,
            rootfsPath: String,
            guestCommand: String,
            workdir: String = "/root",
            binds: List<Pair<String, String>> = emptyList()
        ): List<String> {
            val cmd = mutableListOf(
                prootPath,
                "-R", rootfsPath,
                "-0",
                "-w", workdir,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys"
            )
            for ((host, guest) in binds) cmd += listOf("-b", "$host:$guest")
            cmd += listOf("/bin/bash", "-c", guestCommand)
            return cmd
        }
    }

    fun envFor(extra: Map<String, String>): Array<String> {
        val base = mapOf(
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "HOME" to "/root",
            "TERM" to "xterm",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        ) + extra
        return base.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** Run one guest command and wait for it, with a timeout. */
    fun runOneShot(
        guestCommand: String,
        workdir: String = "/root",
        env: Map<String, String> = emptyMap(),
        timeoutSec: Long = 120
    ): RunResult {
        val pb = ProcessBuilder(commandFor(guestCommand, workdir, env))
        pb.environment().clear()
        pb.environment().putAll(envFor(env).associate {
            val i = it.indexOf('=')
            it.substring(0, i) to it.substring(i + 1)
        })
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        val tErr = Thread { proc.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } }
        tOut.start(); tErr.start()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return RunResult(-1, out.toString(), "timed out after ${timeoutSec}s\n" + err)
        }
        tOut.join(2000); tErr.join(2000)
        return RunResult(proc.exitValue(), out.toString(), err.toString())
    }

    /** Start a long-running guest process, logging to a file. Returns the host Process. */
    fun startBackground(
        guestCommand: String,
        workdir: String,
        env: Map<String, String>,
        logFile: File,
        binds: List<Pair<String, String>> = emptyList()
    ): Process {
        val pb = ProcessBuilder(commandFor(guestCommand, workdir, env, binds))
        pb.environment().clear()
        pb.environment().putAll(envFor(env).associate {
            val i = it.indexOf('=')
            it.substring(0, i) to it.substring(i + 1)
        })
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
        return pb.start()
    }

    /** A persistent interactive guest shell for the console screen.
     * Binds mount host directories into the guest (a deployment
     * session binds its own directory at its workdir, the same mount
     * the supervisor gives the deployment's process). */
    fun startShell(workdir: String = "/root", binds: List<Pair<String, String>> = emptyList()): ShellSession {
        val args = mutableListOf(
            prootBin.absolutePath,
            "-R", rootfsDir.absolutePath,
            "-0",
            "-w", workdir,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys"
        )
        for ((host, guest) in binds) {
            args.add("-b")
            args.add("$host:$guest")
        }
        args.add("/bin/bash")
        val pb = ProcessBuilder(args)
        pb.environment().clear()
        pb.environment().putAll(envFor(emptyMap()).associate {
            val i = it.indexOf('=')
            it.substring(0, i) to it.substring(i + 1)
        })
        pb.redirectErrorStream(true)
        val proc = pb.start()
        return ShellSession(proc)
    }

    class ShellSession(private val proc: Process) {
        private val output = StringBuilder()
        private val lock = Any()
        private val reader = Thread {
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = proc.inputStream.read(buf)
                    if (n < 0) break
                    synchronized(lock) {
                        output.append(String(buf, 0, n))
                        if (output.length > 200_000) output.delete(0, output.length - 200_000)
                    }
                }
            } catch (e: Exception) {
                // process ended
            }
        }.apply { isDaemon = true; start() }

        fun send(line: String) {
            try {
                proc.outputStream.write((line + "\n").toByteArray())
                proc.outputStream.flush()
            } catch (e: Exception) {
                // process ended
            }
        }

        /** Raw bytes with no newline: control keys (tab, escape,
         * arrow sequences, control characters) from the console key bar. */
        fun sendRaw(text: String) {
            try {
                proc.outputStream.write(text.toByteArray())
                proc.outputStream.flush()
            } catch (e: Exception) {
                // process ended
            }
        }

        fun snapshot(): String = synchronized(lock) { output.toString() }

        val alive: Boolean get() = proc.isAlive

        fun close() {
            try { proc.destroy() } catch (e: Exception) { }
        }
    }
}
