-- AGT migration 002: the termux-agt project already carried the old
-- Termux-AGT hosts and deployments tables, so the AGT columns are added
-- additively. Nothing is dropped or renamed; old rows keep working and
-- old columns stay untouched. Applied live 2026-11-15 and verified
-- through PostgREST with the anon key (upsert, heartbeat, delete).
alter table hosts add column if not exists device_id text;
alter table hosts add column if not exists public_key text;
alter table hosts add column if not exists online boolean not null default false;
alter table hosts add column if not exists telemetry jsonb not null default '{}'::jsonb;
create unique index if not exists hosts_device_id_key on hosts (device_id);

alter table deployments add column if not exists name text;
alter table deployments add column if not exists kind text;
alter table deployments add column if not exists source text;
alter table deployments add column if not exists config jsonb not null default '{}'::jsonb;
alter table deployments add column if not exists port integer;
