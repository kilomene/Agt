package com.novainc.agt

import com.novainc.agt.legs.FirebaseLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The Firebase leg reads the expiry inside the custom token itself,
 * so a dead key is recognised the moment it dies instead of failing
 * against RTDB forever. These tests pin that reading down. */
class FirebaseTokenExpiryTest {

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray(Charsets.UTF_8))
        val payload = enc.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$header.$payload.signature"
    }

    @Test
    fun readsExpiryFromPayload() {
        val token = jwt("""{"uid":"agt-test","exp":2000000000}""")
        assertEquals(2_000_000_000_000L, FirebaseLeg.tokenExpiryMs(token))
    }

    @Test
    fun readsPastExpiryToo() {
        val token = jwt("""{"uid":"agt-test","exp":1000000000}""")
        assertEquals(1_000_000_000_000L, FirebaseLeg.tokenExpiryMs(token))
    }

    @Test
    fun missingExpIsNull() {
        assertNull(FirebaseLeg.tokenExpiryMs(jwt("""{"uid":"agt-test"}""")))
    }

    @Test
    fun malformedTokensAreNull() {
        assertNull(FirebaseLeg.tokenExpiryMs(""))
        assertNull(FirebaseLeg.tokenExpiryMs("abc"))
        assertNull(FirebaseLeg.tokenExpiryMs("a.b"))
        assertNull(FirebaseLeg.tokenExpiryMs("a.!!!.c"))
        assertNull(FirebaseLeg.tokenExpiryMs("a.b.c.d"))
    }

    @Test
    fun provenKeyToleratesFirstFailures() {
        // A proven key riding out a network drop: the first two
        // failed cycles must not report ERROR, or the
        // firebaseKeySet flag flaps on every hiccup.
        assertFalse(FirebaseLeg.shouldReportError(failures = 1, hasProven = true))
        assertFalse(FirebaseLeg.shouldReportError(failures = 2, hasProven = true))
    }

    @Test
    fun provenKeyReportsAtThreshold() {
        assertTrue(FirebaseLeg.shouldReportError(failures = 3, hasProven = true))
        assertTrue(FirebaseLeg.shouldReportError(failures = 7, hasProven = true))
    }

    @Test
    fun unprovenKeyReportsImmediately() {
        // A key that never completed a cycle failing is a dead
        // key, not a hiccup: no tolerance.
        assertTrue(FirebaseLeg.shouldReportError(failures = 1, hasProven = false))
        assertTrue(FirebaseLeg.shouldReportError(failures = 2, hasProven = false))
    }
}
