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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.delay

/**
 * Deployment detail: live status, controls, masked secrets, and the
 * streaming log. Secrets stay masked until tapped; the log streams every
 * two seconds and follows the tail.
 */
@Composable
fun DeploymentDetailScreen(deploymentId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var deployment by remember { mutableStateOf<Deployment?>(null) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var snapshotNote by remember { mutableStateOf("") }
    var revealedSecrets by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            deployment = runtime.store.loadDeployments().firstOrNull { it.id == deploymentId }
            logs = runtime.supervisor.readLog(deploymentId).lines().takeLast(300)
            delay(2000)
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
            Column(Modifier.weight(1f)) {
                Text(d?.name ?: "Deployment", color = AgtColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(d?.kind ?: "", color = AgtColors.TextDim, fontSize = 13.sp)
            }
            OutlinedButton(onClick = onBack) { Text("Back", color = AgtColors.GoldSoft) }
        }
        Spacer(Modifier.height(16.dp))
        if (d == null) {
            EmptyState("This deployment no longer exists.")
            return@Column
        }
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STATUS", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                StatusPill(
                    d.status.uppercase(),
                    if (d.status == "running") AgtColors.Emerald else AgtColors.TextDim,
                    pulsing = d.status == "running"
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Command", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(d.command, color = AgtColors.TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Text("Source", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(d.source, color = AgtColors.TextPrimary, fontSize = 13.sp)
            if (d.port > 0) {
                Spacer(Modifier.height(6.dp))
                Text("Port", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${d.port}", color = AgtColors.TextPrimary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("Health", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                if (runtime.supervisor.healthy(d)) "Healthy" else "Not answering yet",
                color = if (runtime.supervisor.healthy(d)) AgtColors.Emerald else AgtColors.Amber,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (d.status == "running") {
                GhostButton("Stop", onClick = { runtime.supervisor.stop(d.id) }, modifier = Modifier.weight(1f))
                GhostButton("Restart", onClick = { runtime.supervisor.restart(d) }, modifier = Modifier.weight(1f))
            } else {
                GoldButton("Start", onClick = { runtime.supervisor.start(d) }, modifier = Modifier.weight(1f))
            }
            GhostButton("Snapshot", onClick = {
                val file = runtime.deployEngine.snapshot(d)
                snapshotNote = "Snapshot saved: ${file.name}"
            }, modifier = Modifier.weight(1f))
        }
        if (snapshotNote.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(snapshotNote, color = AgtColors.Emerald, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (d.env.isNotEmpty()) {
            AgtCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SECRETS", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (revealedSecrets) "Hide" else "Tap to reveal",
                        color = AgtColors.GoldSoft, fontSize = 12.sp,
                        modifier = Modifier.clickable { revealedSecrets = !revealedSecrets }
                    )
                }
                Spacer(Modifier.height(8.dp))
                for ((k, v) in d.env) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k, color = AgtColors.TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (revealedSecrets) v.ifEmpty { "(empty)" } else "••••••••",
                            color = AgtColors.TextDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        AgtCard {
            Text("LIVE LOG", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AgtColors.MatteBlack)
                    .padding(10.dp)
            ) {
                if (logs.isEmpty() || logs.all { it.isBlank() }) {
                    Text("No log lines yet.", color = AgtColors.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                } else {
                    LazyColumn(state = listState) {
                        items(logs.filter { it.isNotBlank() }) { line ->
                            Text(
                                line, color = AgtColors.TextPrimary, fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GhostButton(
            "Delete deployment",
            onClick = {
                runtime.deployEngine.delete(d.id)
                onBack()
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
