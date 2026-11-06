create table learning.prototype_observation (
 event_id uuid primary key,child_id uuid not null references care.child(id),session_id uuid not null references learning.session(id),
 attempt_id uuid not null,slot integer not null,sequence bigint not null,scenario_version_id uuid not null,
 type text not null,node_id text,question_id text,choice_id text,selected_emotion_id text,
 presented boolean not null,first_response boolean not null,support text not null,
 outcome text not null check(outcome in ('MATCH','MISMATCH','UNSCORED')),request_hash char(64) not null,
 occurred_at timestamptz not null,received_at timestamptz not null,
 unique(session_id,attempt_id,sequence)
);
create index prototype_observation_child on learning.prototype_observation(child_id,received_at,event_id);
