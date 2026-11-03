create table learning.generation_job (
    id uuid primary key,
    child_id uuid not null references care.child(id),
    requester_id uuid not null,
    kind text not null check (kind in ('AUTHORING', 'REACTION_MEDIA')),
    source_ref uuid not null,
    receipt_id uuid generated always as (case when kind = 'REACTION_MEDIA' then source_ref end) stored
        references learning.receipt(id),
    input_revision bigint not null check (input_revision > 0),
    authority_revision bigint not null check (authority_revision > 0),
    settings_ref uuid not null,
    reaction_ref uuid,
    reaction_text_hash text check (reaction_text_hash ~ '^[0-9a-f]{64}$'),
    reaction_speaker_id text check (length(reaction_speaker_id) > 0),
    business_fingerprint text not null check (business_fingerprint ~ '^[0-9a-f]{64}$'),
    retain_until timestamptz,
    deadline timestamptz not null,
    created_at timestamptz not null,
    state text not null default 'QUEUED'
        check (state in ('QUEUED', 'RUNNING', 'RESULT_READY', 'HELD', 'FAILED', 'CANCELLED', 'OUTCOME_UNKNOWN')),
    version bigint not null default 1 check (version > 0),
    artifact_id uuid,
    artifact_hash text check (artifact_hash ~ '^[0-9a-f]{64}$'),
    check (deadline > created_at and retain_until > created_at),
    check ((artifact_id is null) = (artifact_hash is null)),
    check ((state = 'RESULT_READY') = (artifact_id is not null)),
    check ((kind = 'REACTION_MEDIA') = (reaction_ref is not null)),
    check ((reaction_ref is null) = (reaction_text_hash is null)),
    check ((reaction_ref is null) = (reaction_speaker_id is null))
);

create index generation_job_business_lookup
    on learning.generation_job (child_id, kind, business_fingerprint, created_at desc);

create unique index generation_job_unresolved_business
    on learning.generation_job (child_id, kind, business_fingerprint)
    where retain_until is null or state in ('QUEUED', 'RUNNING', 'OUTCOME_UNKNOWN');

create unique index generation_job_one_reaction_per_receipt
    on learning.generation_job (receipt_id) where receipt_id is not null;
