# Agt

Agt is a Nova Inc project. A native Android app that turns a phone into a
powerful Linux hosting server: a VPS in an app.

Agt replaces the Termux based Termux-AGT stack. The phone is the server:
it carries a full Linux layer and hosts Telegram bots, Discord bots,
lightweight web servers, mini game servers, memecoin tooling, light
crypto mining, agent deployments, and anything else that can be hosted,
all optimized for low power and low bandwidth, running in the background
with no battery restriction, reconnecting without limit, and working with
the phone locked.

## The three legs

- Cloudflare: tunnel and DNS, the bulk public entry.
- Firebase: real time notification (the doorbell) and presence only.
- Supabase: the database of record and the agent job channel.

## Agent connection

Agents connect to Agt easily: the app registers with a device key pair
and holds an outbound channel, so Phoenix and authorized agents deploy,
run commands, transfer files, and open live console sessions with no
inbound port and no SSH.

## Design

Every surface matches the approved AGT UI design: dark premium theme,
matte black, AGT gold, emerald status light, drawn icons, and a living
motion system. Nothing static.

## Docs

- docs/Termux-AGT-Native-App-Architecture.md: the decided architecture.
- docs/AGT-Build-Plan.md: the phased build plan.

## Status

Build started 2026-11-14. Phase 1 (the app shell) in progress.
