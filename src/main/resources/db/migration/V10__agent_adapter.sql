-- No child/receipt/star ledger is duplicated here. Private immutable generation inputs/results only.
create table learning.agent_source (
 id uuid primary key,child_id uuid not null references care.child(id),requester_id uuid not null,
 input_revision bigint not null default 1,authority_revision bigint not null,settings_ref uuid not null,
 actor_json jsonb not null,snapshot_json jsonb not null,input_json jsonb not null,
 created_at timestamptz not null default now()
);
create table learning.agent_artifact (
 id uuid primary key,source_ref uuid not null references learning.agent_source(id),
 bytes_hash char(64) not null,result_json text not null,summary_refs jsonb not null,
 created_at timestamptz not null default now()
);
