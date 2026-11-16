package com.novainc.agt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.core.Deployment
import com.novainc.agt.core.ProcessRecord
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deployment detail, as designed: one deployment fully open. Live
 * logs stream every second, secrets stay masked (tap to reveal), and
 * the recovery actions (restart, snapshot, backups, stop, console)
 * are all real operations on this deployment.
 */
@Composable
fun DeploymentDetailScreen(deploymentId: String, onBack: () -> Unit, onOpenConsole: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var deployment by remember { mutableStateOf<Deployment?>(null) }
    var process by remember { mutableStateOf<ProcessRecord?>(null) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var snapshotNote by remember { mutableStateOf("") }
    var revealedSecrets by remember { mutableStateOf(false) }
    var showBackups by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<File>>(emptyList()) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            deployment = runtime.store.loadDeployments().firstOrNull { it.id == deploymentId }
            process = runtime.store.loadProcesses().firstOrNull { it.deploymentId == deploymentId }
            logs = runtime.supervisor.readLog(deploymentId).lines().filter { it.isNotBlank() }.takeLast(300)
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    val d = deployment
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(IconKind.ChevronLeft, onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d?.name ?: "Deployment", color = AgtColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (d != null) "${runtimeLabel(d)} · ${sourceLabel(d)}" else "",
                    color = AgtColors.TextDim, fontSize = 13.sp
                )
            }
            if (d != null) {
                // The pill speaks the stored status as it is: the
                // supervisor derives it from the process's actual
                // uptime, so a crash-looping deployment reads
                // CRASH-LOOPING here, never RUNNING.
                TintedPill(
                    when (d.status) {
                        "running" -> "RUNNING"
                        "crash-looping" -> "CRASH-LOOPING"
                        "starting" -> "STARTING"
                        else -> "STOPPED"
                    },
                    when (d.status) {
                        "running" -> AgtColors.Emerald
                        "starting" -> AgtColors.Amber
                        else -> AgtColors.Danger
                    },
                    pulsing = d.status == "running"
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (d == null) {
            EmptyState("This deployment no longer exists.")
            return@Column
        }
        val running = d.status == "running"
        // Under the supervisor's hand (a process exists or is being
        // raised): the log stream is live and Stop, not Start, is the
        // action that applies.
        val active = running || d.status == "starting" || d.status == "crash-looping"
        val proc = process
        AgtCard {
            InfoRow("Subdomain", subdomainOf(d), AgtColors.Gold)
            InfoDivider()
            InfoRow(
                "Port",
                if (d.port > 0) "${d.port} · via tunnel" else "none",
                AgtColors.TextPrimary
            )
            InfoDivider()
            InfoRow(
                "Uptime",
                (when {
                    running && proc != null -> formatUptimeShort(nowMs - proc.startedAt)
                    d.status == "crash-looping" -> "crash-looping"
                    d.status == "starting" -> "starting"
                    else -> "stopped"
                }) +
                    " · restarts ${proc?.restarts ?: 0}",
                AgtColors.TextPrimary
            )
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE LOGS", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (active) AgtColors.Emerald else AgtColors.TextDim)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (active) "STREAMING" else "STOPPED",
                    color = if (active) AgtColors.Emerald else AgtColors.TextDim,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AgtColors.MatteBlack)
                    .padding(10.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("No log lines yet.", color = AgtColors.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { line ->
                            Text(
                                line, color = AgtColors.TextPrimary, fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
        if (d.env.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            AgtCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ENVIRONMENT + SECRETS", color = AgtColors.TextDim, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                    )
                    Text("ENCRYPTED AT REST", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                for ((k, v) in d.env) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { revealedSecrets = !revealedSecrets }
                            .padding(vertical = 5.dp)
                    ) {
                        LineIcon(IconKind.Key, AgtColors.Gold, Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(k, color = AgtColors.TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (revealedSecrets) v.ifEmpty { "(empty)" } else maskSecret(v),
                            color = AgtColors.TextDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Secrets never leave this phone unencrypted, and never appear in logs. Tap a row to reveal.",
                    color = AgtColors.TextDim, fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(IconKind.Refresh, "Restart", Modifier.weight(1f)) {
                runtime.supervisor.restart(d)
            }
            ActionButton(IconKind.Download, "Snapshot", Modifier.weight(1f)) {
                val file = runtime.deployEngine.snapshot(d)
                snapshotNote = "Snapshot saved: ${file.name}"
            }
            ActionButton(IconKind.Database, "Backups", Modifier.weight(1f)) {
                val prefix = d.name.replace(Regex("[^A-Za-z0-9_-]"), "-") + "-"
                backups = runtime.deployEngine.listSnapshots().filter { it.name.startsWith(prefix) }
                showBackups = !showBackups
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active) {
                ActionButton(IconKind.Square, "Stop deployment", Modifier.weight(1f)) {
                    runtime.supervisor.stop(d.id)
                }
            } else {
                ActionButton(IconKind.Play, "Start deployment", Modifier.weight(1f)) {
                    runtime.supervisor.start(d)
                }
            }
            ActionButton(IconKind.Console, "View in console", Modifier.weight(1f)) {
                onOpenConsole()
            }
        }
        if (snapshotNote.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(snapshotNote, color = AgtColors.Emerald, fontSize = 12.sp)
        }
        if (showBackups) {
            Spacer(Modifier.height(12.dp))
            AgtCard {
                Text("BACKUPS OF ${d.name.uppercase()}", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (backups.isEmpty()) {
                    Text(
                        "No backups yet. Snapshot creates one.",
                        color = AgtColors.TextDim, fontSize = 12.5.sp
                    )
                }
                for (f in backups) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(f.name, color = AgtColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text(
                            "${f.length() / 1024} KB · " +
                                SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(f.lastModified())),
                            color = AgtColors.TextDim, fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AgtColors.TextDim, fontSize = 13.5.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun InfoDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AgtColors.Stroke)
    )
}

@Composable
private fun ActionButton(icon: IconKind, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        LineIcon(icon, AgtColors.Gold, Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = AgtColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
