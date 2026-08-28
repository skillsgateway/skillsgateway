-- Enumerated value sets are PostgreSQL types, not CHECK constraints: the column then *is* the
-- set to everything that introspects the schema, and a value outside it is a type error rather
-- than a constraint violation. A type is named <singular table>_<column>, because three tables
-- carry a `state` and a bare `state` type would collide.
--
-- The trade, verified against PostgreSQL 18.6: a new value added with ALTER TYPE ... ADD VALUE
-- cannot be *used* in the same transaction that adds it (Flyway runs a migration in one, so
-- adding a value and backfilling rows with it takes two migrations), and a value can never be
-- removed -- ALTER TYPE ... DROP VALUE is "not implemented", so dropping one means a replacement
-- type, a rewrite of every dependent column, and a DROP TYPE. The value set is close to permanent.

CREATE TYPE marketplace_origin AS ENUM ('upstream', 'hosted');
CREATE TYPE marketplace_push_policy AS ENUM ('append-only', 'allow-rewrite');
CREATE TYPE marketplace_sync_mode AS ENUM ('on-demand', 'scheduled', 'webhook');
CREATE TYPE snapshot_state AS ENUM ('held', 'approved', 'rejected', 'revoked');
CREATE TYPE webhook_delivery_state AS ENUM ('pending', 'delivered', 'failed');
CREATE TYPE audit_sink_kind AS ENUM ('webhook');
CREATE TYPE vetting_run_outcome AS ENUM ('clear', 'blocked');
CREATE TYPE vetting_verdict_state AS ENUM ('pass', 'warn', 'fail', 'error', 'pending');
CREATE TYPE vetting_finding_severity AS ENUM ('info', 'low', 'medium', 'high', 'critical');
CREATE TYPE vetting_waiver_scope_kind AS ENUM ('snapshot', 'path');
CREATE TYPE role_grant_role AS ENUM ('admin', 'approver', 'auditor');

CREATE TABLE marketplaces (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    -- Null only for a gateway-hosted marketplace (GW_0101), which has no upstream to clone from;
    -- the CHECK below is what keeps every other marketplace's URL mandatory.
    url TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    -- Where the content comes from (GW_0101): fetched from an upstream clone URL, or pushed by
    -- the organisation into a gateway-owned origin repository. Immutable after registration --
    -- changing it would swap the supply chain under snapshots that were already approved.
    origin marketplace_origin NOT NULL DEFAULT 'upstream',
    -- Whether a hosted marketplace's publisher may rewrite its lineage (GW_0102). append-only
    -- refuses a non-fast-forward push; allow-rewrite permits it and puts both tips on the ledger.
    push_policy marketplace_push_policy NOT NULL DEFAULT 'append-only',
    -- Best-effort forge metadata captured at registration (GW_0021).
    forge TEXT,
    forge_project TEXT,
    description TEXT,
    upstream_updated_at TIMESTAMPTZ,
    -- How new upstream content reaches quarantine (GW_0056): an operator's click (on-demand),
    -- the polling sweep (scheduled), or a signed forge push webhook (webhook). Only the trigger
    -- varies — every mode lands snapshots held behind the same approval gate.
    sync_mode marketplace_sync_mode NOT NULL DEFAULT 'on-demand',
    -- HMAC key for the inbound webhook (GW_0058). Like webhook_subscribers.secret this must stay
    -- recoverable (HMAC verification needs the key itself, so a PAT-style hash is impossible);
    -- it is returned exactly once by the mode change that generated it and by no read endpoint.
    webhook_secret TEXT,
    -- Last sync attempt, success or failure (GW_0057): stamping failures too is what keeps one
    -- dead upstream from monopolizing the sweep's oldest-first order.
    last_sync_at TIMESTAMPTZ,
    -- An upstream marketplace is defined by its clone URL; a hosted one has none (GW_0101).
    CONSTRAINT marketplaces_upstream_has_url CHECK (origin = 'hosted' OR url IS NOT NULL),
    -- A hosted marketplace has no upstream to poll or be notified about: its ingestion trigger is
    -- the push itself, so the sweep must never see it (GW_0101).
    CONSTRAINT marketplaces_hosted_is_on_demand CHECK (origin <> 'hosted' OR sync_mode = 'on-demand')
);

-- The scheduled sync sweep's only query: scheduled marketplaces, least recently attempted first.
CREATE INDEX idx_marketplaces_sync_queue ON marketplaces (last_sync_at) WHERE sync_mode = 'scheduled';

CREATE TABLE snapshots (
    id BIGSERIAL PRIMARY KEY,
    marketplace_id BIGINT NOT NULL REFERENCES marketplaces (id),
    sha TEXT NOT NULL,
    -- held -> approved | rejected, approved -> revoked, revoked -> approved | rejected.
    -- 'revoked' is retroactive quarantine (GW_0050): a snapshot that was approved and published,
    -- and whose later re-vetting run found a violation the active waivers do not cover. It is a
    -- state of its own rather than a return to 'held' because the difference matters to everyone
    -- reading it — the content was served, and to whom is answerable from the fetch ledger.
    state snapshot_state NOT NULL,
    violation TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    decided_by TEXT,
    decided_at TIMESTAMPTZ,
    -- Revocation stamps are their own columns rather than an overwrite of decided_by/decided_at:
    -- who approved the snapshot, and when, must survive the revocation that retracted it. Cleared
    -- when a fresh approve decision re-publishes the snapshot (GW_0050).
    revoked_at TIMESTAMPTZ,
    revoked_by TEXT,
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

-- The continuous re-vetting sweep's only query: live approved snapshots, oldest run first.
CREATE INDEX idx_snapshots_revet_queue ON snapshots (id) WHERE state = 'approved' AND deleted_at IS NULL;

-- Append-only fetch ledger: no UPDATE/DELETE is ever issued against this table.
CREATE TABLE fetch_log (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL,
    principal TEXT,
    marketplace TEXT NOT NULL,
    event TEXT NOT NULL,
    ref TEXT,
    sha TEXT,
    -- Free-text qualifier for an entry that needs one: today the vetting chain outcome and
    -- the reason a reviewer gave when overriding it (GW_0043). Its own column rather than an
    -- overloaded `ref`, so the exported ledger schema stays honest for SIEM consumers.
    detail TEXT,
    -- Which token authenticated a facade entry (GW_0067); NULL on admin entries and on facade
    -- entries older than per-token attribution. Deliberately not a foreign key: the ledger is
    -- append-only history and must outlive any token row.
    token_id BIGINT
);

-- The staleness read's query (GW_0076): the latest content-transferring fetch per
-- (principal, marketplace). Partial on upload-pack because only a pack send means the identity
-- received the content — info-refs fires on every `git fetch` whether or not anything transfers.
CREATE INDEX idx_fetch_log_adoption ON fetch_log (principal, marketplace, id DESC)
    WHERE event = 'upload-pack';

CREATE TABLE access_tokens (
    id BIGSERIAL PRIMARY KEY,
    principal TEXT NOT NULL,
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    -- Comma-delimited marketplace names the token may fetch (GW_0064); NULL grants every
    -- marketplace, which is what every pre-scoping token meant. Names cannot contain the
    -- delimiter (^[a-z0-9][a-z0-9_-]*$).
    scopes TEXT,
    -- Expiry is decided by comparing this to now at authentication time (GW_0065): no sweep can
    -- be late and no scheduler outage can keep a dead token alive. NULL never expires.
    expires_at TIMESTAMPTZ,
    -- The token this one replaced via rotation (GW_0066): the lineage an auditor follows.
    rotated_from BIGINT REFERENCES access_tokens (id),
    -- Derived from a browser session rather than deliberately provisioned (GW_0104). Recorded
    -- rather than inferred from a short expiry: a short-lived PAT is an ordinary thing to want,
    -- and what the ledger needs to distinguish is how the credential was obtained.
    session_derived BOOLEAN NOT NULL DEFAULT FALSE,
    -- Comma-delimited hosted marketplace names this token may PUSH to (GW_0102). Deliberately
    -- unlike `scopes`: NULL here means none, not all, so no token that predates publication --
    -- and no token whose fetch scope is the every-marketplace form -- can write anything.
    push_scopes TEXT
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
    state webhook_delivery_state NOT NULL,
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
    kind audit_sink_kind NOT NULL,
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

-- Snapshot vetting (GW_0037..GW_0048).
--
-- Deliberately separate from `snapshots.state`: vetting is evidence about a commit, not a
-- vetting state of its own. A snapshot stays `held` whatever the chain says — the chain
-- gates the approval — which is what lets a later re-vetting pass record a new run against
-- an already-approved snapshot without inventing a state transition.

CREATE TABLE vetting_runs (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id BIGINT NOT NULL REFERENCES snapshots (id) ON DELETE CASCADE,
    -- What caused the run: 'ingestion', 'revet-scheduled' (the continuous re-vetting sweep,
    -- GW_0049) or 'revet-manual' (an operator asking for one now, which is also how a scanner
    -- feed update is turned into fresh evidence).
    trigger TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    -- Identity of the chain that produced the run: 'connector@version' for every connector, in
    -- chain order. Recorded so "the same content was vetted again and the answer changed" can be
    -- told apart from "a different chain looked at it" without re-deriving anything (GW_0049).
    chain TEXT,
    -- Fail-closed aggregate over the run's verdicts; see VettingChain. Deliberately raw: this
    -- is what the connectors said, and waivers never rewrite it. The outcome that gates the
    -- approval is the *effective* one, derived on read from this run plus the waivers active
    -- at that instant (GW_0045), which is what makes expiry (GW_0046) need no scheduler.
    outcome vetting_run_outcome NOT NULL
);

-- The only read path: the latest run of one snapshot.
CREATE INDEX idx_vetting_runs_latest ON vetting_runs (snapshot_id, id DESC);

CREATE TABLE vetting_verdicts (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES vetting_runs (id) ON DELETE CASCADE,
    connector TEXT NOT NULL,
    -- Position in the chain, so the recorded run can be replayed in the order it ran.
    position INTEGER NOT NULL,
    -- 'pending' is groundwork for an asynchronous connector whose callback has not arrived;
    -- the aggregation treats it as blocking, so the gate is already correct.
    state vetting_verdict_state NOT NULL,
    detail TEXT,
    report_url TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, connector)
);

CREATE TABLE vetting_findings (
    id BIGSERIAL PRIMARY KEY,
    verdict_id BIGINT NOT NULL REFERENCES vetting_verdicts (id) ON DELETE CASCADE,
    -- Stable rule identifier ('aws-access-key-id'), not an ordinal: it is the identity a
    -- scoped waiver will be written against.
    finding_id TEXT NOT NULL,
    severity vetting_finding_severity NOT NULL,
    location TEXT,
    message TEXT NOT NULL
);

CREATE INDEX idx_vetting_verdicts_run ON vetting_verdicts (run_id);
CREATE INDEX idx_vetting_findings_verdict ON vetting_findings (verdict_id);

-- Vetting waivers (GW_0044..GW_0048): scoped, expiring accepted-risk exceptions.
--
-- A waiver names one rule on one marketplace and one scope. It never rewrites a run; it is an
-- input to the effective-outcome computation, which is why expiry is a comparison against
-- `now` at evaluation time rather than a state anything has to transition through.

CREATE TABLE vetting_waivers (
    id BIGSERIAL PRIMARY KEY,
    -- A waiver never crosses a marketplace: the marketplace is the unit of trust the gateway
    -- governs, so it is also the widest an accepted risk may reach.
    marketplace_id BIGINT NOT NULL REFERENCES marketplaces (id) ON DELETE CASCADE,
    -- The stable rule identifier from vetting_findings.finding_id.
    rule_id TEXT NOT NULL,
    -- 'snapshot' pins the waiver to one commit SHA and dies with it; 'path' survives
    -- re-ingestion and is matched as a directory prefix on the finding's path.
    scope_kind vetting_waiver_scope_kind NOT NULL,
    scope_value TEXT NOT NULL CHECK (scope_value <> ''),
    justification TEXT NOT NULL CHECK (justification <> ''),
    approved_by TEXT NOT NULL CHECK (approved_by <> ''),
    created_at TIMESTAMPTZ NOT NULL,
    -- NOT NULL is the "no unlimited waivers" rule expressed in the schema rather than only in
    -- the service: an unbounded accepted risk cannot be represented at all.
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by TEXT,
    -- Stamped by the expiry sweep the first time it observes this waiver past its expiry, so
    -- the ledger entry is written once. The gate never reads it: expiry is decided by
    -- comparing expires_at to now, whether or not the sweep has ever run.
    expired_recorded_at TIMESTAMPTZ
);

-- The evaluation query: every waiver of one marketplace, filtered in memory by rule and scope.
CREATE INDEX idx_vetting_waivers_marketplace ON vetting_waivers (marketplace_id, rule_id);
-- The sweep's only query.
CREATE INDEX idx_vetting_waivers_expiry_sweep ON vetting_waivers (expires_at)
    WHERE expired_recorded_at IS NULL AND revoked_at IS NULL;

-- Delegated administration (GW_0068..GW_0071).
--
-- Current state only, mirroring webhook_subscribers rather than the soft-delete pattern: a
-- revoked grant has no future behavior to explain, and the audit ledger carries the history.
-- Configuration-bootstrapped admins (skills-gateway.roles.admins) are deliberately NOT rows
-- here — having no row is what makes them unrevocable through the API.

CREATE TABLE role_grants (
    id BIGSERIAL PRIMARY KEY,
    principal TEXT NOT NULL CHECK (principal <> ''),
    role role_grant_role NOT NULL,
    -- NULL for the global roles; an approver grant names its one marketplace. The service
    -- enforces which role carries one; the schema enforces the referenced marketplace exists.
    marketplace_id BIGINT REFERENCES marketplaces (id) ON DELETE CASCADE,
    granted_by TEXT NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    -- NULLS NOT DISTINCT so a duplicate global grant (marketplace_id NULL) is refused too.
    UNIQUE NULLS NOT DISTINCT (principal, role, marketplace_id)
);

-- The authorization check's only query.
CREATE INDEX idx_role_grants_principal ON role_grants (principal);

-- CEL policy deny rules (GW_0089..GW_0091).
--
-- A rule is an organizational prohibition as data: a CEL expression over a snapshot's facts,
-- evaluated fail-closed at the moment of approval. The expression is compiled (parsed and
-- type-checked to boolean) before a row may exist, so a stored expression is a valid one;
-- the gate still refuses rather than skips if that invariant is ever violated.

CREATE TABLE policy_rules (
    id BIGSERIAL PRIMARY KEY,
    -- The identity a denial names on the ledger and in the refusal.
    name TEXT NOT NULL UNIQUE CHECK (name <> ''),
    description TEXT,
    expression TEXT NOT NULL CHECK (expression <> ''),
    -- Disabling is the audited off-switch: the exception path for a deny rule is editing the
    -- rule, never waiving one snapshot past it.
    enabled BOOLEAN NOT NULL,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by TEXT,
    updated_at TIMESTAMPTZ
);
