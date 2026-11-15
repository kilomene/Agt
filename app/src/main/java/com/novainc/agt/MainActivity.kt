package com.novainc.agt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.core.AgtService
import com.novainc.agt.core.ConnectionState
import com.novainc.agt.core.HeartbeatLog
import com.novainc.agt.core.NetworkMonitor
import com.novainc.agt.core.Telemetry
import com.novainc.agt.core.TelemetrySnapshot
import com.novainc.agt.legs.FirebaseLeg
import com.novainc.agt.legs.TunnelLeg
import com.novainc.agt.ui.AgentScreen
import com.novainc.agt.ui.AgtCard
import com.novainc.agt.ui.ConsoleScreen
import com.novainc.agt.ui.DeploymentDetailScreen
import com.novainc.agt.ui.DeploymentsScreen
import com.novainc.agt.ui.GhostButton
import com.novainc.agt.ui.AgtColors
import com.novainc.agt.ui.IconKind
import com.novainc.agt.ui.LineIcon
import com.novainc.agt.ui.NewDeploymentScreen
import com.novainc.agt.ui.SettingsScreen
import com.novainc.agt.ui.StatusPill
import com.novainc.agt.ui.TemplatesScreen
import com.novainc.agt.ui.formatDuration
import com.novainc.agt.ui.formatGb
import com.novainc.agt.ui.formatLatency
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgtApp()
        }
    }
}

private sealed class Dest {
    data object Home : Dest()
    data object Deployments : Dest()
    data object Console : Dest()
    data object Agent : Dest()
    data class Detail(val id: String) : Dest()
    data class New(val templateId: String? = null) : Dest()
    data object Templates : Dest()
    data object Settings : Dest()
}

@Composable
private fun AgtApp() {
    var stack by remember { mutableStateOf<List<Dest>>(listOf(Dest.Home)) }
    val current = stack.last()
    fun push(d: Dest) { stack = stack + d }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun goTab(d: Dest) { stack = listOf(d) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AgtColors.MatteBlack)
    ) {
        Box(Modifier.weight(1f)) {
            when (current) {
                is Dest.Home -> HomeScreen(
                    onOpenConsole = { push(Dest.Console) },
                    onSettings = { push(Dest.Settings) },
                    onOpenDeploy = { goTab(Dest.Deployments) }
                )
                is Dest.Deployments -> DeploymentsScreen(
                    onOpenDetail = { push(Dest.Detail(it)) },
                    onNew = { push(Dest.New()) },
                    onTemplates = { push(Dest.Templates) }
                )
                is Dest.Console -> ConsoleScreen(onBack = ::pop)
                is Dest.Agent -> AgentScreen()
                is Dest.Detail -> DeploymentDetailScreen(current.id, onBack = ::pop)
                is Dest.New -> NewDeploymentScreen(
                    templateId = current.templateId,
                    onCreated = { id -> stack = listOf(Dest.Deployments, Dest.Detail(id)) },
                    onBack = ::pop
                )
                is Dest.Templates -> TemplatesScreen(onDeploy = { push(Dest.New(it)) })
                is Dest.Settings -> SettingsScreen(onBack = ::pop)
            }
        }
        BottomBar(current = current, onTab = ::goTab)
    }
}

@Composable
private fun BottomBar(current: Dest, onTab: (Dest) -> Unit) {
    val tabs = listOf(
        Triple(Dest.Home, "Host", IconKind.Home),
        Triple(Dest.Deployments, "Deploy", IconKind.Layers),
        Triple(Dest.Console, "Console", IconKind.Console),
        Triple(Dest.Agent, "Agent", IconKind.Flame),
        Triple(Dest.Templates, "Templates", IconKind.Grid)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for ((dest, label, icon) in tabs) {
            val selected = when (dest) {
                is Dest.Home -> current is Dest.Home || current is Dest.Settings
                is Dest.Deployments -> current is Dest.Deployments || current is Dest.Detail || current is Dest.New
                is Dest.Console -> current is Dest.Console
                is Dest.Agent -> current is Dest.Agent
                is Dest.Templates -> current is Dest.Templates
                else -> false
            }
            Column(
                modifier = Modifier
                    .clickable { onTab(dest) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LineIcon(icon, if (selected) AgtColors.Gold else AgtColors.TextDim, Modifier.size(22.dp))
                Text(
                    label,
                    color = if (selected) AgtColors.Gold else AgtColors.TextDim,
                    fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpenConsole: () -> Unit, onSettings: () -> Unit, onOpenDeploy: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = remember { AgtRuntime.get(context) }

    val monitor = remember { NetworkMonitor(context) }
    LaunchedEffect(Unit) { monitor.start(scope) }
    val connection by monitor.reconnector.state.collectAsState()
    val serviceRunning by AgtService.running.collectAsState()
    val serviceBase by AgtService.serviceUptimeBaseMs.collectAsState()
    val tunnelState by runtime.tunnel.state.collectAsState()
    val tunnelDetail by runtime.tunnel.detail.collectAsState()
    val tunnelLatency by runtime.tunnel.latencyMs.collectAsState()
    val firebaseState by runtime.firebase.state.collectAsState()
    val firebaseDetail by runtime.firebase.detail.collectAsState()
    val firebaseLatency by runtime.firebase.latencyMs.collectAsState()
    val supabaseLatency by runtime.supabase.latencyMs.collectAsState()
    val linuxReady by runtime.linuxReady.collectAsState()
    val installing by runtime.installing.collectAsState()
    val installProgress by runtime.installProgress.collectAsState()
    val installStatus by runtime.installStatus.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun startService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AgtService.start(context)
    }

    var tickMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var telemetry by remember { mutableStateOf<TelemetrySnapshot?>(null) }
    var batteryExempt by remember { mutableStateOf(false) }
    var heartbeats by remember { mutableStateOf<List<String>>(emptyList()) }
    var deployments by remember { mutableStateOf(runtime.store.loadDeployments()) }
    LaunchedEffect(Unit) {
        // The host starts itself: opening the app is enough. Linux
        // extraction and every leg come up inside the service with no
        // setup steps left for the owner.
        if (!AgtService.running.value) startService()
        while (true) {
            tickMs = SystemClock.elapsedRealtime()
            telemetry = Telemetry.read(context)
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            batteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)
            heartbeats = HeartbeatLog.readLines(context).takeLast(3)
            deployments = runtime.store.loadDeployments()
            delay(1000)
        }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.45f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseScale"
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    val uptimeMs = if (serviceRunning && serviceBase > 0) tickMs - serviceBase else 0L
    val runningCount = deployments.count { it.status == "running" }
    val tunnelLive = tunnelState == TunnelLeg.State.CONNECTED
    val firebaseLive = firebaseState == FirebaseLeg.State.CONNECTED
    val supabaseLive = runtime.supabase.configured
    val liveCount = listOf(tunnelLive, firebaseLive, supabaseLive).count { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp)
    ) {
        // Header: shield tile, AGT wordmark with the gold dot, bell.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.Black)
                    .border(1.5.dp, AgtColors.Gold, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(IconKind.Shield, AgtColors.Gold, Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("AGT", color = AgtColors.TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(".", color = AgtColors.Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AgtColors.Surface)
                    .border(1.dp, AgtColors.Stroke, CircleShape)
                    .clickable { onSettings() },
                contentAlignment = Alignment.Center
            ) {
                LineIcon(IconKind.Bell, AgtColors.TextDim, Modifier.size(21.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Banner: truthful host state from the service alone. Stopped
        // means OFFLINE and a zero uptime; device uptime never poses
        // as host uptime again.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AgtColors.Surface)
                .border(1.5.dp, AgtColors.Gold, RoundedCornerShape(20.dp))
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .scale(if (serviceRunning) pulseScale else 1f)
                        .alpha(if (serviceRunning) pulseAlpha else 1f)
                        .clip(CircleShape)
                        .background(if (serviceRunning) AgtColors.Emerald else AgtColors.Danger)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (serviceRunning) "HOST ONLINE" else "HOST OFFLINE",
                    color = AgtColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, AgtColors.Gold, RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        Build.MODEL.uppercase(),
                        color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Uptime", color = AgtColors.TextDim, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    formatDuration(uptimeMs),
                    color = AgtColors.Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(14.dp))
            if (serviceRunning) {
                Button(
                    onClick = { AgtService.stop(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AgtColors.SurfaceHigh, contentColor = AgtColors.TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Stop service") }
            } else {
                Button(
                    onClick = { startService() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AgtColors.Gold, contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start service", fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Resilience strip: three columns, real states.
        AgtCard {
            Row {
                ResilienceColumn(
                    Modifier.weight(1f), IconKind.Refresh, "Unlimited Reconnection",
                    when (connection) {
                        is ConnectionState.Online -> "Linked" to AgtColors.Emerald
                        is ConnectionState.Reconnecting -> "Searching" to AgtColors.Gold
                        is ConnectionState.Offline -> "Searching" to AgtColors.Gold
                    }
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(96.dp)
                        .background(AgtColors.Stroke)
                )
                ResilienceColumn(
                    Modifier.weight(1f), IconKind.Squares, "Background + Foreground",
                    if (serviceRunning) "Always on" to AgtColors.Gold else "Stopped" to AgtColors.TextDim
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(96.dp)
                        .background(AgtColors.Stroke)
                )
                ResilienceColumn(
                    Modifier.weight(1f), IconKind.Lock, "Runs While Locked",
                    "Nothing changes" to AgtColors.Emerald
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Connection legs, one headed card as designed.
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CONNECTION LEGS", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "$liveCount OF 3 LIVE",
                    color = if (liveCount == 3) AgtColors.Emerald else AgtColors.Amber,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            LegRow(
                IconKind.Cloud, "Cloudflare Tunnel", "Inbound reach, no open ports",
                legStateLabel(tunnelLive, tunnelState == TunnelLeg.State.STARTING, tunnelState == TunnelLeg.State.ERROR),
                formatLatency(tunnelLatency),
                if (!tunnelLive && tunnelDetail.isNotEmpty()) tunnelDetail else null
            )
            LegDivider()
            LegRow(
                IconKind.Flame, "Firebase", "Realtime doorbell for agent jobs",
                legStateLabel(firebaseLive, false, firebaseState == FirebaseLeg.State.ERROR),
                formatLatency(firebaseLatency),
                if (!firebaseLive && firebaseDetail.isNotEmpty()) firebaseDetail else null
            )
            LegDivider()
            LegRow(
                IconKind.Database, "Supabase", "Registry, jobs and history",
                legStateLabel(supabaseLive, false, false),
                formatLatency(supabaseLatency),
                if (!supabaseLive) "This build carries no anon key. CI bakes it from the repo secret." else null
            )
        }

        Spacer(Modifier.height(14.dp))

        // Telemetry tile grid, 3 by 2, all values read live.
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TELEMETRY", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "$runningCount DEPLOYMENTS RUNNING",
                    color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            val t = telemetry
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TelemetryTile(
                    Modifier.weight(1f), IconKind.Cpu,
                    if (t != null) "${t.cpuPercent.toInt()}%" else "...",
                    "CPU", AgtColors.Gold,
                    if (t != null) t.cpuPercent / 100f else 0f
                )
                TelemetryTile(
                    Modifier.weight(1f), IconKind.Memory,
                    if (t != null) "${formatGb(t.memoryUsedGb)} / ${t.memoryTotalGb.toInt()} GB" else "...",
                    "MEMORY", AgtColors.Gold,
                    if (t != null && t.memoryTotalGb > 0) (t.memoryUsedGb / t.memoryTotalGb).toFloat() else 0f
                )
                TelemetryTile(
                    Modifier.weight(1f), IconKind.Storage,
                    if (t != null) "${t.storageFreeGb.toInt()} GB free" else "...",
                    "STORAGE", AgtColors.Emerald,
                    if (t != null && t.storageTotalGb > 0) (1.0 - t.storageFreeGb / t.storageTotalGb).toFloat() else 0f
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TelemetryTile(
                    Modifier.weight(1f), IconKind.Battery,
                    if (t != null) "${t.batteryPercent}%${if (t.batteryCharging) " chg" else ""}" else "...",
                    "BATTERY", AgtColors.Emerald,
                    if (t != null) t.batteryPercent / 100f else 0f
                )
                TelemetryTile(
                    Modifier.weight(1f), IconKind.Thermometer,
                    if (t != null) "${formatGb(t.batteryTempC)} °C" else "...",
                    "TEMP", AgtColors.Emerald,
                    if (t != null) (t.batteryTempC / 50.0).toFloat() else 0f
                )
                TelemetryTile(
                    Modifier.weight(1f), IconKind.Signal,
                    if (t != null) "${formatGb(t.networkMbps)} Mbps" else "...",
                    if (t != null && t.networkLabel.isNotEmpty()) "NETWORK · ${t.networkLabel}" else "NETWORK",
                    AgtColors.Gold,
                    if (t != null) (t.networkMbps / 50.0).toFloat() else 0f
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Quick actions.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Modifier.weight(1f), IconKind.Plus, "Deploy") { onOpenDeploy() }
            QuickAction(Modifier.weight(1f), IconKind.Console, "Console") { onOpenConsole() }
            QuickAction(Modifier.weight(1f), IconKind.Download, "Snapshot") { runtime.syncNow() }
            QuickAction(Modifier.weight(1f), IconKind.Refresh, "Tunnel") { runtime.restartTunnel() }
        }

        Spacer(Modifier.height(14.dp))

        // Linux layer: part of the app, extracted automatically on
        // first start. No install button; a retry appears only if the
        // automatic install actually failed.
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AgtColors.SurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    LineIcon(IconKind.Console, AgtColors.Gold, Modifier.size(21.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Debian bookworm", color = AgtColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            linuxReady -> "Installed with the app. python3, node 18, git, curl."
                            installing -> installStatus.ifEmpty { "Setting up Linux" }
                            installStatus.startsWith("Install failed") -> installStatus
                            else -> "Setting up automatically on first start."
                        },
                        color = AgtColors.TextDim, fontSize = 12.sp
                    )
                }
                StatusPill(
                    when {
                        linuxReady -> "READY"
                        installing -> "SETTING UP"
                        installStatus.startsWith("Install failed") -> "FAILED"
                        else -> "QUEUED"
                    },
                    when {
                        linuxReady -> AgtColors.Emerald
                        installStatus.startsWith("Install failed") -> AgtColors.Danger
                        else -> AgtColors.Amber
                    },
                    pulsing = linuxReady || installing
                )
            }
            if (installing) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(AgtColors.SurfaceHigh)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(installProgress.coerceIn(0f, 1f))
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(AgtColors.Gold)
                    )
                }
            } else if (!linuxReady && installStatus.startsWith("Install failed")) {
                Spacer(Modifier.height(12.dp))
                GhostButton("Retry setup", onClick = { runtime.installLinux() }, modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(14.dp))

        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineIcon(IconKind.Shield, AgtColors.Gold, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("RESILIENCE DETAIL", color = AgtColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            ResilienceRow(
                "Unlimited reconnection",
                when (val c = connection) {
                    is ConnectionState.Online -> "Linked"
                    is ConnectionState.Reconnecting ->
                        "Reconnecting, attempt ${c.attempt}, next retry in ${c.nextDelayMs / 1000}s, never gives up"
                    is ConnectionState.Offline -> "Offline, retry loop armed forever"
                }
            )
            ResilienceRow(
                "Battery exemption",
                if (batteryExempt) "Exempt, unrestricted" else "Not exempt yet, tap below"
            )
            ResilienceRow(
                "Redmi autostart",
                "MIUI can block background starts, allow AGT in autostart below"
            )
            ResilienceRow(
                "Watchdog",
                "Alarm chain checks the host every 10 minutes and revives it"
            )
        }

        Spacer(Modifier.height(14.dp))
        if (heartbeats.isNotEmpty()) {
            AgtCard {
                Text("SERVICE LOG", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                for (line in heartbeats) {
                    Text(line, color = AgtColors.TextDim, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        OutlinedButton(
            onClick = { requestBatteryExemption(context) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Request battery exemption", color = AgtColors.TextPrimary) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { openMiuiAutostart(context) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Allow autostart (Redmi / MIUI)", color = AgtColors.TextPrimary) }
    }
}

/** The legs card state word: Live, Starting, Waiting, Error, or Off. */
private fun legStateLabel(live: Boolean, starting: Boolean, error: Boolean): Pair<String, Color> = when {
    live -> "Live" to AgtColors.Emerald
    error -> "Error" to AgtColors.Danger
    starting -> "Starting" to AgtColors.Amber
    else -> "Waiting" to AgtColors.Amber
}

@Composable
private fun LegDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .background(AgtColors.Stroke)
    )
}

@Composable
private fun LegRow(
    icon: IconKind, name: String, role: String,
    state: Pair<String, Color>, latency: String, detail: String?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AgtColors.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            LineIcon(icon, AgtColors.Gold, Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = AgtColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(role, color = AgtColors.TextDim, fontSize = 12.sp)
            if (detail != null) {
                Text(detail, color = AgtColors.TextDim, fontSize = 11.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(state.second)
            )
            Spacer(Modifier.width(6.dp))
            Text(state.first, color = state.second, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (latency.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(latency, color = AgtColors.TextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ResilienceColumn(
    modifier: Modifier, icon: IconKind, title: String, status: Pair<String, Color>
) {
    Column(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AgtColors.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            LineIcon(icon, AgtColors.Gold, Modifier.size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title, color = AgtColors.TextPrimary, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(status.second)
            )
            Spacer(Modifier.width(5.dp))
            Text(status.first, color = AgtColors.TextDim, fontSize = 11.5.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TelemetryTile(
    modifier: Modifier, icon: IconKind, value: String, label: String,
    barColor: Color, fraction: Float
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AgtColors.SurfaceHigh)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                value, color = AgtColors.TextPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1
            )
            LineIcon(icon, AgtColors.Gold, Modifier.size(17.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = AgtColors.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(AgtColors.Stroke)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun QuickAction(modifier: Modifier, icon: IconKind, label: String, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LineIcon(icon, AgtColors.Gold, Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = AgtColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResilienceRow(title: String, state: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(AgtColors.Emerald)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = AgtColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(state, color = AgtColors.TextDim, fontSize = 12.sp)
        }
    }
}

/**
 * MIUI hides background autostart behind its own security center, and
 * on a Redmi that switch matters more than any code: if MIUI blocks
 * the start, no receiver ever runs. Deep-link straight to the MIUI
 * autostart manager, falling back to the plain app settings page on
 * anything that is not MIUI.
 */
private fun openMiuiAutostart(context: Context) {
    val miui = Intent().apply {
        component = android.content.ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
    }
    try {
        context.startActivity(miui)
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.packageName)
                )
            )
        } catch (ignored: Exception) {
        }
    }
}

private fun requestBatteryExemption(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.packageName)
            )
        )
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
