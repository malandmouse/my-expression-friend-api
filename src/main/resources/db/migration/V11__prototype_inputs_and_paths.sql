-- Additive prototype inputs. Existing account, publication, receipt and star ledgers remain authoritative.
create table care.scenario_profile (
 child_id uuid primary key references care.child(id), revision bigint not null check(revision>=1),
 settings jsonb not null, updated_at timestamptz not null
);
create table care.daily_event_revision (
 child_id uuid primary key references care.child(id), revision bigint not null default 0
);
create table care.daily_event (
 id uuid primary key,child_id uuid not null references care.child(id),version bigint not null default 1,
 occurred_on date not null, text varchar(500) not null, reported_feeling varchar(120),
 created_at timestamptz not null,updated_at timestamptz not null,deleted_at timestamptz
);
create index daily_event_child_date on care.daily_event(child_id,occurred_on desc,id);
-- No free child text: only server-validated, actually completed graph paths and approved NPC lines.
create table learning.receipt_path (
 receipt_id uuid primary key references learning.receipt(id), child_id uuid not null references care.child(id),
 scenario_version_id uuid not null, terminal_scene_id text not null,npc_id text not null,
 choice_ids jsonb not null,scene_ids jsonb not null,heard_lines jsonb not null,recorded_at timestamptz not null
);
create index receipt_path_child on learning.receipt_path(child_id,recorded_at,receipt_id);
create table learning.prototype_calendar (
 singleton boolean primary key default true check(singleton),effective_date date not null,version bigint not null
);
