package com.novainc.agt

import com.novainc.agt.addons.AddonManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-on system's pure rules: manifest validation (a package
 * installs only when its addon.json passes), id and bin-entry
 * shapes (they name directories and files, so paths are refused),
 * and the registry round trip both install doors depend on.
 */
class AddonManifestTest {

    @Test
    fun validManifestParses() {
        val parsed = AddonManifest.parseManifest(
            """{"id":"chromium","name":"Chromium","version":"1.0.0",""" +
                """"description":"A browser","bin":["chromium","chromium-helper"]}"""
        )
        assertTrue(parsed.ok)
        val m = parsed.manifest!!
        assertEquals("chromium", m.id)
        assertEquals("Chromium", m.name)
        assertEquals("1.0.0", m.version)
        assertEquals("A browser", m.description)
        assertEquals(listOf("chromium", "chromium-helper"), m.bin)
    }

    @Test
    fun manifestWithoutBinParses() {
        val parsed = AddonManifest.parseManifest(
            """{"id":"data-pack","name":"Data Pack","version":"2"}"""
        )
        assertTrue(parsed.ok)
        assertEquals(emptyList<String>(), parsed.manifest!!.bin)
        assertEquals("", parsed.manifest!!.description)
    }

    @Test
    fun badIdsAreRefused() {
        for (id in listOf("", "Chromium", "a b", "../x", "-lead", "a/b", "a_b", "a".repeat(64))) {
            val parsed = AddonManifest.parseManifest(
                """{"id":"$id","name":"X","version":"1"}"""
            )
            assertFalse("id '$id' must be refused", parsed.ok)
        }
    }

    @Test
    fun goodIdsAreAccepted() {
        for (id in listOf("a", "chromium", "xfce4", "playwright-core", "a".repeat(63))) {
            assertTrue("id '$id' must be accepted", AddonManifest.isValidId(id))
        }
    }

    @Test
    fun missingNameOrVersionIsRefused() {
        assertFalse(AddonManifest.parseManifest("""{"id":"x","version":"1"}""").ok)
        assertFalse(AddonManifest.parseManifest("""{"id":"x","name":"X"}""").ok)
        assertFalse(AddonManifest.parseManifest("""{"id":"x","name":" ","version":"1"}""").ok)
    }

    @Test
    fun binEntriesMustBeBareFileNames() {
        for (bad in listOf("a/b", "..", ".", "", "a\\\\b")) {
            val parsed = AddonManifest.parseManifest(
                """{"id":"x","name":"X","version":"1","bin":["$bad"]}"""
            )
            assertFalse("bin entry '$bad' must be refused", parsed.ok)
        }
    }

    @Test
    fun duplicateBinEntriesCollapse() {
        val parsed = AddonManifest.parseManifest(
            """{"id":"x","name":"X","version":"1","bin":["x","x","y"]}"""
        )
        assertTrue(parsed.ok)
        assertEquals(listOf("x", "y"), parsed.manifest!!.bin)
    }

    @Test
    fun garbageManifestIsRefused() {
        assertFalse(AddonManifest.parseManifest("not json").ok)
        assertFalse(AddonManifest.parseManifest("").ok)
        assertNull(AddonManifest.parseManifest("[1,2]").manifest)
    }

    @Test
    fun registryRoundTrips() {
        val list = listOf(
            AddonManifest.Installed(
                AddonManifest.Manifest("chromium", "Chromium", "1.0.0", "A browser", listOf("chromium")),
                1700000000000L, "phoenix"
            ),
            AddonManifest.Installed(
                AddonManifest.Manifest("xfce", "XFCE", "4.18", "", emptyList()),
                1700000001000L, "console"
            )
        )
        val back = AddonManifest.parseRegistry(AddonManifest.registryToJson(list))
        assertEquals(list, back)
    }

    @Test
    fun damagedRegistryReadsEmpty() {
        assertEquals(emptyList<AddonManifest.Installed>(), AddonManifest.parseRegistry(""))
        assertEquals(emptyList<AddonManifest.Installed>(), AddonManifest.parseRegistry("garbage"))
        assertEquals(emptyList<AddonManifest.Installed>(), AddonManifest.parseRegistry("""{"x":1}"""))
    }

    @Test
    fun oneBadRecordDoesNotHideTheRest() {
        val text = """{"addons":[
            {"id":"BAD ID","name":"X","version":"1"},
            {"id":"good","name":"Good","version":"1","bin":[]}
        ]}"""
        val back = AddonManifest.parseRegistry(text)
        assertEquals(1, back.size)
        assertEquals("good", back[0].manifest.id)
    }
}
