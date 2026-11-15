package com.novainc.agt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.core.JobFeedEntry
import kotlinx.coroutines.delay

/**
 * The Agent screen: Phoenix, live. The channel is outbound only, every
 * job is signed, and the scopes say exactly what Phoenix may do. The
 * pairing code is shown here until Phoenix pairs, then it is cleared.
 */
@Composable
fun AgentScreen() {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var jobs by remember { mutableStateOf<List<JobFeedEntry>>(emptyList()) }
    var settings by remember { mutableStateOf(runtime.store.loadSettings()) }
    var paired by remember { mutableStateOf(false) }
    var publicKey by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            jobs = runtime.store.loadJobs()
            settings = runtime.store.loadSettings()
            paired = settings.phoenixPublicKey.isNotEmpty()
            publicKey = try { runtime.identity.publicKeyBase64() } catch (e: Exception) { "" }
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
                Text("Agent", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Phoenix, connected to this phone", color = AgtColors.TextDim, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            StatusPill(
                if (paired) "PAIRED" else "KEY BAKED IN",
                AgtColors.Emerald,
                pulsing = true
            )
        }
        Spacer(Modifier.height(16.dp))
        AgtCard {
            Text("OUTBOUND CHANNEL", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Phoenix reaches this phone through Supabase, never the other way round. " +
                    "No inbound port, no SSH. Every job is signed with the Phoenix key and " +
                    "verified here before anything runs.",
                color = AgtColors.TextPrimary, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("Supabase: ${if (runtime.supabase.configured) "ready" else "not configured"}", color = AgtColors.TextDim, fontSize = 12.sp)
            Text("Device id: ${runtime.deviceId()}", color = AgtColors.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (!paired) {
            AgtCard {
                Text("PAIRING CODE (OPTIONAL)", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The Phoenix key is already baked into this build, so signed jobs verify " +
                        "from the first minute and nothing waits on pairing. Pairing is optional: " +
                        "it stores the same key on this phone and hands Phoenix this phone's " +
                        "public key in the reply. Tell Phoenix this code only if you want that link named.",
                    color = AgtColors.TextPrimary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    runtime.pairingCode(),
                    color = AgtColors.GoldSoft, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        AgtCard {
            Text("DEVICE KEY", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Ed25519 public key, this phone's identity:", color = AgtColors.TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(publicKey, color = AgtColors.TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Text("PERMISSION SCOPES", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ScopeRow("Run shell commands", "Inside the Linux layer only")
            ScopeRow("Deploy services", "Templates, git repos, bundles")
            ScopeRow("Restart and stop", "Any deployment on this phone")
            ScopeRow("Read logs", "Deployment logs and console output")
            ScopeRow("Remove deployments", "Deletes the deployment and its data")
            ScopeRow("No access", "Phone files, messages, or other apps")
        }
        Spacer(Modifier.height(12.dp))
        SectionTitle("JOB FEED")
        Spacer(Modifier.height(8.dp))
        if (jobs.isEmpty()) {
            EmptyState("No jobs yet. When Phoenix sends one, it appears here with its result.")
        }
        for (job in jobs) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (job.status == "done") "DONE" else "FAILED",
                    color = if (job.status == "done") AgtColors.Emerald else AgtColors.Danger,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(job.kind, color = AgtColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(job.summary, color = AgtColors.TextDim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ScopeRow(title: String, detail: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✓", color = AgtColors.Emerald, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = AgtColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = AgtColors.TextDim, fontSize = 12.sp)
        }
    }
}
