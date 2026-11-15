package com.novainc.agt

import com.novainc.agt.core.Backoff
import com.novainc.agt.core.ConnectionState
import com.novainc.agt.core.Reconnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectorTest {

    @Test
    fun backoffSequenceDoublesThenCaps() {
        val expected = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L)
        val actual = (1..8).map { Backoff.delayForAttempt(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun backoffNeverExceedsCap() {
        for (attempt in 1..500) {
            val d = Backoff.delayForAttempt(attempt)
            assertTrue("attempt $attempt delay $d exceeds cap", d <= Backoff.CAP_MS)
            assertTrue("attempt $attempt delay $d below base", d >= Backoff.BASE_MS)
        }
    }

    @Test
    fun reconnectorRetriesForeverAtCap() {
        val r = Reconnector()
        var last = 0L
        // 10,000 consecutive failures: the ladder must still answer with the
        // cap delay and keep counting attempts. It never terminates.
        repeat(10_000) { last = r.onRetryFailed() }
        assertEquals(Backoff.CAP_MS, last)
        assertEquals(10_000, r.currentAttempt())
        val state = r.state.value
        assertTrue(state is ConnectionState.Reconnecting)
        assertEquals(10_000, (state as ConnectionState.Reconnecting).attempt)
        assertEquals(Backoff.CAP_MS, state.nextDelayMs)
    }

    @Test
    fun reconnectorResetsOnConnect() {
        val r = Reconnector()
        r.onRetryFailed()
        r.onRetryFailed()
        r.onRetryFailed()
        assertEquals(3, r.currentAttempt())
        r.onConnected()
        assertEquals(0, r.currentAttempt())
        assertEquals(ConnectionState.Online, r.state.value)
        // After a reset the ladder starts again from the base delay.
        assertEquals(1000L, r.onRetryFailed())
    }

    @Test
    fun initialStateIsOffline() {
        val r = Reconnector()
        assertEquals(ConnectionState.Offline, r.state.value)
    }
}
