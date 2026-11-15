package com.novainc.agt.ui

import androidx.compose.ui.graphics.Color

/**
 * The AGT design language, locked by the owner's approval of the UI design:
 * dark premium theme, matte black, AGT gold accent, emerald status light,
 * hand drawn icons, and a living motion system. Nothing static.
 */
object AgtColors {
    val MatteBlack = Color(0xFF0A0A0B)
    val Surface = Color(0xFF141416)
    val SurfaceHigh = Color(0xFF1D1D20)
    val Stroke = Color(0xFF2A2A2E)
    val Gold = Color(0xFFD9A62E)
    val GoldSoft = Color(0xFFF2C14E)
    val Emerald = Color(0xFF34D399)
    val Amber = Color(0xFFF5B544)
    val Danger = Color(0xFFE5534B)
    val TextPrimary = Color(0xFFF5F2EA)
    val TextDim = Color(0xFF9A97A3)
}

fun formatHms(totalMs: Long): String {
    val totalSec = (totalMs / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** The design's uptime form: "6d 14:22:08" once past a day, plain
 * "14:22:08" inside the first day. */
fun formatDuration(totalMs: Long): String {
    val totalSec = (totalMs / 1000).coerceAtLeast(0)
    val days = totalSec / 86400
    return if (days > 0) "${days}d ${formatHms(totalMs % 86_400_000)}" else formatHms(totalMs)
}

fun formatGb(v: Double): String = "%.1f".format(v)

/** A measured latency for the legs card: "38 ms". Null (never
 * measured) renders empty so the UI shows the state alone. */
fun formatLatency(ms: Long?): String = if (ms == null) "" else "$ms ms"
