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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.deploy.Template
import com.novainc.agt.deploy.Templates
import kotlinx.coroutines.launch

/**
 * Templates, as designed: one tap deploys. Tapping Deploy on a card
 * creates the deployment and starts it on the spot, then opens it.
 * Every template is a real program that runs in the Linux layer.
 */
@Composable
fun TemplatesScreen(onDeployed: (String) -> Unit) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deployingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }

    val shown = Templates.ordered.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) ||
            it.blurb.contains(query, ignoreCase = true)
    }

    fun deploy(t: Template) {
        if (deployingId != null) return
        deployingId = t.id
        error = ""
        scope.launch {
            try {
                val existing = runtime.store.loadDeployments().map { it.name }.toSet()
                var name = t.name
                var n = 2
                while (name in existing) { name = "${t.name} $n"; n += 1 }
                val deployment = runtime.deployEngine.createFromTemplate(t, name)
                runtime.supervisor.start(deployment)
                onDeployed(deployment.id)
            } catch (e: Exception) {
                error = "Deploy failed: ${e.message}"
                deployingId = null
            }
        }
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
                Text("One tap deploys", color = AgtColors.TextDim, fontSize = 13.sp)
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
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = AgtColors.Danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        if (shown.isEmpty()) {
            EmptyState("No template matches that search.")
        }
        for (t in shown) {
            TemplateRow(t, deploying = deployingId == t.id, onDeploy = { deploy(t) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun TemplateRow(t: Template, deploying: Boolean, onDeploy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
            .clickable(enabled = !deploying) { onDeploy() }
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
            if (deploying) "Deploying" else "Deploy",
            color = AgtColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
        LineIcon(IconKind.ChevronRight, AgtColors.Gold, Modifier.size(16.dp))
    }
}
