package com.novainc.agt.legs

import android.content.Context
import com.novainc.agt.core.AgtConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress

/**
 * The tunnel leg: the same Cloudflare Tunnel and hostname the Termux
 * stack used, so https://termux.novamail.store keeps working. The
 * cloudflared binary ships as libcloudflared.so in jniLibs and is
 * extracted to the native library dir at install (legacy jniLibs
 * packaging in build.gradle.kts makes that a real file). The tunnel
 * token is baked into the build from the CI secret and passed to
 * cloudflared through the environment: zero setup on the phone.
 *
 * DNS: cloudflared's Go resolver, left to its defaults on Android,
 * asks [::1]:53 for the edge SRV records, and on the owner's phone
 * that stub answers SERVFAIL ("server misbehaving"), so the tunnel
 * never connected even with the internet up. --dns-resolver-addrs
 * (the v1.6.3 approach) fixes only the SRV stage; the region
 * hostnames the SRV records point at still resolve through the
 * same broken stub and die the same way. So cloudflared now runs
 * under PRoot, rooted at the host "/", with two files the leg
 * prepares at every start bound over the system ones: an
 * /etc/hosts pinning region1/region2 to IPs resolved here in
 * Kotlin (which resolves fine through Android netd), and an
 * /etc/resolv.conf carrying the active network's real DNS servers.
 * The mechanism is bench-proven end to end, including two clean
 * 10 minute soak phases on 2026-11-15; see TunnelPlan.
 */
class TunnelLeg(private val context: Context) {
    enum class State { OFF, STARTING, CONNECTED, ERROR, NOT_CONFIGURED }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    /** Last measured TCP connect time to the Cloudflare edge, in
     * milliseconds. Null until a real measurement lands. */
    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs

    private var process: Process? = null
    private var watcher: Job? = null

    /** The last cloudflared output lines, oldest first. Kept so a
     * failure is diagnosable in the tunnel's own words (and rides
     * telemetry to Phoenix), never just a bare exit code. The
     * buffer holds about 40 lines so a full startup sequence,
     * including cloudflared's own Settings line naming the
     * resolver addresses it was handed, survives to telemetry. */
    private val recentLines = ArrayDeque<String>()

    /** Restart delay after a cloudflared exit: starts at 5s, doubles
     * to a 60s cap, resets the moment a connection registers. A dead
     * tunnel must keep retrying forever without thrashing. */
    @Volatile
    private var restartBackoffMs = 5_000L

    fun recentOutput(): List<String> = synchronized(recentLines) { recentLines.toList() }

    fun binary(): File = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")

    fun prootBinary(): File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    /** Where the prepared resolver files live, and PRoot's scratch
     * dir. Both are created on access and never deleted: a missing
     * PROOT_TMP_DIR was one of the console errors in the field. */
    private val tunnelDir: File
        get() = File(context.filesDir, "tunnel").apply { mkdirs() }

    private val prootTmpDir: File
        get() = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    /** The active network's DNS servers as Android sees them. */
    private fun networkDnsServers(): List<InetAddress> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val props = cm.getLinkProperties(cm.activeNetwork)
            props?.dnsServers ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Resolver addresses for cloudflared's SRV stage, as
     * --dns-resolver-addrs values: the active network's DNS
     * servers, then 1.1.1.1 and 8.8.8.8. Never empty. */
    private fun dnsResolverAddrs(): List<String> {
        val addrs = LinkedHashSet<String>()
        for (addr in networkDnsServers()) {
            val host = addr.hostAddress ?: continue
            addrs.add(if (host.contains(':')) "[$host]:53" else "$host:53")
        }
        addrs.add("1.1.1.1:53")
        addrs.add("8.8.8.8:53")
        return addrs.toList()
    }

    /** IPv4 addresses for a hostname, resolved through Android
     * netd (the resolver that actually works on the phone). */
    private fun resolveIpv4(host: String): List<String> {
        return try {
            InetAddress.getAllByName(host)
                .mapNotNull { it.hostAddress }
                .filter { !it.contains(':') }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Write the hosts and resolv.conf files cloudflared will see
     * inside PRoot, and return them. Fresh resolution wins; when
     * resolution fails, the last good hosts set is reused; when
     * there is none, the built-in seed pins are used, so the tunnel
     * always starts with a complete hosts file. */
    private fun prepareResolverFiles(): Pair<File, File> {
        val dir = tunnelDir
        val hostsFile = File(dir, "hosts")
        val resolvFile = File(dir, "resolv.conf")
        val lastGood = File(dir, "hosts.last-good")
        val r1 = resolveIpv4(TunnelPlan.REGION1)
        val r2 = resolveIpv4(TunnelPlan.REGION2)
        when {
            r1.isNotEmpty() && r2.isNotEmpty() -> {
                val content = TunnelPlan.hostsContent(r1, r2)
                hostsFile.writeText(content)
                try { lastGood.writeText(content) } catch (e: Exception) { }
            }
            lastGood.exists() -> {
                try { hostsFile.writeText(lastGood.readText()) } catch (e: Exception) { }
            }
            else -> hostsFile.writeText(
                TunnelPlan.hostsContent(TunnelPlan.SEED_REGION1, TunnelPlan.SEED_REGION2)
            )
        }
        val dnsIps = networkDnsServers().mapNotNull { it.hostAddress }
        resolvFile.writeText(TunnelPlan.resolvContent(dnsIps))
        return hostsFile to resolvFile
    }

    fun start(scope: CoroutineScope, token: String) {
        stop()
        if (token.isBlank()) {
            _state.value = State.NOT_CONFIGURED
            _detail.value = "This build carries no tunnel token. CI bakes it from the repo secret."
            return
        }
        val bin = binary()
        if (!bin.exists()) {
            _state.value = State.ERROR
            _detail.value = "cloudflared binary missing from the app install"
            return
        }
        val prootBin = prootBinary()
        if (!prootBin.exists()) {
            _state.value = State.ERROR
            _detail.value = "proot binary missing from the app install"
            return
        }
        _state.value = State.STARTING
        _detail.value = "Starting tunnel to ${AgtConfig.TUNNEL_HOSTNAME}"
        scope.launch(Dispatchers.IO) {
            try {
                val (hostsFile, resolvFile) = prepareResolverFiles()
                val cmd = TunnelPlan.prootCommand(
                    prootBin.absolutePath,
                    hostsFile.absolutePath,
                    resolvFile.absolutePath,
                    bin.absolutePath,
                    dnsResolverAddrs()
                )
                val pb = ProcessBuilder(cmd)
                pb.environment()["TUNNEL_TOKEN"] = token
                pb.environment()["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
                pb.environment()["HOME"] = tunnelDir.absolutePath
                pb.redirectErrorStream(true)
                val proc = pb.start()
                process = proc
                val reader = Thread {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(recentLines) {
                            recentLines.addLast(line)
                            while (recentLines.size > 40) recentLines.removeFirst()
                        }
                        if (line.contains("Registered tunnel connection")) {
                            restartBackoffMs = 5_000L
                            _state.value = State.CONNECTED
                            _detail.value = "Connected as ${AgtConfig.TUNNEL_HOSTNAME}"
                        } else if (line.contains("error", ignoreCase = true) ||
                            line.contains("failed", ignoreCase = true)
                        ) {
                            _detail.value = line.take(200)
                        }
                    }
                }.apply { isDaemon = true; start() }
                watcher = scope.launch {
                    while (isActive) {
                        delay(5000)
                        if (proc.isAlive && _state.value == State.CONNECTED) {
                            // Real latency: time a TCP connect to the
                            // Cloudflare edge endpoint cloudflared uses.
                            try {
                                val t0 = System.currentTimeMillis()
                                java.net.Socket().use { s ->
                                    s.connect(java.net.InetSocketAddress("region1.v2.argotunnel.com", 7844), 3000)
                                }
                                _latencyMs.value = System.currentTimeMillis() - t0
                            } catch (e: Exception) {
                                // No measurement this cycle: keep the last real one.
                            }
                        }
                        if (!proc.isAlive) {
                            _state.value = State.ERROR
                            val lastLine = synchronized(recentLines) { recentLines.lastOrNull() } ?: ""
                            _detail.value = "cloudflared exited (code ${proc.exitValue()}); restarting" +
                                if (lastLine.isNotEmpty()) ". Last output: ${lastLine.take(140)}" else ""
                            val waitMs = restartBackoffMs
                            restartBackoffMs = (restartBackoffMs * 2).coerceAtMost(60_000L)
                            delay(waitMs)
                            if (isActive) start(scope, token)
                            break
                        }
                    }
                }
                reader.join()
            } catch (e: Exception) {
                _state.value = State.ERROR
                _detail.value = e.message ?: "tunnel failed to start"
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        try {
            process?.destroy()
            if (process?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) == false) {
                process?.destroyForcibly()
            }
        } catch (e: Exception) {
        }
        process = null
        if (_state.value != State.NOT_CONFIGURED) {
            _state.value = State.OFF
            _detail.value = ""
        }
    }
}
