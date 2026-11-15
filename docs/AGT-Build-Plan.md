# AGT Build Plan

Name: AGT (owner's ruling, 2026-11-14)
Design: Termux-AGT-Native-App-Architecture.md
Standard: extremely advanced, heavily featured, end to end functional, per
the owner's standing advanced standard. Everything runs at zero cost on
free tiers.

## The requirement above all: the agent connects easily

AGT is built so Phoenix connects to it without friction. The app registers
with a device key pair, holds an outbound channel to the cloud, and takes
signed jobs from Phoenix: deploy, command, file transfer, logs, restart.
Phoenix can open a live console session streamed over that outbound
channel. No inbound port, no SSH, no tunnel needed for the agent path, so
it works behind any NAT. Authorized agents deploy through the same
channel, each with its own scope.

## Phase 0: Foundations

- Create the dedicated Firebase project on the Spark plan (doorbell and
  presence only, never the database of record, never auth).
- Supabase additions on the existing termux-agt project (ref
  ktbyxyzbqfdhmedkrmpb): hosts, jobs, deployments, and deployment log
  tables; row level security; Realtime enabled on the jobs table.
- Repo and branding: AGT name, icon, Nova identity.
- Acceptance: schema live; a test job row can be written by Phoenix and
  read back by an authenticated client.

## Phase 1: The app shell that cannot die

- Native Android app (Kotlin): foreground service, boot receiver, polite
  wake locks, battery optimization exemption requested at first run with a
  clear in app explanation.
- Self update: the app checks Supabase Storage for a signed newer build
  and walks the owner through the Android install confirmation, so fixes
  never need a trip to the phone after the first install.
- Home screen: host status, uptime, and the state of all three legs.
- Reconnection engine: unlimited retries on every leg with capped
  backoff, racing the legs against each other. Connection loss is a
  state, never a stop.
- Lock independence: every service runs with the screen off and the phone
  locked. Only sensitive in app screens require unlock.
- Acceptance: reboot the phone; AGT returns on its own and reports
  presence within one minute, proven on the Redmi.

## Phase 2: The Linux layer

- Bundle a Debian class rootfs; first run extracts it into app storage.
- PRoot style sandbox runner: shell sessions, background processes,
  package installs, Node and Python runtimes.
- Process table in the app: every Linux process visible, with logs and
  controls.
- Later accelerator: Android Virtualization Framework VM path on phones
  that support it.
- Acceptance: inside AGT, run Node and Python servers, install a package,
  restart the app, and watch the supervisor restore the processes.

## Phase 3: The three legs

- Tunnel leg: bundled cloudflared, same tunnel and hostname
  (termux.novamail.store), so existing URLs keep working.
- Firebase leg: FCM high priority wake plus RTDB presence with
  onDisconnect; heartbeats small and steady.
- Supabase leg: database sync, job polling as a fallback, and the Realtime
  channel that carries live sessions.
- Acceptance: kill any one leg; the other paths still reach the phone, and
  the dashboard shows each leg's true state.

## Phase 4: Agent connection (Phoenix first)

- Host registration: AGT generates its device key pair on the phone; the
  public key is the host identity in Supabase.
- Job channel: Phoenix writes signed jobs to the jobs table; Firebase
  rings; AGT executes inside the Linux layer; results stream back over
  Realtime.
- Live console: an interactive shell session streamed over the outbound
  connection, usable from this VM.
- Deploy API for agents: the same channel accepts deployments from
  authorized agents, signed and scoped per agent.
- Acceptance: from this VM, Phoenix deploys a test service to the phone,
  opens a live console, pulls its logs, and removes it, all without
  touching the phone.

## Phase 5: The deployment engine

- Supervisor with health checks and automatic restart; per deployment
  directory, environment, secrets, port, and subdomain.
- Scheduled jobs with battery aware deferral.
- Snapshots and encrypted backups to cloud storage on a schedule.
- One tap templates for the owner's named workloads: Telegram bot,
  Discord bot, generic bot, lightweight web server, mini game server,
  memecoin tools, and a light crypto miner, each pre tuned for low power
  and low bandwidth.
- Acceptance: the meme bot and the existing API run as AGT deployments for
  48 hours with zero manual touches.

## Phase 6: Surfaces

- Cloud dashboard upgrade: hosts, deployments, live logs on demand, a
  console, and telemetry (battery, storage, temperature, network).
- In app console finished to the advanced standard: every screen live, no
  placeholder controls.
- Motion design is a requirement, not decoration (owner's ruling,
  2026-11-14: a well designed animated app, never static): animated screen
  transitions, pulsing presence and leg indicators, animated telemetry,
  streaming logs, and micro interactions on every control.
- Design match law (owner's order, 2026-11-14, strict): everything built
  matches the approved AGT UI design exactly (artifact agt-app-ui-design):
  matte black, AGT gold, emerald status, drawn SVG icons, the same motion
  system. No different look is introduced without his word.
- Acceptance: full control of the phone host from the dashboard with the
  phone screen off.

## Phase 7: Migration and hardening

- Move production services from the Termux stack to AGT deployments. Keep
  Termux installed but idle as a fallback for one week, then retire it.
- The watchdog on this VM is retargeted to AGT health and presence, under
  the same escalation dedup discipline.
- Security pass: signed jobs verified on the phone, secrets never leave
  the phone unencrypted, per agent scopes enforced.
- Final soak: at least 30 minutes of clean live operation before it is
  called done, per the build test discipline, then a 7 day watch.

## What the owner does, total

Installs AGT once on the Redmi and grants the first run permissions
(foreground service, battery exemption, storage). Everything after that is
Phoenix's work.
