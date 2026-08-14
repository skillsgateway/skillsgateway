CREATE TABLE marketplaces (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE snapshots (
    id BIGSERIAL PRIMARY KEY,
    marketplace_id BIGINT NOT NULL REFERENCES marketplaces (id),
    sha TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('held', 'approved', 'rejected')),
    violation TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    decided_by TEXT,
    decided_at TIMESTAMPTZ,
    UNIQUE (marketplace_id, sha)
);

-- Append-only fetch ledger: no UPDATE/DELETE is ever issued against this table.
CREATE TABLE fetch_log (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL,
    principal TEXT,
    marketplace TEXT NOT NULL,
    event TEXT NOT NULL,
    ref TEXT,
    sha TEXT
);

CREATE TABLE access_tokens (
    id BIGSERIAL PRIMARY KEY,
    principal TEXT NOT NULL,
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
