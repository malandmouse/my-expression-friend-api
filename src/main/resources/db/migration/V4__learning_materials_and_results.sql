create schema learning;

-- Only the trusted generation/publication adapter writes materials and plans.
-- Public summary confirmation is deliberately independent of publication.
create table learning.material (
    id uuid primary key,
    child_id uuid not null references care.child(id),
    summary_id uuid not null unique,
    scenario_version_id uuid not null unique,
    subject varchar(40) not null check (length(trim(subject)) > 0),
    summary varchar(300) not null check (length(trim(summary)) > 0),
    content_hash char(64) not null,
    review_reference varchar(200) not null,
    content jsonb not null,
    status varchar(20) not null default 'UNCONFIRMED'
        check (status in ('UNCONFIRMED','CONFIRMED','ON_HOLD')),
    current boolean not null default true,
    revoked_at timestamptz,
    created_at timestamptz not null,
    reviewed_at timestamptz,
    reviewer_id uuid references identity.member(id)
);
create index material_child_idx on learning.material(child_id, id);

create table learning.plan (
    id uuid primary key,
    child_id uuid not null references care.child(id),
    day_index integer not null check (day_index = 1),
    timezone varchar(128) not null,
    publication_reference varchar(200) not null,
    published_at timestamptz not null,
    revoked_at timestamptz,
    unique (child_id, day_index)
);
create table learning.plan_slot (
    plan_id uuid not null references learning.plan(id),
    slot integer not null check (slot between 1 and 5),
    material_id uuid not null references learning.material(id),
    content_hash char(64) not null,
    primary key (plan_id, slot),
    unique (plan_id, material_id)
);
create table learning.session (
    id uuid primary key,
    plan_id uuid not null unique references learning.plan(id),
    device_id varchar(128) not null,
    local_date date not null,
    reward_expires_at timestamptz not null,
    revision bigint not null default 1 check (revision > 0),
    world_revision bigint not null default 0 check (world_revision >= 0),
    stars integer not null default 0 check (stars between 0 and 5),
    completed_count integer not null default 0 check (completed_count between 0 and 5),
    started_at timestamptz not null,
    completed_at timestamptz,
    unique (id, plan_id),
    check (stars = completed_count)
);
create table learning.receipt (
    id uuid primary key,
    session_id uuid not null references learning.session(id),
    plan_id uuid not null,
    slot integer not null,
    client_commit_id varchar(128) not null,
    request_digest char(64) not null,
    response jsonb not null,
    completed_at timestamptz not null,
    foreign key (session_id, plan_id) references learning.session(id, plan_id),
    foreign key (plan_id, slot) references learning.plan_slot(plan_id, slot),
    unique (plan_id, slot),
    unique (session_id, client_commit_id)
);
create table learning.star_award (
    receipt_id uuid primary key references learning.receipt(id),
    session_id uuid not null references learning.session(id),
    slot integer not null check (slot between 1 and 5),
    amount integer not null check (amount = 1),
    unique (session_id, slot)
);
-- Only reviewed fictional story effects are stored; never raw child expressions.
create table learning.story_event (
    receipt_id uuid primary key references learning.receipt(id),
    child_id uuid not null references care.child(id),
    world_revision bigint not null,
    effects jsonb not null,
    recorded_at timestamptz not null,
    unique (child_id, world_revision)
);
