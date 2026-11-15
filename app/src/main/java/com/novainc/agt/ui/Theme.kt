package com.novainc.agt.ui

import androidx.compose.ui.graphics.Color
import com.novainc.agt.core.Deployment
import com.novainc.agt.deploy.Templates

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

/** The design's compact uptime for deployment cards: "6d 04:12" past
 * a day, "04:12" inside the first day. */
fun formatUptimeShort(totalMs: Long): String {
    val totalSec = (totalMs / 1000).coerceAtLeast(0)
    val days = totalSec / 86400
    val h = (totalSec % 86400) / 3600
    val m = (totalSec % 3600) / 60
    return if (days > 0) "${days}d %02d:%02d".format(h, m) else "%02d:%02d".format(h, m)
}

/** Relative age for the agent job feed: "now", "12 min", "1 h", "3 d". */
fun formatRelative(atMs: Long, nowMs: Long): String {
    val diff = (nowMs - atMs).coerceAtLeast(0)
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000} min"
        diff < 86_400_000 -> "${diff / 3_600_000} h"
        else -> "${diff / 86_400_000} d"
    }
}

/** The design's secret mask: dots with only the last characters
 * showing, the way the deployment detail screen presents env values. */
fun maskSecret(v: String): String = when {
    v.isEmpty() -> "(empty)"
    v.length <= 3 -> "••••"
    else -> "••••••••••" + v.takeLast(3)
}

/** The deployment's public name under the AGT domain, derived from
 * its display name exactly the way the design shows it. */
fun slugify(name: String): String =
    name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("").trim('-').replace(Regex("-+"), "-")

fun subdomainOf(name: String): String = slugify(name) + ".agt.novamail.store"

/** Runtime word for a deployment, read from its real start command. */
fun runtimeLabel(d: Deployment): String = when {
    d.command.startsWith("node") -> "Node 18"
    d.command.startsWith("python3") -> "Python 3"
    else -> "Linux VM"
}

/** Source word for a deployment: where it actually came from. */
fun sourceLabel(d: Deployment): String = when {
    d.kind == "git" -> "from Git"
    d.kind == "bundle" -> "from bundle"
    else -> "from Template"
}

/** The card icon for a deployment: the template's own icon for
 * template deployments, a branch for git, layers for a bundle. */
fun deploymentIcon(d: Deployment): IconKind = when (d.kind) {
    "git" -> IconKind.Branch
    "bundle" -> IconKind.Layers
    else -> Templates.byId(d.kind)?.icon ?: IconKind.Cloud
}
