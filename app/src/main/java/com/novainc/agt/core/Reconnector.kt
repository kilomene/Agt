package com.novainc.agt.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connection state for the host link.
 *
 * The reconnection law for Agt: unlimited reconnection. There is no attempt
 * count at which the app gives up. Backoff grows to a cap and holds there,
 * retrying forever, so a phone that loses its network at 3am is linked again
 * the moment the network returns, with no human touch.
 */
sealed interface ConnectionState {
    data object Online : ConnectionState
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : ConnectionState
    data object Offline : ConnectionState
}

object Backoff {
    const val BASE_MS: Long = 1_000L
    const val CAP_MS: Long = 30_000L

    /**
     * Delay before retry number [attempt] (1-based):
     * 1s, 2s, 4s, 8s, 16s, then capped at 30s forever.
     */
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based" }
        var delay = BASE_MS
        var i = 1
        while (i < attempt) {
            delay = minOf(delay * 2, CAP_MS)
            if (delay >= CAP_MS) return CAP_MS
            i++
        }
        return delay
    }
}

class Reconnector {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Offline)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var attempt: Int = 0

    fun currentAttempt(): Int = attempt

    /** The link is up. Reset the backoff ladder and report Online. */
    fun onConnected() {
        attempt = 0
        _state.value = ConnectionState.Online
    }

    /**
     * The link dropped (or a retry failed). Advance the ladder and return how
     * long to wait before the next retry. This never terminates: after the
     * cap is reached it returns the cap on every subsequent call, forever.
     */
    fun onRetryFailed(): Long {
        attempt += 1
        val delay = Backoff.delayForAttempt(attempt)
        _state.value = ConnectionState.Reconnecting(attempt, delay)
        return delay
    }

    /** Link dropped with no retry scheduled yet (initial Offline). */
    fun onDisconnected() {
        if (_state.value !is ConnectionState.Reconnecting) {
            _state.value = ConnectionState.Offline
        }
    }
}
