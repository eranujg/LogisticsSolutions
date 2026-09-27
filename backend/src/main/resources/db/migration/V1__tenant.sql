CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE tenant (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code        citext      NOT NULL UNIQUE,
    name        text        NOT NULL,
    status      text        NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz NOT NULL DEFAULT now()
);
