-- Encrypted, short-lived expression candidates. This is not a second completion or child ledger.
create table learning.prototype_expression_call (
 id uuid primary key,child_id uuid not null references care.child(id),session_id uuid not null references learning.session(id),slot integer not null,
 local_date date not null,kind text not null,mode text not null,key_digest text not null,request_digest text not null,
 business_digest text not null,state text not null check(state in ('RESERVED','COMPLETED','FAILED','OUTCOME_UNKNOWN')),
 input_reserved bigint not null,output_reserved bigint not null,cost_reserved bigint not null,
 input_used bigint,output_used bigint,cost_used bigint,provider_request_id text,response_ref uuid,
 created_at timestamptz not null,settled_at timestamptz,
 unique(session_id,slot,kind,key_digest)
);
create unique index prototype_expression_unresolved_business on learning.prototype_expression_call(session_id,slot,kind,business_digest)
 where state in ('RESERVED','COMPLETED','OUTCOME_UNKNOWN');
create table learning.prototype_expression_candidate (
 id uuid primary key,call_id uuid not null unique references learning.prototype_expression_call(id),
 session_id uuid not null references learning.session(id),slot integer not null,attempt_id uuid not null,
 kind text not null check(kind in ('OFFER','CANDIDATE')),draft_version bigint not null,
 context_hash char(64) not null,original_hash char(64) not null,encrypted_body bytea not null,
 expires_at timestamptz not null,created_at timestamptz not null
);
alter table learning.prototype_expression_call add foreign key(response_ref) references learning.prototype_expression_candidate(id);
create table learning.prototype_expression_proof (
 id uuid primary key,session_id uuid not null references learning.session(id),slot integer not null,
 actor_id text not null,draft_version bigint not null,expression_choice text not null check(expression_choice in ('ORIGINAL','AI_COMPOSED')),
 candidate_id uuid references learning.prototype_expression_candidate(id),context_hash char(64) not null,
 reaction_text varchar(500) not null,expires_at timestamptz not null,created_at timestamptz not null
);
create table learning.prototype_reaction (
 receipt_id uuid primary key references learning.receipt(id),child_id uuid not null references care.child(id),
 actor_id text not null,text varchar(500) not null,text_hash char(64) not null,mode text not null,
 state text not null check(state in ('PENDING','RESERVED','READY','FAILED','OUTCOME_UNKNOWN')),
 asset_id uuid unique references learning.core_artifact(id),audio_hash char(64),byte_length integer,duration_ms integer,
 error_code text,reserved_at timestamptz,completed_at timestamptz
);
