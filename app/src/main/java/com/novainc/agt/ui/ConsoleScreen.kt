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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novainc.agt.core.AgtRuntime
import com.novainc.agt.linux.ShellSession
import kotlinx.coroutines.delay

/**
 * The Linux console, and it is a terminal: each tab is a real shell
 * session, a native process of the app running on a genuine
 * pseudo-terminal (see NativeLinux.startShell), so the shell echoes
 * typing itself, prints its own prompt, streams output live, and
 * takes control keys and interactive programs the way Termux does.
 * One session for the host plus one per deployment (started in the
 * deployment's own directory, where its files simply are), and a
 * working key bar: ctrl, tab, esc, paste, and an extended keys row
 * with arrows. Every session is kept by a keeper in the app: if
 * its shell ever exits, a fresh one starts immediately and the
 * tab continues on it, so a tab never presents a dead shell.
 * The sessions themselves are owned by the runtime (and so by
 * the foreground service), never by this screen: this screen
 * only attaches its view to the living shells and detaches when
 * it goes away. Opening, closing or leaving this tab starts,
 * stops and restarts nothing. Readiness here is the runtime's
 * answer-based verdict (a shell actually answered its probe),
 * never a file check.
 *
 * Input is keystroke-true, the way Termux bridges an editable
 * field to a terminal: the text field is a capture surface, not
 * a buffer. It is kept empty; every change the keyboard commits
 * is diffed and forwarded to the shell's terminal stream at
 * once (inserted characters as themselves, deletions as DEL),
 * hardware key events (Enter, Backspace, Tab, Escape, arrows,
 * Ctrl combinations) are forwarded as their terminal bytes, and
 * the field then resets to empty. Autocorrect and capitalization
 * are off so the keyboard commits characters instead of composing
 * words. Nothing the user types is ever displayed from the field:
 * what the screen shows is only the transcript the shell itself
 * produced through its pty, echo included. There is no Run
 * button and no send-a-line path; the line-buffered field this
 * replaces (type locally, tap Run, hope the shell was alive) is
 * designed out. The transcript is rendered through
 * [renderTerminal]: SGR colours interpreted, carriage-return
 * and backspace line editing resolved, every other escape
 * sequence stripped, so coloured output shows as colour and no
 * escape garbage reaches the screen.
 *
 * The terminal surface is always composed: transcript, input
 * field and key bar exist whether or not a shell has answered
 * yet. The branch this replaces composed them only when the
 * runtime's verdict was ready, so a guest that had not answered
 * left a screen whose terminal box held one status sentence
 * and nothing to type into: a dead box, in the owner's words
 * nowhere to type. Readiness is now a status line above the
 * terminal box (with the header pill), never a gate on the
 * surface: it says what is true (setting up, installed but the
 * shell has not answered yet, or the shell answered and through
 * which shape), and install state never appears inside the
 * terminal box as if it were shell output. Sessions are handed
 * out by the runtime registry the moment the screen asks, ready
 * or not: keys typed while a shell is still being raised go to
 * the session's stream, and the keeper's restart notes in the
 * transcript show what the shell is doing, so the screen is
 * never a dead box. Tapping anywhere on the terminal surface
 * focuses the input and raises the soft keyboard.
 */
@Composable
fun ConsoleScreen() {
    val context = LocalContext.current
    val runtime = remember { AgtRuntime.get(context) }
    val clipboard = LocalClipboardManager.current
    val linuxReady by runtime.linuxReady.collectAsState()
    val linuxDetail by runtime.linuxDetail.collectAsState()
    val installStatus by runtime.installStatus.collectAsState()
    val deployments = remember { runtime.store.loadDeployments() }

    var extraHostSessions by remember { mutableStateOf(0) }
    val tabCount = 1 + deployments.size + extraHostSessions
    var activeTab by remember { mutableStateOf(0) }
    var output by remember { mutableStateOf("") }
    // The input field's value, held empty by design: it exists
    // only to receive the keyboard's committed changes, each of
    // which is forwarded to the shell and cleared (see
    // onFieldChange). It is never a buffer and never displayed
    // as shell output.
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var keysOpen by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun tabLabel(i: Int): String = when {
        i == 0 -> "host"
        i <= deployments.size -> deployments[i - 1].name
        else -> "host ${i - deployments.size + 1}"
    }

    /** The living shell behind a tab, looked up in the runtime's
     * registry: this screen attaches to it and never owns it, so
     * this lookup creates the shell only the very first time any
     * tab asks, and afterwards always returns the same living
     * session. Keys: host tabs by position, deployment tabs by
     * deployment id, so a deployment tab reattaches to its own
     * shell even if the deployment list reorders. The bind's guest
     * path is the deployment's effective workdir (the old screen
     * code bound the raw stored workdir, which is empty for
     * deployments that never set one and produced a malformed
     * bind argument; the supervisor's own launches already used
     * the effective value). There is deliberately no readiness
     * gate on this lookup: the registry hands a session out the
     * moment the screen asks, ready or not, so the console always
     * has a living session stream to type into; while its shell
     * is still being raised, the keeper's notes in the transcript
     * say what it is doing. */
    fun sessionFor(i: Int): ShellSession? {
        return try {
            when {
                i == 0 || i > deployments.size ->
                    runtime.shellSession("host-$i", "/root")
                else -> {
                    val d = deployments[i - 1]
                    val hostDir = runtime.store.deploymentDir(d.id)
                    val workdir = d.workdir.ifEmpty { "/root" }
                    runtime.shellSession(
                        "dep-${d.id}",
                        workdir,
                        binds = listOf(hostDir.absolutePath to workdir)
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The control byte for a character typed while the ctrl key
     * is armed: the classic terminal mapping, letters to 1..26
     * and the punctuation controls around them. Null when the
     * character has no control form, in which case it is sent
     * as itself. */
    fun controlCodeFor(c: Char): Char? = when (c) {
        in 'A'..'Z' -> (c - 'A' + 1).toChar()
        in 'a'..'z' -> (c - 'a' + 1).toChar()
        '@' -> '\u0000'
        '[' -> '\u001B'
        '\\' -> '\u001C'
        ']' -> '\u001D'
        '^' -> '\u001E'
        '_' -> '\u001F'
        '?' -> '\u007F'
        else -> null
    }

    /** Forward text the keyboard committed to the active tab's
     * shell, at once and as-is. With ctrl armed, the first
     * character goes as its control byte instead (the key bar's
     * ctrl is a one-shot modifier, the way Termux's is) and the
     * rest follow raw. Restarting a dead shell mid-write is the
     * keeper's and the session's business, not the sender's:
     * writeRaw retries once against the fresh shell. */
    fun forwardText(text: String) {
        if (text.isEmpty()) return
        val session = sessionFor(activeTab) ?: return
        if (ctrlArmed) {
            val code = controlCodeFor(text[0])
            if (code != null) {
                session.sendRaw(code.toString())
                ctrlArmed = false
                if (text.length > 1) session.sendRaw(text.substring(1))
                return
            }
            ctrlArmed = false
        }
        session.sendRaw(text)
    }

    /** The field-to-terminal bridge. The field is kept empty, so
     * a change is either an insertion (characters the keyboard
     * committed: forward them) or a deletion against text the
     * field no longer holds (a backspace over already-forwarded
     * characters: forward DEL once per character, which is what
     * a terminal's backspace key sends). Either way the field
     * resets to empty and the shell's own echo, arriving through
     * the transcript, is the only thing ever displayed. */
    fun onFieldChange(new: TextFieldValue) {
        val oldText = fieldValue.text
        val newText = new.text
        if (newText != oldText) {
            var prefix = 0
            while (prefix < oldText.length && prefix < newText.length &&
                oldText[prefix] == newText[prefix]
            ) prefix++
            var suffix = 0
            while (suffix < oldText.length - prefix && suffix < newText.length - prefix &&
                oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
            ) suffix++
            val deleted = oldText.length - prefix - suffix
            if (deleted > 0) {
                sessionFor(activeTab)?.sendRaw("\u007F".repeat(deleted))
            }
            val inserted = newText.substring(prefix, newText.length - suffix)
            if (inserted.isNotEmpty()) forwardText(inserted)
        }
        fieldValue = TextFieldValue("")
    }

    // No dispose hook closes anything here, deliberately: leaving
    // this screen detaches the view only. The shells live in the
    // runtime and outlive every screen (the pipe-era screen owned
    // its sessions and closed them all on dispose, so navigating
    // away killed every shell; that coupling is removed).
    LaunchedEffect(activeTab) {
        while (true) {
            output = sessionFor(activeTab)?.snapshot() ?: ""
            delay(400)
        }
    }
    val rendered = remember(output) { renderTerminal(output) }
    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Linux Console", color = AgtColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Native Linux layer · no root needed", color = AgtColors.TextDim, fontSize = 13.sp)
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
        // Readiness is a status line, and it lives here, above
        // the terminal box, never inside it. Install and setup
        // state is app chrome; the branch this replaces put it
        // inside the box as the box's only content whenever the
        // shell had not answered, which removed the transcript,
        // the input and the keyboard from the screen entirely.
        // The line says exactly what is true and cannot
        // contradict the pill: setting up, installed but the
        // shell has not answered yet, or the shell answered
        // (linuxDetail names the shape it answered through).
        val statusLine = when {
            linuxReady -> linuxDetail
                .ifEmpty { "The shell answered its probe." }
                .replaceFirstChar { it.uppercase() }
            installStatus.startsWith("Install failed") ->
                "Linux setup hit a problem and is retrying on its own. The shell has not answered yet."
            installStatus.isNotEmpty() && installStatus != "Linux ready" ->
                "$installStatus The shell has not answered yet."
            else ->
                "Linux is installed; the shell has not answered yet. The app is working through its start shapes."
        }
        Text(statusLine, color = AgtColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(AgtColors.MatteBlack)
                .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
                .clickable {
                    // A tap anywhere on the terminal surface
                    // focuses the input and raises the keyboard:
                    // the surface is where typing goes, so touching
                    // it is how the owner says so.
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
                .padding(14.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .clickable {
                                // Taps on the transcript itself
                                // focus the input too (the box's own
                                // clickable covers the rest of the
                                // surface; a scrollable child can
                                // claim the tap first).
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                    ) {
                        Text(
                            rendered,
                            color = AgtColors.TextPrimary,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // No drawn prompt here: the prompt belongs to
                    // the shell and appears in the transcript
                    // above, printed by bash itself on its pty. A
                    // static "agt@host" label would be the app
                    // pretending to be the shell. The field below
                    // draws no text of its own either: it is a
                    // capture surface for the keyboard, kept
                    // empty, and the hint under it is app
                    // chrome, not shell output.
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { onFieldChange(it) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = AgtColors.TextPrimary, fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(AgtColors.Gold),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                // Hardware and external keyboards:
                                // the keys a terminal lives on are
                                // forwarded as their terminal bytes
                                // and consumed, so they never also
                                // land in the field as text.
                                if (event.type != KeyEventType.KeyDown) {
                                    false
                                } else {
                                    val session = sessionFor(activeTab)
                                    val bytes: String? = when {
                                        event.isCtrlPressed &&
                                            event.key.keyCode in Key.A.keyCode..Key.Z.keyCode ->
                                            ((event.key.keyCode - Key.A.keyCode + 1)
                                                .toChar()).toString()
                                        event.key == Key.Enter ||
                                            event.key == Key.NumPadEnter -> "\r"
                                        event.key == Key.Backspace -> "\u007F"
                                        event.key == Key.Tab -> "\t"
                                        event.key == Key.Escape -> "\u001B"
                                        event.key == Key.DirectionUp -> "\u001B[A"
                                        event.key == Key.DirectionDown -> "\u001B[B"
                                        event.key == Key.DirectionLeft -> "\u001B[D"
                                        event.key == Key.DirectionRight -> "\u001B[C"
                                        else -> null
                                    }
                                    if (bytes != null && session != null) {
                                        session.sendRaw(bytes)
                                        true
                                    } else {
                                        bytes != null
                                    }
                                }
                            },
                        keyboardActions = KeyboardActions(
                            // Soft keyboards whose Enter is an
                            // action key rather than a committed
                            // character: Done is the shell's
                            // Enter, a bare carriage return.
                            onDone = { sessionFor(activeTab)?.sendRaw("\r") }
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        ),
                        decorationBox = { innerTextField ->
                            Box {
                                if (fieldValue.text.isEmpty()) {
                                    Text(
                                        "tap and type: keystrokes go straight to the shell",
                                        color = AgtColors.TextDim,
                                        fontSize = 12.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            KeyButton("ctrl", Modifier.weight(1f), active = ctrlArmed) { ctrlArmed = !ctrlArmed }
            KeyButton("tab", Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\t") }
            KeyButton("esc", Modifier.weight(1f)) { sessionFor(activeTab)?.sendRaw("\u001B") }
            KeyButton("paste", Modifier.weight(1f)) {
                // Pasted text goes straight to the shell's
                // terminal, raw: the shell's own bracketed-paste
                // handling decides what it means there. It is
                // never staged in a local buffer first.
                clipboard.getText()?.text?.let { text ->
                    sessionFor(activeTab)?.sendRaw(text)
                }
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
