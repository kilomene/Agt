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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.novainc.agt.core.AgtService
import com.novainc.agt.core.ConnectionState
import com.novainc.agt.core.NetworkMonitor
import com.novainc.agt.core.Telemetry
import com.novainc.agt.core.TelemetrySnapshot
import com.novainc.agt.ui.AgtColors
import com.novainc.agt.ui.ConsoleScreen
import com.novainc.agt.ui.IconKind
import com.novainc.agt.ui.LineIcon
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

private enum class Screen { Home, Console }

@Composable
private fun AgtApp() {
    var screen by remember { mutableStateOf(Screen.Home) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AgtColors.MatteBlack)
    ) {
        when (screen) {
            Screen.Home -> HomeScreen(onOpenConsole = { screen = Screen.Console })
            Screen.Console -> ConsoleScreen(onBack = { screen = Screen.Home })
        }
    }
}

@Composable
private fun HomeScreen(onOpenConsole: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val monitor = remember { NetworkMonitor(context) }
    LaunchedEffect(Unit) { monitor.start(scope) }
    val connection by monitor.reconnector.state.collectAsState()
    val serviceRunning by AgtService.running.collectAsState()
    val serviceBase by AgtService.serviceUptimeBaseMs.collectAsState()

    var tickMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var telemetry by remember { mutableStateOf<TelemetrySnapshot?>(null) }
    var batteryExempt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            tickMs = SystemClock.elapsedRealtime()
            telemetry = Telemetry.read(context)
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            batteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)
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
        // Header: identity block with the Nova N mark and live status pill.
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
        }

        Spacer(Modifier.height(20.dp))

        // Host uptime, ticking every second.
        Card {
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
        Text("CONNECTION LEGS", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LegCard(IconKind.Cloud, "Cloudflare Tunnel", "Public door: termux.novamail.store", online)
        Spacer(Modifier.height(10.dp))
        LegCard(IconKind.Bolt, "Firebase", "Doorbell and presence, real time", online)
        Spacer(Modifier.height(10.dp))
        LegCard(IconKind.Database, "Supabase", "Database of record", online)

        Spacer(Modifier.height(16.dp))
        Text("TELEMETRY", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

        // Resilience strip: the never-goes-dark law, shown live.
        Card {
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

        Spacer(Modifier.height(20.dp))

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
        Spacer(Modifier.height(8.dp))
        Text(
            "Phase 1 shell: the three legs land in Phase 3. Leg cards show the live network state honestly until then.",
            color = AgtColors.TextDim, fontSize = 11.sp
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun LegCard(icon: IconKind, name: String, role: String, online: Boolean) {
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
        }
        Text(
            if (online) "NET UP, PHASE 3" else "NO NETWORK",
            color = if (online) AgtColors.Amber else AgtColors.Danger,
            fontSize = 10.sp, fontWeight = FontWeight.Bold
        )
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
