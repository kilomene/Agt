package com.novainc.agt

import com.novainc.agt.core.NotifyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notification catalogue: every kind lands on its own channel,
 * unknown kinds fall back to alerts, and only the heavy kinds are
 * high importance. */
class AgtNotifyKindTest {

    @Test
    fun eachKindOwnsItsChannel() {
        assertEquals("agt_critical", NotifyKind.channelFor("critical"))
        assertEquals("agt_alerts", NotifyKind.channelFor("alert"))
        assertEquals("agt_security", NotifyKind.channelFor("security"))
        assertEquals("agt_jobs", NotifyKind.channelFor("job"))
        assertEquals("agt_status", NotifyKind.channelFor("status"))
    }

    @Test
    fun unknownAndMissingKindsFallBackToAlerts() {
        assertEquals("agt_alerts", NotifyKind.channelFor(null))
        assertEquals("agt_alerts", NotifyKind.channelFor(""))
        assertEquals("agt_alerts", NotifyKind.channelFor("something-else"))
    }

    @Test
    fun kindMatchingIsCaseInsensitive() {
        assertEquals("agt_critical", NotifyKind.channelFor("CRITICAL"))
        assertEquals("agt_jobs", NotifyKind.channelFor("Job"))
    }

    @Test
    fun onlyHeavyKindsAreHighImportance() {
        assertTrue(NotifyKind.isHigh("critical"))
        assertTrue(NotifyKind.isHigh("alert"))
        assertTrue(NotifyKind.isHigh("security"))
        assertFalse(NotifyKind.isHigh("job"))
        assertFalse(NotifyKind.isHigh("status"))
        assertFalse(NotifyKind.isHigh(null))
    }
}
