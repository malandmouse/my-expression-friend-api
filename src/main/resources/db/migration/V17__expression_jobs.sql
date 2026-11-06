-- Uses existing expression candidates/cost ledger; no separate child/completion ledger.
create table learning.prototype_expression_job (
 id uuid primary key,child_id uuid not null references care.child(id),session_id uuid not null references learning.session(id),
 slot integer not null check(slot between 1 and 5),attempt_id uuid not null,draft_version bigint not null,
 kind text not null check(kind in ('OPTIONS','EXPANSION')),context_hash char(64) not null,original_hash char(64) not null,
 key_digest text not null,request_digest text not null,state text not null check(state in ('QUEUED','RUNNING','COMPLETED','FAILED','OUTCOME_UNKNOWN','EXPIRED')),
 encrypted_request bytea,result_ref uuid references learning.prototype_expression_candidate(id),error_code text,
 created_at timestamptz not null,expires_at timestamptz not null,
 unique(session_id,slot,kind,key_digest)
);
create index expression_job_lookup on learning.prototype_expression_job(session_id,slot,kind,request_digest);
