package com.novainc.agt.linux

import java.io.InputStream
import java.io.OutputStream

/**
 * One living shell process as the console session sees it: a pair
 * of streams (the terminal's output coming back, the keystrokes
 * going in), a wait that returns the exit code, and a destroy.
 * The only implementation is the native pty process (NativePty):
 * the session layer no longer knows or cares how the process was
 * started, which is what let the shell's engine be replaced
 * (proot guest out, native process in) without the console, the
 * keeper or the runtime registry changing their behaviour.
 */
interface ShellProcess {
    val inputStream: InputStream
    val outputStream: OutputStream

    /** Block until the process exits; return its exit code. */
    fun waitFor(): Int

    /** End the process. The session's keeper treats any exit,
     * including this one after close(), as final only when the
     * session itself is closed. */
    fun destroy()
}
