-- A named summary policy, never a fabricated FULL_REVIEW.
alter table learning.core_evidence drop constraint core_evidence_kind_check;
alter table learning.core_evidence add constraint core_evidence_kind_check check(kind in ('FULL_REVIEW','SUMMARY_APPROVAL','CHILD_USE','PUBLICATION'));
create table learning.prototype_release (
 id uuid primary key,child_id uuid not null references care.child(id),job_id uuid not null unique references learning.generation_job(id),
 revision bigint not null default 1,state text not null check(state in ('HELD','CONFIRMED_MEDIA_PENDING','MEDIA_RUNNING','MEDIA_FAILED','OUTCOME_UNKNOWN','READY','REVOKED')),
 draft_hash char(64) not null,approved_by uuid not null references identity.member(id),consent_revision bigint not null,
 child_use_allowed boolean not null,summary_ids jsonb not null,actor_pin jsonb not null,
 mode text not null check(mode in ('MOCK','LIVE')),plan_id uuid references learning.plan(id),
 approved_at timestamptz not null,updated_at timestamptz not null,error_code text,media_config jsonb
);
alter table learning.prototype_day add foreign key(release_id) references learning.prototype_release(id);
create table learning.prototype_media (
 id uuid not null unique,release_id uuid not null references learning.prototype_release(id),sentence_key varchar(160) not null,
 slot integer not null check(slot between 1 and 5),scene_id text not null,sentence_id text not null,sentence_index integer not null,
 text text not null,text_hash char(64) not null,actor_id text not null,emotion text not null,intensity numeric not null,
 face_cue text not null,animation_cue text not null,
 state text not null check(state in ('PENDING','RESERVED','COMPLETED','FAILED','OUTCOME_UNKNOWN')),
 mode text not null,artifact_id uuid references learning.core_artifact(id),acting jsonb,audio_hash char(64),
 byte_length integer,duration_ms integer,error_code text,reserved_at timestamptz,completed_at timestamptz,attempts integer not null default 0,
 primary key(release_id,sentence_key)
);
create table learning.prototype_package (
 validation_id uuid primary key references learning.core_dependency_validation(id),release_id uuid not null references learning.prototype_release(id),
 slot integer not null,body jsonb not null,source_hash char(64) not null,unique(release_id,slot)
);
create trigger prototype_package_immutable before update or delete on learning.prototype_package
 for each row execute function learning.reject_manuscript_mutation();
alter table learning.agent_source add column input_metadata jsonb;
