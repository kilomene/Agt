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

## Finishing round: wake lock, widget, real-time alerts (2026-11-15)

Owner asked for three finishing features after the complete build; all
three are in, built, and CI-verified (run 43738218110, head 9379f322,
14 unit tests, 0 failures).

- Wake lock: AgtService now acquires a PARTIAL_WAKE_LOCK
  (tag AGT::HostWakeLock) on start and holds it for the whole service
  life, released exactly once in onDestroy. The screen can lock; the
  CPU keeps serving. State is exposed as AgtService.wakeLockHeld and
  written into every heartbeat line.
- Home screen widget (ui/AgtWidget): matte black, gold wordmark,
  emerald/danger/dim status light, in the approved design language.
  Shows service state, running deployments, tunnel state, hostname,
  wake lock state, and update time. Reads the store directly (truth
  even when the service is stopped), refreshed by the service on every
  30s heartbeat plus the system 30 min backstop. Tap opens the app.
- Real-time alerts (core/AlertNotifier, high importance channel
  agt_alerts): the supervisor fires on deployment down, back up after
  crash restart, and stopped with auto-restart off; the runtime fires
  on tunnel down and tunnel back up; Phoenix can push an alert through
  the Firebase doorbell node /agt/hosts/<id>/alert.json
  ({"at","title","message"}) and it lands as a phone notification.
  Local-first by design: the phone is the server, so it is the first
  to know. True FCM push still needs the app registered in the
  Firebase console (google-services.json) plus the Phoenix token
  minter; the RTDB alert node covers the same path today.

Also fixed in this round: the bundled rootfs rides as
assets/debian-bookworm-agt-arm64.bundle (101,060,887 bytes, sha256
cf221b91bfac1021ea7861b821af94619bd29c60c4edd5ac57321d0602b7d05d,
verified byte-identical inside the final APK), because aapt silently
decompressed the .tar.gz asset and stripped its extension, which
would have broken the bundled install path.

Final APK: 154,407,182 bytes, sha256
24da8030851f15e850be096911ee23b44e06d92c7a43515c80158fed3ccb2455.

## FCM round (2026-11-15): real push, secrets in CI, live schema fixed

Owner order: FCM must be real, and the tunnel token and Supabase key
belong in GitHub repo secrets, injected at build time, not pasted into
the app by hand. Done in that shape:

- Firebase: the AGT Android app (com.novainc.agt) is registered in
  project agentlink-84609 through the Firebase Management API using the
  project service account. App id
  1:997851914896:android:c8c7e0cab334c28c202e15. The real
  google-services.json was fetched from the same API.
- App: Firebase BoM 33.3.0, firebase-messaging and firebase-database,
  google-services plugin, and a real AgtMessagingService
  (MESSAGING_EVENT). Notification pushes land through AlertNotifier on
  the high importance channel; data-only {type: wake} pushes start the
  host service. onNewToken stores the registration token and publishes
  it at once; the token also rides every Supabase heartbeat in
  telemetry.fcmToken, which is the address Phoenix pushes to.
  FcmPayload resolution is unit tested (5 new tests).
- Secrets: SUPABASE_ANON_KEY, TUNNEL_TOKEN and GOOGLE_SERVICES_JSON_B64
  are set as GitHub repo secrets (verified in the secrets list). CI
  materializes app/google-services.json from the secret (gitignored,
  never committed) and passes the other two as build env; Gradle writes
  them into BuildConfig, and AgtConfig reads them from there. Settings
  entries still override the baked values. The app now works on first
  launch with nothing to paste.
- Supabase: the real anon key was read from the dashboard (kilomene1960
  org, termux-agt project) and proven against PostgREST. That check
  exposed a real defect: the live hosts and deployments tables are the
  old Termux-AGT tables, so AGT upserts would have failed on the phone.
  Migration 002 adds the AGT columns to both tables (device_id with a
  unique index, public_key, online, telemetry; name, kind, source,
  config, port) and migration 003 relaxes three legacy NOT NULL columns
  with no defaults (hosts.token_hash, deployments.project_id,
  deployments.version). Both applied live. The full app path was then
  proven with the anon key alone: host upsert, heartbeat patch,
  deployment upsert, job insert and poll, cleanup deletes, all green.
- Firebase RTDB: rules gained an agt/hosts/$device section (the owning
  device, auth.uid equal to the device id, may read and write its own
  node); the seven AgentLink sections are untouched, and an
  unauthenticated write probe is denied with 401. FirebaseLeg now
  exchanges the Phoenix-minted custom token for an ID token at the
  Identity Toolkit endpoint (cached 50 minutes, direct use as fallback).
- Phoenix side: agt-phoenix/fcm_send.py sends real FCM HTTP v1 pushes
  with the service account (by token, or by device id with the token
  read from Supabase). Its self-test reached Google and got
  INVALID_ARGUMENT for a bogus token, proving auth, project and
  endpoint wiring before any phone exists. agt-phoenix/
  mint_firebase_token.py mints the RTDB custom token and delivers it as
  a signed firebase_token job; agt_jobs.py now falls back to the stored
  anon key file so the tooling runs without env setup.
- Still phone-bound: an FCM push can only land once the app is
  installed and has registered its token. Everything server-side is
  built and proven up to that exact boundary.

## Sensitivity and notifications round (2026-11-15, v1.2.0)

Owner order: more notification types, and the host must be extremely
sensitive and responsive in foreground and background, with the wake
lock truly working and everything in real time.

Notifications are now a catalogue (core/NotifyKind.kt, unit tested):
five kinds, each owning its own Android channel with its own
importance, so the owner can tune each family in system settings.
- critical (HIGH): the host is hurt. Deployment crash and restart,
  tunnel down.
- alert (HIGH): Phoenix pushed alerts (FCM or the RTDB doorbell, both
  carry a kind field now) and job failures.
- security (HIGH): pairing succeeded or failed, rejected signatures.
- job (DEFAULT): agent job completions.
- status (LOW, silent): recoveries. Deployment back up, tunnel back up.
Every notification uses expanded big text and carries an Open AGT
action. Phoenix side, fcm_send.py gained a --kind flag.

Responsiveness:
- The job poll loop no longer sleeps blindly for 15 seconds: it waits
  in 250ms slices, and a doorbell ring or FCM wake calls
  JobEngine.requestPoll(), cutting pickup to about a quarter second.
  pollJobsOnce() is a real trigger now, not a comment.
- Job lifecycle events feed the catalogue: done, failed (with the
  error text), pairing outcomes as security events.

Wake lock and persistence:
- The heartbeat now verifies the wake lock every 30 seconds and
  re-acquires it on the spot if the system ever dropped it, logging
  the re-acquisition. The lock state already rides every heartbeat
  line and the Settings host card.
- The service has three comeback roads: START_STICKY, a boot
  receiver, and a new one-second restart alarm (RestartReceiver)
  scheduled on task removal and on destroy. All three respect the
  owner's deliberate stop (AgtService.userStopped), so swiping the app
  away or a system kill is a blink, while a real stop stays stopped.
- The persistent foreground notification is a live instrument now:
  every heartbeat refreshes its line with the running deployment
  count and tunnel state.
- Battery exemption was already in place (permission, status and the
  request button on Home); MIUI autostart remains a phone-side toggle
  the owner controls.
