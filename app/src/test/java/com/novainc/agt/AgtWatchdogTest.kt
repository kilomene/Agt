package com.novainc.agt

import com.novainc.agt.core.WatchdogPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watchdog restarts a silently killed host, never overrides a
 * deliberate stop, and never double-starts a live service. */
class AgtWatchdogTest {

    @Test
    fun restartsWhenKilledSilently() {
        assertTrue(WatchdogPolicy.shouldRestart(userStopped = false, serviceRunning = false))
    }

    @Test
    fun respectsADeliberateStop() {
        assertFalse(WatchdogPolicy.shouldRestart(userStopped = true, serviceRunning = false))
    }

    @Test
    fun neverDoubleStarts() {
        assertFalse(WatchdogPolicy.shouldRestart(userStopped = false, serviceRunning = true))
        assertEquals(120_000L, WatchdogPolicy.INTERVAL_MS)
    }
}
