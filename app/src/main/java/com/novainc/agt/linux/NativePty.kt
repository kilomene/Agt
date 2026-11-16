package com.novainc.agt.linux

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The native terminal bridge: libagtpty, a small JNI library built
 * for this app from its own C source (native-binaries/src/agtpty.c).
 * It creates a real pseudo-terminal and forks the requested program
 * onto its slave side, the pattern documented in the Termux source
 * study (report section 16): open /dev/ptmx, termios and window
 * size on the master, fork; the child starts a new session, takes
 * the slave as its controlling terminal on stdin/stdout/stderr,
 * installs exactly the environment it was given, changes directory
 * and execs. The child is a native bionic process of the app
 * itself: no ptrace, no translation layer, no guest loader stands
 * between the app and the shell.
 */
object NativePty {
    init {
        System.loadLibrary("agtpty")
    }

    /** Returns [masterFd, pid], or [negativeStage, 0] on failure:
     * -1 open /dev/ptmx, -2 grantpt/unlockpt/ptsname, -3 fork. */
    external fun createSubprocess(
        cmd: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int
    ): IntArray

    /** The child's exit code, or -signal when it died by a signal. */
    external fun waitFor(pid: Int): Int

    external fun killProcess(pid: Int, sig: Int)

    external fun closeFd(fd: Int)

    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    /** Start a program on a fresh pty and wrap it as a ShellProcess. */
    fun create(
        cmd: String,
        argv: List<String>,
        env: Map<String, String>,
        cwd: String,
        rows: Int = 40,
        cols: Int = 120
    ): ShellProcess {
        val envp = env.entries.map { "${it.key}=${it.value}" }.toTypedArray()
        val result = createSubprocess(cmd, argv.toTypedArray(), envp, cwd, rows, cols)
        val fd = result.getOrElse(0) { -1 }
        val pid = result.getOrElse(1) { 0 }
        check(fd >= 0) { "native pty start failed at stage $fd" }
        return NativePtyProcess(fd, pid)
    }
}

/** A process living on a pty master fd, seen by the session layer
 * as streams plus a waitable, killable pid. The fd is owned by the
 * ParcelFileDescriptor; closing it hangs the terminal up, which is
 * how destroy() reaches the whole process group on the slave. */
private class NativePtyProcess(
    private val fd: Int,
    private val pid: Int
) : ShellProcess {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(fd)

    override val inputStream: InputStream = FileInputStream(pfd.fileDescriptor)
    override val outputStream: OutputStream = FileOutputStream(pfd.fileDescriptor)

    override fun waitFor(): Int = NativePty.waitFor(pid)

    override fun destroy() {
        // SIGTERM first (15), then hang up the terminal: closing
        // the master delivers SIGHUP to the slave's foreground
        // process group, so nothing on the pty outlives teardown.
        try { NativePty.killProcess(pid, 15) } catch (e: Exception) { }
        try { pfd.close() } catch (e: Exception) { }
    }
}
