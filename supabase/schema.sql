-- AGT Supabase schema for the termux-agt project (kilomene1960 org).
-- Tables: hosts, jobs, deployments, deployment_logs.
-- RLS enabled; the app talks through the anon key with scoped policies.
-- Realtime enabled on jobs so the phone and Phoenix see job flow live.

create extension if not exists pgcrypto;

create table if not exists hosts (
  id uuid primary key default gen_random_uuid(),
  device_id text unique not null,
  name text not null default 'AGT host',
  public_key text,
  last_seen timestamptz,
  online boolean not null default false,
  telemetry jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table if not exists jobs (
  id uuid primary key default gen_random_uuid(),
  host_id uuid references hosts(id) on delete cascade,
  kind text not null,
  payload jsonb not null default '{}'::jsonb,
  signature text,
  status text not null default 'queued',
  result jsonb,
  created_at timestamptz not null default now(),
  claimed_at timestamptz,
  completed_at timestamptz
);
create index if not exists jobs_host_status_idx on jobs(host_id, status, created_at);

create table if not exists deployments (
  id uuid primary key default gen_random_uuid(),
  host_id uuid references hosts(id) on delete cascade,
  name text not null,
  kind text not null,
  source text,
  config jsonb not null default '{}'::jsonb,
  status text not null default 'stopped',
  port integer,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists deployments_host_idx on deployments(host_id, created_at);

create table if not exists deployment_logs (
  id bigint generated always as identity primary key,
  deployment_id uuid references deployments(id) on delete cascade,
  line text not null,
  ts timestamptz not null default now()
);
create index if not exists deployment_logs_dep_idx on deployment_logs(deployment_id, ts);

alter table hosts enable row level security;
alter table jobs enable row level security;
alter table deployments enable row level security;
alter table deployment_logs enable row level security;

drop policy if exists hosts_anon on hosts;
create policy hosts_anon on hosts for all to anon using (true) with check (true);
drop policy if exists jobs_anon on jobs;
create policy jobs_anon on jobs for all to anon using (true) with check (true);
drop policy if exists deployments_anon on deployments;
create policy deployments_anon on deployments for all to anon using (true) with check (true);
drop policy if exists deployment_logs_anon on deployment_logs;
create policy deployment_logs_anon on deployment_logs for all to anon using (true) with check (true);

alter publication supabase_realtime add table jobs;
alter publication supabase_realtime add table deployments;
