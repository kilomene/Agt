package com.novainc.agt

import com.novainc.agt.vault.SecretRedactor
import com.novainc.agt.vault.VaultNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-agent Vault's pure rules: output scrubbing (a known
 * value never survives into a saved log or job result, and a
 * token-shaped string is masked even when it never passed
 * through the vault) and the secret name / env var naming rules.
 */
class VaultRedactorTest {

    @Test
    fun knownValuesAreMaskedEverywhere() {
        val out = SecretRedactor.redact(
            "token is hunter2secret and again hunter2secret",
            listOf("hunter2secret")
        )
        assertEquals("token is [secret] and again [secret]", out)
    }

    @Test
    fun shortValuesAreNotMasked() {
        // A 3 character "value" would shred ordinary text if it
        // were treated as a secret; real credentials are longer.
        val out = SecretRedactor.redact("the cat sat", listOf("cat"))
        assertEquals("the cat sat", out)
    }

    @Test
    fun telegramTokenShapeIsMaskedWithoutAKnownValue() {
        val token = "9259725096:AAH7xYz9BcDeFgHiJkLmNoPqRsTuVwXyZ1234"
        val out = SecretRedactor.redact("calling https://api.telegram.org/bot$token/getMe", emptyList())
        assertFalse(out.contains(token))
        assertTrue(out.contains("[secret]"))
    }

    @Test
    fun bearerHeaderValueIsMasked() {
        val out = SecretRedactor.redact("Authorization: Bearer abc.def-123", emptyList())
        assertEquals("Authorization: Bearer [secret]", out)
    }

    @Test
    fun plainTextPassesThrough() {
        val text = "server listening on port 8787, 3 orders today"
        assertEquals(text, SecretRedactor.redact(text, listOf("hunter2secret")))
    }

    @Test
    fun secretNamesValidate() {
        assertTrue(VaultNames.isValidName("telegram-bot-token"))
        assertTrue(VaultNames.isValidName("kalshi_api_key"))
        assertTrue(VaultNames.isValidName("a"))
        assertFalse(VaultNames.isValidName(""))
        assertFalse(VaultNames.isValidName("../escape"))
        assertFalse(VaultNames.isValidName("has space"))
        assertFalse(VaultNames.isValidName("slash/name"))
        assertFalse(VaultNames.isValidName("-leading-dash"))
    }

    @Test
    fun envVarNamesAreUpperSnake() {
        assertEquals("TELEGRAM_BOT_TOKEN", VaultNames.envVarName("telegram-bot-token"))
        assertEquals("KALSHI_API_KEY", VaultNames.envVarName("kalshi_api_key"))
        assertEquals("A_B_C", VaultNames.envVarName("a.b-c"))
        assertEquals("S_9LIVES", VaultNames.envVarName("9lives"))
    }
}
