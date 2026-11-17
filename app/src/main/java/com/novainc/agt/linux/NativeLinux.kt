package com.novainc.agt.linux

import android.content.Context
import android.system.Os
import com.novainc.agt.core.AgtConfig
import com.novainc.agt.core.AgtStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * AGT's Linux layer, rebuilt on the Termux pattern (report
 * section 16): Linux is part of the app, not a guest installed
 * under a translation layer. The app ships a bootstrap zip of
 * native bionic binaries cross-compiled for this app's own prefix
 * (/data/data/com.novainc.agt/files/usr: bash, busybox and
 * libncurses today), extracts it into the app's files space as
 * part of the app itself on first start, and executes the
 * binaries natively: the shell is a native process on a real pty
 * (NativePty), deployments are native processes under the same
 * supervisor, and command jobs run the same bash directly.
 *
 * There is no proot in this path, no ptrace, no guest dynamic
 * loader and no rootfs: the failure mode that closed the previous
 * road (a glibc guest loader refused at startup under Android's
 * per-app syscall policy, sections 1-15) does not exist here,
 * because bionic binaries are what Android runs for every app.
 *
 * What "installed" and "ready" mean, kept separate as the shell
 * laws require: installed = the bootstrap for the current version
 * is extracted (a file fact). Ready = a native shell actually
 * answered its echo probe (diagnose()). Only the second is ever
 * reported as readiness.
 */
class NativeLinux(
    private val context: Context,
    private val store: AgtStore
) {
    val prefixDir: File = File(context.filesDir, "usr")
    val binDir: File = File(prefixDir, "bin")
    val libDir: File = File(prefixDir, "lib")
    val tmpDir: File = File(prefixDir, "tmp")
    val homeDir: File = File(context.filesDir, "home")
    private val stagingDir: File = File(context.filesDir, "usr-staging")
    private val markerFile: File = File(prefixDir, ".agt-bootstrap")

    val bashPath: String = File(binDir, "bash").absolutePath

    /** The bootstrap for the current version is extracted and its
     * shell is present and executable. A file fact only; readiness
     * is diagnose()'s probe verdict, never this. */
    fun isInstalled(): Boolean {
        if (!markerFile.exists()) return false
        if (store.loadSettings().rootfsVersion != AgtConfig.BOOTSTRAP_VERSION) return false
        val bash = File(binDir, "bash")
        return bash.exists() && bash.canExecute()
    }

    /**
     * Extract the shipped bootstrap into the prefix, Termux-style:
     * into a staging directory first (zip entries relative to the
     * prefix, SYMLINKS.txt carrying the links a zip cannot store),
     * then swapped into place, so a half-extracted prefix is never
     * what the app runs. The version is recorded in the settings'
     * rootfsVersion field (kept name; it now names the installed
     * Linux bundle, bootstrap instead of rootfs).
     */
    fun install(onProgress: (Int, String) -> Unit): Result<Unit> {
        return try {
            // The packaged bootstrap is verified against its pin
            // before a single file lands: a corrupted or swapped
            // asset fails the install in the open instead of
            // extracting a Linux that half works.
            onProgress(2, "Checking the packaged Linux")
            val assetBytes = context.assets.open(AgtConfig.BOOTSTRAP_ASSET)
                .use { it.readBytes() }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(assetBytes).joinToString("") { "%02x".format(it) }
            if (digest != AgtConfig.BOOTSTRAP_SHA256) {
                return Result.failure(IllegalStateException(
                    "bootstrap checksum mismatch: the packaged Linux " +
                        "does not match its pin"))
            }
            onProgress(5, "Unpacking the built-in Linux tools")
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            val symlinks = mutableListOf<Pair<String, String>>()
            java.io.ByteArrayInputStream(assetBytes).use { raw ->
                ZipInputStream(raw).use { zip ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == "SYMLINKS.txt") {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            symlinks += parseSymlinks(text)
                        } else if (entry.isDirectory) {
                            File(stagingDir, entry.name).mkdirs()
                        } else {
                            val target = File(stagingDir, entry.name)
                            check(target.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                                "bootstrap entry escapes the prefix: ${entry.name}"
                            }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            onProgress(60, "Linking the tool names")
            for ((linkTarget, linkPath) in symlinks) {
                val link = File(stagingDir, linkPath)
                link.parentFile?.mkdirs()
                try { Os.symlink(linkTarget, link.absolutePath) } catch (e: Exception) { }
            }
            // Executables and libraries get their exec bit here,
            // not from zip metadata: bin/ and lib/ are runnable by
            // construction of the bootstrap itself.
            stagingDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(stagingDir).path
                    if (rel.startsWith("bin/") || rel.startsWith("lib/")) {
                        try { Os.chmod(f.absolutePath, 493) } catch (e: Exception) { }
                    }
                }
            }
            File(stagingDir, ".agt-bootstrap")
                .writeText(AgtConfig.BOOTSTRAP_VERSION + "\n")
            onProgress(85, "Moving Linux into place")
            prefixDir.deleteRecursively()
            if (!stagingDir.renameTo(prefixDir)) {
                stagingDir.copyRecursively(prefixDir, overwrite = true)
                stagingDir.deleteRecursively()
            }
            tmpDir.mkdirs()
            homeDir.mkdirs()
            store.updateSettings { s -> s.copy(rootfsVersion = AgtConfig.BOOTSTRAP_VERSION) }
            onProgress(100, "Linux tools installed")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The environment every native Linux process runs with: the
     * Termux shape (HOME, PREFIX, PATH with the prefix first,
     * TMPDIR inside the prefix, LD_LIBRARY_PATH at the prefix's
     * lib so bash finds its libncurses), plus caller extras. */
    fun environment(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val env = linkedMapOf(
            "HOME" to homeDir.absolutePath,
            "PREFIX" to prefixDir.absolutePath,
            "PATH" to "${binDir.absolutePath}:/system/bin:/system/xbin",
            "TMPDIR" to tmpDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "SHELL" to bashPath,
            "LD_LIBRARY_PATH" to libDir.absolutePath
        )
        env.putAll(extra)
        return env
    }

    /** Map a workdir string from the proot era (a guest path) to
     * the native directory it means now: the guest's /root was the
     * working home, so it maps to the app's home directory; a real
     * existing directory passes through as itself. */
    fun resolveWorkdir(workdir: String): File {
        if (workdir.isEmpty() || workdir == "/root") return homeDir
        val f = File(workdir)
        return if (f.isDirectory) f else homeDir
    }

    /** The environment for a process whose working directory is
     * [dir]: the shared environment plus PWD naming that directory.
     * Android denies this app getcwd() path reconstruction (the
     * parents above the app's own directory are not readable by
     * the app uid), so a shell spawned without PWD prints
     * "shell-init: error retrieving current directory: getcwd:
     * cannot access parent directories: Permission denied" at
     * startup and its pwd builtin fails, even though its working
     * directory is exactly right (proven on the phone 2026-11-16:
     * /proc/self/cwd named the right directory while pwd printed
     * nothing). With PWD present and matching the working
     * directory, bash validates it by stat, which works, and never
     * calls getcwd: same shell, PWD set, pwd answers and the error
     * is gone. A caller-supplied PWD wins. */
    private fun environmentFor(dir: File, extra: Map<String, String>): Map<String, String> {
        val merged = environment(extra)
        return if (merged.containsKey("PWD")) merged else merged + ("PWD" to dir.absolutePath)
    }

    data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** Run one command through the native bash and wait for it,
     * with a timeout. The command job path: same capture contract
     * the guest one-shot had (streams separate, timeout kills). */
    fun runOneShot(
        command: String,
        workdir: String = "",
        env: Map<String, String> = emptyMap(),
        timeoutSec: Long = 120
    ): RunResult {
        val dir = resolveWorkdir(workdir)
        val pb = ProcessBuilder(bashPath, "-c", command)
        pb.directory(dir)
        val pbEnv = pb.environment()
        pbEnv.clear()
        pbEnv.putAll(environmentFor(dir, env))
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

    /** Start a long-running native process for a deployment,
     * logging to a file. The deployment's own directory is its
     * working directory and its HOME unless its env says
     * otherwise: what the bind mount used to fake inside the
     * guest is simply true here. */
    fun startBackground(
        command: String,
        workdir: File,
        env: Map<String, String>,
        logFile: File
    ): Process {
        val pb = ProcessBuilder(bashPath, "-c", command)
        pb.directory(workdir)
        val pbEnv = pb.environment()
        pbEnv.clear()
        val merged = environmentFor(workdir, env)
        pbEnv.putAll(merged)
        if (!env.containsKey("HOME")) {
            pbEnv["HOME"] = workdir.absolutePath
        }
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
        return pb.start()
    }

    /** A persistent interactive shell for the console: native bash
     * (a login shell, so it reads the prefix's own etc/profile,
     * the path compiled into this bash at build time) on a real
     * pty, wrapped in the keeper session the console has always
     * used. Sessions are raised per working directory: the
     * console's home session in the home directory, a deployment's
     * session in the deployment's own directory. */
    fun startShell(workdir: File = homeDir): ShellSession {
        return ShellSession { startShellProcess(workdir) }
    }

    private fun startShellProcess(workdir: File): ShellProcess {
        return NativePty.create(
            cmd = bashPath,
            argv = listOf(bashPath, "-l"),
            env = environmentFor(workdir, emptyMap()),
            cwd = workdir.absolutePath
        )
    }

    data class Diagnosis(
        val consoleOk: Boolean,
        val report: String,
        val detailLine: String
    )

    /**
     * The native self-test, successor to the guest ladder: prove
     * the shell by making it answer. The report (telemetry's
     * linuxLadder field, same key) carries the bootstrap version,
     * content hashes of the binaries the shell's start depends on
     * (the old H line's job, kept), and the probe's own words.
     * There are no start shapes to search: the process either
     * starts natively or the failure is printed by the runtime
     * itself, in the open.
     */
    fun diagnose(): Diagnosis {
        val lines = mutableListOf<String>()
        lines += "N native bootstrap=${AgtConfig.BOOTSTRAP_VERSION} installed=${isInstalled()}"
        lines += "N hashes " + listOf("bin/bash", "bin/busybox", "lib/libncurses.so.6.4")
            .joinToString(" ") { rel ->
                val f = File(prefixDir, rel)
                if (f.exists()) "${rel.substringAfterLast('/')}=${sha256Prefix(f)}" else "${rel.substringAfterLast('/')}=missing"
            }
        if (!isInstalled()) {
            lines += "N probe skipped: bootstrap not installed"
            return Diagnosis(false, lines.joinToString("\n"), "native Linux not installed yet")
        }
        // The probe proves three things, not one: the shell answers,
        // it knows where it stands (pwd prints the home directory,
        // which only works when the spawn carried PWD, see
        // environmentFor), and its startup is clean (no getcwd
        // error on stderr). A shell that answers but cannot say
        // where it is, or that greets with an error, is not ready.
        val probe = try {
            runOneShot("pwd; echo AGT_LINUX_OK", timeoutSec = 20)
        } catch (e: Exception) {
            lines += "N probe threw: ${e.message}"
            return Diagnosis(false, lines.joinToString("\n"),
                "native shell probe failed to start: ${e.message}".take(160))
        }
        val ok = probe.exitCode == 0 && probe.stdout.contains("AGT_LINUX_OK") &&
            probe.stdout.contains("files/home") && !probe.stderr.contains("getcwd")
        val heard = (probe.stdout + probe.stderr).replace(Regex("\\s+"), " ").trim().take(110)
        lines += "N probe exit=${probe.exitCode} pwdOk=${probe.stdout.contains("files/home")} " +
            "stderrClean=${!probe.stderr.contains("getcwd")} out='$heard'"
        val detail = if (ok) {
            "native shell answered AGT_LINUX_OK (bash on bionic, no proot)"
        } else {
            "native shell probe exit=${probe.exitCode} out='$heard'".take(160)
        }
        return Diagnosis(ok, lines.joinToString("\n"), detail)
    }

    private fun sha256Prefix(f: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.take(12)
        } catch (e: Exception) {
            "unreadable"
        }
    }

    companion object {
        /** Parse SYMLINKS.txt: one link per line, in the Termux
         * format "target←linkpath" (the arrow is U+2190), the
         * target exactly as the link should read, the path
         * relative to the prefix. Blank lines are ignored. Pure,
         * so the format is unit-testable off-device. */
        fun parseSymlinks(text: String): List<Pair<String, String>> {
            return text.lines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty()) return@mapNotNull null
                val parts = t.split("←")
                if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                    return@mapNotNull null
                }
                parts[0] to parts[1]
            }
        }
    }
}
