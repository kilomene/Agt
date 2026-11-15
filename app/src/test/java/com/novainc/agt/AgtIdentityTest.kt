package com.novainc.agt

import com.novainc.agt.agent.DeviceIdentity
import com.novainc.agt.core.AgtConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Provider
import java.security.Security

/**
 * DeviceIdentity conformance and regression tests.
 *
 * The vectors were produced by an independent Ed25519 implementation
 * (Python's cryptography library) and are pinned byte for byte:
 * Ed25519 signing is deterministic, so the app's signatures must
 * equal them exactly, not merely verify. The Phoenix case is a real
 * job payload signed by the real Phoenix sender key, verified
 * against the public key baked into AgtConfig.
 *
 * The provider test reproduces the Android field failure that
 * shipped in v1.6.0 to v1.6.2: a provider named "BC" is registered
 * but cannot do Ed25519 (Android's built-in stripped BouncyCastle).
 * The old JCA-based identity died on that with "no such algorithm:
 * Ed25519 for provider BC" before any network traffic. The current
 * identity uses no JCA at all, so it must work with the poisoned
 * provider installed and the real BouncyCastle absent.
 */
class AgtIdentityTest {

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }
        return out
    }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    private val seed = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val publicHex = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"

    @Test
    fun derivesTheSamePublicKeyAsTheIndependentImplementation() {
        assertEquals(publicHex, hex(DeviceIdentity.derivePublicKey(seed)))
    }

    @Test
    fun signsByteIdenticalToTheIndependentImplementation() {
        val public = DeviceIdentity.derivePublicKey(seed)
        val empty = ByteArray(0)
        assertEquals(
            "9ca53579530654d5c3df77089ef45eda613e2fedf670e96bedac4639504e5845" +
                "ef4b95d5793077233dd16817b2532e9c5525872a73a4ad74b759369a9e05c102",
            hex(DeviceIdentity.sign(seed, empty))
        )
        assertTrue(
            DeviceIdentity.verify(
                public, empty,
                hex("9ca53579530654d5c3df77089ef45eda613e2fedf670e96bedac4639504e5845ef4b95d5793077233dd16817b2532e9c5525872a73a4ad74b759369a9e05c102")
            )
        )
        val job = """{"jobId":"test-1","kind":"firebase_token","payload":{"token":"abc"}}"""
            .toByteArray(Charsets.UTF_8)
        assertEquals(
            "740f777299ae3917540a8bc5ca4cd8df433c631e175f264cd079de677134e70e" +
                "ddd0a222598b1f54ab7f7b267f5a0161dd925492044905c6c8bc4c73e757150b",
            hex(DeviceIdentity.sign(seed, job))
        )
        assertTrue(
            DeviceIdentity.verify(
                public, job,
                hex("740f777299ae3917540a8bc5ca4cd8df433c631e175f264cd079de677134e70eddd0a222598b1f54ab7f7b267f5a0161dd925492044905c6c8bc4c73e757150b")
            )
        )
        assertFalse(
            DeviceIdentity.verify(
                public,
                """{"jobId":"test-2","kind":"firebase_token","payload":{"token":"abc"}}"""
                    .toByteArray(Charsets.UTF_8),
                hex("740f777299ae3917540a8bc5ca4cd8df433c631e175f264cd079de677134e70eddd0a222598b1f54ab7f7b267f5a0161dd925492044905c6c8bc4c73e757150b")
            )
        )
    }

    @Test
    fun verifiesARealPhoenixJobSignature() {
        val canonical =
            """{"jobId":"harness-1","kind":"command","payload":{"cmd":"echo hello"},"ts":1794700000}"""
        val signature = DeviceIdentity.signatureFromBase64(
            "ryp8BeFebkIaI1oxCsFAPDTo6S7KZU2UTgtA3sxuEl39yrCy1NjlyseTcuoeCcFru5h6y2xOT0uZckir0GTcBw"
        )!!
        val phoenixPublic = DeviceIdentity.publicKeyBytesFromBase64(AgtConfig.PHOENIX_PUBLIC_KEY)!!
        assertTrue(
            DeviceIdentity.verify(
                phoenixPublic,
                canonical.toByteArray(Charsets.UTF_8),
                signature
            )
        )
    }

    /** A provider named "BC" that offers nothing, standing in for
     * Android's stripped built-in. */
    private class EmptyBcProvider : Provider("BC", 1.0, "poisoned stand-in")

    @Test
    fun identityWorksWithAndroidsStrippedBcProvider() {
        val realBc = Security.getProvider("BC")
        if (realBc != null) Security.removeProvider("BC")
        Security.insertProviderAt(EmptyBcProvider(), 1)
        try {
            // The exact condition from the field failure. The old
            // code threw NoSuchAlgorithmException here; the identity
            // must derive, sign and verify without noticing.
            val public = DeviceIdentity.derivePublicKey(seed)
            assertEquals(publicHex, hex(public))
            val msg = "sync must not die here".toByteArray(Charsets.UTF_8)
            val sig = DeviceIdentity.sign(seed, msg)
            assertTrue(DeviceIdentity.verify(public, msg, sig))
        } finally {
            Security.removeProvider("BC")
            if (realBc != null) Security.addProvider(realBc)
        }
    }
}
