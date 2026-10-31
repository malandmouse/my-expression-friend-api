alter table identity.notification_outbox add column delivery_state varchar(20) not null default 'PENDING'
    check (delivery_state in ('PENDING','PROCESSING','SENT','SKIPPED','FAILED'));
alter table identity.notification_outbox add column attempts integer not null default 0 check (attempts>=0);
alter table identity.notification_outbox add column next_attempt_at timestamptz not null default current_timestamp;
alter table identity.notification_outbox add column lease_id uuid;
alter table identity.notification_outbox add column lease_until timestamptz;
alter table identity.notification_outbox add column encrypted_request bytea;
alter table identity.notification_outbox add column provider_message_id varchar(128);
alter table identity.notification_outbox add column last_error_code varchar(80);
update identity.notification_outbox set delivery_state='SENT' where delivered_at is not null;
create index notification_delivery_idx on identity.notification_outbox(kind,delivery_state,next_attempt_at,created_at)
    where delivered_at is null;
