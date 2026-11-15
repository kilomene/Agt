package com.novainc.agt.core

import com.novainc.agt.BuildConfig

/**
 * Fixed endpoints for the three legs, plus the client config that CI
 * injects from GitHub repo secrets at build time (BuildConfig): the
 * Supabase anon key and the Cloudflare tunnel token are baked into the
 * build so the app works on first launch with nothing to paste and
 * nothing to set up. There is no Settings override: the owner ordered
 * the secrets into GitHub, baked at build time, never pasted by hand.
 */
object AgtConfig {
    const val SUPABASE_URL = "https://ktbyxyzbqfdhmedkrmpb.supabase.co"
    // From CI secret SUPABASE_ANON_KEY via BuildConfig. An empty value
    // (local build without the secret) makes the Supabase leg report
    // "not configured" instead of guessing a key.
    val SUPABASE_ANON_KEY: String = BuildConfig.SUPABASE_ANON_KEY
    // From CI secret TUNNEL_TOKEN via BuildConfig: the Cloudflare tunnel
    // token for TUNNEL_HOSTNAME, baked in so the tunnel connects on
    // first launch with zero setup.
    val TUNNEL_TOKEN: String = BuildConfig.TUNNEL_TOKEN
    // The Phoenix Ed25519 public key (raw 32 bytes, base64, no padding).
    // A public key is not a secret, so it ships inside the app: every
    // signed job verifies against it out of the box, including the
    // firebase_token delivery, with no pairing step gating setup.
    // Pairing can still store the same key per device; verification
    // falls back to this baked key whenever nothing is stored.
    const val PHOENIX_PUBLIC_KEY = "AMW5c64fBaSMK4Tp7sL1qu/J1hIAlzvVKbr0TOu9mrw"
    const val FIREBASE_RTDB_URL = "https://agentlink-84609-default-rtdb.firebaseio.com"
    // Web API key of the same Firebase project: public client config,
    // used only to exchange the Phoenix-minted custom token for an ID
    // token at the Identity Toolkit endpoint.
    const val FIREBASE_API_KEY = "AIzaSyDk9Ic4pbQglf0kyIBa_oLGmu5iHbnWFFI"
    const val TUNNEL_HOSTNAME = "termux.novamail.store"
    // The rootfs ships inside the APK when the build bundles it. If it is
    // not bundled, the app downloads it in 10 MB parts from the repo
    // (raw.githubusercontent.com) and reassembles them, then verifies the
    // sha256 below. A single-file release asset was tried first: GitHub's
    // uploads host rejects this VM's credential, so parts are the working
    // distribution path. The release rootfs-v1 exists on the repo but
    // carries no asset yet for the same reason.
    val ROOTFS_PART_URLS: List<String> = (0..9).map { i ->
        "https://raw.githubusercontent.com/kilomene/Agt/main/rootfs-parts/" +
            "debian-bookworm-agt-arm64.tar.gz.part%02d".format(i)
    }
    const val ROOTFS_SHA256 = "cf221b91bfac1021ea7861b821af94619bd29c60c4edd5ac57321d0602b7d05d"
    const val ROOTFS_SIZE_BYTES = 101060887L
    const val ROOTFS_VERSION = "bookworm-agt-v1"
}
