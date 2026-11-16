package com.novainc.agt

import com.google.gson.JsonParser
import com.novainc.agt.agent.DeviceIdentity
import com.novainc.agt.agent.JobEngine
import com.novainc.agt.deploy.DeploymentEngine
import com.novainc.agt.deploy.Templates
import androidx.compose.ui.graphics.Color
import com.novainc.agt.linux.NativeLinux
import com.novainc.agt.ui.renderTerminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgtCompleteTest {

    @Test
    fun bootstrapSymlinksParse() {
        // The bootstrap zip carries its links as SYMLINKS.txt in
        // the Termux format (target, arrow U+2190, path relative
        // to the prefix); the extractor recreates them from this.
        val links = NativeLinux.parseSymlinks(
            "busybox←bin/ls\nbusybox←bin/cat\n\nlibncurses.so.6.4←lib/libncurses.so.6\n"
        )
        assertEquals(3, links.size)
        assertEquals("busybox" to "bin/ls", links[0])
        assertEquals("busybox" to "bin/cat", links[1])
        assertEquals("libncurses.so.6.4" to "lib/libncurses.so.6", links[2])
    }

    @Test
    fun bootstrapSymlinksSkipMalformedLines() {
        val links = NativeLinux.parseSymlinks("no arrow here\n←\n   \n")
        assertTrue(links.isEmpty())
    }

    @Test
    fun gitTarballUrlForms() {
        assertEquals(
            "https://codeload.github.com/kilomene/Agt/tar.gz/refs/heads/main",
            DeploymentEngine.gitTarballUrl("https://github.com/kilomene/Agt")
        )
        assertEquals(
            "https://codeload.github.com/kilomene/Agt/tar.gz/refs/heads/dev",
            DeploymentEngine.gitTarballUrl("https://github.com/kilomene/Agt/tree/dev")
        )
        assertEquals(
            "https://codeload.github.com/kilomene/Agt/tar.gz/refs/heads/main",
            DeploymentEngine.gitTarballUrl("https://github.com/kilomene/Agt.git")
        )
    }

    @Test
    fun canonicalJsonSortsKeysRecursively() {
        val el = JsonParser.parseString("""{"b":1,"a":{"d":4,"c":3},"arr":[{"z":1,"y":2}]}""")
        assertEquals(
            """{"a":{"c":3,"d":4},"arr":[{"y":2,"z":1}],"b":1}""",
            JobEngine.canonicalJson(el)
        )
    }

    @Test
    fun deviceIdentitySignsAndVerifies() {
        // Fixed seed, so this is a stable roundtrip through the raw
        // byte API (no JCA anywhere; see AgtIdentityTest for the
        // cross-implementation vectors and the Android provider
        // regression test).
        val seed = ByteArray(32) { it.toByte() }
        val payload = "hello agt".toByteArray(Charsets.UTF_8)
        val public = DeviceIdentity.derivePublicKey(seed)
        val sig = DeviceIdentity.sign(seed, payload)
        assertTrue(DeviceIdentity.verify(public, payload, sig))
        assertFalse(DeviceIdentity.verify(public, "tampered".toByteArray(Charsets.UTF_8), sig))
        // Both published key forms must decode to the same raw key:
        // the 44-byte SPKI form and the raw 32-byte form Phoenix sends.
        val spkiB64 = DeviceIdentity.encodePublicKeyBase64(public)
        assertTrue(public.contentEquals(DeviceIdentity.publicKeyBytesFromBase64(spkiB64)))
        val rawB64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(public)
        assertTrue(public.contentEquals(DeviceIdentity.publicKeyBytesFromBase64(rawB64)))
        // And an unpadded signature must decode.
        val sigB64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(sig)
        val decoded = DeviceIdentity.signatureFromBase64(sigB64)
        assertTrue(decoded.contentEquals(sig))
        assertTrue(DeviceIdentity.verify(public, payload, decoded!!))
    }

    @Test
    fun templatesAreRealPrograms() {
        assertEquals(7, Templates.all.size)
        for (t in Templates.all) {
            assertTrue("template ${t.id} needs a command", t.command.isNotBlank())
            assertTrue("template ${t.id} needs files", t.files.isNotEmpty())
            for ((name, content) in t.files) {
                assertTrue("template ${t.id} file $name is empty", content.isNotBlank())
            }
        }
        val web = Templates.byId("web-server")!!
        assertTrue(web.files["index.html"]!!.contains("AGT"))
        assertTrue(web.port == 8080)
        val miner = Templates.byId("light-miner")!!
        assertTrue(miner.files["miner.py"]!!.contains("sha256"))
        val telegram = Templates.byId("telegram-bot")!!
        assertTrue(telegram.env.containsKey("BOT_TOKEN"))
        val discord = Templates.byId("discord-bot")!!
        assertTrue(discord.files.containsKey("discord_ws.py"))
    }

    @Test
    fun terminalPlainTextPassesThroughUnstyled() {
        val out = renderTerminal("hello\nworld")
        assertEquals("hello\nworld", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun terminalSgrColourAppliesAndResets() {
        val out = renderTerminal("\u001B[31mred\u001B[0m plain")
        assertEquals("red plain", out.text)
        assertEquals(1, out.spanStyles.size)
        val span = out.spanStyles[0]
        assertEquals(0, span.start)
        assertEquals(3, span.end)
        assertEquals(Color(0xFFFF7B72), span.item.color)
    }

    @Test
    fun terminalBracketedPasteTogglesAreStripped() {
        // bash wraps its prompt in these on a pty; they must never
        // reach the screen as garbage.
        val out = renderTerminal("\u001B[?2004hX\u001B[?2004l")
        assertEquals("X", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun terminalOscSequenceIsStripped() {
        val out = renderTerminal("\u001B]0;window title\u0007X")
        assertEquals("X", out.text)
    }

    @Test
    fun terminalBackspaceOverwrites() {
        // The readline editing sequence: type echx, backspace, o.
        assertEquals("echo", renderTerminal("echx\bo").text)
    }

    @Test
    fun terminalCarriageReturnOverwritesLineStart() {
        assertEquals("XYc", renderTerminal("abc\rXY").text)
    }

    @Test
    fun terminalExtendedColoursResolve() {
        val cube = renderTerminal("\u001B[38;5;196mR")
        assertEquals(Color(0xFFFF0000), cube.spanStyles[0].item.color)
        val rgb = renderTerminal("\u001B[38;2;10;20;30mT")
        assertEquals(Color(10, 20, 30), rgb.spanStyles[0].item.color)
    }
}
