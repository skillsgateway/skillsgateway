CREATE TABLE marketplaces (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    -- Best-effort forge metadata captured at registration (GW_0021).
    forge TEXT,
    forge_project TEXT,
    description TEXT,
    upstream_updated_at TIMESTAMPTZ
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
    -- Retention (GW_0031..GW_0034). Deletion is orthogonal to the vetting state: a deleted
    -- snapshot keeps the state it was decided into, so the record of what was held, approved,
    -- or rejected survives, and a restore cannot invent a transition.
    deleted_at TIMESTAMPTZ,
    deleted_reason TEXT,
    -- When the restore window elapses and compaction may remove the row and its git storage.
    purge_after TIMESTAMPTZ,
    UNIQUE (marketplace_id, sha)
);

-- The compaction pass's only query: soft-deleted snapshots whose window has elapsed.
CREATE INDEX idx_snapshots_purge_queue ON snapshots (purge_after) WHERE deleted_at IS NOT NULL;

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

-- Lifecycle event webhooks (GW_0023..GW_0025).

CREATE TABLE webhook_subscribers (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    url TEXT NOT NULL,
    -- Signing key: unlike a PAT this must stay recoverable, and is never returned
    -- by any read endpoint after creation.
    secret TEXT NOT NULL,
    -- Comma-delimited event filter; '*' subscribes to every lifecycle event.
    events TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE webhook_deliveries (
    id BIGSERIAL PRIMARY KEY,
    subscriber_id BIGINT NOT NULL REFERENCES webhook_subscribers (id) ON DELETE CASCADE,
    event TEXT NOT NULL,
    -- Serialized once at emit time so every retry sends byte-identical content
    -- and the signature basis never drifts.
    payload TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('pending', 'delivered', 'failed')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_status INTEGER,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- The dispatcher's only query.
CREATE INDEX idx_webhook_deliveries_due ON webhook_deliveries (state, next_attempt_at);

-- Audit ledger export sinks (GW_0028..GW_0029).

CREATE TABLE audit_sinks (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    -- Only 'webhook' is accepted in v1; the column exists so a Kafka or syslog sink
    -- slots in behind the same cursor contract without a schema change.
    kind TEXT NOT NULL CHECK (kind IN ('webhook')),
    -- The delivery channel: an ordinary webhook subscriber, so audit batches are signed,
    -- retried, and recorded by exactly the machinery lifecycle events already use.
    subscriber_id BIGINT NOT NULL REFERENCES webhook_subscribers (id) ON DELETE CASCADE,
    -- Id of the last fetch_log entry handed to this sink; the only per-consumer state.
    -- Resetting it is what "replay" means (GW_0029).
    cursor_position BIGINT NOT NULL DEFAULT 0,
    batch_size INTEGER NOT NULL DEFAULT 500,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
