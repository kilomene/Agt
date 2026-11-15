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

/**
 * The tunnel leg: the same Cloudflare Tunnel and hostname the Termux
 * stack used, so https://termux.novamail.store keeps working. The
 * cloudflared binary ships as libcloudflared.so in jniLibs and is
 * extracted to the native library dir at install (legacy jniLibs
 * packaging in build.gradle.kts makes that a real file). The tunnel
 * token is baked into the build from the CI secret and passed to
 * cloudflared through the environment: zero setup on the phone.
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

    fun binary(): File = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")

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
        _state.value = State.STARTING
        _detail.value = "Starting tunnel to ${AgtConfig.TUNNEL_HOSTNAME}"
        scope.launch(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(bin.absolutePath, "tunnel", "run")
                pb.environment()["TUNNEL_TOKEN"] = token
                pb.redirectErrorStream(true)
                val proc = pb.start()
                process = proc
                val reader = Thread {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (line.contains("Registered tunnel connection")) {
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
                            _detail.value = "cloudflared exited (code ${proc.exitValue()}); restarting"
                            delay(5000)
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
