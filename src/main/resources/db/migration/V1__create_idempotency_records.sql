CREATE SCHEMA IF NOT EXISTS foundation;

-- No raw request, credential, child utterance, or plaintext response is stored.
CREATE TABLE foundation.idempotency_record (
    actor_scope varchar(128) NOT NULL,
    method varchar(16) NOT NULL,
    path varchar(1024) NOT NULL,
    key_digest char(64) NOT NULL,
    request_digest char(64) NOT NULL,
    status_code smallint NOT NULL CHECK (status_code BETWEEN 200 AND 299),
    content_type varchar(128),
    encrypted_body bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor_scope, method, path, key_digest),
    CHECK (length(encrypted_body) >= 28)
);

COMMENT ON TABLE foundation.idempotency_record IS
    'Accepted responses committed atomically with business state. Never purge without operation-specific replay/retention policy.';
