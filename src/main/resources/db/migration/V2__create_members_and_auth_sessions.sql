create schema if not exists identity;

create table identity.member (
    id uuid primary key,
    email varchar(254) not null unique,
    display_name varchar(80) not null,
    password_hash varchar(512) not null,
    requested_role varchar(20) not null check (requested_role in ('GUARDIAN', 'THERAPIST')),
    role varchar(20) check (role in ('GUARDIAN', 'THERAPIST', 'ADMIN')),
    status varchar(20) not null check (status in ('PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED')),
    email_verified_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    check (email = lower(email) and email = btrim(email)),
    check (status <> 'ACTIVE' or (role is not null and email_verified_at is not null)),
    check (status <> 'PENDING' or role is null)
);

create table identity.auth_session (
    id uuid primary key,
    member_id uuid not null references identity.member(id),
    created_at timestamptz not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    check (expires_at > created_at)
);
create index auth_session_member_idx on identity.auth_session(member_id);

-- Keep consumed hashes until the whole session expires so reuse can revoke its family.
create table identity.refresh_token (
    token_hash char(64) primary key,
    session_id uuid not null references identity.auth_session(id),
    created_at timestamptz not null,
    consumed_at timestamptz
);
create index refresh_token_session_idx on identity.refresh_token(session_id);

-- Shared across server instances; contains hashed bucket identifiers, never credentials.
create table identity.auth_rate_bucket (
    bucket_key char(64) primary key,
    window_start timestamptz not null,
    attempts integer not null check (attempts > 0)
);
