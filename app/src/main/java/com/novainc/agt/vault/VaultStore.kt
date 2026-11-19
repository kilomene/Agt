package com.novainc.agt.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pure name rules for the vault, kept free of Android so the unit
 * tests can exercise them: a secret name is a short label made of
 * letters, digits, dot, dash and underscore (never a path), and
 * its environment variable form is the upper snake case of it.
 */
object VaultNames {

    private val NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,63}""")

    fun isValidName(name: String): Boolean = NAME.matches(name)

    /** The environment variable a deployment sees its secret
     * under: telegram-bot-token arrives as TELEGRAM_BOT_TOKEN. */
    fun envVarName(name: String): String {
        val up = name.uppercase().map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
        return if (up.firstOrNull()?.isDigit() == true) "S_$up" else up
    }

    /** A filesystem-safe directory name for one agent's vault:
     * agent ids carry no spaces by the trust rules, and anything
     * else unexpected is folded to an underscore rather than
     * trusted as a path. */
    fun dirName(agentId: String): String =
        agentId.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
}

/**
 * The per-agent Vault (owner design 2026-11-18): there is no
 * general shared vault. Every admitted agent gets its OWN vault,
 * a directory files/agt/vault/<agent-id>/ holding one encrypted
 * record per named secret, encrypted under a per-agent AES key
 * that lives in the Android Keystore and never leaves it.
 *
 * The isolation is structural, not a permission check bolted on:
 * every operation names the agent whose vault it touches, the job
 * engine passes the VERIFIED SIGNER's id (never a payload field),
 * and a deployment is injected only from the vault of the agent
 * that owns it. Phoenix as MAIN cannot read another agent's
 * secrets either: no code path here takes a value out of one
 * vault on another agent's behalf. The one internal reader of
 * every value is the output redactor's value list, which only
 * ever REMOVES values from text, never returns them.
 *
 * Values are never written anywhere in plain text: not to the
 * job result (secret_set returns {stored, name} only), not to a
 * log, not to telemetry. A value leaves the vault exactly once
 * per use: into the environment of a deployment process owned by
 * the same agent, at start, never on its command line, so a
 * process listing shows nothing.
 */
class VaultStore(context: Context) {

    private val root = File(context.filesDir, "agt/vault").apply { mkdirs() }
    private val lock = Any()

    private fun agentDir(agentId: String): File =
        File(root, VaultNames.dirName(agentId)).apply { mkdirs() }

    private fun recordFile(agentId: String, name: String): File =
        File(agentDir(agentId), "$name.enc")

    private fun metaFile(agentId: String): File = File(agentDir(agentId), "meta.json")

    // ---------------- keystore ----------------

    private fun keyAlias(agentId: String): String =
        "agt-vault-" + VaultNames.dirName(agentId)

    /** The agent's vault key, created on first use. Held by the
     * Android Keystore: app-private, non-exportable, and gone
     * with the app data, which is exactly the vault's lifetime. */
    private fun keyFor(agentId: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias(agentId), null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                keyAlias(agentId),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun deleteKey(agentId: String) {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(keyAlias(agentId))
        } catch (e: Exception) {
        }
    }

    // ---------------- crypto ----------------

    private fun encrypt(agentId: String, value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(agentId))
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + body
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(agentId: String, stored: String): String? {
        return try {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val iv = packed.copyOfRange(0, 12)
            val body = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyFor(agentId), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // A record whose key is gone (vault reset, keystore
            // cleared) reads as missing, never as a crash and
            // never as garbage handed to a deployment.
            null
        }
    }

    // ---------------- metadata ----------------

    private fun loadMeta(agentId: String): JsonObject {
        val f = metaFile(agentId)
        if (!f.exists()) return JsonObject()
        return try {
            JsonParser.parseString(f.readText()).asJsonObject
        } catch (e: Exception) {
            JsonObject()
        }
    }

    private fun saveMeta(agentId: String, meta: JsonObject) {
        metaFile(agentId).writeText(meta.toString())
    }

    // ---------------- operations ----------------

    /** Store (or replace) one secret in one agent's vault. The
     * value is encrypted before it touches the disk and is never
     * returned, logged, or echoed by any caller surface. */
    fun set(agentId: String, name: String, value: String): Boolean {
        if (!VaultNames.isValidName(name) || value.isEmpty()) return false
        synchronized(lock) {
            recordFile(agentId, name).writeText(encrypt(agentId, value))
            val meta = loadMeta(agentId)
            val now = System.currentTimeMillis()
            val entry = meta.getAsJsonObject(name) ?: JsonObject()
            if (!entry.has("createdAt")) entry.addProperty("createdAt", now)
            entry.addProperty("updatedAt", now)
            meta.add(name, entry)
            saveMeta(agentId, meta)
            return true
        }
    }

    /** The NAMES in one agent's vault, never a value. This is the
     * only listing that exists, and it is scoped to the one vault:
     * there is no cross-agent listing anywhere in the app. */
    fun names(agentId: String): List<String> = synchronized(lock) {
        agentDir(agentId).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".enc") }
            ?.map { it.name.removeSuffix(".enc") }
            ?.sorted()
            ?: emptyList()
    }

    /** One value out of one agent's vault, for exactly two
     * internal uses: injecting a deployment owned by that same
     * agent at start, and the redactor's removal list. No job,
     * screen, log, or telemetry path calls this. */
    fun get(agentId: String, name: String): String? {
        if (!VaultNames.isValidName(name)) return null
        val f = recordFile(agentId, name)
        if (!f.exists()) return null
        return decrypt(agentId, f.readText().trim())
    }

    /** Remove one secret from one agent's vault. */
    fun delete(agentId: String, name: String): Boolean {
        if (!VaultNames.isValidName(name)) return false
        synchronized(lock) {
            val f = recordFile(agentId, name)
            val existed = f.exists()
            if (existed) {
                shred(f)
                val meta = loadMeta(agentId)
                meta.remove(name)
                saveMeta(agentId, meta)
            }
            return existed
        }
    }

    /** Wipe one agent's whole vault: every record shredded, the
     * metadata dropped, and the Keystore key deleted, so even a
     * record that somehow survived has nothing left to decrypt
     * it with. This is what revocation does, and what the phone
     * owner's reset button on the Agent screen does. */
    fun wipe(agentId: String) {
        synchronized(lock) {
            val dir = agentDir(agentId)
            dir.listFiles()?.forEach { f -> if (f.isFile) shred(f) }
            dir.deleteRecursively()
            deleteKey(agentId)
        }
    }

    /** The agent ids that hold a vault on this phone (directory
     * names only). Used by the migration and the redactor's value
     * sweep; names inside a vault are never enumerated across
     * agents by any agent-facing path. */
    fun agentIds(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    /** Every value in every vault, for the redactor ONLY: the
     * list is used to REMOVE values from outgoing text, and is
     * never itself returned, stored, or logged anywhere. */
    fun allValues(): List<String> {
        val out = mutableListOf<String>()
        for (agent in agentIds()) {
            for (name in names(agent)) {
                get(agent, name)?.let { out.add(it) }
            }
        }
        return out
    }

    /** Overwrite a file with zeros, then delete it: a shredded
     * secret does not sit in a deleted-but-readable block any
     * longer than the platform forces it to. */
    private fun shred(f: File) {
        try {
            val len = f.length()
            if (len > 0) {
                f.outputStream().use { out ->
                    val zeros = ByteArray(4096)
                    var left = len
                    while (left > 0) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        out.write(zeros, 0, n)
                        left -= n
                    }
                }
            }
        } catch (e: Exception) {
        }
        f.delete()
    }

    /**
     * Migration (design rule 6): the interim secrets path
     * (files/agt/secrets/<name>.txt, written by MAIN-signed jobs
     * before the vault existed) is imported into the MAIN agent's
     * vault and the plain files are shredded. Runs once; the
     * marker file makes later starts skip it. Files with names
     * the vault cannot hold are left exactly where they are
     * rather than silently dropped.
     */
    fun migrateInterimSecrets(filesDir: File, mainAgentId: String) {
        val marker = File(root, ".interim-migrated")
        if (marker.exists()) return
        val interim = File(filesDir, "agt/secrets")
        if (interim.isDirectory) {
            interim.listFiles()?.forEach { f ->
                if (f.isFile && VaultNames.isValidName(f.name.removeSuffix(".txt"))) {
                    val name = f.name.removeSuffix(".txt")
                    val value = try { f.readText().trim() } catch (e: Exception) { "" }
                    if (value.isNotEmpty() && set(mainAgentId, name, value)) {
                        shred(f)
                    }
                }
            }
        }
        marker.writeText("done\n")
    }
}
