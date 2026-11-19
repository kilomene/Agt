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
    // The Linux bootstrap ships inside the APK as a single asset:
    // a zip of native bionic binaries cross-compiled for this app's
    // own prefix (bash, busybox, libncurses; see NativeLinux and
    // report section 16), staged into assets by the build from the
    // repo's native-bootstrap directory and verified there against
    // the sha256 below. It replaces the Debian rootfs bundle
    // (101 MB, proot guest) entirely: the download the owner pays
    // for shrank by roughly the rootfs's whole size. The installed
    // bundle's version is recorded in the settings' rootfsVersion
    // field, whose name predates the change.
    const val BOOTSTRAP_ASSET = "bootstrap-aarch64.zip"
    const val BOOTSTRAP_SHA256 = "6f6e49a5f5d692ee4fc35889df43f028caee46be5310e31efbc80772158f5d25"
    // BOOTSTRAP_VERSION must change every time the zip's contents
    // change: NativeLinux.isInstalled() trusts the recorded version
    // and skips extraction when it matches, so a new zip under an
    // old version string never lands on an updated phone (proven
    // the hard way on v1.6.15: the pin moved, the version did not,
    // and python/git stayed inside the APK). The phase harness pins
    // this string together with the sha so the pair cannot drift.
    const val BOOTSTRAP_VERSION = "agt-bootstrap-v4"
}
