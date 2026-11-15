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

## Strict hardening round (2026-11-15, v1.3.0)

Owner order: background running must be strictly hardened, more
sensitive than the Termux stack ever was. On top of v1.2.0:

- WiFi lock: the service now holds a low-latency WiFi lock next to
  the partial wake lock (the pairing Termux uses, taken further).
  Both locks are verified every heartbeat and re-acquired on the
  spot if the system drops either; both ride the heartbeat line.
- Watchdog alarm chain (core/Watchdog.kt, policy unit tested): an
  exact alarm fires every 10 minutes; RestartReceiver restarts the
  service if the whole process died silently and re-arms the next
  alarm. The chain starts at boot and at every service start, and a
  deliberate owner stop ends it. The host now has four comeback
  roads: START_STICKY, boot receiver, one-second restart alarm, and
  this watchdog chain.
- Doorbell sensitivity: the Firebase wake and alert poll tightened
  from 30 seconds to 10 seconds (FCM wake remains instant, and the
  job poll still cuts to about 250ms on a ring).
- Redmi/MIUI: Home gained an "Allow autostart (Redmi / MIUI)" button
  deep-linking to MIUI's autostart manager (plain app settings as
  fallback), plus watchdog and autostart rows on the resilience card.
  On a Redmi that MIUI switch is the one gate no code can pass, so
  the app now takes the owner straight to it.

## Design fidelity and zero-setup round (2026-11-15, v1.4.0)

Owner verdict on v1.3.0, with photos: the UI did not match the
approved design, the tunnel leg reported "cloudflared binary missing
from the app install", the Firebase leg sat at TOKEN PENDING, and
Settings asked him to paste the tunnel token and the anon key by
hand. Every one of those was real. Fixes, root cause first:

- Binary missing, root cause: AGP stores jniLibs uncompressed inside
  the APK by default and never extracts them, so
  File(nativeLibraryDir, "libcloudflared.so") does not exist on the
  phone even though the binary is inside the APK (the same latent bug
  waited in libproot.so for the Linux layer). Fix: legacy jniLibs
  packaging in build.gradle.kts, which extracts both binaries to real
  files at install time.
- Zero setup, enforced in code: the Settings paste fields and save
  buttons are deleted. Settings is a status page now. The legs read
  only the values CI bakes from the GitHub repo secrets
  (SupabaseLeg.effectiveKey returns the BuildConfig key; the tunnel
  starts with the BuildConfig token). Stored overrides are ignored.
- Firebase key bootstrap: the Phoenix Ed25519 public key is baked
  into AgtConfig (a public key is not a secret). JobEngine verifies
  signed jobs against the paired key when one is stored and the
  baked key otherwise, so the firebase_token delivery verifies on a
  fresh install with no pairing step gating it. Pairing is now
  optional and the Agent screen says so.
- Linux is part of the app: the service starts itself when the app
  opens, and startAll extracts the bundled rootfs automatically when
  Linux is not installed yet. The Install Linux button is gone; a
  retry appears only after a real install failure.
- Host screen rebuilt to the approved design exactly: shield tile
  header with the AGT. wordmark and a bell button (opens Settings),
  gold-bordered banner (HOST ONLINE or OFFLINE from the service state
  alone, device model pill, gold uptime in the "6d 14:22:08" form;
  device uptime never poses as host uptime again), the three-column
  resilience strip with real states, one CONNECTION LEGS card with a
  live count and real measured latency (tunnel: TCP connect to the
  Cloudflare edge; Firebase: presence write round trip; Supabase:
  host upsert round trip; nothing prints before it is measured), the
  TELEMETRY card with a 3 by 2 tile grid (CPU from /proc/stat deltas,
  memory used, storage free, battery with charge state, battery
  temperature, network link rate and radio label, all read live),
  the four quick actions (Deploy, Console, Snapshot triggers an
  immediate Supabase sync, Tunnel restarts the leg), and the five-tab
  bottom nav (Host, Deploy, Console, Agent, Templates) with new hand
  drawn icons (home, layers, flame, grid, bell, lock, refresh, and
  the rest of the set).
- Telemetry to Phoenix now carries appVersion and firebaseKeySet so
  Phoenix can watch a phone come up on the new build and deliver the
  Firebase key itself.
- Tests: AgtFormatTest adds 7 tests (duration form, latency form,
  CPU sampler math and parsing). Suite is 33 tests.

## Every screen to the design, realtime timing law (2026-11-15, v1.5.0)

Owner order, same day: every screen matches the approved design
exactly, and the whole app runs realtime: heartbeat every 5 seconds,
watchdog every 5 minutes, cron cadence every 2 minutes, real time
notifications and alerts, realtime instant everything.

- Timing law: AgtService heartbeat 30s to 5s (the beat also refreshes
  the live notification, the widget, and verifies both locks).
  WatchdogPolicy.INTERVAL_MS 600000 to 300000 (5 minutes); every copy
  string that said 10 minutes now says 5. The tunnel alert watcher in
  AgtRuntime now polls state every 1s, so a tunnel drop or recovery
  notifies within a second of the transition. Phoenix-side crons for
  this system already run every 2 minutes (key delivery, watchdog).
- Templates screen rebuilt to the design: "One tap deploys" header
  with a working search, the gold low-power info card, and rows with
  icon tiles, the design's per-template blurbs, and a gold Deploy
  action. One tap now really is one tap: the deployment is created
  and started on the spot and opens in its detail screen. Templates
  renamed and re-iconed to the design (Telegram Bot, Discord Bot,
  Bot, Web Server, Mini Game Server, Memecoin Tools, Light Miner)
  with new hand drawn icons (send, chat, robot, globe, gamepad,
  coin, gauge) and a design display order. The Light Miner now paces
  its hash loop to about 30 percent of one core, so its design line
  "CPU capped at 30%" is true.
- Deployments screen rebuilt to the design: "N total, supervised by
  AGT" with working search, cards with per-deployment icons, tinted
  RUNNING / STARTING / STOPPED pills, mono detail lines (port,
  subdomain, restarts, battery guard state), live uptime, round
  restart / stop / start buttons, and the gold New Deployment button
  pinned at the bottom. Refresh is every second.
- Deployment Detail rebuilt to the design: circular back button,
  tinted status pill, the Subdomain / Port / Uptime info card, LIVE
  LOGS with a STREAMING marker streaming every second, ENVIRONMENT +
  SECRETS with the design mask (dots plus the last characters, tap
  to reveal) and its footnote, and the action grid (Restart,
  Snapshot, Backups with a real snapshot list, Stop or Start
  deployment, View in console). The Delete button left the screen to
  match the design; removal still works through Phoenix jobs.
- New Deployment rebuilt to the design: source cards (Git URL,
  Upload bundle, Agent job) with the gold selected state, repository
  URL with a live validity check, name and port side by side, a real
  runtime selector that presets the start command, environment
  variable rows with add and remove, and the two toggles wired for
  real: auto restart (already a supervisor input) and battery guard
  (new: the supervisor holds a crash restart while the battery is
  under 25 percent and not charging, logging the hold instead of
  alerting). The gold button reads "Deploy to this phone" with the
  design caption under it. The Agent job card states the truth:
  Phoenix jobs arrive signed and start on their own.
- Console rebuilt to the design: "Linux Console" with a VM READY
  pill, session tabs (host plus one per deployment, each a real
  shell started in that deployment's directory with its files bound
  in; the + button opens another host session), and the key bar:
  ctrl (the next typed letter goes as a control character), tab,
  esc, paste from the clipboard, and a keys row with real arrow,
  home, and end sequences, all sent raw to the shell via the new
  ShellSession.sendRaw. Proot.startShell learned bind mounts.
- Agent screen rebuilt to the design: Phoenix card (LIVE from the
  real service and Supabase state, channel, Ed25519 job signing,
  inbound ports), the four permission scopes with drawn checks and
  GRANTED markers, RECENT JOB ACTIVITY with real signed job rows,
  relative times, and a FULL LOG expander, and the gold "Open live
  console session" button. The gear opens Settings, where the
  pairing code and device public key now live (AGENT IDENTITY card).
- Icons: 20 new hand drawn kinds (the seven above plus search, key,
  gear, chevrons, check, upload, branch, play, server, square,
  close). No emoji, no stock glyphs, the standing rule.
- Tests: watchdog interval assertion updated to 300000; AgtFormatTest
  adds uptime-short, relative-age, secret-mask, and slug/subdomain
  tests. Suite is 37 tests.
- Honest residue, stated before shipping: the subdomain shown on the
  cards is the design's address form (name.agt.novamail.store),
  derived from the deployment name. Per-deployment subdomain routing
  through the tunnel is not wired yet; today every deployment rides
  the one tunnel hostname and its own port. Wiring wildcard routing
  is a separate step on the owner's word.

## Instant Firebase wiring round (2026-11-15, v1.6.0)

Owner's ruling, in his words: everything wired together inside the
code, working instantly the moment the app installs and launches,
and his instant realtime law over every mechanism (watchdog, crons,
heartbeat, background, foreground, wake locks). Reading the v1.5.0
leg honestly found two defects in the Firebase key path:

1. The key was delivered by a cron watcher that only ticked every
   2 minutes, so a fresh install could wait minutes for its key.
2. A Firebase custom token dies 1 hour after it is minted, and
   nothing anywhere refreshed it: the leg would have failed against
   RTDB forever about an hour after the key finally landed, while
   telemetry kept claiming a key was set because it only checked
   that a token string existed.

Fixes, all in the wiring, no UI change at all:

- Phoenix side: the cron watcher is retired. An always-on keeper
  daemon (systemd service agt-firebase-keeper) polls the phone's
  Supabase host row every 2 seconds and mints plus sends a fresh
  signed firebase_token job the moment the phone reports in without
  a usable key, re-mints every 40 minutes while the phone is active
  so the phone always holds a key with at least about 20 minutes of
  life, and replaces an undelivered queued key job older than
  30 minutes with a fresh mint. A cron guard keeps the keeper itself
  alive across VM replacements and reports DELIVERED or GIVE_UP
  once. Proven end to end against a synthetic host before install:
  key job landed, claims correct (uid, 3600s lifetime), in-flight
  guard held, delivery confirmation recorded.
- JobEngine: a new onFirebaseTokenSaved hook fires the moment a
  firebase_token job saves its key.
- FirebaseLeg: AgtRuntime wires that hook to refreshNow(), so the
  leg restarts and connects with the new key in the same second.
  The not-configured recheck drops from 30s to 2s. The leg now
  reads the expiry inside the stored token (tokenExpiryMs, unit
  tested): a dead key is treated as no key, never as an RTDB
  failure, and a changed key drops the cached ID token so the next
  exchange uses the new key.
- Telemetry: firebaseKeySet now means a usable key (stored, not
  expired, leg connected), which is the signal the keeper mints on.
- Tests: FirebaseTokenExpiryTest adds 4 tests (expiry read, past
  expiry, missing exp, malformed tokens). Suite is 41 tests.
