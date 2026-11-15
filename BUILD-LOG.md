# AGT Build Log

## Phase 1: app shell that cannot die (2026-11-15)

Built and verified on GitHub Actions (owner's chosen build route, 2026-11-15),
because this VM's Java/Gradle artifact downloads through the egress proxy are
unreliable while GitHub's runners download cleanly. Local Gradle runs 1 to 5
never reached compilation on this VM (daemon connection, then plugin
resolution); the failures were VM environment, not app code. The local
diagnosis is recorded in the project memory notes for 2026-11-14 and
2026-11-15.

### Verified result

- Repository: https://github.com/kilomene/Agt (main)
- Green run: GitHub Actions run 43693419638, head f23d6712, conclusion success
- Command run by CI: gradle :app:testDebugUnitTest :app:assembleDebug
  (Gradle 8.7, Temurin Java 17, Android SDK on ubuntu-latest)
- Unit tests: 5 tests, 0 failures, 0 ignored, 100 percent success
  (com.novainc.agt.ReconnectorTest, from the CI test report artifact)
- APK artifact: agt-debug-apk (GitHub artifact id 14153396883)
- APK file: app-debug.apk, 8,871,507 bytes,
  sha256 bd859bfd1959567e8bc91798a651092a47b964982f0cdb439f1dc5fe926a7dd5
- APK structure checked after download: AndroidManifest.xml present,
  6 dex files, launcher icons present, 146 entries total
- The first clean CI run (43693168051) failed compilation on a real code bug:
  three drawCircle calls in ui/Icons.kt passed (color, center, radius) while
  the Compose signature is (color, radius, center). Fixed with named
  arguments; the next run went green. No other compile errors existed.

### What is real in Phase 1

- Full app shell in Kotlin and Jetpack Compose matching the approved AGT UI
  design (matte black, AGT gold, emerald status, hand drawn Canvas icons)
- Foreground service (AgtService) with a persistent notification, START_STICKY,
  heartbeat line written every 30 seconds to the app private heartbeat log
- BootReceiver starts the service on BOOT_COMPLETED
- Reconnector: unlimited reconnection engine, backoff 1s doubling to a 30s cap
  and holding there forever, covered by the 5 unit tests above
- NetworkMonitor on ConnectivityManager callbacks plus a retry loop
- Telemetry from real Android APIs: battery, storage, memory, device uptime
- Console screen reading the real heartbeat log file, auto scrolling
- Home screen: live uptime ticking every second, telemetry grid, three leg
  cards, Resilience strip, Start and Stop service, battery exemption intent
- Launcher icon generated from the owner's Nova N artwork

### Honest limits and later phases

- The three leg cards (Cloudflare Tunnel, Firebase, Supabase) are labelled
  honestly in the app: they report network state only. The legs themselves
  land in Phase 3; nothing in Phase 1 connects to them yet.
- Phase 2: Linux layer (bundled userland, PRoot style sandbox)
- Phase 3: the three outbound legs (tunnel, Firebase doorbell and presence,
  Supabase database of record)
- Phase 4: agent connection (signed jobs from Phoenix, live console)
- Phase 5: deployment engine and the one tap workload templates
- Not yet done: installing this APK on the Redmi and the first run permission
  grants (foreground service, battery exemption) need the owner's hands once.
- This BUILD-LOG.md goes to GitHub with the next code push, to avoid firing
  a docs-only CI run.

## Complete project: Phases 2 to 5 (2026-11-15)

Built in one continuous push on the owner's order ("don't stop anymore...
fully done with the project... everything ready as a complete project").
All five phases are in this tree. CI verification for this push is recorded
below once the run lands.

### Phase 2: Linux layer

- Proot.kt: PRoot runner with a bundled Debian 12 bookworm arm64 rootfs
  (Python 3.11, Node 18, git, curl, ca-certificates pre-installed). The
  installer prefers the rootfs bundled in the APK assets, falls back to
  downloading it in 10 MB parts from the repo and reassembling, and verifies
  sha256 cf221b91bfac1021ea7861b821af94619bd29c60c4edd5ac57321d0602b7d05d
  (101,060,887 bytes) either way. apt's gpgv method inside the rootfs is the
  AGT replacement method (agt-gpgv-method), proven on this VM.
- ProcessSupervisor.kt: start/stop/restart, port health checks, 5s watcher,
  auto-restart with backoff 1s doubling to 30s, restore on app restart.
- Rootfs distribution: the tarball is split into ten 10 MB parts in
  rootfs-parts/ (reassembled sha256 verified). A GitHub release asset was
  tried first and failed: GitHub's uploads host rejects this VM's credential
  (401 on every POST, including a 6-byte probe; api.github.com works). The
  release rootfs-v1 exists (id 445964041) but carries no asset yet; the
  parts in the repo are the working distribution path.
- Native binaries ship in native-binaries/ and CI copies them into
  jniLibs/arm64-v8a as libproot.so / libcloudflared.so after sha256 checks
  (proot fa10b1a7..., cloudflared abe54a00...).

### Phase 3: the three legs

- TunnelLeg.kt: cloudflared tunnel run, token from Settings only, never
  baked into the APK or repo. State machine OFF / STARTING / CONNECTED /
  ERROR with auto-restart.
- FirebaseLeg.kt: RTDB presence (PUT every 30s) plus wake doorbell polling.
  Activates when Phoenix delivers a firebase_token job; until then it
  reports NOT CONFIGURED and the Supabase poll covers the phone.
- SupabaseLeg.kt: PostgREST client for hosts, jobs, deployments,
  deployment_logs. Tables plus RLS plus Realtime applied to the termux-agt
  project (ktbyxyzbqfdhmedkrmpb) 2026-11-15. The anon key is NOT in the
  repo: it is entered once in Settings (new field) or delivered by Phoenix;
  the leg reports NOT CONFIGURED until then.

### Phase 4: agent connection

- DeviceIdentity.kt: Ed25519 key pair generated on the phone, private key
  in app-private storage, public key shown in the app. Two real bugs found
  and fixed before this push: java.util.Base64 rejects unpadded input while
  both encoders strip padding, and Phoenix sends the raw 32-byte public
  key while the code expected X.509. Both forms now decode.
- JobEngine.kt: 15s job poll. Pairing is unsigned and checks the 6-digit
  code; every other job needs a valid Ed25519 signature over the canonical
  JSON form (sorted keys, no whitespace) verified against the paired
  Phoenix key. Kinds: pair, command, deploy, restart, stop, logs, remove,
  firebase_token.
- Phoenix side: ~/workspace/agt-phoenix/agt_jobs.py plus the Phoenix
  keypair (private key 0600, outside the repo). Canonical form verified
  byte-identical against the Kotlin test vector; signatures verify against
  the public key AMW5c64fBaSMK4Tp7sL1qu/J1hIAlzvVKbr0TOu9mrw.
- The live console streams over the same outbound connection; no inbound
  port and no SSH are needed.

### Phase 5: deployment engine and templates

- DeploymentEngine.kt: create from template, git URL (public repos as
  tarballs), or uploaded bundle; delete; snapshots (tar.gz, sanitized
  names). Templates.kt: 7 real zero-install workloads (web server, Telegram
  bot, Discord bot with a vendored pure-Python websocket client,
  generic bot, game server, memecoin watcher, light SHA-256d miner).
- Screens rebuilt to the approved design: Deployments, Deployment Detail
  (live logs, masked secrets), Linux Console (real PRoot bash session plus
  process list), Agent (pairing code, device key, permission scopes, job
  feed), New Deployment (Template / Git URL / Upload), Templates,
  Settings (tunnel token plus Supabase anon key).

### Unit tests added

- AgtCompleteTest: PRoot command builder, git tarball URL forms, canonical
  JSON (python reference vector), Ed25519 sign/verify plus raw-key and
  unpadded-signature decoding, template completeness (7 templates, real
  files). Runs with the existing ReconnectorTest in CI.

### Honest limits at delivery

- In-phone apt-get update lifecycle is untested (the shipped rootfs has the
  core packages pre-installed, so installs do not need it; on this VM apt
  verification works through the AGT gpgv method but the update step hung
  under nested PRoot).
- The Firebase leg activates when Phoenix delivers the firebase_token job;
  the token minting helper is still to be built. Supabase polling covers
  the phone until then.
- The Supabase anon key must be entered once in Settings (or delivered by
  Phoenix) before jobs flow.
- First install downloads nothing extra: the rootfs rides inside the APK
  (about 96 MB), so the APK is large. First run still needs the tunnel
  token in Settings and the owner's first-run permission grants.
- Phone-side acceptance (install on the Redmi, run a Node and a Python
  server inside AGT, restart the app, watch the supervisor restore them,
  deploy from this VM) still needs the owner's hands and the paired phone.
