package com.novainc.agt.linux

/**
 * A console shell session with a keeper. The session owns a starter
 * that produces a fresh shell process (today: a native bash on a
 * real pty, raised by NativeLinux through NativePty); the keeper
 * thread drains the current process into the transcript and, the
 * moment it exits, starts the next one and says so in the
 * transcript. The shell behind the console is therefore always a
 * living one: an exit (a crash, the owner typing exit, a killed
 * process) is a restart, never a dead screen. Only close() ends
 * the session for good.
 *
 * The session is owned by AgtRuntime's registry, which the
 * foreground service owns: opening or closing the Console tab only
 * attaches or detaches the view. This class moved out of Proot.kt
 * unchanged in behaviour when the shell's engine was replaced;
 * the starter's product is now a ShellProcess instead of a guest
 * Process, and nothing else about the keeper contract changed.
 */
class ShellSession(private val starter: () -> ShellProcess) {
    private val output = StringBuilder()
    private val lock = Any()

    @Volatile
    private var proc: ShellProcess = starter()

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
            // shell. A shell that dies within moments of starting
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
     * itself, so nothing is echoed locally here: a local echo on
     * top of the pty's would print every line twice. If a write
     * lands in the moment between a shell's exit and its keeper
     * restart, it is retried once against the fresh shell instead
     * of being lost. */
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

    /** Raw bytes to the shell's terminal, exactly as given: the
     * console forwards every typed keystroke through here as it
     * happens (committed characters from the soft keyboard,
     * hardware key events, and the extra-keys row: tab, escape,
     * arrow sequences, control characters). Nothing is
     * line-buffered on the app side; the shell echoes and edits
     * through its own pty, so what the console shows is what the
     * shell produced. Through the pty these do what they do in
     * Termux: Ctrl-C interrupts the foreground program, tab
     * completes, arrows move through history. */
    fun sendRaw(text: String) {
        writeRaw(text.toByteArray())
    }

    fun snapshot(): String = synchronized(lock) { output.toString() }

    fun close() {
        closed = true
        try { proc.destroy() } catch (e: Exception) { }
    }
}
