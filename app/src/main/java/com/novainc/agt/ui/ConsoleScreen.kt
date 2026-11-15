package com.novainc.agt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.linux.Proot
import kotlinx.coroutines.delay

/**
 * The Linux console, as designed: real shell sessions inside the
 * Debian layer. One session for the host plus one per deployment
 * (started in the deployment's own directory, with its files bound
 * in), and a working key bar: ctrl, tab, esc, paste, and an extended
 * keys row with arrows. Nothing here is a mock: every key goes to a
 * real bash under PRoot.
 */
@Composable
fun ConsoleScreen() {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    val clipboard = LocalClipboardManager.current
    val linuxReady = runtime.proot.isInstalled()
    val deployments = remember { runtime.store.loadDeployments() }

    var extraHostSessions by remember { mutableStateOf(0) }
    val tabCount = 1 + deployments.size + extraHostSessions
    var activeTab by remember { mutableStateOf(0) }
    val sessions = remember { mutableMapOf<Int, Proot.ShellSession?>() }
    var output by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var ctrlArmed by remember { mutableStateOf(false) }
    var keysOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun tabLabel(i: Int): String = when {
        i == 0 -> "host"
        i <= deployments.size -> deployments[i - 1].name
        else -> "host ${i - deployments.size + 1}"
    }

    fun sessionFor(i: Int): Proot.ShellSession? {
        if (!linuxReady) return null
        if (sessions.containsKey(i)) return sessions[i]
        val s = try {
            when {
                i == 0 || i > deployments.size -> runtime.proot.startShell("/root")
                else -> {
                    val d = deployments[i - 1]
                    val hostDir = runtime.store.deploymentDir(d.id)
                    runtime.proot.startShell(
                        d.workdir.ifEmpty { "/root" },
                        binds = listOf(hostDir.absolutePath to d.workdir)
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
        sessions[i] = s
        return s
    }

    DisposableEffect(Unit) {
        onDispose { sessions.values.forEach { it?.close() } }
    }
    LaunchedEffect(activeTab, linuxReady) {
        while (true) {
            output = sessionFor(activeTab)?.snapshot() ?: ""
            delay(400)
        }
    }
    val lines = output.split("\n")
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    val session = if (linuxReady) sessions[activeTab] else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Linux Console", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Debian 12 layer · no root needed", color = AgtColors.TextDim, fontSize = 13.sp)
            }
            TintedPill(
                if (linuxReady) "VM READY" else "VM NOT READY",
                if (linuxReady) AgtColors.Gold else AgtColors.Amber,
                pulsing = linuxReady
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until tabCount) {
                val selected = i == activeTab
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) AgtColors.Gold.copy(alpha = 0.1f) else AgtColors.Surface)
                        .border(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) AgtColors.Gold else AgtColors.Stroke,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { activeTab = i }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(AgtColors.Emerald)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "Session ${i + 1} · ${tabLabel(i)}",
                        color = if (selected) AgtColors.Gold else AgtColors.TextDim,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AgtColors.Surface)
                    .border(1.dp, AgtColors.Stroke, RoundedCornerShape(12.dp))
                    .clickable {
                        extraHostSessions += 1
                        activeTab = tabCount
                    },
                contentAlignment = Alignment.Center
            ) {
                LineIcon(IconKind.Plus, AgtColors.TextDim, Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(AgtColors.MatteBlack)
                .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            if (!linuxReady) {
                Text(
                    "Linux sets itself up on first start. Once it is ready, this console opens a real shell inside Debian.",
                    color = AgtColors.TextDim, fontSize = 13.sp
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        LazyColumn(state = listState) {
                            items(lines) { line ->
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
                        Text("agt@host:~$ ", color = AgtColors.Gold, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                        BasicTextField(
                            value = input,
                            onValueChange = { new ->
                                if (ctrlArmed && new.length > input.length) {
                                    val c = new.last().uppercaseChar()
                                    if (c in 'A'..'Z') {
                                        sessionFor(activeTab)?.sendRaw(((c - 'A' + 1).toChar()).toString())
                                        ctrlArmed = false
                                    } else {
                                        input = new
                                    }
                                } else {
                                    input = new
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = AgtColors.TextPrimary, fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(AgtColors.Gold),
                            modifier = Modifier.weight(1f),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    sessionFor(activeTab)?.send(input)
                                    input = ""
                                }
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Run",
                            color = AgtColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                sessionFor(activeTab)?.send(input)
                                input = ""
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            KeyButton("ctrl", Modifier.weight(1f), active = ctrlArmed) { ctrlArmed = !ctrlArmed }
            KeyButton("tab", Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\t") }
            KeyButton("esc", Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\u001B") }
            KeyButton("paste", Modifier.weight(1f)) {
                clipboard.getText()?.text?.let { input += it }
            }
            KeyButton("keys", Modifier.weight(1f), active = keysOpen) { keysOpen = !keysOpen }
        }
        if (keysOpen) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                ArrowButton(IconKind.ChevronUp, Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\u001B[A") }
                ArrowButton(IconKind.ChevronDown, Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\u001B[B") }
                ArrowButton(IconKind.ChevronLeft, Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\u001B[D") }
                ArrowButton(IconKind.ChevronRight, Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\u001B[C") }
                KeyButton("home", Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\u001B[H") }
                KeyButton("end", Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\u001B[F") }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AgtColors.Surface)
            .border(
                if (active) 1.5.dp else 1.dp,
                if (active) AgtColors.Gold else AgtColors.Stroke,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) AgtColors.Gold else AgtColors.TextDim,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ArrowButton(icon: IconKind, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        LineIcon(icon, AgtColors.TextDim, Modifier.size(17.dp))
    }
}
