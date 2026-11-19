package com.novainc.agt.vault

/**
 * Output scrubbing for the per-agent Vault (design rule 5): any
 * text that is about to be saved or returned as a job result or a
 * deployment log passes through here first, and every known vault
 * value in it is replaced with [secret]. This closes the hole the
 * vault exists for: a program that prints its own token (a bot
 * echoing its config, an env dump) must not leave the value sitting
 * in a saved log or a job result where another agent's process
 * listing or log read could pick it up.
 *
 * Pure Kotlin, no Android, so the unit tests exercise the exact
 * code the JobEngine and the supervisor run.
 */
object SecretRedactor {

    const val MASK = "[secret]"

    /** Telegram bot tokens: a numeric bot id, a colon, and a long
     * url-safe body. Recognized by shape alone, so a bot token is
     * scrubbed even when it never passed through the vault (the
     * 1fd4b135 case: the token was a plain file, visible in saved
     * job output). No leading word boundary: in the Bot API URL
     * the id is glued straight onto the word bot
     * (api.telegram.org/bot<id>:<body>/...), which is exactly the
     * shape that leaks into process listings and logs. */
    private val TELEGRAM_TOKEN = Regex("""\d{6,12}:[A-Za-z0-9_-]{30,}\b""")

    /** JSON Web Tokens: three base64url segments, the first one
     * starting with the usual eyJ header prefix. */
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")

    /** An Authorization bearer header value. */
    private val BEARER = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]+""")

    /**
     * Replace every occurrence of every known value, then every
     * token-shaped string, with the mask. Values shorter than 4
     * characters are skipped: masking a 1 to 3 character string
     * would shred ordinary text, and a real credential is never
     * that short.
     */
    fun redact(text: String, values: Collection<String>): String {
        if (text.isEmpty()) return text
        var out = text
        for (v in values) {
            if (v.length >= 4) out = out.replace(v, MASK)
        }
        out = TELEGRAM_TOKEN.replace(out, MASK)
        out = JWT.replace(out, MASK)
        out = BEARER.replace(out) { m -> m.groupValues[1] + MASK }
        return out
    }
}
