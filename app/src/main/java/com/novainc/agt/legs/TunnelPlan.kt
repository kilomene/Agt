package com.novainc.agt.legs

/**
 * Pure builders for the tunnel's PRoot launch, separated from the
 * Android-facing leg so they can be unit tested without a device.
 *
 * Why PRoot at all: cloudflared's Go resolver, left to its defaults
 * on Android, asks [::1]:53 for the tunnel edge addresses and gets
 * SERVFAIL ("server misbehaving"), so the tunnel never connected
 * even with the internet up. --dns-resolver-addrs fixes only the
 * first stage (the SRV lookup); resolving the SRV targets
 * (region1/region2.v2.argotunnel.com) still uses the same broken
 * system resolver and dies the same way. Bench-proven on
 * 2026-11-15, twice over two 10 minute soak phases: run cloudflared
 * under PRoot rooted at the host "/" with an /etc/hosts that pins
 * the region hostnames to IPs the app resolved itself (Kotlin
 * resolves fine through Android netd) and an /etc/resolv.conf with
 * working nameservers, and every DNS stage passes.
 */
object TunnelPlan {
    const val REGION1 = "region1.v2.argotunnel.com"
    const val REGION2 = "region2.v2.argotunnel.com"

    /** Last-resort edge pins, used only when live resolution fails
     * and no earlier good set was persisted. The region addresses
     * are anycast inside these two /24s and change slowly; the set
     * below was read fresh from 1.1.1.1 on 2026-11-15. */
    val SEED_REGION1 = listOf("198.41.192.67", "198.41.192.77", "198.41.192.107")
    val SEED_REGION2 = listOf("198.41.200.193", "198.41.200.113", "198.41.200.63")

    /** /etc/hosts content for the PRoot view: localhost plus every
     * resolved IP pinned to its region hostname, so cloudflared's
     * stage-2 lookup is answered from the file and never reaches
     * the broken system resolver. */
    fun hostsContent(region1Ips: List<String>, region2Ips: List<String>): String {
        val sb = StringBuilder()
        sb.append("127.0.0.1 localhost\n")
        sb.append("::1 localhost ip6-localhost ip6-loopback\n")
        for (ip in region1Ips) sb.append(ip).append(' ').append(REGION1).append('\n')
        for (ip in region2Ips) sb.append(ip).append(' ').append(REGION2).append('\n')
        return sb.toString()
    }

    /** /etc/resolv.conf content for the PRoot view: the given DNS
     * servers (bare IPs, order preserved, duplicates dropped), then
     * 1.1.1.1 and 8.8.8.8 when not already present. This carries
     * the lookups the hosts file does not cover (the features and
     * API prechecks), which fail soft but noisy without it. */
    fun resolvContent(dnsServers: List<String>): String {
        val addrs = LinkedHashSet<String>()
        for (ip in dnsServers) {
            val clean = ip.trim().trim('[', ']')
            if (clean.isNotEmpty()) addrs.add(clean)
        }
        addrs.add("1.1.1.1")
        addrs.add("8.8.8.8")
        return addrs.joinToString("") { "nameserver $it\n" }
    }

    /** The full PRoot command that runs cloudflared on the host
     * root with the prepared hosts and resolv.conf bound over the
     * system ones. PRoot 5.3.0 rejects a "--" separator before the
     * command, so there is none. The --dns-resolver-addrs flags
     * stay: they cover the SRV stage, the binds cover the rest. */
    fun prootCommand(
        prootPath: String,
        hostsPath: String,
        resolvPath: String,
        cloudflaredPath: String,
        dnsResolverAddrs: List<String>
    ): List<String> {
        val cmd = mutableListOf(
            prootPath,
            "-R", "/",
            "-b", "$hostsPath:/etc/hosts",
            "-b", "$resolvPath:/etc/resolv.conf",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            cloudflaredPath,
            "tunnel", "run"
        )
        for (addr in dnsResolverAddrs) {
            cmd += "--dns-resolver-addrs"
            cmd += addr
        }
        return cmd
    }
}
