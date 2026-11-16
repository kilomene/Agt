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
    fun selfTest(timeoutSec: Long = 30): RunResult {
        winningShape?.let { shape ->
            val r = runOneShot(
                "echo AGT_LINUX_OK",
                env = mapOf("PROOT_VERBOSE" to "2"),
                timeoutSec = timeoutSec,
                shape = shape
            )
            if (r.exitCode == 0 && r.stdout.contains("AGT_LINUX_OK")) return r
            winningShape = null
        }
        var last: RunResult? = null
        for (shape in START_SHAPES) {
            val r = runOneShot(
                "echo AGT_LINUX_OK",
                env = mapOf("PROOT_VERBOSE" to "2"),
                timeoutSec = timeoutSec,
                shape = shape
            )
            last = r
            if (r.exitCode == 0 && r.stdout.contains("AGT_LINUX_OK")) {
                winningShape = shape
                return r
            }
        }
        return last ?: RunResult(-2, "", "no start shape was attempted")
    }

    data class Diagnosis(
        val consoleOk: Boolean,
        val baselineLine: String,
        val report: String,
        val shapeName: String
    )

    /** The start shape that most recently answered the echo probe
     * on this device, chosen by the self-setup search in selfTest()
     * and diagnoseGuest(). Null until a probe answers. Every guest
     * launch (self-test, console sessions, deployment processes,
     * job commands) goes through the winning shape, so the app sets
     * its own Linux up instead of betting every launch on the one
     * shipped invocation. */
    @Volatile
    private var winningShape: StartShape? = null

    /** The shape guest launches should use right now: the proven
     * winner once a probe has answered, the shipped base shape
     * until then. */
    fun startShape(): StartShape = winningShape ?: SHAPE_BASE

    private val bashEcho = listOf("/bin/bash", "-c", "echo AGT_LINUX_OK")

    /** The diagnostic ladder, kept in the app permanently. The
     * guest died on the phone with exit 255 and total silence while
     * the identical tree runs under emulation and on the amd64
     * bench, so no off-phone test can find the fault: the phone
     * itself has to say which step kills it. The whole ladder
     * rides telemetry as linuxLadder, so the verdict is readable
     * from here.
     *   H   content hashes of the files the loader chain reads,
     *       plus the /lib link target: rules the extractor in or out.
     *   S   one line per start shape, probed in START_SHAPES order:
     *       the self-setup search. The first shape whose guest
     *       answers the echo wins, is named on the "S win" line,
     *       and is used by every guest launch from then on.
     *   R4  a static ELF (cloudflared) bound into the rootfs and
     *       run directly under the winning shape: proot and ptrace
     *       inside the guest, with no dynamic loader involved.
     *   R5  the dynamic loader itself as the tracee under the
     *       winning shape: whether the interpreter file can execute
     *       at all under proot here.
     *   R6  the pty probe: the console's own terminal mechanism
     *       (script giving bash a real pseudo-terminal) under the
     *       winning shape. If this answers, the console is a true
     *       terminal on this device, not a pipe mirror. */
    fun diagnoseGuest(): Diagnosis {
        val lines = mutableListOf<String>()
        lines += hashLine()
        // The shape search comes first: one probe per start shape,
        // in START_SHAPES order, and the first shape whose guest
        // answers the echo wins and is kept for every later launch.
        // These S lines cover the old ladder's variables (base was
        // R1, no0 was R2, env was R3) and add the seccomp axis.
        var winner: StartShape? = null
        var baseLine = ""
        var winLine = ""
        for (shape in START_SHAPES) {
            val r = runArgv(
                prootArgv(shape, tail = bashEcho),
                shape = shape,
                env = mapOf("PROOT_VERBOSE" to "2"),
                timeoutSec = 20
            )
            val line = rungLine("S ${shape.name}", r)
            lines += line
            if (shape == SHAPE_BASE) baseLine = line
            if (winner == null && r.exitCode == 0 && r.stdout.contains("AGT_LINUX_OK")) {
                winner = shape
                winLine = line
            }
        }
        winningShape = winner
        lines += "S win=${winner?.name ?: "none"}"
        val useShape = winner ?: SHAPE_BASE
        val cf = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        lines += if (cf.exists()) {
            rungLine(
                "R4 static",
                runArgv(
                    prootArgv(
                        useShape,
                        binds = listOf(cf.absolutePath to "/usr/local/bin/cfstatic"),
                        tail = listOf("/usr/local/bin/cfstatic", "--version")
                    ),
                    shape = useShape, env = emptyMap(), timeoutSec = 20
                )
            )
        } else {
            "R4 static exit=-2 out='cloudflared binary missing'"
        }
        lines += rungLine(
            "R5 loader",
            runArgv(
                prootArgv(useShape, tail = listOf("/lib/ld-linux-aarch64.so.1", "--version")),
                shape = useShape, env = emptyMap(), timeoutSec = 20
            )
        )
        lines += rungLine(
            "R6 pty",
            runOneShot(
                "printf 'echo AGT_PTY_OK\\n' | script -qec '$PTY_SHELL_COMMAND' /dev/null",
                timeoutSec = 25,
                shape = useShape
            )
        )
        val ok = winner != null
        return Diagnosis(
            ok,
            if (ok) winLine else baseLine,
            lines.joinToString("\n"),
            winner?.name ?: "none"
        )
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
     * differs from the shape's invocation only where it means to.
     * The tail runs under the same env -i guest cleaning as every
     * production launch (envPrefix), so a rung measures its shape,
     * not a different environment. */
    private fun prootArgv(
        shape: StartShape,
        binds: List<Pair<String, String>> = emptyList(),
        tail: List<String>
    ): List<String> {
        tmpDir.mkdirs()
        val cmd = mutableListOf(
            prootBin.absolutePath, "--link2symlink", "--kill-on-exit",
            "-R", rootfsDir.absolutePath
        )
        if (shape.fakeRoot) cmd += "-0"
        cmd += listOf("-w", "/root", "-b", "/dev", "-b", "/proc", "-b", "/sys")
        for ((host, guest) in binds) cmd += listOf("-b", "$host:$guest")
        cmd += envPrefix()
        cmd += tail
        return cmd
    }

    /** Put a launch's HOST environment on a process builder. The
     * environment proot itself runs in is a separate layer from
     * the environment the guest sees, and the two are never mixed:
     * this applier owns the host layer only. The host env is
     * cleared first when the shape says so, then rebuilt with
     * exactly the variables proot itself needs (hostEnvFor), the
     * shape's own host variables, and the caller's host extras.
     * The guest layer is applied inside proot instead: every
     * guest argv carries /usr/bin/env -i with the clean guest set
     * (see the companion's envPrefix), so no host path and no
     * Android runtime variable can leak into the guest, and the
     * guest set is identical on every launch. One applier for
     * every guest launch, so a shape means the same thing
     * everywhere. */
    private fun applyEnv(pb: ProcessBuilder, shape: StartShape, extra: Map<String, String>) {
        if (shape.clearEnv) pb.environment().clear()
        pb.environment().putAll(hostEnvFor(shape, extra))
    }

    /** The host environment for one guest launch: the variables
     * proot itself reads, and nothing else.
     *   PROOT_TMP_DIR and TMPDIR: proot creates its loader and
     *     extension temp files here. Android has no host /tmp to
     *     fall back to, so both names point at the app's scratch
     *     dir, which commandFor creates before every launch and
     *     nothing ever deletes. (Bench: either variable alone is
     *     enough for proot to find a temp dir; both are set
     *     because the fallback chain is proot's own business and
     *     the second name costs nothing.)
     *   LD_LIBRARY_PATH: the native library dir. This build of
     *     proot is statically linked, so the variable is inert
     *     for it (bench: a deliberately bogus value changes
     *     nothing); it is set because the production launchers
     *     this design follows set it, and a future dynamically
     *     linked proot would need it to find its libraries.
     *   PROOT_LOADER and PROOT_LOADER_32: set ONLY when a loader
     *     file actually ships in the native library dir. This
     *     proot build consults PROOT_LOADER (bench-proven: a
     *     nonexistent path fails every launch loudly, and the
     *     binary's own extracted loader works), so pointing it
     *     at a file that does not exist would break every start;
     *     while no loader ships, the variables stay unset and
     *     proot extracts its embedded loader into PROOT_TMP_DIR,
     *     the path the working tunnel launch already proves.
     * Shape extras and caller extras join only when they are
     * host keys (isHostEnvKey); a caller's guest variables travel
     * in the argv's env -i set instead, never through here. */
    private fun hostEnvFor(shape: StartShape, extra: Map<String, String>): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        val loader = File(context.applicationInfo.nativeLibraryDir, "libprootloader.so")
        if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
        val loader32 = File(context.applicationInfo.nativeLibraryDir, "libprootloader32.so")
        if (loader32.exists()) env["PROOT_LOADER_32"] = loader32.absolutePath
        env.putAll(shape.extraEnv)
        for ((k, v) in extra) if (isHostEnvKey(k)) env[k] = v
        return env
    }

    /** Run an explicit argv, capturing both streams: the diagnostic
     * ladder's raw proot invocations. */
    fun runArgv(
        argv: List<String>,
        shape: StartShape,
        env: Map<String, String>,
        timeoutSec: Long
    ): RunResult {
        val pb = ProcessBuilder(argv)
        applyEnv(pb, shape, env)
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
        binds: List<Pair<String, String>> = emptyList(),
        shape: StartShape = startShape()
    ): List<String> {
        // PROOT_TMP_DIR must exist before PRoot starts; a missing
        // scratch dir was one of the field console errors, and the
        // dir is never deleted once created.
        tmpDir.mkdirs()
        return buildProotCommand(
            prootBin.absolutePath, rootfsDir.absolutePath, guestCommand, workdir, binds, shape, env
        )
    }

    /** One way of starting the guest, on the HOST side: the
     * fake-root flag, whether proot's own environment is cleared
     * before the host set is rebuilt, and any extra variables the
     * shape needs (read by proot itself, like PROOT_NO_SECCOMP).
     * The GUEST environment is not part of a shape: every guest
     * command, on every shape, runs under /usr/bin/env -i with
     * the one clean guest set (the companion's GUEST_ENV_BASE),
     * the two-layer pattern the production Android proot
     * launchers use. A shape is data, so the self-setup search,
     * the ladder and every launch site all mean the same thing
     * by the same name. */
    data class StartShape(
        val name: String,
        val fakeRoot: Boolean,
        val clearEnv: Boolean,
        val extraEnv: Map<String, String> = emptyMap()
    )

    companion object {
        /** The shipped invocation: fake root, cleared environment. */
        val SHAPE_BASE = StartShape("base", fakeRoot = true, clearEnv = true)

        /** Base with proot's seccomp acceleration off. The shipped
         * proot binary carries the vendor workaround string for
         * this variable, and the bench verdict (see the root-cause
         * report of 2026-11-16) makes the acceleration the leading
         * suspect for the guest tracee dying by signal on the
         * phone, so this shape is probed first. */
        val SHAPE_NOSECCOMP = StartShape(
            "noseccomp", fakeRoot = true, clearEnv = true,
            extraEnv = mapOf("PROOT_NO_SECCOMP" to "1")
        )
        val SHAPE_NOSECCOMP_NO0 = StartShape(
            "noseccomp-no0", fakeRoot = false, clearEnv = true,
            extraEnv = mapOf("PROOT_NO_SECCOMP" to "1")
        )
        val SHAPE_NO0 = StartShape("no0", fakeRoot = false, clearEnv = true)
        val SHAPE_ENV = StartShape("env", fakeRoot = true, clearEnv = false)
        val SHAPE_NO0_ENV = StartShape("no0-env", fakeRoot = false, clearEnv = false)

        /** Probe order for the self-setup search, safest first:
         * the seccomp-off shape (the leading suspect's workaround),
         * the shipped base, the no-fake-root pair, then the
         * inherited-environment pair (closest to the tunnel's
         * environment handling, and the least controlled, so last). */
        val START_SHAPES = listOf(
            SHAPE_NOSECCOMP, SHAPE_BASE, SHAPE_NOSECCOMP_NO0,
            SHAPE_NO0, SHAPE_ENV, SHAPE_NO0_ENV
        )

        /** The command script runs to give the console shell its
         * real terminal: size the pty (script inherits a 0 by 0
         * size from the app's pipes, which full-screen programs
         * cannot draw into), then replace the shell with a login
         * bash, the way proot-distro starts its Debian shells. */
        const val PTY_SHELL_COMMAND = "stty rows 40 cols 120; exec bash -l"

        /** The clean guest environment, the only variables a
         * guest process starts with: applied inside proot by
         * /usr/bin/env -i (envPrefix), never by inheritance from
         * the host process. This is the guest half of the
         * two-layer environment the production Android proot
         * launchers use; the host half (the variables proot
         * itself reads) lives in hostEnvFor. TMPDIR here is the
         * GUEST's /tmp, inside the rootfs; it has nothing to do
         * with the host-side PROOT_TMP_DIR. */
        val GUEST_ENV_BASE: Map<String, String> = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LANG" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "TMPDIR" to "/tmp"
        )

        /** Host-layer keys: variables read by proot itself or by
         * the host dynamic loader. They belong in proot's own
         * environment and must never be passed into the guest's
         * env -i set: a guest has no use for a host PROOT_TMP_DIR
         * path, and the bench showed the old launch leaking
         * exactly that into the guest. */
        fun isHostEnvKey(key: String): Boolean =
            key.startsWith("PROOT_") || key == "LD_LIBRARY_PATH"

        /** The guest environment for one launch as KEY=VALUE
         * arguments: the clean base set plus the caller's guest
         * extras (deployment variables, job variables), with
         * host-layer keys filtered out. */
        fun guestEnvList(extra: Map<String, String> = emptyMap()): List<String> {
            val merged = LinkedHashMap(GUEST_ENV_BASE)
            for ((k, v) in extra) if (!isHostEnvKey(k)) merged[k] = v
            return merged.map { (k, v) -> "$k=$v" }
        }

        /** The argv prefix that applies the clean guest
         * environment inside proot: /usr/bin/env -i followed by
         * the guest set. Every guest argv in the app (one-shots,
         * background processes, ladder rungs, the console's
         * script tail) starts its tracee this way. */
        fun envPrefix(extra: Map<String, String> = emptyMap()): List<String> =
            listOf("/usr/bin/env", "-i") + guestEnvList(extra)

        /** Pure command builder, unit-testable without Android.
         * Every guest launch carries the two proot extensions the
         * distro tooling uses on Android: --link2symlink (hardlink
         * emulation for SELinux-enforcing app data) and
         * --kill-on-exit (no guest process outlives its proot).
         * Both are bench-proven neutral for startup. The tracee
         * is env -i with the clean guest set in front of
         * /bin/bash -c: the guest environment is constructed
         * inside proot, never inherited from the host process. */
        fun buildProotCommand(
            prootPath: String,
            rootfsPath: String,
            guestCommand: String,
            workdir: String = "/root",
            binds: List<Pair<String, String>> = emptyList(),
            shape: StartShape = SHAPE_BASE,
            env: Map<String, String> = emptyMap()
        ): List<String> {
            val cmd = mutableListOf(
                prootPath,
                "--link2symlink",
                "--kill-on-exit",
                "-R", rootfsPath
            )
            if (shape.fakeRoot) cmd += "-0"
            cmd += listOf(
                "-w", workdir,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys"
            )
            for ((host, guest) in binds) cmd += listOf("-b", "$host:$guest")
            cmd += envPrefix(env)
            cmd += listOf("/bin/bash", "-c", guestCommand)
            return cmd
        }
    }

    data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** Run one guest command and wait for it, with a timeout. */
    fun runOneShot(
        guestCommand: String,
        workdir: String = "/root",
        env: Map<String, String> = emptyMap(),
        timeoutSec: Long = 120,
        shape: StartShape = startShape()
    ): RunResult {
        val pb = ProcessBuilder(commandFor(guestCommand, workdir, env, shape = shape))
        applyEnv(pb, shape, env)
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
        val shape = startShape()
        val pb = ProcessBuilder(commandFor(guestCommand, workdir, env, binds, shape))
        applyEnv(pb, shape, env)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
        return pb.start()
    }

    /** A persistent interactive guest shell for the console screen:
     * a real terminal, not a pipe mirror. The guest tail is
     * util-linux `script`, which allocates a genuine pseudo-terminal
     * inside the guest and runs a login bash on it (the ladder's R6
     * mechanism, bench-proven end to end): the shell itself echoes
     * typing as it is typed, prints its own prompt, takes control
     * keys through the pty line discipline, and runs interactive
     * programs. Binds mount host directories into the guest (a
     * deployment session binds its own directory at its workdir,
     * the same mount the supervisor gives the deployment's process).
     *
     * The returned session carries a keeper: if the shell process
     * ever exits, for any reason, a fresh shell starts immediately
     * and the session continues on it. The console never sits in
     * front of a dead shell. */
    fun startShell(workdir: String = "/root", binds: List<Pair<String, String>> = emptyList()): ShellSession {
        return ShellSession { startShellProcess(workdir, binds) }
    }

    /** Start one shell process on a real pty, on the start shape
     * that currently answers. The session's keeper calls this again
     * every time a shell exits, so a restart always uses the
     * freshest proven shape. */
    private fun startShellProcess(workdir: String, binds: List<Pair<String, String>>): Process {
        val shape = startShape()
        val args = mutableListOf(
            prootBin.absolutePath,
            "--link2symlink", "--kill-on-exit",
            "-R", rootfsDir.absolutePath
        )
        if (shape.fakeRoot) args += "-0"
        args += listOf("-w", workdir, "-b", "/dev", "-b", "/proc", "-b", "/sys")
        for ((host, guest) in binds) args += listOf("-b", "$host:$guest")
        args += envPrefix()
        args += listOf("/usr/bin/script", "-qec", PTY_SHELL_COMMAND, "/dev/null")
        val pb = ProcessBuilder(args)
        applyEnv(pb, shape, emptyMap())
        pb.redirectErrorStream(true)
        return pb.start()
    }

    /** A console shell session with a keeper. The session owns a
     * starter that produces a fresh shell process; the keeper
     * thread drains the current process into the transcript and,
     * the moment it exits, starts the next one and says so in the
     * transcript. The shell behind the console is therefore always
     * a living one: an exit (a crash, the owner typing exit, a
     * killed process) is a restart, never a dead screen. Only
     * close() ends the session for good. */
    class ShellSession(private val starter: () -> Process) {
        private val output = StringBuilder()
        private val lock = Any()

        @Volatile
        private var proc: Process = starter()

        @Volatile
        private var closed = false

        private val keeper = Thread {
            var fastDeaths = 0
            while (!closed) {
                val current = proc
                val startedAt = System.currentTimeMillis()
                try {
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = current.inputStream.read(buf)
                        if (n < 0) break
                        append(String(buf, 0, n))
                    }
                } catch (e: Exception) {
                    // Stream closed: the process is ending either way.
                }
                val code = try { current.waitFor() } catch (e: Exception) { -1 }
                if (closed) break
                // The shell exited. Say so in the transcript, then
                // start its replacement at once: the keeper's whole
                // job is that this session never presents a dead
                // shell. A shell that dies within moments of
                // starting (the guest cannot start on this device)
                // backs off a few seconds between attempts so the
                // retry never becomes a hot spin, and it never
                // gives up.
                val livedMs = System.currentTimeMillis() - startedAt
                fastDeaths = if (livedMs < 3_000) fastDeaths + 1 else 0
                append("\n[agt] this shell exited (code $code). Restarting it now.\n")
                if (fastDeaths > 0) {
                    try { Thread.sleep(minOf(fastDeaths * 1_000L, 5_000L)) } catch (e: Exception) { }
                }
                if (closed) break
                try {
                    proc = starter()
                } catch (e: Exception) {
                    append("\n[agt] the shell could not be restarted (${e.message}); retrying.\n")
                    try { Thread.sleep(2_000) } catch (ie: Exception) { }
                }
            }
        }.apply { isDaemon = true; start() }

        private fun append(text: String) {
            synchronized(lock) {
                output.append(text)
                if (output.length > 200_000) output.delete(0, output.length - 200_000)
            }
        }

        /** Write bytes to the living shell. The pty echoes input
         * itself, so nothing is echoed locally here: a local echo
         * on top of the pty's would print every line twice (the
         * pipe-era ShellSession echoed locally for exactly the
         * opposite reason, and its comment said to remove it the
         * day the session gained a real pty; that day is this
         * design). If a write lands in the moment between a shell's
         * exit and its keeper restart, it is retried once against
         * the fresh shell instead of being lost. */
        private fun writeRaw(bytes: ByteArray) {
            try {
                proc.outputStream.write(bytes)
                proc.outputStream.flush()
                return
            } catch (e: Exception) {
                if (closed) return
            }
            try { Thread.sleep(400) } catch (e: Exception) { }
            try {
                proc.outputStream.write(bytes)
                proc.outputStream.flush()
            } catch (e: Exception) {
                // The keeper is still restarting; the transcript
                // shows the restart, and the next send lands.
            }
        }

        /** Raw bytes to the shell's terminal, exactly as given:
         * the console forwards every typed keystroke through here
         * as it happens (committed characters from the soft
         * keyboard, hardware key events, and the extra-keys row:
         * tab, escape, arrow sequences, control characters).
         * Nothing is line-buffered on the app side; the shell
         * echoes and edits through its own pty, so what the
         * console shows is what the shell produced. Through the
         * pty these do what they do in Termux: Ctrl-C interrupts
         * the foreground program, tab completes, arrows move
         * through history. */
        fun sendRaw(text: String) {
            writeRaw(text.toByteArray())
        }

        fun snapshot(): String = synchronized(lock) { output.toString() }

        fun close() {
            closed = true
            try { proc.destroy() } catch (e: Exception) { }
        }
    }
}
