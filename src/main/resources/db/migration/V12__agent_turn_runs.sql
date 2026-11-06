create table learning.agent_run (
 job_id uuid primary key references learning.generation_job(id),input_hash char(64) not null,
 mode text not null check(mode in ('MOCK','LIVE')),state text not null check(state in ('RUNNING','RESULT_READY','FAILED','OUTCOME_UNKNOWN')),
 result_json jsonb,error_code text,created_at timestamptz not null,updated_at timestamptz not null
);
create table learning.agent_call (
 job_id uuid not null references learning.agent_run(job_id),call_key varchar(100) not null,
 child_id uuid not null references care.child(id),local_date date not null,mode text not null,
 request_hash char(64) not null,state text not null check(state in ('RESERVED','COMPLETED','FAILED','OUTCOME_UNKNOWN')),
 input_reserved bigint not null,output_reserved bigint not null,cost_reserved bigint not null,
 input_used bigint,output_used bigint,cost_used bigint,provider_request_id varchar(256),
 response_json jsonb,created_at timestamptz not null,settled_at timestamptz,
 primary key(job_id,call_key)
);
create index agent_call_day_budget on learning.agent_call(child_id,local_date,mode);
