package com.novainc.agt.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The service heartbeat log: one line every 30 seconds from AgtService,
 * stored in app-private storage. The in-app Console screen reads this file,
 * so what the owner sees is the service's real heartbeat, not a mock.
 */
object HeartbeatLog {
    private const val FILE_NAME = "heartbeat.log"
    private const val MAX_LINES = 500

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    fun append(context: Context, line: String) {
        val f = file(context)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        f.appendText("$stamp  $line\n")
        trim(f)
    }

    fun readLines(context: Context, max: Int = 200): List<String> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val lines = f.readLines()
        return if (lines.size > max) lines.takeLast(max) else lines
    }

    private fun trim(f: File) {
        runCatching {
            val lines = f.readLines()
            if (lines.size > MAX_LINES) {
                f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        }
    }
}
