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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.deploy.DeploymentEngine
import com.novainc.agt.deploy.Template
import com.novainc.agt.deploy.Templates
import kotlinx.coroutines.launch

/**
 * Templates. Tapping a template opens its configuration first:
 * name, port, start command and environment variables are shown
 * and editable before anything deploys, along with the public
 * address the deployment will get. Deploying with the defaults is
 * one more tap, but nothing deploys unseen anymore (the owner's
 * complaint: Deploy used to fire instantly with defaults, with no
 * chance to set a name, a port or a token).
 */
@Composable
fun TemplatesScreen(onDeployed: (String) -> Unit) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var configuring by remember { mutableStateOf<Template?>(null) }

    val cfg = configuring
    if (cfg != null) {
        TemplateConfigView(
            t = cfg,
            runtime = runtime,
            onBack = { configuring = null },
            onDeployed = onDeployed
        )
        return
    }

    val shown = Templates.ordered.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) ||
            it.blurb.contains(query, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Templates", color = AgtColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Configure, then deploy", color = AgtColors.TextDim, fontSize = 13.sp)
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
            AgtField(query, { query = it }, "SEARCH", placeholder = "Search templates")
        }
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AgtColors.Surface)
                .border(1.dp, AgtColors.Gold, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row {
                LineIcon(IconKind.Bolt, AgtColors.Gold, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Optimized for low power and low bandwidth.",
                        color = AgtColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Every template runs in the background, unrestricted, supervised by AGT.",
                        color = AgtColors.TextDim, fontSize = 13.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (shown.isEmpty()) {
            EmptyState("No template matches that search.")
        }
        for (t in shown) {
            TemplateRow(t, onDeploy = { configuring = t })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun TemplateRow(t: Template, onDeploy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
            .clickable { onDeploy() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AgtColors.SurfaceHigh)
                .border(1.dp, AgtColors.Stroke, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            LineIcon(t.icon, AgtColors.Gold, Modifier.size(23.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, color = AgtColors.TextPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Text(t.blurb, color = AgtColors.TextDim, fontSize = 12.sp)
        }
        Text(
            "Deploy",
            color = AgtColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
        LineIcon(IconKind.ChevronRight, AgtColors.Gold, Modifier.size(16.dp))
    }
}

/** The first free display name for a template: the template's own
 * name, then "Name 2", "Name 3", ... The configuration screen
 * starts from this suggestion; the owner can change it. */
private fun suggestName(base: String, existing: Set<String>): String {
    if (base !in existing) return base
    var n = 2
    while ("$base $n" in existing) n += 1
    return "$base $n"
}

/**
 * The template configuration step: every value that used to be an
 * invisible default is on this screen before the deploy fires.
 * The address preview is live: it shows the exact subdomain the
 * deployment will own, including the unique code that is added
 * when another deployment already holds the name's address.
 */
@Composable
private fun TemplateConfigView(
    t: Template,
    runtime: AgtRuntime,
    onBack: () -> Unit,
    onDeployed: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val existing = remember { runtime.store.loadDeployments() }
    val takenSlugs = remember { existing.map { DeploymentEngine.effectiveSlug(it) }.toSet() }
    var name by remember { mutableStateOf(suggestName(t.name, existing.map { it.name }.toSet())) }
    var portText by remember { mutableStateOf(if (t.port > 0) t.port.toString() else "") }
    var command by remember { mutableStateOf(t.command) }
    var envRows by remember {
        mutableStateOf(t.env.entries.map { it.key to it.value })
    }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val baseSlug = slugify(name)
    val addressText = when {
        baseSlug.isBlank() -> "Name the deployment to see its address."
        baseSlug in takenSlugs -> "$baseSlug-<unique code>-agt.novamail.store"
        else -> "$baseSlug-agt.novamail.store"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Configure", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(t.name + " template", color = AgtColors.TextDim, fontSize = 13.sp)
            }
            CircleIconButton(IconKind.Close, onClick = onBack)
        }
        Spacer(Modifier.height(16.dp))
        AgtCard {
            Row {
                LineIcon(t.icon, AgtColors.Gold, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        t.description,
                        color = AgtColors.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(t.blurb, color = AgtColors.TextDim, fontSize = 12.5.sp)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AgtField(name, { name = it }, "NAME", placeholder = "meme-bot", modifier = Modifier.weight(1f))
            AgtField(
                portText, { portText = it.filter { c -> c.isDigit() } }, "PORT",
                placeholder = "none", modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))
        AgtField(command, { command = it }, "START COMMAND", placeholder = t.command)
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
                CircleIconButton(IconKind.Close, onClick = {
                    envRows = envRows.toMutableList().also { it.removeAt(i) }
                }, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "+ Add variable",
            color = AgtColors.Gold, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { envRows = envRows + ("" to "") }
        )
        Spacer(Modifier.height(18.dp))
        SectionTitle("PUBLIC ADDRESS")
        Spacer(Modifier.height(8.dp))
        Text(
            addressText,
            color = AgtColors.Gold, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace
        )
        Text(
            "Every deployment owns its address alone. When the name's address is taken, a unique code is added so no two servers ever share one.",
            color = AgtColors.TextDim, fontSize = 12.sp
        )
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
                if (command.isBlank()) { error = "Give the deployment a start command."; return@GoldIconButton }
                val port = portText.toIntOrNull() ?: 0
                val env = envRows.mapNotNull { (k, v) -> if (k.isBlank()) null else k to v }.toMap()
                busy = true
                error = ""
                scope.launch {
                    try {
                        val deployment = runtime.deployEngine.createFromTemplate(
                            t, name.trim(), command, port, env
                        )
                        runtime.supervisor.start(deployment)
                        onDeployed(deployment.id)
                    } catch (e: Exception) {
                        error = "Deploy failed: ${e.message}"
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )
    }
}
