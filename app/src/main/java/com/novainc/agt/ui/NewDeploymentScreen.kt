package com.novainc.agt.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.deploy.DeploymentEngine
import kotlinx.coroutines.launch
import java.io.File

/**
 * New deployment, as designed: from source to running service in one
 * form. Git URL, upload bundle, or agent job; name, port, runtime,
 * start command, environment variables, and the two supervision
 * toggles (auto restart, battery guard), both wired to the supervisor.
 */
@Composable
fun NewDeploymentScreen(
    onCreated: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(0) }
    var gitUrl by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var runtimeLabel by remember { mutableStateOf("Node 18") }
    var runtimeOpen by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("npm start") }
    var envRows by remember { mutableStateOf(listOf("" to "")) }
    var autoRestart by remember { mutableStateOf(true) }
    var batteryGuard by remember { mutableStateOf(true) }
    var bundleName by remember { mutableStateOf("") }
    var bundleFile by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val bundlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                val out = File(context.cacheDir, "bundle-${System.currentTimeMillis()}.tar.gz")
                input?.use { ins -> out.outputStream().use { o -> ins.copyTo(o) } }
                bundleFile = out
                bundleName = uri.lastPathSegment ?: "bundle.tar.gz"
            } catch (e: Exception) {
                error = "Could not read bundle: ${e.message}"
            }
        }
    }

    val gitValid = gitUrl.isNotBlank() &&
        runCatching { DeploymentEngine.gitTarballUrl(gitUrl) }.isSuccess
    val runtimeOptions = listOf(
        "Node 18" to "npm start",
        "Python 3" to "python3 main.py",
        "Linux shell" to "bash start.sh"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("New Deployment", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Supervised from the first second", color = AgtColors.TextDim, fontSize = 13.sp)
            }
            CircleIconButton(IconKind.Close, onClick = onBack)
        }
        Spacer(Modifier.height(20.dp))
        SectionTitle("SOURCE")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceCard(Modifier.weight(1f), IconKind.Branch, "Git URL", source == 0) { source = 0 }
            SourceCard(Modifier.weight(1f), IconKind.Upload, "Upload bundle", source == 1) { source = 1 }
            SourceCard(Modifier.weight(1f), IconKind.Flame, "Agent job", source == 2) { source = 2 }
        }
        Spacer(Modifier.height(18.dp))
        when (source) {
            0 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        AgtField(gitUrl, { gitUrl = it }, "REPOSITORY URL", placeholder = "github.com/owner/repo")
                    }
                    if (gitValid) {
                        Spacer(Modifier.width(10.dp))
                        LineIcon(IconKind.Check, AgtColors.Emerald, Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "A public GitHub repo. The app fetches it as a tarball and extracts it into the deployment.",
                    color = AgtColors.TextDim, fontSize = 12.sp
                )
            }
            1 -> {
                GhostButton("Pick a bundle (.tar.gz)", onClick = { bundlePicker.launch("application/gzip") })
                if (bundleName.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Picked: $bundleName", color = AgtColors.TextPrimary, fontSize = 13.sp)
                }
            }
            else -> {
                AgtCard {
                    Row {
                        LineIcon(IconKind.Flame, AgtColors.Gold, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Phoenix deploys to this phone with signed agent jobs.",
                                color = AgtColors.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "They arrive on their own, verify against the baked Phoenix key, and start supervised. Nothing to fill in here.",
                                color = AgtColors.TextDim, fontSize = 12.5.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Job signing: Ed25519 · verified",
                                color = AgtColors.Gold, fontSize = 12.5.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        if (source != 2) {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgtField(name, { name = it }, "NAME", placeholder = "meme-bot", modifier = Modifier.weight(1f))
                AgtField(
                    portText, { portText = it.filter { c -> c.isDigit() } }, "PORT",
                    placeholder = "8080", modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("RUNTIME", color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AgtColors.SurfaceHigh)
                            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(12.dp))
                            .clickable { runtimeOpen = !runtimeOpen }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(runtimeLabel, color = AgtColors.TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        LineIcon(IconKind.ChevronDown, AgtColors.TextDim, Modifier.size(16.dp))
                    }
                    if (runtimeOpen) {
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AgtColors.Surface)
                                .border(1.dp, AgtColors.Stroke, RoundedCornerShape(12.dp))
                                .padding(vertical = 4.dp)
                        ) {
                            for ((label, cmd) in runtimeOptions) {
                                Text(
                                    label,
                                    color = if (label == runtimeLabel) AgtColors.Gold else AgtColors.TextPrimary,
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            runtimeLabel = label
                                            command = cmd
                                            runtimeOpen = false
                                        }
                                        .padding(horizontal = 14.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                }
                AgtField(command, { command = it }, "START COMMAND", placeholder = "npm start", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            SectionTitle("ENVIRONMENT VARIABLES")
            Spacer(Modifier.height(10.dp))
            envRows.forEachIndexed { i, (k, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgtField(
                        k, { new -> envRows = envRows.toMutableList().also { it[i] = new to v } },
                        "", placeholder = "BOT_TOKEN", modifier = Modifier.weight(1f)
                    )
                    AgtField(
                        v, { new -> envRows = envRows.toMutableList().also { it[i] = k to new } },
                        "", placeholder = "value", modifier = Modifier.weight(1f)
                    )
                    if (envRows.size > 1) {
                        CircleIconButton(IconKind.Close, onClick = {
                            envRows = envRows.toMutableList().also { it.removeAt(i) }
                        }, modifier = Modifier.size(36.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "+ Add variable",
                color = AgtColors.Gold, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { envRows = envRows + ("" to "") }
            )
            Spacer(Modifier.height(18.dp))
            AgtCard {
                ToggleRow("Auto restart on crash", autoRestart) { autoRestart = it }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .height(1.dp)
                        .background(AgtColors.Stroke)
                )
                ToggleRow("Battery guard for heavy work", batteryGuard) { batteryGuard = it }
            }
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = AgtColors.Danger, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            GoldIconButton(
                IconKind.Download,
                if (busy) "Deploying..." else "Deploy to this phone",
                onClick = {
                    if (name.isBlank()) { error = "Give the deployment a name."; return@GoldIconButton }
                    if (source == 0 && !gitValid) { error = "Paste a valid GitHub repo URL."; return@GoldIconButton }
                    if (source == 1 && bundleFile == null) { error = "Pick a bundle file first."; return@GoldIconButton }
                    val port = portText.toIntOrNull() ?: 0
                    val env = envRows.mapNotNull { (k, v) -> if (k.isBlank()) null else k to v }.toMap()
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            val deployment = when (source) {
                                0 -> runtime.deployEngine.createFromGit(
                                    gitUrl, name, command, port, env, autoRestart, batteryGuard
                                )
                                else -> runtime.deployEngine.createFromBundle(
                                    bundleFile!!, name, command, port, env, autoRestart, batteryGuard
                                )
                            }
                            runtime.supervisor.start(deployment)
                            onCreated(deployment.id)
                        } catch (e: Exception) {
                            error = e.message ?: "Deploy failed."
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Gets its own subdomain and secrets vault on deploy.",
                color = AgtColors.TextDim, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SourceCard(modifier: Modifier, icon: IconKind, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) AgtColors.Gold.copy(alpha = 0.08f) else AgtColors.Surface)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) AgtColors.Gold else AgtColors.Stroke,
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LineIcon(icon, if (selected) AgtColors.Gold else AgtColors.TextDim, Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = if (selected) AgtColors.TextPrimary else AgtColors.TextDim,
            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AgtColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AgtColors.Emerald,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = AgtColors.SurfaceHigh,
                uncheckedThumbColor = AgtColors.TextDim
            )
        )
    }
}
