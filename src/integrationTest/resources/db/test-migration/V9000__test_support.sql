CREATE SCHEMA test_support;
CREATE TABLE test_support.business_effect (
    id uuid PRIMARY KEY,
    amount integer NOT NULL
);
CREATE TABLE test_support.access_state (
    id integer PRIMARY KEY,
    revoked boolean NOT NULL
);
