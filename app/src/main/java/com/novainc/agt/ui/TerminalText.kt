package com.novainc.agt.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Render a raw terminal transcript the way a terminal would show
 * it, at exactly this level, no more:
 *
 * INTERPRETED: SGR sequences (ESC [ ... m). Standard and bright
 * foreground and background colours, the 256-colour cube and
 * greys, truecolour (38;2 and 48;2), bold, italic, underline,
 * and the resets (0, 22, 23, 24, 39, 49). Ordinary coloured
 * output (ls --color and friends) shows its colours.
 *
 * RESOLVED BY A LINE MODEL: carriage return returns the write
 * position to the line start and backspace steps it back one,
 * with later characters overwriting earlier ones, so readline
 * editing (the echx, backspace, echo sequence) and progress
 * lines render as the final line the shell settled on, not as
 * the raw byte pile.
 *
 * STRIPPED, NOT INTERPRETED: every other escape sequence. CSI
 * sequences for cursor movement, erasing and mode switches
 * (including the bracketed-paste toggles bash wraps its prompt
 * in), OSC sequences (window titles), charset selects and lone
 * escape pairs are consumed and dropped, and stray control
 * characters are dropped, so none of them can appear as garbage
 * in the transcript.
 *
 * NOT SERVED, stated plainly: cursor addressing. A full-screen
 * program (vim, top, less) redraws by moving the cursor to
 * absolute positions; those sequences are stripped above, so
 * such a program's output does not render as its screen here.
 * Serving it would take a real terminal emulator grid, which
 * this renderer is not and does not claim to be. Line-oriented
 * shells and interactive line programs (the console's actual
 * work) are served completely.
 */
private data class TermStyle(
    val fg: Color? = null,
    val bg: Color? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
) {
    fun isDefault(): Boolean =
        fg == null && bg == null && !bold && !italic && !underline

    fun toSpanStyle(): SpanStyle = SpanStyle(
        color = fg ?: Color.Unspecified,
        background = bg ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null
    )
}

/** A dark-terminal palette: the standard xterm colour positions,
 * at brightness values that stay readable on the app's matte
 * black (the pure xterm black would be invisible there). */
private val TERM_NORMAL = listOf(
    Color(0xFF484F58), Color(0xFFFF7B72), Color(0xFF3FB950), Color(0xFFD29922),
    Color(0xFF58A6FF), Color(0xFFBC8CFF), Color(0xFF39C5CF), Color(0xFFB1BAC4)
)
private val TERM_BRIGHT = listOf(
    Color(0xFF6E7681), Color(0xFFFFA198), Color(0xFF56D364), Color(0xFFE3B341),
    Color(0xFF79C0FF), Color(0xFFD2A8FF), Color(0xFF56D4DD), Color(0xFFF0F6FC)
)

/** The xterm 256-colour space: palette, then the 6 by 6 by 6
 * cube, then the greyscale ramp. */
private fun colour256(index: Int): Color {
    return when {
        index < 8 -> TERM_NORMAL[index]
        index < 16 -> TERM_BRIGHT[index - 8]
        index < 232 -> {
            val n = index - 16
            fun level(v: Int) = if (v == 0) 0 else 55 + 40 * v
            Color(level(n / 36), level((n / 6) % 6), level(n % 6))
        }
        else -> {
            val g = 8 + 10 * (index - 232)
            Color(g, g, g)
        }
    }
}

/** Apply one SGR parameter list to a style. Unknown parameters
 * are ignored, which is what a terminal does with the ones it
 * does not implement. */
private fun applySgr(style: TermStyle, params: List<Int>): TermStyle {
    var s = style
    val list = if (params.isEmpty()) listOf(0) else params
    var i = 0
    while (i < list.size) {
        val p = list[i]
        when {
            p == 0 -> s = TermStyle()
            p == 1 -> s = s.copy(bold = true)
            p == 3 -> s = s.copy(italic = true)
            p == 4 -> s = s.copy(underline = true)
            p == 22 -> s = s.copy(bold = false)
            p == 23 -> s = s.copy(italic = false)
            p == 24 -> s = s.copy(underline = false)
            p in 30..37 -> s = s.copy(fg = TERM_NORMAL[p - 30])
            p == 39 -> s = s.copy(fg = null)
            p in 40..47 -> s = s.copy(bg = TERM_NORMAL[p - 40])
            p == 49 -> s = s.copy(bg = null)
            p in 90..97 -> s = s.copy(fg = TERM_BRIGHT[p - 90])
            p in 100..107 -> s = s.copy(bg = TERM_BRIGHT[p - 100])
            p == 38 || p == 48 -> {
                val isFg = p == 38
                if (i + 1 < list.size && list[i + 1] == 5 && i + 2 < list.size) {
                    val c = colour256(list[i + 2])
                    s = if (isFg) s.copy(fg = c) else s.copy(bg = c)
                    i += 2
                } else if (i + 1 < list.size && list[i + 1] == 2 && i + 4 < list.size) {
                    val c = Color(list[i + 2], list[i + 3], list[i + 4])
                    s = if (isFg) s.copy(fg = c) else s.copy(bg = c)
                    i += 4
                }
            }
        }
        i++
    }
    return s
}

/** Convert a raw shell transcript into display text with its
 * SGR styling applied. Pure: no Android state, unit tested. */
fun renderTerminal(raw: String): AnnotatedString {
    val lines = mutableListOf<MutableList<Pair<Char, TermStyle>>>()
    var line = mutableListOf<Pair<Char, TermStyle>>()
    var cursor = 0
    var style = TermStyle()

    fun putChar(c: Char) {
        val cell = c to style
        if (cursor < line.size) line[cursor] = cell else line.add(cell)
        cursor++
    }

    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '\u001B' -> {
                if (i + 1 >= raw.length) {
                    i = raw.length
                } else when (raw[i + 1]) {
                    '[' -> {
                        // CSI: parameter bytes, intermediate
                        // bytes, one final byte. Only the SGR
                        // final (m) is interpreted; every other
                        // CSI is consumed and dropped.
                        var j = i + 2
                        val paramStart = j
                        while (j < raw.length && raw[j] in '\u0030'..'\u003F') j++
                        val paramStr = raw.substring(paramStart, j)
                        while (j < raw.length && raw[j] in '\u0020'..'\u002F') j++
                        if (j < raw.length && raw[j] in '\u0040'..'\u007E') {
                            if (raw[j] == 'm') {
                                val params = if (paramStr.isEmpty()) {
                                    emptyList()
                                } else {
                                    paramStr.split(';').map { it.toIntOrNull() ?: 0 }
                                }
                                style = applySgr(style, params)
                            }
                            j++
                        }
                        i = j
                    }
                    ']' -> {
                        // OSC: runs to a BEL or an ESC backslash
                        // terminator; dropped either way.
                        var j = i + 2
                        while (j < raw.length) {
                            if (raw[j] == '\u0007') {
                                j++
                                break
                            }
                            if (raw[j] == '\u001B' && j + 1 < raw.length && raw[j + 1] == '\\') {
                                j += 2
                                break
                            }
                            j++
                        }
                        i = j
                    }
                    '(', ')', '#', '%' -> i = minOf(i + 3, raw.length)
                    else -> i = minOf(i + 2, raw.length)
                }
            }
            c == '\n' -> {
                lines.add(line)
                line = mutableListOf()
                cursor = 0
                i++
            }
            c == '\r' -> {
                cursor = 0
                i++
            }
            c == '\u0008' -> {
                if (cursor > 0) cursor--
                i++
            }
            c == '\t' -> {
                do {
                    putChar(' ')
                } while (cursor % 8 != 0)
                i++
            }
            c < ' ' || c == '\u007F' -> i++
            else -> {
                putChar(c)
                i++
            }
        }
    }
    lines.add(line)

    return buildAnnotatedString {
        lines.forEachIndexed { li, cells ->
            if (li > 0) append('\n')
            var runStyle: TermStyle? = null
            val sb = StringBuilder()
            fun flush() {
                if (sb.isEmpty()) return
                val st = runStyle
                if (st == null || st.isDefault()) {
                    append(sb.toString())
                } else {
                    withStyle(st.toSpanStyle()) { append(sb.toString()) }
                }
                sb.clear()
            }
            for ((ch, st) in cells) {
                if (runStyle != st) {
                    flush()
                    runStyle = st
                }
                sb.append(ch)
            }
            flush()
        }
    }
}
