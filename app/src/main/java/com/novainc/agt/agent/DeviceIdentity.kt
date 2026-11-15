package com.novainc.agt.agent

import android.content.Context
import com.novainc.agt.core.AgtStore
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The host identity: an Ed25519 key pair generated once on the phone.
 * The public key is the host's identity in Supabase; the private key
 * never leaves the app-private directory. Phoenix signs every job with
 * its own Ed25519 key; the app only runs jobs that verify against the
 * paired Phoenix public key.
 */
class DeviceIdentity(context: Context, private val store: AgtStore) {
    private val keyFile = File(context.filesDir, "agt/device_key.pkcs8")

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun keyPair(): KeyPair {
        if (keyFile.exists()) {
            return try {
                val spec = PKCS8EncodedKeySpec(keyFile.readBytes())
                val kf = KeyFactory.getInstance("Ed25519", "BC")
                val private = kf.generatePrivate(spec)
                val public = publicFromPrivate(private)
                KeyPair(public, private)
            } catch (e: Exception) {
                generate()
            }
        }
        return generate()
    }

    private fun generate(): KeyPair {
        val gen = KeyPairGenerator.getInstance("Ed25519", "BC")
        gen.initialize(256, SecureRandom())
        val pair = gen.generateKeyPair()
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(pair.private.encoded)
        return pair
    }

    private fun publicFromPrivate(private: PrivateKey): PublicKey {
        // Derive the public key from the private key bytes (Ed25519 seed).
        val priv = (private as org.bouncycastle.jcajce.provider.asymmetric.edec.BCEdDSAPrivateKey)
        return publicKeyFromBytes(priv.publicKeyBytes)
    }

    fun publicKeyBase64(): String =
        Base64.getEncoder().withoutPadding().encodeToString(keyPair().public.encoded)

    fun sign(bytes: ByteArray): ByteArray {
        val sig = Signature.getInstance("Ed25519", "BC")
        sig.initSign(keyPair().private)
        sig.update(bytes)
        return sig.sign()
    }

    companion object {
        /** Decode base64 that may have its padding stripped (both the app
         * and the Phoenix sender encode without padding). */
        fun decodeB64(b64: String): ByteArray {
            val padded = b64.padEnd(((b64.length + 3) / 4) * 4, '=')
            return Base64.getDecoder().decode(padded)
        }

        /** Build a PublicKey from either form: the 32 raw bytes (what
         * Phoenix sends, and what Bouncy Castle's publicKeyBytes holds)
         * or a full 44-byte X.509 SubjectPublicKeyInfo encoding. */
        fun publicKeyFromBytes(bytes: ByteArray): PublicKey {
            val spki = if (bytes.size == 32) {
                // Ed25519 SPKI prefix: SEQUENCE(42) SEQUENCE(5) OID 1.3.101.112 BIT STRING
                byteArrayOf(
                    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
                    0x70, 0x03, 0x21, 0x00
                ) + bytes
            } else {
                bytes
            }
            return KeyFactory.getInstance("Ed25519", "BC")
                .generatePublic(X509EncodedKeySpec(spki))
        }

        fun publicKeyFromBase64(b64: String): PublicKey? {
            return try {
                publicKeyFromBytes(decodeB64(b64))
            } catch (e: Exception) {
                null
            }
        }

        fun verify(public: PublicKey, bytes: ByteArray, signature: ByteArray): Boolean {
            return try {
                val sig = Signature.getInstance("Ed25519", "BC")
                sig.initVerify(public)
                sig.update(bytes)
                sig.verify(signature)
            } catch (e: Exception) {
                false
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
