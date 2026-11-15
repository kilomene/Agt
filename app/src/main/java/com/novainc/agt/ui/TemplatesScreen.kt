package com.novainc.agt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.deploy.Templates

/**
 * Templates: the seven one-tap workloads. Deploying one creates the
 * deployment and starts it.
 */
@Composable
fun TemplatesScreen(onDeploy: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text("Templates", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "One tap to host a workload. Each template is a real program that runs in the Linux layer.",
            color = AgtColors.TextDim, fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))
        for (t in Templates.all) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AgtColors.Surface)
                    .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(t.icon, AgtColors.Gold, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = AgtColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(t.description, color = AgtColors.TextDim, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    GoldButton("Deploy", onClick = { onDeploy(t.id) })
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
