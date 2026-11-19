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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.agent.AgentTrust
import com.novainc.agt.core.AgentKey
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.core.AgtService
import com.novainc.agt.core.JobFeedEntry
import kotlinx.coroutines.delay

/**
 * The Agent screen, as designed: Phoenix, live. The channel is
 * outbound only, every job is signed and verified, the scopes say
 * exactly what Phoenix may do, and the job feed shows real signed
 * work as it lands, attributed to the agent that sent it. The
 * trusted agents card below the scopes lists every admitted agent:
 * Phoenix as the main agent, plus each agent the owner has let in,
 * each revocable from here except the main agent. The pairing code
 * and device key live in Settings; pairing is optional because the
 * Phoenix key is baked into the build.
 */
@Composable
fun AgentScreen(onOpenConsole: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var jobs by remember { mutableStateOf<List<JobFeedEntry>>(emptyList()) }
    var paired by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var agents by remember { mutableStateOf<List<AgentKey>>(emptyList()) }
    var pairCode by remember { mutableStateOf("") }
    var pairLabel by remember { mutableStateOf("") }
    var newAgentId by remember { mutableStateOf("") }
    var mintNote by remember { mutableStateOf("") }
    var vaultAgent by remember { mutableStateOf(AgentTrust.MAIN_AGENT_ID) }
    var vaultNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var secretName by remember { mutableStateOf("") }
    var secretValue by remember { mutableStateOf("") }
    var vaultNote by remember { mutableStateOf("") }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val serviceRunning by AgtService.running.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            jobs = runtime.store.loadJobs()
            val settings = runtime.store.loadSettings()
            paired = settings.phoenixPublicKey.isNotEmpty()
            agents = AgentTrust.agentsOf(settings)
            pairCode = AgentTrust.storedPairingCode(settings)
            pairLabel = AgentTrust.storedPairingAgentId(settings)
            vaultNames = runtime.vault.names(vaultAgent)
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val live = serviceRunning && runtime.supabase.configured
    val shownJobs = if (expanded) jobs.take(30) else jobs.take(3)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Agent", color = AgtColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Your connected agent", color = AgtColors.TextDim, fontSize = 13.sp)
            }
            CircleIconButton(IconKind.Gear, onClick = onSettings)
        }
        Spacer(Modifier.height(16.dp))
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(AgtColors.MatteBlack)
                        .border(1.5.dp, AgtColors.Gold, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    LineIcon(IconKind.Flame, AgtColors.Gold, Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Phoenix", color = AgtColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (live) AgtColors.Emerald else AgtColors.Amber)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (live) "LIVE" else "STANDBY",
                            color = if (live) AgtColors.Emerald else AgtColors.Amber,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        when {
                            !serviceRunning -> "Host service is off. Start it on the Host tab."
                            paired -> "Paired to this host · key verified"
                            else -> "Key baked into this build · verified on every job"
                        },
                        color = AgtColors.TextDim, fontSize = 12.5.sp
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            AgentRow("Channel", "Outbound realtime · Firebase + Supabase", AgtColors.TextPrimary)
            AgentDivider()
            AgentRow("Job signing", "Ed25519 · verified", AgtColors.Gold)
            AgentDivider()
            AgentRow("Inbound ports", "None open, ever", AgtColors.TextPrimary)
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PERMISSION SCOPES", color = AgtColors.TextDim, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                )
                Text("GRANTED BY YOU", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            ScopeRow("Deploy, restart and stop services")
            ScopeRow("Read logs and telemetry")
            ScopeRow("Run console commands")
            ScopeRow("Manage snapshots and backups")
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TRUSTED AGENTS", color = AgtColors.TextDim, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                )
                Text("VERIFIED BY KEY", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Phoenix is the main agent. Any other agent here was admitted by you or by " +
                    "Phoenix, signs with its own key, and every job it sends is stamped with " +
                    "its name. Revoking an agent ends its access at once; the main agent " +
                    "cannot be revoked.",
                color = AgtColors.TextDim, fontSize = 12.5.sp
            )
            Spacer(Modifier.height(6.dp))
            for (agent in agents) {
                TrustedAgentRow(agent, onRevoke = { runtime.revokeAgent(AgentTrust.storedAgentId(agent)) })
            }
            Spacer(Modifier.height(12.dp))
            AgentDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "ADMIT ANOTHER AGENT", color = AgtColors.TextDim, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            AgtField(
                value = newAgentId,
                onChange = { newAgentId = it },
                label = "AGENT ID (NO SPACES)",
                placeholder = "e.g. scout"
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                "Mint pairing code for this agent",
                onClick = {
                    val id = newAgentId.trim()
                    if (id.isNotEmpty() && id.none { it.isWhitespace() }) {
                        val code = runtime.mintAgentPairingCode(id)
                        mintNote = "Code $code admits $id. It replaces any code already showing."
                        newAgentId = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (mintNote.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(mintNote, color = AgtColors.GoldSoft, fontSize = 12.5.sp)
            }
            if (pairCode.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                AgentRow("Pairing code", pairCode, AgtColors.GoldSoft)
                AgentRow(
                    "Minted for",
                    if (pairLabel.isEmpty()) "Phoenix (main agent)" else pairLabel,
                    AgtColors.TextPrimary
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AGENT VAULTS", color = AgtColors.TextDim, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                )
                Text("WRITE-ONLY", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Every agent has its own vault, encrypted on this phone. A secret goes in " +
                    "and is never shown back: not here, not in a job result, not in a log. " +
                    "A deployment only ever receives secrets from its own owner's vault. " +
                    "You, holding this phone, are the only one who can wipe a vault.",
                color = AgtColors.TextDim, fontSize = 12.5.sp
            )
            Spacer(Modifier.height(10.dp))
            Row {
                for (agent in agents) {
                    val id = AgentTrust.storedAgentId(agent)
                    val selected = id == vaultAgent
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) AgtColors.SurfaceHigh else AgtColors.MatteBlack)
                            .border(
                                1.dp,
                                if (selected) AgtColors.Gold else AgtColors.Stroke,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { vaultAgent = id }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            id,
                            color = if (selected) AgtColors.GoldSoft else AgtColors.TextDim,
                            fontSize = 12.5.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            if (vaultNames.isEmpty()) {
                Text(
                    "No secrets in $vaultAgent's vault yet.",
                    color = AgtColors.TextDim, fontSize = 12.5.sp
                )
            }
            for (name in vaultNames) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LineIcon(IconKind.Key, AgtColors.Gold, Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        name, color = AgtColors.TextPrimary, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                    )
                    Text(
                        "DELETE",
                        color = AgtColors.Danger, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            runtime.vault.delete(vaultAgent, name)
                            vaultNames = runtime.vault.names(vaultAgent)
                            vaultNote = "$name removed from $vaultAgent's vault."
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AgentDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "ADD OR REPLACE A SECRET", color = AgtColors.TextDim, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            AgtField(
                value = secretName,
                onChange = { secretName = it },
                label = "SECRET NAME",
                placeholder = "e.g. telegram-bot-token"
            )
            Spacer(Modifier.height(10.dp))
            AgtField(
                value = secretValue,
                onChange = { secretValue = it },
                label = "SECRET VALUE (NEVER SHOWN AGAIN)",
                placeholder = "Paste the secret"
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                "Store in $vaultAgent's vault",
                onClick = {
                    val n = secretName.trim()
                    if (com.novainc.agt.vault.VaultNames.isValidName(n) && secretValue.isNotEmpty()) {
                        if (runtime.vault.set(vaultAgent, n, secretValue)) {
                            vaultNote = "$n stored in $vaultAgent's vault."
                            secretName = ""
                            secretValue = ""
                            vaultNames = runtime.vault.names(vaultAgent)
                        } else {
                            vaultNote = "That secret could not be stored."
                        }
                    } else {
                        vaultNote = "Name must be letters, digits, dot, dash or underscore, and the value must not be empty."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            GhostButton(
                "Wipe $vaultAgent's whole vault",
                onClick = {
                    runtime.vault.wipe(vaultAgent)
                    vaultNames = runtime.vault.names(vaultAgent)
                    vaultNote = "$vaultAgent's vault is wiped."
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (vaultNote.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(vaultNote, color = AgtColors.GoldSoft, fontSize = 12.5.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RECENT JOB ACTIVITY", color = AgtColors.TextDim, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                )
                if (jobs.size > 3) {
                    Text(
                        if (expanded) "COLLAPSE" else "FULL LOG",
                        color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (jobs.isEmpty()) {
                Text(
                    "No jobs yet. When Phoenix sends one, it appears here with its result.",
                    color = AgtColors.TextDim, fontSize = 12.5.sp
                )
            }
            for (job in shownJobs) {
                JobRow(job, nowMs)
            }
        }
        Spacer(Modifier.height(16.dp))
        GoldIconButton(
            IconKind.Console, "Open live console session",
            onClick = onOpenConsole,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AgentRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AgtColors.TextDim, fontSize = 13.5.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AgentDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AgtColors.Stroke)
    )
}

@Composable
private fun ScopeRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(IconKind.Check, AgtColors.Emerald, Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = AgtColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("GRANTED", color = AgtColors.Emerald, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TrustedAgentRow(agent: AgentKey, onRevoke: () -> Unit) {
    val agentId = AgentTrust.storedAgentId(agent)
    val isMain = AgentTrust.storedRole(agent) == AgentKey.ROLE_MAIN
    val keyPreview = AgentTrust.storedPublicKey(agent).take(16)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AgtColors.SurfaceHigh)
                .border(1.dp, AgtColors.Stroke, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            LineIcon(IconKind.Key, AgtColors.Gold, Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    agentId, color = AgtColors.TextPrimary,
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isMain) "MAIN" else "AGENT",
                    color = if (isMain) AgtColors.Gold else AgtColors.TextDim,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Ed25519 · $keyPreview...",
                color = AgtColors.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace
            )
        }
        if (!isMain) {
            Text(
                "REVOKE",
                color = AgtColors.Danger, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onRevoke() }
            )
        }
    }
}

@Composable
private fun JobRow(job: JobFeedEntry, nowMs: Long) {
    val icon = when (job.kind) {
        "deploy" -> IconKind.Download
        "restart" -> IconKind.Refresh
        "stop" -> IconKind.Square
        "command" -> IconKind.Console
        "logs" -> IconKind.Console
        "remove" -> IconKind.Close
        "pair" -> IconKind.Key
        "agent_add" -> IconKind.Key
        "agent_revoke" -> IconKind.Close
        "secret_set" -> IconKind.Key
        "secret_list" -> IconKind.Key
        "secret_delete" -> IconKind.Close
        "firebase_token" -> IconKind.Flame
        else -> IconKind.Bolt
    }
    val done = job.status == "done"
    val agent = AgentTrust.feedAgentOf(job)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AgtColors.SurfaceHigh)
                .border(1.dp, AgtColors.Stroke, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            LineIcon(icon, AgtColors.Gold, Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                job.summary.ifEmpty { job.kind },
                color = AgtColors.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                if (agent.isEmpty()) {
                    "Signed job · ${if (done) "completed" else "failed"}"
                } else {
                    "Signed job · by $agent · ${if (done) "completed" else "failed"}"
                },
                color = AgtColors.TextDim, fontSize = 12.sp
            )
        }
        Text(formatRelative(job.at, nowMs), color = AgtColors.TextDim, fontSize = 12.sp)
    }
}
