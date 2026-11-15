package com.novainc.agt.core

/**
 * Fixed endpoints for the three legs. The Supabase anon key is a public
 * key by design (PostgREST + RLS); it is not a secret and is safe to ship.
 * The tunnel token is NEVER shipped: the owner pastes it once in Settings.
 */
object AgtConfig {
    const val SUPABASE_URL = "https://ktbyxyzbqfdhmedkrmpb.supabase.co"
    // Filled in from the real termux-agt project before release. Never guess
    // a key: an empty key makes the Supabase leg report "not configured".
    const val SUPABASE_ANON_KEY = ""
    const val FIREBASE_RTDB_URL = "https://agentlink-84609-default-rtdb.firebaseio.com"
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
