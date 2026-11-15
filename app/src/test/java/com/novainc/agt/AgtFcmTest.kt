package com.novainc.agt

import com.novainc.agt.legs.FcmPayload
import org.junit.Assert.assertEquals
import org.junit.Test

/** FCM payload resolution: notification fields win, data fields back
 * them up, and a malformed push still lands as a readable alert. */
class AgtFcmTest {
    @Test
    fun notificationFieldsWin() {
        val (title, body) = FcmPayload.resolve(
            mapOf("title" to "Data title", "message" to "Data body"),
            "Push title", "Push body"
        )
        assertEquals("Push title", title)
        assertEquals("Push body", body)
    }

    @Test
    fun dataFieldsBackUp() {
        val (title, body) = FcmPayload.resolve(
            mapOf("title" to "Deployment down", "message" to "web-server exited"),
            null, null
        )
        assertEquals("Deployment down", title)
        assertEquals("web-server exited", body)
    }

    @Test
    fun bodyAliasWorks() {
        val (_, body) = FcmPayload.resolve(mapOf("body" to "alias body"), null, null)
        assertEquals("alias body", body)
    }

    @Test
    fun emptyPushGetsDefaults() {
        val (title, body) = FcmPayload.resolve(emptyMap(), null, null)
        assertEquals("AGT alert", title)
        assertEquals("", body)
    }

    @Test
    fun blankFieldsFallThrough() {
        val (title, _) = FcmPayload.resolve(mapOf("title" to "Real title"), "  ", null)
        assertEquals("Real title", title)
    }
}
