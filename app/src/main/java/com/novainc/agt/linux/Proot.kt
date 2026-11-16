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
        get() = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    val supported: Boolean
        get() = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    fun isInstalled(): Boolean {
        val settings = store.loadSettings()
        if (!settings.linuxReady) return false
        if (settings.rootfsVersion != AgtConfig.ROOTFS_VERSION) return false
        return treeIntact()
    }

    /** Integrity of the extracted tree, checked the way the guest
     * itself will use it. The v1.6.0 extractor wrote Debian's
     * symlinks as empty regular files: the paths existed, so the old
     * existence-only check called the tree ready, but bash could not
     * start because its loader chain ran through those dead files.
     * The shipped rootfs tarball also omits the /lib symlink every
     * Debian merged-usr tree carries. Without it, the interpreter
     * path every ELF requests (/lib/ld-linux-aarch64.so.1) and every
     * library path ld.so.cache stores under /lib/aarch64-linux-gnu
     * resolve into a void and no guest program can start; install() creates /lib
     * as a symlink to usr/lib, and a tree whose /lib is not that
     * symlink (older installs hold a real directory there instead)
     * is not installed, whatever the settings flag says. */
    fun treeIntact(): Boolean {
        if (!isSymlink(File(rootfsDir, "bin"))) return false
        if (!isSymlink(File(rootfsDir, "lib"))) return false
        if (!isSymlink(File(rootfsDir, "usr/bin/python3"))) return false
        if (File(rootfsDir, "usr/bin/bash").length() < 100_000) return false
        if (!File(rootfsDir, "usr/bin/node").exists()) return false
        val loader = File(rootfsDir, "lib/ld-linux-aarch64.so.1")
        if (!(loader.exists() && loader.length() > 100_000)) return false
        // The interpreters must also carry their execute bits: the
        // old extractor kept only a coarse setExecutable flag, and a
        // tree whose files merely exist can still refuse every
        // exec. X_OK is checked on the resolved targets, the same
        // files the guest will actually run.
        return canExecute(File(rootfsDir, "usr/bin/bash")) &&
            canExecute(File(rootfsDir, "usr/bin/python3")) &&
            canExecute(File(rootfsDir, "usr/bin/node"))
    }

    /** True when the file (symlinks followed) is executable by us. */
    private fun canExecute(file: File): Boolean {
        return try {
            android.system.Os.access(file.path, android.system.OsConstants.X_OK)
        } catch (e: Exception) {
            false
        }
    }

    /** Prove the guest actually runs: the one check that cannot
     * lie. Existence, sizes and permission bits all said "ready"
     * through shipped builds whose bash could not start at all;
     * an answered echo can only come from a working guest. PRoot
     * runs verbose here so a guest that cannot start explains
     * itself in the result: the v1.6.4 field failure exited with
     * no output at all, and a silent failure cannot be diagnosed
     * from the phone's own telemetry. */
    fun selfTest(timeoutSec: Long = 30): RunResult =
        runOneShot(
            "echo AGT_LINUX_OK",
            env = mapOf("PROOT_VERBOSE" to "2"),
            timeoutSec = timeoutSec
        )

    data class Diagnosis(val consoleOk: Boolean, val baselineLine: String, val report: String)

    private val bashEcho = listOf("/bin/bash", "-c", "echo AGT_LINUX_OK")

    /** The v1.6.6 diagnostic ladder. The guest dies on the phone
     * with exit 255 and total silence while the identical tree runs
     * under emulation and on the amd64 bench, so no off-phone test
     * can find the fault: the phone itself has to say which step
     * kills it. Each rung changes exactly one variable of the
     * failing invocation, and the whole ladder rides telemetry as
     * linuxLadder, so the verdict is readable from here.
     *   H   content hashes of the files the loader chain reads,
     *       plus the /lib link target: rules the extractor in or out.
     *   R1  the baseline invocation, exactly as shipped (fake root,
     *       cleared environment, verbose proot).
     *   R2  baseline without the fake-root flag.
     *   R3  baseline with the app environment inherited, not cleared.
     *   R4  a static ELF (cloudflared) bound into the rootfs and run
     *       directly: proot and ptrace inside the guest, with no
     *       dynamic loader involved.
     *   R5  the dynamic loader itself as the tracee: whether the
     *       interpreter file can execute at all under proot here. */
    fun diagnoseGuest(): Diagnosis {
        val lines = mutableListOf<String>()
        lines += hashLine()
        val r1 = selfTest()
        val ok1 = r1.exitCode == 0 && r1.stdout.contains("AGT_LINUX_OK")
        val base = rungLine("R1 base", r1)
        lines += base
        lines += rungLine(
            "R2 no0",
            runArgv(
                prootArgv(fakeRoot = false, tail = bashEcho),
                clearEnv = true, env = mapOf("PROOT_VERBOSE" to "2"), timeoutSec = 20
            )
        )
        lines += rungLine(
            "R3 env",
            runArgv(
                prootArgv(fakeRoot = true, tail = bashEcho),
                clearEnv = false, env = mapOf("PROOT_VERBOSE" to "2"), timeoutSec = 20
            )
        )
        val cf = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        lines += if (cf.exists()) {
            rungLine(
                "R4 static",
                runArgv(
                    prootArgv(
                        fakeRoot = true,
                        binds = listOf(cf.absolutePath to "/usr/local/bin/cfstatic"),
                        tail = listOf("/usr/local/bin/cfstatic", "--version")
                    ),
                    clearEnv = true, env = emptyMap(), timeoutSec = 20
                )
            )
        } else {
            "R4 static exit=-2 out='cloudflared binary missing'"
        }
        lines += rungLine(
            "R5 loader",
            runArgv(
                prootArgv(fakeRoot = true, tail = listOf("/lib/ld-linux-aarch64.so.1", "--version")),
                clearEnv = true, env = emptyMap(), timeoutSec = 20
            )
        )
        return Diagnosis(ok1, base, lines.joinToString("\n"))
    }

    /** One ladder rung as a single telemetry line: name, exit code,
     * and the output squashed onto one line and bounded. */
    private fun rungLine(name: String, r: RunResult): String {
        val out = (r.stdout + r.stderr).replace(Regex("\\s+"), " ").trim().take(110)
        return "$name exit=${r.exitCode} out='$out'"
    }

    /** Hashes of the exact files the guest loader chain reads,
     * first 12 hex chars each, plus the /lib link state. Compared
     * against the known-good bundle off-phone, they prove whether
     * the bytes on the phone are the bytes that run under
     * emulation, which rules the Java extractor in or out. */
    private fun hashLine(): String {
        fun h(rel: String): String = try {
            sha256(File(rootfsDir, rel)).take(12)
        } catch (e: Exception) {
            "ERR"
        }
        val libTarget = try {
            val lib = File(rootfsDir, "lib")
            if (isSymlink(lib)) android.system.Os.readlink(lib.path) else "NOTLINK"
        } catch (e: Exception) {
            "ERR"
        }
        return "H bash=${h("usr/bin/bash")} ld=${h("lib/ld-linux-aarch64.so.1")} " +
            "libc=${h("usr/lib/aarch64-linux-gnu/libc.so.6")} " +
            "py=${h("usr/bin/python3.11")} lib->$libTarget"
    }

    /** Build a raw proot argv with a caller-chosen tracee, for the
     * diagnostic ladder. Mirrors buildProotCommand's flags so a rung
     * differs from the shipped invocation only where it means to. */
    private fun prootArgv(
        fakeRoot: Boolean,
        binds: List<Pair<String, String>> = emptyList(),
        tail: List<String>
    ): List<String> {
        tmpDir.mkdirs()
        val cmd = mutableListOf(prootBin.absolutePath, "-R", rootfsDir.absolutePath)
        if (fakeRoot) cmd += "-0"
        cmd += listOf("-w", "/root", "-b", "/dev", "-b", "/proc", "-b", "/sys")
        for ((host, guest) in binds) cmd += listOf("-b", "$host:$guest")
        cmd += tail
        return cmd
    }

    /** Run an explicit argv, capturing both streams: the diagnostic
     * ladder's raw proot invocations. clearEnv mirrors the shipped
     * behaviour; the ladder's R3 rung turns it off. */
    fun runArgv(
        argv: List<String>,
        clearEnv: Boolean,
        env: Map<String, String>,
        timeoutSec: Long
    ): RunResult {
        val pb = ProcessBuilder(argv)
        if (clearEnv) pb.environment().clear()
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

    /** The merged-usr layout repair: /lib as a symlink to usr/lib.
     * Idempotent. A /lib that is already the right symlink is left
     * alone; anything else there (a real directory holding only the
     * old installer's loader link, a wrong link, nothing) is
     * replaced. The usr/lib side of the tree carries everything the
     * link exposes, including ld-linux-aarch64.so.1 itself. */
    private fun ensureLibSymlink() {
        val lib = File(rootfsDir, "lib")
        if (isSymlink(lib)) {
            try {
                if (android.system.Os.readlink(lib.path) == "usr/lib") return
            } catch (e: Exception) {
                // Fall through and recreate the link.
            }
        }
        removePath(lib)
        android.system.Os.symlink("usr/lib", lib.path)
    }

    /** Repair the layout of a tree extracted by an older build, in
     * place, without re-extracting 100 MB. Called at service start
     * before anything consults isInstalled(), so an upgraded phone
     * heals its existing tree on the first launch. The merge runs
     * first: the shipped tarball's /lib was a real directory holding
     * a dozen libraries that exist nowhere in usr/lib, and replacing
     * the directory with the symlink without merging orphans them
     * (the v1.6.5 repair did exactly that: bash survived, python3
     * died on the missing libexpat). */
    fun repairLayout() {
        if (!rootfsDir.exists()) return
        mergeLibDir()
        ensureLibSymlink()
    }

    /** Copy every entry of a real /lib directory into usr/lib,
     * preserving relative paths, link targets and permission bits,
     * skipping anything usr/lib already has. Runs before the /lib
     * symlink replaces the directory, so nothing the tree shipped
     * is lost. A /lib that is already a symlink (or absent) needs
     * no merge. */
    private fun mergeLibDir() {
        val lib = File(rootfsDir, "lib")
        if (!lib.isDirectory || isSymlink(lib)) return
        val usrLib = File(rootfsDir, "usr/lib")
        lib.walkTopDown().forEach { f ->
            if (f == lib) return@forEach
            val rel = lib.toPath().relativize(f.toPath()).toString()
            val dest = File(usrLib, rel)
            when {
                isSymlink(f) -> {
                    if (!dest.exists() && !isSymlink(dest)) {
                        dest.parentFile?.mkdirs()
                        try {
                            android.system.Os.symlink(
                                android.system.Os.readlink(f.path), dest.path
                            )
                        } catch (e: Exception) {
                            // A link that cannot be recreated is
                            // skipped; treeIntact() judges the result.
                        }
                    }
                }
                f.isDirectory -> dest.mkdirs()
                f.isFile -> {
                    if (!dest.exists()) {
                        dest.parentFile?.mkdirs()
                        f.copyTo(dest, overwrite = false)
                        try {
                            android.system.Os.chmod(
                                dest.path,
                                android.system.Os.lstat(f.path).st_mode and 0x1FF
                            )
                        } catch (e: Exception) {
                            // Mode copy is best effort; the exec-bit
                            // checks in treeIntact() judge the result.
                        }
                    }
                }
            }
        }
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
                // The merged-usr layout: /lib must be a symlink to
                // usr/lib, the layout Debian ships and every path in
                // the tree assumes. The v1 bundle omitted that
                // symlink and split a dozen libraries into a real
                // /lib directory; bundle v2 ships the merged layout,
                // and this call keeps any tree honest regardless.
                ensureLibSymlink()
                if (!treeIntact()) {
                    return@withContext Result.failure(
                        IllegalStateException("Linux tree failed its integrity check after extraction")
                    )
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
                            applyTarMode(canonical, entry.mode)
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    /** Apply the tar entry's permission bits exactly as recorded.
     * The old behaviour (setExecutable when any exec bit was set)
     * kept the exec flag but flattened everything else, and a file
     * that lands without the mode the tree ships can be refused by
     * execve even when it exists. Falls back to the coarse flag
     * when chmod itself is refused. */
    private fun applyTarMode(file: File, mode: Int) {
        try {
            android.system.Os.chmod(file.path, mode and 0x1FF)
        } catch (e: Exception) {
            if (mode and 0x49 != 0) file.setExecutable(true, false)
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
    ): List<String> {
        // PROOT_TMP_DIR must exist before PRoot starts; a missing
        // scratch dir was one of the field console errors, and the
        // dir is never deleted once created.
        tmpDir.mkdirs()
        return buildProotCommand(
            prootBin.absolutePath, rootfsDir.absolutePath, guestCommand, workdir, binds
        )
    }

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
