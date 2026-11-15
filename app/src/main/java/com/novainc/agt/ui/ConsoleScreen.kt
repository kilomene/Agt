package com.novainc.agt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.HeartbeatLog
import kotlinx.coroutines.delay

/**
 * The service console: the real heartbeat log written by AgtService every
 * 30 seconds, streamed into the screen every 2 seconds. What is shown here
 * is the service's own record, read from its file.
 */
@Composable
fun ConsoleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            lines = HeartbeatLog.readLines(context)
            delay(2000)
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineIcon(IconKind.Console, AgtColors.Gold, Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Service console", color = AgtColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Live heartbeat log from AgtService", color = AgtColors.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Back", color = AgtColors.GoldSoft) }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(AgtColors.Surface)
                .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            if (lines.isEmpty()) {
                Text(
                    "No heartbeats yet. Start the service on the Home screen and this console fills with its real heartbeat lines.",
                    color = AgtColors.TextDim, fontSize = 13.sp
                )
            } else {
                LazyColumn(state = listState) {
                    items(lines) { line ->
                        Text(
                            line,
                            color = AgtColors.TextPrimary,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
