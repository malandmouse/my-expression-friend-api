-- Additive: existing V4 materials, pins, receipts and encrypted replay bytes remain untouched.
alter table learning.material add column content_origin varchar(24) not null default 'LEGACY_REVIEWED'
    check (content_origin in ('LEGACY_REVIEWED','CORE_DRAFT'));
alter table learning.material add constraint material_child_version_unique unique(id,child_id,scenario_version_id);

create table learning.core_manuscript (
    material_id uuid primary key,
    child_id uuid not null references care.child(id),
    scenario_version_id uuid not null unique,
    source_material_id varchar(256) not null check(length(trim(source_material_id))>0),
    source_scenario_version_id varchar(256) not null unique check(length(trim(source_scenario_version_id))>0),
    source_content_hash char(64) not null check(source_content_hash ~ '^[0-9a-f]{64}$'),
    payload_hash char(64) not null check(payload_hash ~ '^[0-9a-f]{64}$'),
    payload text not null check(octet_length(payload)<=65536),
    admission_hash char(64) not null check(admission_hash ~ '^[0-9a-f]{64}$'),
    validation_profile varchar(24) not null check(validation_profile in ('CORE_0_3','GENERATED_1_TO_3')),
    created_at timestamptz not null,
    unique(material_id,payload_hash),
    foreign key(material_id,child_id,scenario_version_id) references learning.material(id,child_id,scenario_version_id)
);
create table learning.core_manuscript_validation (
    material_id uuid primary key,
    validated_hash char(64) not null,
    validator_version varchar(100) not null,
    report jsonb not null,
    validated_at timestamptz not null,
    foreign key(material_id,validated_hash) references learning.core_manuscript(material_id,payload_hash),
    check(report @> '{"structuralStatus":"PASS","dependenciesStatus":"NOT_CHECKED","publicationStatus":"NOT_APPROVED"}'::jsonb),
    check(report->>'validatedHash' is not null and report->>'validatedHash'=validated_hash),
    check(report->>'validatorVersion' is not null and report->>'validatorVersion'=validator_version)
);

-- Summary decisions live on material. They cannot rewrite manuscript bytes or validation evidence.
create function learning.reject_manuscript_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'Core manuscript and validation evidence are immutable';
end;
$$;
create trigger core_manuscript_immutable before update or delete on learning.core_manuscript
    for each row execute function learning.reject_manuscript_mutation();
create trigger core_manuscript_validation_immutable before update or delete on learning.core_manuscript_validation
    for each row execute function learning.reject_manuscript_mutation();
