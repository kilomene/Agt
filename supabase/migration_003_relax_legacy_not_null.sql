-- AGT migration 003: three legacy columns from the old Termux-AGT schema
-- are NOT NULL with no default, which rejects AGT host and deployment
-- upserts. Relaxing them changes nothing for the old system (it always
-- writes them) and lets the AGT rows in. Applied live 2026-11-15.
alter table hosts alter column token_hash drop not null;
alter table deployments alter column project_id drop not null;
alter table deployments alter column version drop not null;
