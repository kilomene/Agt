package com.novainc.agt

import com.novainc.agt.legs.TunnelPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v1.6.4 tunnel launch plan: the PRoot binds and file
 * contents that carry cloudflared past Android's broken [::1]:53
 * resolver stub. These builders are the exact code the leg runs;
 * the mechanism behind them was bench-proven end to end on
 * 2026-11-15 (two clean 10 minute soak phases). */
class TunnelPlanTest {

    @Test
    fun hostsPinsEveryResolvedIpToItsRegion() {
        val content = TunnelPlan.hostsContent(
            listOf("198.41.192.67", "198.41.192.77"),
            listOf("198.41.200.193")
        )
        assertTrue(content.contains("127.0.0.1 localhost"))
        assertTrue(content.contains("198.41.192.67 region1.v2.argotunnel.com"))
        assertTrue(content.contains("198.41.192.77 region1.v2.argotunnel.com"))
        assertTrue(content.contains("198.41.200.193 region2.v2.argotunnel.com"))
    }

    @Test
    fun hostsSeedsAreCompleteForBothRegions() {
        assertTrue(TunnelPlan.SEED_REGION1.isNotEmpty())
        assertTrue(TunnelPlan.SEED_REGION2.isNotEmpty())
        assertTrue(TunnelPlan.SEED_REGION1.all { it.startsWith("198.41.192.") })
        assertTrue(TunnelPlan.SEED_REGION2.all { it.startsWith("198.41.200.") })
    }

    @Test
    fun resolvKeepsOrderDropsDuplicatesAndAppendsFallbacks() {
        val content = TunnelPlan.resolvContent(
            listOf("192.168.1.1", "192.168.1.1", "10.0.0.2")
        )
        assertEquals(
            "nameserver 192.168.1.1\n" +
                "nameserver 10.0.0.2\n" +
                "nameserver 1.1.1.1\n" +
                "nameserver 8.8.8.8\n",
            content
        )
    }

    @Test
    fun resolvDoesNotDuplicateAFallbackAlreadyPresent() {
        val content = TunnelPlan.resolvContent(listOf("1.1.1.1"))
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", content)
    }

    @Test
    fun resolvWithNoNetworkServersStillHasTheFallbacks() {
        val content = TunnelPlan.resolvContent(emptyList())
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", content)
    }

    @Test
    fun prootCommandBindsHostsAndResolvOverTheSystemOnes() {
        val cmd = TunnelPlan.prootCommand(
            "/data/app/lib/libproot.so",
            "/data/data/com.novainc.agt/files/tunnel/hosts",
            "/data/data/com.novainc.agt/files/tunnel/resolv.conf",
            "/data/app/lib/libcloudflared.so",
            listOf("192.168.1.1:53", "1.1.1.1:53")
        )
        assertEquals("/data/app/lib/libproot.so", cmd[0])
        assertEquals(listOf("-R", "/"), cmd.subList(1, 3))
        assertTrue(cmd.contains("/data/data/com.novainc.agt/files/tunnel/hosts:/etc/hosts"))
        assertTrue(cmd.contains("/data/data/com.novainc.agt/files/tunnel/resolv.conf:/etc/resolv.conf"))
        // PRoot 5.3.0 rejects a "--" separator before the command.
        assertFalse(cmd.contains("--"))
        val cfIndex = cmd.indexOf("/data/app/lib/libcloudflared.so")
        assertEquals(listOf("tunnel", "run"), cmd.subList(cfIndex + 1, cfIndex + 3))
        assertEquals(
            listOf("--dns-resolver-addrs", "192.168.1.1:53", "--dns-resolver-addrs", "1.1.1.1:53"),
            cmd.subList(cfIndex + 3, cmd.size)
        )
    }
}
