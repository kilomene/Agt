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
import com.novainc.agt.ui.GoldButton
import com.novainc.agt.ui.AgtColors
import com.novainc.agt.ui.IconKind
import com.novainc.agt.ui.LineIcon
import com.novainc.agt.ui.NewDeploymentScreen
import com.novainc.agt.ui.SectionTitle
import com.novainc.agt.ui.SettingsScreen
import com.novainc.agt.ui.StatusPill
import com.novainc.agt.ui.TemplatesScreen
import com.novainc.agt.ui.formatGb
import com.novainc.agt.ui.formatHms
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
                is Dest.Home -> HomeScreen(onOpenConsole = { push(Dest.Console) }, onSettings = { push(Dest.Settings) })
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
        Triple(Dest.Home, "Home", IconKind.Cloud),
        Triple(Dest.Deployments, "Deploy", IconKind.Database),
        Triple(Dest.Console, "Console", IconKind.Console),
        Triple(Dest.Agent, "Agent", IconKind.Bolt)
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
                is Dest.Home -> current is Dest.Home
                is Dest.Deployments -> current is Dest.Deployments || current is Dest.Detail || current is Dest.New || current is Dest.Templates
                is Dest.Console -> current is Dest.Console
                is Dest.Agent -> current is Dest.Agent
                else -> false
            }
            Column(
                modifier = Modifier
                    .clickable { onTab(dest) }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
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
private fun HomeScreen(onOpenConsole: () -> Unit, onSettings: () -> Unit) {
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
    val firebaseState by runtime.firebase.state.collectAsState()
    val firebaseDetail by runtime.firebase.detail.collectAsState()
    val linuxReady by runtime.linuxReady.collectAsState()
    val installing by runtime.installing.collectAsState()
    val installProgress by runtime.installProgress.collectAsState()
    val installStatus by runtime.installStatus.collectAsState()

    var tickMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var telemetry by remember { mutableStateOf<TelemetrySnapshot?>(null) }
    var batteryExempt by remember { mutableStateOf(false) }
    var heartbeats by remember { mutableStateOf<List<String>>(emptyList()) }
    var deployments by remember { mutableStateOf(runtime.store.loadDeployments()) }
    LaunchedEffect(Unit) {
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

    val online = connection is ConnectionState.Online
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(1.5.dp, AgtColors.Gold, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("N", color = AgtColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Agt", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Nova Inc host", color = AgtColors.TextDim, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(AgtColors.Surface)
                    .border(1.dp, AgtColors.Stroke, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .scale(if (online) pulseScale else 1f)
                        .alpha(if (online) pulseAlpha else 1f)
                        .clip(CircleShape)
                        .background(if (online) AgtColors.Emerald else AgtColors.Danger)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (online) "ONLINE" else "OFFLINE",
                    color = if (online) AgtColors.Emerald else AgtColors.Danger,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Settings", color = AgtColors.GoldSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onSettings() }
            )
        }

        Spacer(Modifier.height(20.dp))

        AgtCard {
            Text("HOST UPTIME", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            val uptimeMs = if (serviceRunning && serviceBase > 0) tickMs - serviceBase
                else telemetry?.deviceUptimeMs ?: 0L
            Text(
                formatHms(uptimeMs),
                color = AgtColors.TextPrimary, fontSize = 38.sp, fontWeight = FontWeight.Bold
            )
            Text(
                if (serviceRunning) "Service running. Device uptime ${formatHms(telemetry?.deviceUptimeMs ?: 0L)}"
                else "Service stopped. Showing device uptime.",
                color = AgtColors.TextDim, fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("CONNECTION LEGS")
        Spacer(Modifier.height(8.dp))
        LegCard(
            IconKind.Cloud, "Cloudflare Tunnel", "Public door: termux.novamail.store",
            when (tunnelState) {
                TunnelLeg.State.CONNECTED -> "CONNECTED" to AgtColors.Emerald
                TunnelLeg.State.STARTING -> "STARTING" to AgtColors.Amber
                TunnelLeg.State.NOT_CONFIGURED -> "TOKEN NOT SET" to AgtColors.Amber
                TunnelLeg.State.ERROR -> "ERROR" to AgtColors.Danger
                TunnelLeg.State.OFF -> "OFF" to AgtColors.TextDim
            },
            tunnelDetail
        )
        Spacer(Modifier.height(10.dp))
        LegCard(
            IconKind.Bolt, "Firebase", "Doorbell and presence, real time",
            when (firebaseState) {
                FirebaseLeg.State.CONNECTED -> "CONNECTED" to AgtColors.Emerald
                FirebaseLeg.State.NOT_CONFIGURED -> "TOKEN PENDING" to AgtColors.Amber
                FirebaseLeg.State.ERROR -> "ERROR" to AgtColors.Danger
                FirebaseLeg.State.OFF -> "OFF" to AgtColors.TextDim
            },
            firebaseDetail
        )
        Spacer(Modifier.height(10.dp))
        LegCard(
            IconKind.Database, "Supabase", "Database of record",
            if (runtime.supabase.configured) "READY" to AgtColors.Emerald else "NOT CONFIGURED" to AgtColors.Amber,
            if (runtime.supabase.configured) "Jobs, hosts, deployments sync" else "Anon key not set in the build"
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle("LINUX LAYER")
        Spacer(Modifier.height(8.dp))
        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineIcon(IconKind.Console, AgtColors.Gold, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Debian bookworm", color = AgtColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (linuxReady) "python3, node 18, git, curl installed. ${deployments.size} deployment(s)."
                        else "Not installed yet. One download, about 100 MB.",
                        color = AgtColors.TextDim, fontSize = 12.sp
                    )
                }
                StatusPill(
                    if (linuxReady) "READY" else "NOT INSTALLED",
                    if (linuxReady) AgtColors.Emerald else AgtColors.Amber,
                    pulsing = linuxReady
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
                Spacer(Modifier.height(6.dp))
                Text(installStatus, color = AgtColors.TextDim, fontSize = 12.sp)
            } else if (!linuxReady) {
                Spacer(Modifier.height(12.dp))
                GoldButton("Install Linux", onClick = { runtime.installLinux() }, modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("TELEMETRY")
        Spacer(Modifier.height(8.dp))
        val t = telemetry
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryCard(
                Modifier.weight(1f), IconKind.Battery, "Battery",
                if (t != null) "${t.batteryPercent}%" else "...",
                if (t?.batteryCharging == true) "Charging" else "On battery",
                if (t != null) t.batteryPercent / 100f else 0f
            )
            TelemetryCard(
                Modifier.weight(1f), IconKind.Storage, "Storage free",
                if (t != null) "${formatGb(t.storageFreeGb)} GB" else "...",
                if (t != null) "of ${formatGb(t.storageTotalGb)} GB" else "",
                if (t != null && t.storageTotalGb > 0) (t.storageFreeGb / t.storageTotalGb).toFloat() else 0f
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryCard(
                Modifier.weight(1f), IconKind.Memory, "Memory free",
                if (t != null) "${formatGb(t.memoryAvailGb)} GB" else "...",
                if (t != null) "of ${formatGb(t.memoryTotalGb)} GB" else "",
                if (t != null && t.memoryTotalGb > 0) (t.memoryAvailGb / t.memoryTotalGb).toFloat() else 0f
            )
            TelemetryCard(
                Modifier.weight(1f), IconKind.Clock, "Device uptime",
                formatHms(t?.deviceUptimeMs ?: 0L),
                "Since last boot", null
            )
        }

        Spacer(Modifier.height(16.dp))

        AgtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineIcon(IconKind.Shield, AgtColors.Gold, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("RESILIENCE", color = AgtColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                "Background and foreground",
                if (serviceRunning) "Foreground service running" else "Service stopped, tap Start"
            )
            ResilienceRow("Runs while locked", "Screen lock changes nothing")
            ResilienceRow(
                "Battery exemption",
                if (batteryExempt) "Exempt, unrestricted" else "Not exempt yet, tap below"
            )
        }

        Spacer(Modifier.height(16.dp))
        if (heartbeats.isNotEmpty()) {
            AgtCard {
                Text("SERVICE LOG", color = AgtColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                for (line in heartbeats) {
                    Text(line, color = AgtColors.TextDim, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (serviceRunning) {
                Button(
                    onClick = { AgtService.stop(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AgtColors.SurfaceHigh, contentColor = AgtColors.TextPrimary
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("Stop service") }
            } else {
                Button(
                    onClick = { startService() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AgtColors.Gold, contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("Start service", fontWeight = FontWeight.Bold) }
            }
            OutlinedButton(
                onClick = onOpenConsole,
                modifier = Modifier.weight(1f)
            ) { Text("Open console", color = AgtColors.GoldSoft) }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { requestBatteryExemption(context) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Request battery exemption", color = AgtColors.TextPrimary) }
    }
}

@Composable
private fun LegCard(icon: IconKind, name: String, role: String, state: Pair<String, Color>, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(icon, AgtColors.Gold, Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = AgtColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(role, color = AgtColors.TextDim, fontSize = 12.sp)
            if (detail.isNotEmpty()) {
                Text(detail, color = AgtColors.TextDim, fontSize = 11.sp)
            }
        }
        Text(state.first, color = state.second, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TelemetryCard(
    modifier: Modifier, icon: IconKind, label: String,
    value: String, sub: String, fraction: Float?
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineIcon(icon, AgtColors.Gold, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(value, color = AgtColors.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) Text(sub, color = AgtColors.TextDim, fontSize = 11.sp)
        if (fraction != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(AgtColors.SurfaceHigh)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(AgtColors.Emerald)
                )
            }
        }
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
