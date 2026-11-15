package com.novainc.agt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.core.Deployment
import kotlinx.coroutines.delay

/**
 * Deployments: every hosted service on this phone, with working restart
 * controls. Tapping one opens its detail screen with live logs.
 */
@Composable
fun DeploymentsScreen(
    onOpenDetail: (String) -> Unit,
    onNew: () -> Unit,
    onTemplates: () -> Unit
) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var deployments by remember { mutableStateOf<List<Deployment>>(emptyList()) }
    var linuxReady by remember { mutableStateOf(runtime.proot.isInstalled()) }

    LaunchedEffect(Unit) {
        while (true) {
            deployments = runtime.store.loadDeployments()
            linuxReady = runtime.proot.isInstalled()
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Deployments", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${deployments.count { it.status == "running" }} running of ${deployments.size}",
                    color = AgtColors.TextDim, fontSize = 13.sp
                )
            }
            Spacer(Modifier.weight(1f))
            StatusPill(
                if (linuxReady) "LINUX READY" else "LINUX NOT INSTALLED",
                if (linuxReady) AgtColors.Emerald else AgtColors.Amber
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GoldButton("New deployment", onClick = onNew, modifier = Modifier.weight(1f))
            GhostButton("Templates", onClick = onTemplates, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        if (deployments.isEmpty()) {
            EmptyState(
                "Nothing hosted yet. Deploy a template, a git repo, or a bundle, and it runs here in the background."
            )
        }
        for (d in deployments) {
            DeploymentRow(
                deployment = d,
                healthy = runtime.supervisor.healthy(d),
                onOpen = { onOpenDetail(d.id) },
                onStart = { runtime.supervisor.start(d) },
                onStop = { runtime.supervisor.stop(d.id) },
                onRestart = { runtime.supervisor.restart(d) }
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DeploymentRow(
    deployment: Deployment,
    healthy: Boolean,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    val running = deployment.status == "running"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineIcon(IconKind.Cloud, AgtColors.Gold, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(deployment.name, color = AgtColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    deployment.kind + if (deployment.port > 0) "  ·  port ${deployment.port}" else "",
                    color = AgtColors.TextDim, fontSize = 12.sp
                )
            }
            StatusPill(
                when {
                    running && healthy -> "RUNNING"
                    running -> "STARTING"
                    else -> "STOPPED"
                },
                when {
                    running && healthy -> AgtColors.Emerald
                    running -> AgtColors.Amber
                    else -> AgtColors.TextDim
                },
                pulsing = running && healthy
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                GhostButton("Stop", onClick = onStop, modifier = Modifier.weight(1f))
                GhostButton("Restart", onClick = onRestart, modifier = Modifier.weight(1f))
            } else {
                GoldButton("Start", onClick = onStart, modifier = Modifier.weight(1f))
            }
            GhostButton("Open", onClick = onOpen, modifier = Modifier.weight(1f))
        }
    }
}
