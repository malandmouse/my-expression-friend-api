alter table identity.member alter column requested_role drop not null;
alter table identity.auth_session add column reauthenticated_at timestamptz;
alter table identity.auth_session add column verification_ref uuid;

create table identity.email_verification (
    id uuid primary key,
    member_id uuid not null references identity.member(id),
    token_hash char(64) not null unique,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null
);
create index email_verification_member_idx on identity.email_verification(member_id);

create table identity.audit_event (
    id uuid primary key,
    actor_id uuid not null references identity.member(id),
    action varchar(80) not null,
    target_id uuid not null,
    reason varchar(500),
    before_state jsonb not null,
    after_state jsonb not null,
    recorded_at timestamptz not null
);

create table identity.notification_outbox (
    id uuid primary key,
    recipient_id uuid not null references identity.member(id),
    kind varchar(80) not null,
    encrypted_payload bytea not null,
    created_at timestamptz not null,
    delivered_at timestamptz
);

create schema care;
create table care.child (
    id uuid primary key,
    guardian_id uuid not null references identity.member(id),
    pseudonym varchar(40) not null,
    profile jsonb not null,
    profile_version bigint not null check (profile_version > 0),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);
create index child_guardian_idx on care.child(guardian_id, id);

create table care.therapist_invitation (
    id uuid primary key,
    child_id uuid not null references care.child(id),
    guardian_id uuid not null references identity.member(id),
    therapist_id uuid references identity.member(id),
    target_email varchar(254) not null,
    state varchar(20) not null check (state in ('PENDING','ACCEPTED','DECLINED','CANCELLED','EXPIRED')),
    created_at timestamptz not null,
    expires_at timestamptz not null,
    resolved_at timestamptz,
    check (guardian_id <> therapist_id)
);
create unique index pending_invitation_unique on care.therapist_invitation(child_id, target_email) where state='PENDING';
create index invitation_recipient_idx on care.therapist_invitation(therapist_id, created_at);

create table care.child_therapist (
    child_id uuid not null references care.child(id),
    therapist_id uuid not null references identity.member(id),
    invitation_id uuid not null references care.therapist_invitation(id),
    state varchar(20) not null check (state in ('ACTIVE','REVOKED')),
    accepted_at timestamptz not null,
    revoked_at timestamptz,
    primary key (child_id, therapist_id)
);
create index child_therapist_member_idx on care.child_therapist(therapist_id, child_id);

create table care.consent (
    child_id uuid not null references care.child(id),
    scope varchar(50) not null check (scope in ('CORE_PROFILE_LEARNING','SPEECH_STT','CARD_EXTERNAL_PROCESSING','CAMERA_LOCAL','OPTIONAL_RESEARCH_OR_MODEL_TRAINING','LOCAL_DRAFT_RESUME')),
    id uuid not null unique,
    granted boolean not null,
    notice_version varchar(80) not null,
    verification_ref uuid not null,
    recorded_at timestamptz not null,
    withdrawn_at timestamptz,
    revision bigint not null check (revision > 0),
    primary key (child_id, scope)
);
