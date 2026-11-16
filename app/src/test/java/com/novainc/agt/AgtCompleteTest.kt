package com.novainc.agt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.novainc.agt.agent.AgentTrust
import com.novainc.agt.agent.DeviceIdentity
import com.novainc.agt.agent.JobEngine
import com.novainc.agt.core.AgentKey
import com.novainc.agt.core.AgtSettings
import com.novainc.agt.core.JobFeedEntry
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

    // Multi-agent trust (AgentTrust): fixed seeds per agent, so
    // every signature below is a real Ed25519 signature over the
    // canonical payload, verified by the exact code JobEngine runs.

    private fun seedOf(fill: Int): ByteArray = ByteArray(32) { fill.toByte() }

    private fun pubOf(seed: ByteArray): String =
        DeviceIdentity.encodePublicKeyBase64(DeviceIdentity.derivePublicKey(seed))

    private fun signB64(seed: ByteArray, payload: JsonObject): String {
        val sig = DeviceIdentity.sign(seed, JobEngine.canonicalJson(payload).toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(sig)
    }

    private fun payloadOf(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun legacySettings(): AgtSettings =
        AgtSettings(phoenixPublicKey = pubOf(seedOf(7)))

    private fun settingsWithScout(): AgtSettings {
        val base = legacySettings()
        val change = AgentTrust.applyAgentAdd(
            base, AgentTrust.mainAgent(base), "scout", pubOf(seedOf(42))
        )
        assertTrue("main-signed agent_add must succeed", change.ok)
        return change.settings!!
    }

    @Test
    fun legacySettingsMigrateToMainEntry() {
        // A settings value with only the legacy key and no agents
        // list (what every build before multi-agent trust wrote)
        // gains exactly one entry: the main agent, id phoenix,
        // holding the legacy key, with the legacy field kept in sync.
        val legacy = legacySettings()
        assertTrue(AgentTrust.storedAgents(legacy).isEmpty())
        val migrated = AgentTrust.synced(legacy)
        val agents = AgentTrust.storedAgents(migrated)
        assertEquals(1, agents.size)
        assertEquals("phoenix", AgentTrust.storedAgentId(agents[0]))
        assertEquals(AgentKey.ROLE_MAIN, AgentTrust.storedRole(agents[0]))
        assertEquals(pubOf(seedOf(7)), AgentTrust.storedPublicKey(agents[0]))
        assertEquals(pubOf(seedOf(7)), AgentTrust.storedPhoenixKey(migrated))
        // The migration is stable: syncing the migrated value is a no-op.
        assertEquals(migrated, AgentTrust.synced(migrated))
        // Even before migration is persisted, the effective list
        // already reads the legacy key as the main entry.
        val effective = AgentTrust.agentsOf(legacy)
        assertEquals(1, effective.size)
        assertEquals("phoenix", AgentTrust.storedAgentId(effective[0]))
    }

    @Test
    fun mainSignedJobVerifies() {
        val settings = settingsWithScout()
        val payload = payloadOf("""{"command":"ls /root"}""")
        val signer = AgentTrust.verifySigner(settings, payload, signB64(seedOf(7), payload))
        assertTrue(signer != null)
        assertEquals("phoenix", AgentTrust.storedAgentId(signer!!))
        assertEquals(AgentKey.ROLE_MAIN, AgentTrust.storedRole(signer))
    }

    @Test
    fun agentSignedJobVerifies() {
        val settings = settingsWithScout()
        val payload = payloadOf("""{"command":"ls /root"}""")
        val signer = AgentTrust.verifySigner(settings, payload, signB64(seedOf(42), payload))
        assertTrue(signer != null)
        assertEquals("scout", AgentTrust.storedAgentId(signer!!))
        assertEquals(AgentKey.ROLE_AGENT, AgentTrust.storedRole(signer))
    }

    @Test
    fun untrustedKeyJobFails() {
        val settings = settingsWithScout()
        val payload = payloadOf("""{"command":"ls /root"}""")
        // A stranger's key is not on the trusted list: no signer.
        assertTrue(AgentTrust.verifySigner(settings, payload, signB64(seedOf(99), payload)) == null)
        // And a tampered payload fails under a trusted key too.
        val sig = signB64(seedOf(7), payload)
        val tampered = payloadOf("""{"command":"rm -rf /"}""")
        assertTrue(AgentTrust.verifySigner(settings, tampered, sig) == null)
    }

    @Test
    fun revokedAgentJobFails() {
        val settings = settingsWithScout()
        val payload = payloadOf("""{"command":"ls /root"}""")
        assertTrue(AgentTrust.verifySigner(settings, payload, signB64(seedOf(42), payload)) != null)
        val change = AgentTrust.revokeAgent(settings, "scout")
        assertTrue(change.ok)
        val after = change.settings!!
        assertTrue(AgentTrust.verifySigner(after, payload, signB64(seedOf(42), payload)) == null)
        // Revoking one agent never disturbs the main agent.
        assertTrue(AgentTrust.verifySigner(after, payload, signB64(seedOf(7), payload)) != null)
    }

    @Test
    fun agentSignedAgentAddFails() {
        val settings = settingsWithScout()
        val scout = AgentTrust.findAgent(settings, "scout")!!
        // An admitted agent cannot admit another agent, by job...
        val byJob = AgentTrust.applyAgentAdd(settings, scout, "rogue", pubOf(seedOf(99)))
        assertFalse(byJob.ok)
        assertTrue(byJob.error!!.contains("main agent"))
        // ...and cannot revoke anyone either.
        val revokeByJob = AgentTrust.applyAgentRevoke(settings, scout, "scout")
        assertFalse(revokeByJob.ok)
        // The main agent can do both.
        val main = AgentTrust.mainAgent(settings)
        assertTrue(AgentTrust.applyAgentAdd(settings, main, "rogue", pubOf(seedOf(99))).ok)
    }

    @Test
    fun attributionStampsResultAndFeed() {
        val settings = settingsWithScout()
        val payload = payloadOf("""{"command":"ls /root"}""")
        val signer = AgentTrust.verifySigner(settings, payload, signB64(seedOf(42), payload))
        // The result JSON carries the signing agent's id.
        val result = AgentTrust.stampAgent(JsonObject(), signer)
        assertEquals("scout", result.get("agent").asString)
        // The feed entry is built with the same id and reads it back.
        val entry = JobFeedEntry(
            id = "job-1", kind = "command", status = "done",
            summary = "exit 0", agent = AgentTrust.agentIdOf(signer)
        )
        assertEquals("scout", AgentTrust.feedAgentOf(entry))
        // A main-signed job attributes to phoenix, an unverified
        // job to nobody.
        val mainSigner = AgentTrust.verifySigner(settings, payload, signB64(seedOf(7), payload))
        assertEquals("phoenix", AgentTrust.agentIdOf(mainSigner))
        assertEquals("", AgentTrust.agentIdOf(null))
    }

    @Test
    fun revokeMainAgentRefused() {
        val settings = settingsWithScout()
        // The local door refuses...
        val local = AgentTrust.revokeAgent(settings, "phoenix")
        assertFalse(local.ok)
        assertTrue(local.error!!.contains("main agent"))
        // ...and so does the job door, even for the main signer.
        val byJob = AgentTrust.applyAgentRevoke(
            settings, AgentTrust.mainAgent(settings), "phoenix"
        )
        assertFalse(byJob.ok)
        // The main entry is still there and still verifies.
        val payload = payloadOf("""{"command":"ls"}""")
        assertTrue(AgentTrust.verifySigner(settings, payload, signB64(seedOf(7), payload)) != null)
    }

    @Test
    fun pairWithEmptyAgentIdReplacesMainKey() {
        val settings = settingsWithScout()
        val newMainSeed = seedOf(13)
        val paired = AgentTrust.pairAsMain(settings, pubOf(newMainSeed))
        // The main key is replaced in both views of it...
        assertEquals(pubOf(newMainSeed), AgentTrust.storedPhoenixKey(paired))
        assertEquals(pubOf(newMainSeed), AgentTrust.storedPublicKey(AgentTrust.mainAgent(paired)))
        // ...the admitted agent survives the re-pair...
        assertTrue(AgentTrust.findAgent(paired, "scout") != null)
        // ...and verification follows the new main key, not the old.
        val payload = payloadOf("""{"command":"ls"}""")
        assertTrue(AgentTrust.verifySigner(paired, payload, signB64(newMainSeed, payload)) != null)
        assertTrue(AgentTrust.verifySigner(paired, payload, signB64(seedOf(7), payload)) == null)
    }

    @Test
    fun labelledPairAddsAgentAndKeepsMain() {
        val settings = legacySettings()
        val change = AgentTrust.pairAsAgent(settings, "scout", pubOf(seedOf(42)))
        assertTrue(change.ok)
        val after = change.settings!!
        // Main is untouched, in both views.
        assertEquals(pubOf(seedOf(7)), AgentTrust.storedPhoenixKey(after))
        assertEquals(pubOf(seedOf(7)), AgentTrust.storedPublicKey(AgentTrust.mainAgent(after)))
        // The new agent verifies as itself.
        val payload = payloadOf("""{"command":"ls"}""")
        val signer = AgentTrust.verifySigner(after, payload, signB64(seedOf(42), payload))
        assertEquals("scout", AgentTrust.agentIdOf(signer))
        // A pair through the agent door can never name the main id.
        assertFalse(AgentTrust.pairAsAgent(settings, "phoenix", pubOf(seedOf(99))).ok)
        // And agent_add cannot shadow the main id either.
        assertFalse(
            AgentTrust.applyAgentAdd(
                settings, AgentTrust.mainAgent(settings), "phoenix", pubOf(seedOf(99))
            ).ok
        )
    }
}
