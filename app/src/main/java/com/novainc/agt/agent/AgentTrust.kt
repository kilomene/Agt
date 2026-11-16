package com.novainc.agt.agent

import com.google.gson.JsonObject
import com.novainc.agt.core.AgentKey
import com.novainc.agt.core.AgtConfig
import com.novainc.agt.core.AgtSettings
import com.novainc.agt.core.JobFeedEntry

/**
 * Multi-agent trust: the pure decision logic behind the trusted
 * agents list. Phoenix is the main agent; other agents may also be
 * admitted, each with its own Ed25519 key, so their jobs verify,
 * run, and are attributed to them, and revoking one agent's entry
 * ends its access without touching anyone else's.
 *
 * Everything here is a pure function of an AgtSettings value: no
 * store, no Android, so the unit tests exercise the exact code the
 * JobEngine and the Agent screen run. All reads of the settings
 * fields are null-tolerant for the usual Gson reason: Gson does not
 * apply Kotlin default values when it hydrates a file written by an
 * older build, so a field added later reads back null, not its
 * default. Every field read goes through the stored accessors below,
 * the same discipline as DeploymentEngine.storedSlug.
 */
object AgentTrust {

    /** The main agent's id: short label, no spaces, stored as given. */
    const val MAIN_AGENT_ID = "phoenix"

    /** The outcome of a trust change: the new settings to persist,
     * or the refusal reason when settings is null. */
    class Change(val settings: AgtSettings?, val error: String?) {
        val ok: Boolean get() = settings != null
    }

    // Null-tolerant stored-field accessors. The declared Kotlin
    // types are non-null, but a Gson-hydrated value can still carry
    // null in any of them; assigning to a nullable local first is
    // what makes the null check real instead of assumed.

    fun storedAgentId(a: AgentKey): String {
        val raw: String? = a.agentId
        return if (raw.isNullOrBlank()) "" else raw
    }

    fun storedPublicKey(a: AgentKey): String {
        val raw: String? = a.publicKey
        return if (raw.isNullOrBlank()) "" else raw
    }

    fun storedRole(a: AgentKey): String {
        val raw: String? = a.role
        return if (raw.isNullOrBlank()) AgentKey.ROLE_AGENT else raw
    }

    fun storedPhoenixKey(s: AgtSettings): String {
        val raw: String? = s.phoenixPublicKey
        return if (raw.isNullOrBlank()) "" else raw
    }

    fun storedPairingCode(s: AgtSettings): String {
        val raw: String? = s.pairingCode
        return if (raw.isNullOrBlank()) "" else raw
    }

    fun storedPairingAgentId(s: AgtSettings): String {
        val raw: String? = s.pairingAgentId
        return if (raw.isNullOrBlank()) "" else raw
    }

    fun feedAgentOf(e: JobFeedEntry): String {
        val raw: String? = e.agent
        return if (raw.isNullOrBlank()) "" else raw
    }

    /** The agents list exactly as stored, minus entries too broken
     * to trust (no id or no key). Empty when the settings predate
     * multi-agent trust and have not been migrated yet. */
    fun storedAgents(s: AgtSettings): List<AgentKey> {
        val raw: List<AgentKey>? = s.agents
        if (raw == null) return emptyList()
        val out = mutableListOf<AgentKey>()
        for (a in raw) {
            if (a == null) continue
            if (storedAgentId(a).isEmpty() || storedPublicKey(a).isEmpty()) continue
            out.add(a)
        }
        return out
    }

    /**
     * The effective trusted list, MAIN first: the stored list when
     * it already carries a MAIN entry; otherwise a MAIN entry
     * synthesized from the legacy phoenixPublicKey, or from the key
     * baked into the build when nothing was ever paired. The
     * synthesized entry is what first-load migration persists (see
     * synced), and what lets a fresh install verify main-agent jobs
     * with no pairing step, exactly as the single-key build did.
     */
    fun agentsOf(s: AgtSettings): List<AgentKey> {
        val stored = storedAgents(s)
        val main = stored.firstOrNull { storedRole(it) == AgentKey.ROLE_MAIN }
        if (main != null) {
            return listOf(main) + stored.filter { it !== main }
        }
        val mainKey = storedPhoenixKey(s).ifEmpty { AgtConfig.PHOENIX_PUBLIC_KEY }
        val synthesized = AgentKey(MAIN_AGENT_ID, mainKey, AgentKey.ROLE_MAIN, 0L)
        return listOf(synthesized) + stored
    }

    /** The MAIN entry of the effective list. */
    fun mainAgent(s: AgtSettings): AgentKey =
        agentsOf(s).first { storedRole(it) == AgentKey.ROLE_MAIN }

    /** The effective entry with this id, or null. */
    fun findAgent(s: AgtSettings, agentId: String): AgentKey? =
        agentsOf(s).firstOrNull { storedAgentId(it) == agentId }

    /**
     * Settings with the agents list and the legacy phoenixPublicKey
     * in agreement, materializing the migration when needed:
     * a stored MAIN entry wins and phoenixPublicKey is rewritten
     * from it; with no MAIN entry but a stored phoenixPublicKey,
     * that key becomes the MAIN entry (the first-load migration);
     * with neither, the settings pass through untouched (the baked
     * build key stays a verify-time fallback, never persisted).
     * AgtStore runs this on every load and every save, so the two
     * views of the main key can never drift apart.
     */
    fun synced(s: AgtSettings): AgtSettings {
        val stored = storedAgents(s)
        val main = stored.firstOrNull { storedRole(it) == AgentKey.ROLE_MAIN }
        if (main != null) {
            val mainKey = storedPublicKey(main)
            return if (storedPhoenixKey(s) == mainKey && s.agents != null) s
            else s.copy(phoenixPublicKey = mainKey, agents = stored)
        }
        val legacy = storedPhoenixKey(s)
        if (legacy.isEmpty()) return s
        val migrated = AgentKey(MAIN_AGENT_ID, legacy, AgentKey.ROLE_MAIN, System.currentTimeMillis())
        return s.copy(agents = listOf(migrated) + stored)
    }

    /**
     * Verify a job signature against the trusted list, MAIN first,
     * then each AGENT entry in order. Returns the entry whose key
     * verified, which is the attribution for the job, or null when
     * no trusted key matches: the caller then fails the job exactly
     * as the single-key build did.
     */
    fun verifySigner(s: AgtSettings, payload: JsonObject, signatureB64: String): AgentKey? {
        val sig = DeviceIdentity.signatureFromBase64(signatureB64) ?: return null
        val bytes = JobEngine.canonicalJson(payload).toByteArray(Charsets.UTF_8)
        for (agent in agentsOf(s)) {
            val pub = DeviceIdentity.publicKeyBytesFromBase64(storedPublicKey(agent)) ?: continue
            if (DeviceIdentity.verify(pub, bytes, sig)) return agent
        }
        return null
    }

    /** The agentId a job is attributed to: the signer's id, or
     * empty when the job never verified. */
    fun agentIdOf(signer: AgentKey?): String =
        if (signer == null) "" else storedAgentId(signer)

    /** Stamp the signer's agentId into a job result, so the result
     * written back to Supabase names the agent that sent the job. */
    fun stampAgent(result: JsonObject, signer: AgentKey?): JsonObject {
        if (signer != null) result.addProperty("agent", storedAgentId(signer))
        return result
    }

    private fun validateNewAgent(s: AgtSettings, agentId: String, publicKeyB64: String): String? {
        if (agentId.isBlank()) return "agentId missing"
        if (agentId.any { it.isWhitespace() }) return "agentId must not contain spaces"
        if (DeviceIdentity.publicKeyBytesFromBase64(publicKeyB64) == null) return "bad agent public key"
        val main = mainAgent(s)
        if (agentId == storedAgentId(main)) return "agentId names the main agent"
        return null
    }

    private fun upsertAgent(s: AgtSettings, agentId: String, publicKeyB64: String): AgtSettings {
        val stored = storedAgents(s)
        val existing = stored.firstOrNull { storedAgentId(it) == agentId }
        val entry = if (existing != null) {
            existing.copy(publicKey = publicKeyB64)
        } else {
            AgentKey(agentId, publicKeyB64, AgentKey.ROLE_AGENT, System.currentTimeMillis())
        }
        val rest = stored.filter { storedAgentId(it) != agentId }
        return s.copy(agents = rest + entry)
    }

    /**
     * The agent_add door for jobs: MAIN-signed only. A non-main
     * signer is refused before anything is read from the payload,
     * so an admitted agent can never admit another one.
     */
    fun applyAgentAdd(s: AgtSettings, signer: AgentKey?, agentId: String, publicKeyB64: String): Change {
        if (signer == null || storedRole(signer) != AgentKey.ROLE_MAIN) {
            return Change(null, "agent_add requires the main agent")
        }
        validateNewAgent(s, agentId, publicKeyB64)?.let { return Change(null, it) }
        return Change(upsertAgent(s, agentId, publicKeyB64), null)
    }

    /**
     * The agent_revoke door for jobs: MAIN-signed only, and the
     * MAIN entry itself can never be revoked, here or anywhere.
     */
    fun applyAgentRevoke(s: AgtSettings, signer: AgentKey?, agentId: String): Change {
        if (signer == null || storedRole(signer) != AgentKey.ROLE_MAIN) {
            return Change(null, "agent_revoke requires the main agent")
        }
        return revokeAgent(s, agentId)
    }

    /**
     * Revoke one agent's trust: the local door (the Agent screen's
     * revoke button, where the owner holding the phone is the
     * authority) and the shared tail of the job door. Removing the
     * entry is the whole mechanism: that key's later jobs simply
     * stop verifying. Revoking MAIN is refused; revoking an unknown
     * id is refused rather than silently succeeding.
     */
    fun revokeAgent(s: AgtSettings, agentId: String): Change {
        // The main agent is refused by identity first, before the
        // stored lookup: on a settings value whose MAIN entry has
        // not been persisted yet (it exists only as the synthesized
        // effective entry), revoking its id must still read as the
        // main-agent refusal, never as an unknown agent.
        if (agentId == storedAgentId(mainAgent(s))) {
            return Change(null, "the main agent cannot be revoked")
        }
        val stored = storedAgents(s)
        val target = stored.firstOrNull { storedAgentId(it) == agentId }
            ?: return Change(null, "no such agent")
        if (storedRole(target) == AgentKey.ROLE_MAIN) {
            return Change(null, "the main agent cannot be revoked")
        }
        return Change(s.copy(agents = stored.filter { storedAgentId(it) != agentId }), null)
    }

    /**
     * Pair with an empty agentId: today's bootstrap behaviour,
     * unchanged. The presented key replaces the MAIN entry's key
     * and the legacy phoenixPublicKey with it, admitted AGENT
     * entries are kept, and the pairing code is spent.
     */
    fun pairAsMain(s: AgtSettings, publicKeyB64: String): AgtSettings {
        val stored = storedAgents(s)
        val oldMain = stored.firstOrNull { storedRole(it) == AgentKey.ROLE_MAIN }
        val main = AgentKey(
            MAIN_AGENT_ID, publicKeyB64, AgentKey.ROLE_MAIN,
            oldMain?.addedAt ?: System.currentTimeMillis()
        )
        val agents = listOf(main) + stored.filter { storedRole(it) != AgentKey.ROLE_MAIN }
        return s.copy(
            phoenixPublicKey = publicKeyB64,
            agents = agents,
            pairingCode = "",
            pairingAgentId = ""
        )
    }

    /**
     * Pair against a code minted for one agentId: the presented key
     * becomes (or refreshes) that agent's AGENT entry. The MAIN
     * entry and the legacy phoenixPublicKey are untouched, and the
     * code is spent. A code labelled for the main agent's own id is
     * refused: replacing the main key goes through the empty-agentId
     * pair, never through the agent door.
     */
    fun pairAsAgent(s: AgtSettings, agentId: String, publicKeyB64: String): Change {
        validateNewAgent(s, agentId, publicKeyB64)?.let { return Change(null, it) }
        val updated = upsertAgent(s, agentId, publicKeyB64)
        return Change(updated.copy(pairingCode = "", pairingAgentId = ""), null)
    }
}
