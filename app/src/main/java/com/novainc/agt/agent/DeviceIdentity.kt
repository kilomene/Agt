package com.novainc.agt.agent

import android.content.Context
import com.novainc.agt.core.AgtStore
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The host identity: an Ed25519 key pair generated once on the phone.
 * The public key is the host's identity in Supabase; the private seed
 * never leaves the app-private directory. Phoenix signs every job
 * with its own Ed25519 key; the app only runs jobs that verify
 * against the paired Phoenix public key.
 *
 * All cryptography here runs on the BouncyCastle lightweight RFC
 * 8032 API bundled in the APK, never on JCA providers. That is a
 * hard requirement, learned from a field failure: the old code asked
 * JCA for provider "BC", Android ships a stripped built-in provider
 * under that exact name with no Ed25519 in it, and the bundled full
 * provider was only registered when no "BC" existed at all. On the
 * phone every key and signature call therefore threw
 * NoSuchAlgorithmException ("no such algorithm: Ed25519 for
 * provider BC") before any network traffic, which killed sync, the
 * FCM token upsert and job verification in one place, while desktop
 * JVM tests stayed green because the JDK has no built-in "BC". The
 * lightweight API is pure math over byte arrays: same code path on
 * every runtime, nothing to register, nothing to resolve.
 */
class DeviceIdentity(context: Context, private val store: AgtStore) {
    private val keyFile = File(context.filesDir, "agt/device_key.pkcs8")

    @Volatile
    private var cachedSeed: ByteArray? = null

    private fun seed(): ByteArray {
        cachedSeed?.let { return it }
        val loaded = loadOrCreateSeed()
        cachedSeed = loaded
        return loaded
    }

    private fun loadOrCreateSeed(): ByteArray {
        if (keyFile.exists()) {
            val raw = keyFile.readBytes()
            if (raw.size == 32) return raw
            // Builds up to v1.6.2 stored a PKCS8 blob written through
            // the JCA on runtimes where that worked. Recover its
            // 32-byte seed with the bundled ASN.1 reader and rewrite
            // the file in the raw seed form, so the identity (and
            // the public key Phoenix already knows) survives.
            try {
                val info = PrivateKeyInfo.getInstance(raw)
                val seed = (info.parsePrivateKey() as ASN1OctetString).octets
                if (seed.size == 32) {
                    persist(seed)
                    return seed
                }
            } catch (e: Exception) {
                // Not a readable PKCS8 blob: fall through and mint a
                // fresh identity below.
            }
        }
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        persist(seed)
        return seed
    }

    private fun persist(seed: ByteArray) {
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(seed)
    }

    /** The raw 32-byte public key for this host. */
    fun publicKeyBytes(): ByteArray = derivePublicKey(seed())

    /** The wire form the app has always published: base64 (no
     * padding) of the X.509 SubjectPublicKeyInfo encoding. */
    fun publicKeyBase64(): String = encodePublicKeyBase64(publicKeyBytes())

    fun sign(bytes: ByteArray): ByteArray = sign(seed(), bytes)

    companion object {
        // Ed25519 SPKI prefix: SEQUENCE(42) SEQUENCE(5) OID 1.3.101.112 BIT STRING
        private val SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        )

        /** Derive the raw public key from a 32-byte seed. Pure
         * function of the seed; the unit tests pin it against an
         * independent implementation's vectors. */
        fun derivePublicKey(seed: ByteArray): ByteArray {
            val pk = ByteArray(32)
            Ed25519.generatePublicKey(seed, 0, pk, 0)
            return pk
        }

        /** Deterministic RFC 8032 signature over [bytes]. */
        fun sign(seed: ByteArray, bytes: ByteArray): ByteArray {
            val pk = derivePublicKey(seed)
            val sig = ByteArray(64)
            Ed25519.sign(seed, 0, pk, 0, bytes, 0, bytes.size, sig, 0)
            return sig
        }

        /** Verify an RFC 8032 signature against a raw 32-byte
         * public key. */
        fun verify(publicBytes: ByteArray, bytes: ByteArray, signature: ByteArray): Boolean {
            if (publicBytes.size != 32 || signature.size != 64) return false
            return try {
                Ed25519.verify(signature, 0, publicBytes, 0, bytes, 0, bytes.size)
            } catch (e: Exception) {
                false
            }
        }

        fun encodePublicKeyBase64(rawPublic: ByteArray): String =
            Base64.getEncoder().withoutPadding().encodeToString(SPKI_PREFIX + rawPublic)

        /** Decode base64 that may have its padding stripped (both the app
         * and the Phoenix sender encode without padding). */
        fun decodeB64(b64: String): ByteArray {
            val padded = b64.padEnd(((b64.length + 3) / 4) * 4, '=')
            return Base64.getDecoder().decode(padded)
        }

        /** The raw 32-byte public key from either published form:
         * the 32 raw bytes (what Phoenix sends) or the full 44-byte
         * X.509 SubjectPublicKeyInfo encoding. Null when the input
         * is neither. */
        fun publicKeyBytesFromBase64(b64: String): ByteArray? {
            return try {
                val bytes = decodeB64(b64)
                when {
                    bytes.size == 32 -> bytes
                    bytes.size == 44 &&
                        bytes.copyOfRange(0, SPKI_PREFIX.size).contentEquals(SPKI_PREFIX) ->
                        bytes.copyOfRange(SPKI_PREFIX.size, 44)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        fun signatureFromBase64(b64: String): ByteArray? {
            return try {
                decodeB64(b64)
            } catch (e: Exception) {
                null
            }
        }
    }
}
