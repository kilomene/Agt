# Termux-AGT Native App Architecture

Status: decided by the owner, 2026-11-14. This replaces the Termux design.
The mobile app is the server. Termux is gone.
The app is named AGT (owner's ruling, 2026-11-14). Build plan:
AGT-Build-Plan.md.

## The decision in one paragraph

A native Android app replaces the whole Termux stack (API, worker process,
supervisor, cloudflared inside Termux, sshd). The app runs as a foreground
service, starts on boot, and holds every outbound connection itself.
Cloudflare keeps the tunnel and DNS. Firebase carries the real time
notification work. Supabase stays the database, exactly as before (project
termux-agt, ref ktbyxyzbqfdhmedkrmpb). No platform is leaned on hard enough
for its free tier limits to stop the system: the load is deliberately split
across all three, and the heavy compute runs on the phone, which has no
meter at all.

## Roles

### The phone app (native Android)

- The server. Hosts the API locally inside the app and runs the jobs.
- Foreground service plus boot start: Android killing the app is the failure
  that caused the month long Termux outage, so staying alive is a core
  feature, not an afterthought.
- Holds three outbound legs: the Cloudflare tunnel connection, the Firebase
  channel (FCM plus RTDB presence), and the Supabase connection.
- Answers commands through its own channel. No sshd, no Termux.

### Cloudflare: tunnel and DNS only

- The tunnel is the bulk public entry: callers reach the app through
  termux.novamail.store.
- The tunnel itself has no daily request cap, which is why bulk traffic
  rides it. The daily caps live in Workers (100k requests a day on the free
  plan), so Workers stay out of the bulk path.
- No Cloudflare Worker is part of this design.

### Firebase: real time notification (free Spark plan)

- FCM is the doorbell. It is the only channel that can reliably wake a
  sleeping Android app, because it rides the Google Play Services
  connection that the OS does not kill.
- RTDB is presence: the cloud knows the moment the phone comes online or
  drops, using onDisconnect.
- Small realtime signals only. Payloads stay tiny, because Firestore free
  quotas are daily and RTDB free egress is a monthly pool (10 GB) with a
  100 connection cap.
- Firebase is never the database of record and never the auth system. That
  discipline carries over from AgentLink.

### Supabase: the database (existing project)

- Postgres stays the database of record, as it was under Termux.
- Edge Functions are a scarce budget (500k calls a month on the free tier),
  spent only where a public door is truly needed. The design does not lean
  on them.
- Egress is the meter to protect (5 GB a month on the free tier). Rules:
  no polling (push through Firebase instead), small payloads, pagination,
  results cached on the phone.
- The log ingestion path is never touched: log drains are a paid feature
  and a fast way to make the free tier demand payment. Logs live on the
  phone and are pulled on demand through the tunnel.

## Hosting power: the Linux layer and the advanced feature set

Owner's ruling, 2026-11-14: the app does not narrow what the phone can
host. It carries a Linux layer and hosts anything, and the hosting is to be
extremely powerful, not basic.

- Linux layer: a full Linux userland bundled inside the app (Debian class
  rootfs), running without root through a PRoot style sandbox, so arbitrary
  Linux programs run: Node, Python, native binaries, background daemons,
  the meme bot, agent deployments. On phones that support hardware
  virtualization (Android Virtualization Framework), the same layer can
  run in a real VM for near native speed. The sandbox path works on every
  phone; the VM path is an accelerator where available.
- Deployments: install from a git URL, an uploaded bundle, or an agent
  issued deployment job arriving through the Supabase queue with a Firebase
  ring. Each deployment gets its own directory, environment, secrets, port,
  and public route through the tunnel. Deployments serve HTTP on their
  assigned port, the same contract as the old Termux one shots.
- Supervision: every deployment is supervised: automatic restart on crash,
  health checks, and start, stop, and restart from the dashboard or from
  the app itself, under the same never stay dead discipline as the app.
- Scheduling: cron style scheduled jobs inside the Linux layer, plus
  battery aware deferral: heavy work waits for charging or a healthy
  battery unless it is marked urgent.
- Storage: persistent volumes per deployment, snapshots, and encrypted
  backup to cloud storage under the egress discipline (scheduled and
  compressed, never a constant stream).
- Networking extras: WebSocket support, a subdomain per deployment, and
  room for more phones to join later as extra hosts, all reporting presence
  through Firebase.
- Control surface: the cloud dashboard manages deployments (deploy, logs
  on demand, restart, remove) by sending jobs through Supabase with a
  Firebase ring. The app also carries its own console for working on the
  phone directly.
- Phone telemetry: battery, storage, temperature, and network state ride
  the tiny presence payloads, so a struggling phone is visible before it
  goes silent.
- Workload catalog (owner's list, 2026-11-14): AGT acts as a VPS. Named
  workloads: Telegram bots, Discord bots, bots generally, lightweight web
  servers, mini game servers, memecoin tooling (the meme bot class of
  jobs), light crypto mining, and anything else that can be hosted.
- Optimization law (owner, 2026-11-14): every workload runs optimized:
  low power, low bandwidth, in the background, with no battery restriction
  holding it back. The mechanism: the app takes the battery optimization
  exemption at first run and runs as a foreground service; deployments
  share pooled connections instead of polling; payloads stay small by
  design across all three platforms.

## The load splitting law

No single platform's meter can strangle the system.

- Bulk HTTP traffic rides the tunnel: unmetered.
- Notifications and presence ride Firebase: tiny payloads only.
- Data rides Supabase: with the egress discipline above.
- Function work is split thin across Supabase Edge Functions and Firebase
  functions, and every cloud function stays thin: route, queue, notify.
- Real compute runs on the phone. The phone is the server, and its compute
  is free and unmetered. This is the deepest part of the diversification.

## How requests flow

1. Normal path: a caller hits termux.novamail.store, the request rides the
   tunnel straight to the app, the app answers.
2. Tunnel leg down: the caller reaches a thin Edge Function instead. It
   writes the request as a job in Supabase, Firebase rings the phone, the
   app wakes, runs the job, writes the result back, and the caller gets
   the answer. Slower, but nothing is refused.
3. Dashboard: the website stays hosted in the cloud and reads Supabase plus
   Firebase presence. It keeps working even when the phone is offline; it
   simply shows the phone as offline.

## Failure behaviour

Any one leg can die and the system degrades instead of dying:

- Tunnel down: the queue and doorbell path carries traffic.
- Firebase down: the tunnel path still works; wake up falls back to a low
  frequency Supabase Realtime check.
- Supabase down: the app keeps serving what it holds locally and syncs when
  Supabase returns.

## Never goes dark

Owner's requirements, 2026-11-14 (the same law he set for AgentLink:
unlimited retry to connect):

- Unlimited reconnection: if the internet drops, AGT reconnects forever.
  Backoff grows to a cap and then holds there, retrying without limit and
  racing all three legs, so whichever path returns first wins. There is no
  attempt count at which it gives up.
- Background and foreground: AGT runs as a foreground service and keeps
  working in the background. The visible notification is its badge of
  life, not a requirement to stay on screen.
- Locked phone: screen lock changes nothing. The service, the three legs,
  the deployments, and the reconnection engine all run with the screen off
  and the phone locked. Only sensitive in app screens ask for unlock.

## Agent connection

Owner's requirement, 2026-11-14: Phoenix must be able to connect to AGT
easily. AGT registers with a device key pair generated on the phone and
holds an outbound Realtime channel to Supabase. Phoenix sends signed jobs
(deploy, command, file transfer, logs, restart) through the Supabase queue
with a Firebase ring, and can open a live console session streamed over
that same outbound connection. No inbound port and no SSH are needed, so
the connection works behind any NAT. Authorized agents deploy through the
same channel, each with its own scope.

## What goes away

Termux, the Termux supervisor, sshd, and the phone side Node stack. The
watchdog's phone side failure mode (nothing restarts until a human opens
Termux) is designed out: the app starts itself on boot and keeps itself
alive as a foreground service.

## Open questions

1. RESOLVED 2026-11-14: the app carries a Linux layer and hosts anything,
   with the advanced feature set above. Nothing the Termux stack could
   host is lost.
2. Firebase project: a dedicated new project for Termux-AGT on the Spark
   plan is the clean option.
3. First install still needs hands on the phone once. After that, the app
   maintains itself.
