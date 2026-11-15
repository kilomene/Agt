package com.novainc.agt

import com.novainc.agt.core.WidgetStatus
import com.novainc.agt.legs.FirebaseLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wake lock support, widget status logic, and Firebase alert parsing. */
class AgtAlertsWidgetTest {

    @Test
    fun widgetStatusLineCoversAllShapes() {
        assertEquals("Online, no deployments yet", WidgetStatus.statusLine(true, 0, 0))
        assertEquals("Stopped, no deployments yet", WidgetStatus.statusLine(false, 0, 0))
        assertEquals("Online, 2 of 3 running", WidgetStatus.statusLine(true, 2, 3))
        assertEquals("Stopped, 0 of 1 running", WidgetStatus.statusLine(false, 0, 1))
    }

    @Test
    fun widgetDotChoosesTheRightLight() {
        assertEquals("dim", WidgetStatus.dotFor(false, 0, 0))
        assertEquals("dim", WidgetStatus.dotFor(false, 2, 2))
        assertEquals("danger", WidgetStatus.dotFor(true, 0, 2))
        assertEquals("emerald", WidgetStatus.dotFor(true, 1, 2))
        assertEquals("emerald", WidgetStatus.dotFor(true, 0, 0))
    }

    @Test
    fun firebaseAlertParsesARealNode() {
        val alert = FirebaseLeg.parseAlert(
            """{"at": 1730000000000, "title": "Server down", "message": "bot-1 exited"}"""
        )
        assertEquals(1730000000000L, alert?.at)
        assertEquals("Server down", alert?.title)
        assertEquals("bot-1 exited", alert?.message)
    }

    @Test
    fun firebaseAlertDefaultsAndRejectsBadInput() {
        val bare = FirebaseLeg.parseAlert("""{"at": 5}""")
        assertEquals("AGT alert", bare?.title)
        assertEquals("", bare?.message)
        assertNull(FirebaseLeg.parseAlert(""))
        assertNull(FirebaseLeg.parseAlert("null"))
        assertNull(FirebaseLeg.parseAlert("not json"))
        assertNull(FirebaseLeg.parseAlert("""{"title": "no timestamp"}"""))
    }
}
