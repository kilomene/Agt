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
import androidx.compose.runtime.DisposableEffect
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
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.core.ProcessRecord
import kotlinx.coroutines.delay

/**
 * The Linux console: a live shell session inside the Debian rootfs plus
 * the process list. The shell is a real bash running under PRoot; every
 * line typed here runs in the guest, and the output streams back live.
 */
@Composable
fun ConsoleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    var output by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var processes by remember { mutableStateOf<List<ProcessRecord>>(emptyList()) }
    var shellReady by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val session = remember {
        if (runtime.proot.isInstalled()) {
            try {
                runtime.proot.startShell()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }

    LaunchedEffect(Unit) {
        shellReady = session?.alive == true
        while (true) {
            output = session?.snapshot() ?: ""
            processes = runtime.store.loadProcesses()
            shellReady = session?.alive == true
            delay(500)
        }
    }
    LaunchedEffect(output.length) {
        // Keep the tail visible as the shell prints.
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
                Text("Linux console", color = AgtColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (runtime.proot.isInstalled()) "Debian bookworm under PRoot" else "Linux not installed yet",
                    color = AgtColors.TextDim, fontSize = 12.sp
                )
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
            if (session == null) {
                Text(
                    "Install the Linux layer from the Home screen first, then this console opens a real shell inside Debian.",
                    color = AgtColors.TextDim, fontSize = 13.sp
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        LazyColumn(state = listState) {
                            items(output.split("\n")) { line ->
                                Text(
                                    line.ifEmpty { " " },
                                    color = AgtColors.TextPrimary,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$ ", color = AgtColors.Gold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(6.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = AgtColors.TextPrimary, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(AgtColors.Gold),
                            modifier = Modifier.weight(1f),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = {
                                    session.send(input)
                                    input = ""
                                }
                            ),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        GoldButton("Run", onClick = {
                            session.send(input)
                            input = ""
                        })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionTitle("PROCESSES")
        Spacer(Modifier.height(8.dp))
        if (processes.isEmpty()) {
            EmptyState("No processes yet. Start a deployment and it appears here.")
        }
        for (p in processes) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AgtColors.Surface)
                    .border(1.dp, AgtColors.Stroke, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (p.status == "running") AgtColors.Emerald else AgtColors.TextDim)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = AgtColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.command, color = AgtColors.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    if (p.restarts > 0) "restarted ${p.restarts}x" else p.status,
                    color = AgtColors.TextDim, fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
