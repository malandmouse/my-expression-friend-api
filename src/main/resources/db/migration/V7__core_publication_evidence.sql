-- Append-only version evidence. No V6 report is promoted or rewritten.
create table learning.core_artifact (
    id uuid primary key,
    kind varchar(16) not null check(kind in ('POLICY','PERSONA','CATALOG','MEDIA')),
    source_id varchar(256) not null,
    bytes bytea not null check(octet_length(bytes) between 1 and 10485760),
    bytes_hash char(64) not null check(bytes_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null,
    unique(kind,source_id)
);
create table learning.core_dependency_validation (
    id uuid primary key,
    material_id uuid not null references learning.core_manuscript(material_id),
    payload_hash char(64) not null,
    manifest_hash char(64) not null,
    manifest text not null,
    paths jsonb not null,
    validator_version varchar(80) not null,
    validated_at timestamptz not null,
    unique(id,manifest_hash),
    unique(material_id,manifest_hash)
);
create table learning.core_evidence (
    id uuid primary key,
    validation_id uuid not null,
    manifest_hash char(64) not null,
    kind varchar(16) not null check(kind in ('FULL_REVIEW','CHILD_USE','PUBLICATION')),
    child_id uuid not null references care.child(id),
    actor_id uuid not null references identity.member(id),
    consent_revision bigint not null,
    reference varchar(200) not null,
    verification_ref uuid,
    parents uuid[] not null default '{}',
    recorded_at timestamptz not null,
    foreign key(validation_id,manifest_hash) references learning.core_dependency_validation(id,manifest_hash),
    unique(validation_id,kind,actor_id,consent_revision,reference,parents)
);
-- Revocation is an append-only tombstone; evidence and payload bytes stay immutable.
create table learning.core_revocation (
    target_id uuid primary key,
    actor_id uuid not null references identity.member(id),
    reason varchar(200) not null,
    recorded_at timestamptz not null
);
alter table learning.plan_slot add column publication_id uuid references learning.core_evidence(id);
alter table learning.plan add column manifest_hash char(64);
alter table learning.plan add column manifest text;
create trigger core_artifact_immutable before update or delete on learning.core_artifact
    for each row execute function learning.reject_manuscript_mutation();
create trigger core_dependency_immutable before update or delete on learning.core_dependency_validation
    for each row execute function learning.reject_manuscript_mutation();
create trigger core_evidence_immutable before update or delete on learning.core_evidence
    for each row execute function learning.reject_manuscript_mutation();
create trigger core_revocation_immutable before update or delete on learning.core_revocation
    for each row execute function learning.reject_manuscript_mutation();
create function learning.freeze_core_plan() returns trigger language plpgsql as $$
begin
    if old.manifest is not null and (new.manifest is distinct from old.manifest
        or new.manifest_hash is distinct from old.manifest_hash or new.child_id<>old.child_id
        or new.timezone<>old.timezone or new.day_index<>old.day_index or new.id<>old.id) then
        raise exception 'Core plan manifest is immutable';
    end if;
    return new;
end $$;
create trigger core_plan_immutable before update on learning.plan for each row execute function learning.freeze_core_plan();
create function learning.freeze_core_slot() returns trigger language plpgsql as $$
begin
    if old.publication_id is not null then raise exception 'Published Core plan slot is immutable'; end if;
    if tg_op='DELETE' then return old; end if; return new;
end $$;
create trigger core_slot_immutable before update or delete on learning.plan_slot for each row execute function learning.freeze_core_slot();
create function learning.freeze_core_projection() returns trigger language plpgsql as $$
begin
    if old.content_origin='CORE_DRAFT' and (to_jsonb(new)-array['status','reviewed_at','reviewer_id','current','revoked_at'])
        is distinct from (to_jsonb(old)-array['status','reviewed_at','reviewer_id','current','revoked_at']) then
        raise exception 'Core projection is immutable';
    end if;return new;
end $$;
create trigger core_projection_immutable before update on learning.material for each row execute function learning.freeze_core_projection();
