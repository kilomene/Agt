package com.novainc.agt.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.deploy.Templates
import kotlinx.coroutines.launch
import java.io.File

/**
 * New deployment: three sources, one form. Template (picked from the
 * Templates screen or here), a git URL, or an uploaded bundle. The
 * deployment starts as soon as it is created.
 */
@Composable
fun NewDeploymentScreen(
    templateId: String?,
    onCreated: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(if (templateId != null) 0 else 1) }
    var selectedTemplate by remember { mutableStateOf(templateId?.let { Templates.byId(it) }) }
    var name by remember { mutableStateOf(selectedTemplate?.name ?: "") }
    var gitUrl by remember { mutableStateOf("") }
    var command by remember { mutableStateOf(selectedTemplate?.command ?: "python3 -m http.server 8080") }
    var portText by remember { mutableStateOf(selectedTemplate?.port?.takeIf { it > 0 }?.toString() ?: "") }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text("New deployment", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Pick a source, name it, and it starts running.", color = AgtColors.TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, label) in listOf("Template", "Git URL", "Upload bundle").withIndex()) {
                val selected = source == i
                if (selected) {
                    GoldButton(label, onClick = { source = i })
                } else {
                    GhostButton(label, onClick = { source = i })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        when (source) {
            0 -> {
                SectionTitle("TEMPLATE")
                Spacer(Modifier.height(8.dp))
                for (t in Templates.all) {
                    Row {
                        if (selectedTemplate?.id == t.id) {
                            GoldButton(t.name, onClick = {
                                selectedTemplate = t
                                name = t.name
                                command = t.command
                                portText = t.port.takeIf { it > 0 }?.toString() ?: ""
                            })
                        } else {
                            GhostButton(t.name, onClick = {
                                selectedTemplate = t
                                name = t.name
                                command = t.command
                                portText = t.port.takeIf { it > 0 }?.toString() ?: ""
                            })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            1 -> {
                AgtField(gitUrl, { gitUrl = it }, "Git URL", placeholder = "https://github.com/owner/repo")
                Spacer(Modifier.height(6.dp))
                Text(
                    "A public repo. The app fetches it as a tarball and extracts it into the deployment.",
                    color = AgtColors.TextDim, fontSize = 12.sp
                )
            }
            2 -> {
                GhostButton("Pick a bundle (.tar.gz)", onClick = { bundlePicker.launch("application/gzip") })
                if (bundleName.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Picked: $bundleName", color = AgtColors.TextPrimary, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AgtField(name, { name = it }, "Name", placeholder = "my-service")
        Spacer(Modifier.height(12.dp))
        AgtField(command, { command = it }, "Command", placeholder = "python3 -m http.server 8080")
        Spacer(Modifier.height(12.dp))
        AgtField(portText, { portText = it.filter { c -> c.isDigit() } }, "Port (0 for none)", placeholder = "8080")
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = AgtColors.Danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        GoldButton(
            if (busy) "Deploying..." else "Deploy and start",
            onClick = {
                if (name.isBlank()) { error = "Give the deployment a name."; return@GoldButton }
                val port = portText.toIntOrNull() ?: 0
                busy = true
                error = ""
                scope.launch {
                    try {
                        val deployment = when (source) {
                            0 -> {
                                val t = selectedTemplate ?: throw IllegalStateException("Pick a template.")
                                runtime.deployEngine.createFromTemplate(t, name)
                            }
                            1 -> {
                                if (gitUrl.isBlank()) throw IllegalStateException("Paste a git URL.")
                                runtime.deployEngine.createFromGit(gitUrl, name, command, port)
                            }
                            else -> {
                                val bundle = bundleFile ?: throw IllegalStateException("Pick a bundle file.")
                                runtime.deployEngine.createFromBundle(bundle, name, command, port)
                            }
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
        GhostButton("Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}
