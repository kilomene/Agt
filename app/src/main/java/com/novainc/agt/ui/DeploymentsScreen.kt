package com.novainc.agt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

/**
 * Deployments, as designed: everything the host runs, each one
 * supervised, reachable, and one tap from control. Cards carry the
 * real port, subdomain, restart count and uptime from the process
 * records, and refresh every second.
 */
@Composable
fun DeploymentsScreen(
    onOpenDetail: (String) -> Unit,
    onNew: () -> Unit
) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var deployments by remember { mutableStateOf<List<Deployment>>(emptyList()) }
    var processes by remember { mutableStateOf<List<ProcessRecord>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            deployments = runtime.store.loadDeployments()
            processes = runtime.store.loadProcesses()
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val shown = deployments.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Deployments", color = AgtColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${deployments.size} total · supervised by AGT",
                    color = AgtColors.TextDim, fontSize = 13.sp
                )
            }
            CircleIconButton(
                IconKind.Search,
                onClick = { searching = !searching; if (!searching) query = "" },
                tint = if (searching) AgtColors.Gold else AgtColors.TextDim,
                borderColor = if (searching) AgtColors.Gold else AgtColors.Stroke
            )
        }
        if (searching) {
            Spacer(Modifier.height(12.dp))
            AgtField(query, { query = it }, "SEARCH", placeholder = "Search deployments")
        }
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            if (deployments.isEmpty()) {
                EmptyState(
                    "Nothing hosted yet. Deploy a template, a git repo, or a bundle, and it runs here in the background."
                )
            } else if (shown.isEmpty()) {
                EmptyState("No deployment matches that search.")
            }
            for (d in shown) {
                DeploymentCard(
                    deployment = d,
                    process = processes.firstOrNull { it.deploymentId == d.id },
                    healthy = runtime.supervisor.healthy(d),
                    nowMs = nowMs,
                    onOpen = { onOpenDetail(d.id) },
                    onStart = { runtime.supervisor.start(d) },
                    onStop = { runtime.supervisor.stop(d.id) },
                    onRestart = { runtime.supervisor.restart(d) }
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        GoldIconButton(
            IconKind.Plus, "New Deployment",
            onClick = onNew,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DeploymentCard(
    deployment: Deployment,
    process: ProcessRecord?,
    healthy: Boolean,
    nowMs: Long,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    val running = deployment.status == "running"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AgtColors.SurfaceHigh)
                    .border(1.dp, AgtColors.Stroke, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(deploymentIcon(deployment), AgtColors.Gold, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(deployment.name, color = AgtColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${runtimeLabel(deployment)} · ${sourceLabel(deployment)}",
                    color = AgtColors.TextDim, fontSize = 12.5.sp
                )
            }
            TintedPill(
                when {
                    running && healthy -> "RUNNING"
                    running -> "STARTING"
                    else -> "STOPPED"
                },
                when {
                    running && healthy -> AgtColors.Emerald
                    running -> AgtColors.Amber
                    else -> AgtColors.Danger
                },
                pulsing = running && healthy
            )
        }
        Spacer(Modifier.height(12.dp))
        Row {
            if (deployment.port > 0) {
                Text("port", color = AgtColors.TextDim, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text("${deployment.port}", color = AgtColors.TextPrimary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(16.dp))
            }
            Text(
                subdomainOf(deployment.name),
                color = AgtColors.TextPrimary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text("restarts", color = AgtColors.TextDim, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text("${process?.restarts ?: 0}", color = AgtColors.TextPrimary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
            if (deployment.batteryGuard) {
                Spacer(Modifier.width(16.dp))
                Text("battery guard on", color = AgtColors.TextDim, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (running && process != null) "up ${formatUptimeShort(nowMs - process.startedAt)}" else "stopped",
                color = AgtColors.TextDim, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            if (running) {
                RoundAction(IconKind.Refresh, AgtColors.TextPrimary) { onRestart() }
                Spacer(Modifier.width(10.dp))
                RoundStop { onStop() }
            } else {
                RoundAction(IconKind.Play, AgtColors.Emerald) { onStart() }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: IconKind, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(AgtColors.SurfaceHigh)
            .border(1.dp, AgtColors.Stroke, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        LineIcon(icon, tint, Modifier.size(18.dp))
    }
}

@Composable
private fun RoundStop(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(AgtColors.SurfaceHigh)
            .border(1.dp, AgtColors.Stroke, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AgtColors.TextPrimary)
        )
    }
}
