alter table learning.plan drop constraint plan_day_index_check;
alter table learning.plan add constraint plan_day_index_check check(day_index>=1);
alter table learning.plan add column unlocked_date date;
alter table learning.plan add column unlocked_at timestamptz;
alter table learning.receipt add column runtime_request_digest char(64);
update learning.plan p set unlocked_date=s.local_date,unlocked_at=s.started_at from learning.session s where s.plan_id=p.id;
create unique index plan_child_unlock_date_unique on learning.plan(child_id,unlocked_date) where unlocked_date is not null;

-- Stop on inconsistent legacy evidence rather than silently repairing rewards.
do $$ begin
    if exists(select 1 from learning.session s where s.world_revision <> s.completed_count
        or s.completed_count<>(select count(*) from learning.receipt r where r.session_id=s.id)
        or s.stars<>(select count(*) from learning.star_award a where a.session_id=s.id))
        or exists(select 1 from learning.receipt r left join learning.story_event e on e.receipt_id=r.id
            where e.receipt_id is null or (r.response->>'worldRevision')::bigint is distinct from e.world_revision)
        or exists(select child_id from learning.story_event group by child_id having min(world_revision)<>1 or max(world_revision)<>count(*))
        or exists(select 1 from learning.story_event e join learning.receipt r on r.id=e.receipt_id
            join learning.plan p on p.id=r.plan_id where e.child_id<>p.child_id)
        then raise exception 'Legacy learning evidence inconsistent; upgrade blocked';
    end if;
end $$;
create table learning.child_world (
    child_id uuid primary key references care.child(id),
    revision bigint not null check(revision>=0)
);
insert into learning.child_world select c.id,coalesce(max(e.world_revision),0) from care.child c
    left join learning.story_event e on e.child_id=c.id group by c.id;

create table learning.runtime_device (
    id uuid primary key,
    child_id uuid not null references care.child(id),
    device_id varchar(128) not null,
    generation bigint not null default 1 check(generation>0),
    consent_revision bigint not null,
    revoked boolean not null default false,
    unique(child_id,device_id)
);
create table learning.device_grant (
    id uuid primary key,
    device uuid not null references learning.runtime_device(id),
    generation bigint not null,
    code_hash char(64) not null unique,
    expires_at timestamptz not null,
    issuer_scope char(64) not null,
    request_key char(64) not null,
    issuer_id uuid not null references identity.member(id),
    issuer_session_id uuid not null references identity.auth_session(id),
    consumed_at timestamptz
);
create table learning.runtime_credential (
    id uuid primary key,
    device uuid not null references learning.runtime_device(id),
    generation bigint not null,
    plan_id uuid references learning.plan(id),
    token_hash char(64) not null unique,
    expires_at timestamptz not null
);
create table learning.runtime_refresh (
    id uuid primary key,
    device uuid not null references learning.runtime_device(id),
    generation bigint not null,
    token_hash char(64) not null unique,
    expires_at timestamptz not null,
    used boolean not null default false,
    accepted_key char(64),
    body_hash char(64),
    successor uuid references learning.runtime_refresh(id)
);
create table learning.session_pin (
    session_id uuid primary key references learning.session(id),
    plan_hash char(64) not null,
    entry_world_revision bigint not null,
    manifest text not null,
    pinned_at timestamptz not null
);
create trigger session_pin_immutable before update or delete on learning.session_pin
    for each row execute function learning.reject_manuscript_mutation();
create table learning.runtime_checkpoint (
    session_id uuid primary key references learning.session(id),
    slot integer not null check(slot between 1 and 5),
    attempt_id uuid not null,
    revision bigint not null check(revision>0),
    body jsonb not null,
    saved_at timestamptz not null
);
create table learning.asset_lease (
    id uuid primary key,
    device uuid not null references learning.runtime_device(id),
    generation bigint not null,
    session_id uuid not null references learning.session(id),
    slot integer not null check(slot between 1 and 5),
    plan_hash char(64) not null,
    asset_id uuid not null references learning.core_artifact(id),
    asset_hash char(64) not null,
    superseded boolean not null default false,
    expires_at timestamptz not null
);
-- Care remains the authority. Re-consent cannot resurrect old devices, grants, or leases.
create function learning.invalidate_runtime_consent() returns trigger language plpgsql as $$
begin
    if old.scope='CORE_PROFILE_LEARNING' and (new.revision<>old.revision or new.granted<>old.granted) then
        update learning.runtime_device set generation=generation+1,revoked=true where child_id=new.child_id;
    end if;
    return new;
end $$;
create trigger runtime_consent_changed after update on care.consent
    for each row execute function learning.invalidate_runtime_consent();
create function learning.invalidate_runtime_child() returns trigger language plpgsql as $$
begin
    if new.deleted_at is distinct from old.deleted_at or new.guardian_id<>old.guardian_id then
        update learning.runtime_device set generation=generation+1,revoked=true where child_id=new.id;
    end if;
    return new;
end $$;
create trigger runtime_child_changed after update on care.child
    for each row execute function learning.invalidate_runtime_child();
create function learning.invalidate_runtime_guardian() returns trigger language plpgsql as $$
begin
    if new.status<>old.status or new.role is distinct from old.role then
        update learning.runtime_device set generation=generation+1,revoked=true
            where child_id in(select id from care.child where guardian_id=new.id);
    end if;
    return new;
end $$;
create trigger runtime_guardian_changed after update on identity.member
    for each row execute function learning.invalidate_runtime_guardian();
