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
 * cloudflared binary ships as libcloudflared.so in jniLibs. The tunnel
 * token is never baked in: it lives in Settings, entered once by the
 * owner, and is passed to cloudflared through the environment.
 */
class TunnelLeg(private val context: Context) {
    enum class State { OFF, STARTING, CONNECTED, ERROR, NOT_CONFIGURED }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    private var process: Process? = null
    private var watcher: Job? = null

    fun binary(): File = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")

    fun start(scope: CoroutineScope, token: String) {
        stop()
        if (token.isBlank()) {
            _state.value = State.NOT_CONFIGURED
            _detail.value = "Tunnel token not set. Add it in Settings."
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
