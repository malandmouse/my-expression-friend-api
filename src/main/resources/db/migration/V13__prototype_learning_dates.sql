create table learning.prototype_day (
 child_id uuid not null references care.child(id),local_date date not null,program_day integer not null check(program_day between 1 and 30),
 job_id uuid not null references learning.generation_job(id),release_id uuid,plan_id uuid unique references learning.plan(id),
 created_at timestamptz not null,primary key(child_id,local_date),unique(child_id,program_day)
);
create table learning.prototype_calendar_command (
 id uuid primary key,expected_version bigint not null,result_date date not null,result_version bigint not null
);
-- P1 freezes generation settings at request time; subsequent profile edits affect the next job only.
alter table learning.agent_source add column frozen_profile boolean not null default false;
