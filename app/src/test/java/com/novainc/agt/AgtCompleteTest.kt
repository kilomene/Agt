package com.novainc.agt

import com.google.gson.JsonParser
import com.novainc.agt.agent.DeviceIdentity
import com.novainc.agt.agent.JobEngine
import com.novainc.agt.deploy.DeploymentEngine
import com.novainc.agt.deploy.Templates
import com.novainc.agt.linux.Proot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgtCompleteTest {

    @Test
    fun prootCommandIsSandboxedAndBound() {
        val cmd = Proot.buildProotCommand(
            "/data/app/lib/libproot.so", "/data/data/com.novainc.agt/files/agt/linux/rootfs",
            "python3 -m http.server 8080", "/root/deployments/abc",
            listOf("/data/data/x" to "/root/deployments/abc")
        )
        assertEquals("/data/app/lib/libproot.so", cmd[0])
        assertTrue(cmd.contains("-R"))
        assertTrue(cmd.contains("-0"))
        assertTrue(cmd.contains("/bin/bash"))
        assertTrue(cmd.contains("python3 -m http.server 8080"))
        assertTrue(cmd.contains("/data/data/x:/root/deployments/abc"))
        // No host shell is ever involved: the guest command is one argument.
        assertEquals(cmd.last(), "python3 -m http.server 8080")
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
}
