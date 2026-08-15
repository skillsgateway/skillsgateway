# Design: add-audit-ledger-export

## Context

The audit ledger is one table, `fetch_log`, written by exactly two collaborators —
`FetchAuditHook` for facade fetches (GW_0008) and `AdminAuditLogger` for administrative
actions (GW_0022) — through `FetchLogRepository.append(...)`, which issues a single
`INSERT` and nothing else. Its shape is `(id BIGSERIAL, ts, source, principal,
marketplace, event, ref, sha)`; `source` is a client address for fetches and the literal
`admin` for administrative actions. The only reader today is `GET /api/audit`, which does
`SELECT * FROM fetch_log ORDER BY id` and returns the whole table as untyped maps — fine
for a portal table with hundreds of rows, useless as a compliance feed.

Change #27 (already on this branch) added the delivery half of what an export needs:
`webhook_subscribers` (target URL behind the scheme allowlist, show-once `whsec_` secret),
`webhook_deliveries` (durable, leased, `pending/delivered/failed`), `WebhookSigner`
(HMAC-SHA256 `sha256=<hex>` over the exact body) and `WebhookDispatcher` (claim, POST,
exponential backoff, bounded attempts). Nothing in that machinery is lifecycle-specific
below `WebhookService.emit(...)`.

OpenTelemetry is deliberately not this path: ARCHITECTURE.md treats telemetry as
operational signal that may be sampled and dropped. Compliance evidence may not be.

## Goals / Non-Goals

**Goals:** a SIEM can obtain every ledger entry, in order, without gaps, either by polling
an authenticated NDJSON endpoint with a cursor or by receiving signed batches pushed to it;
a consumer that falls behind, is rebuilt, or misses a delivery can replay from any position;
delivery is at-least-once and each entry carries the sequence a receiver de-duplicates on
(GW_0027–GW_0030).

**Non-Goals:** Kafka and syslog sinks (see follow-ons); exactly-once delivery; filtering or
redaction of ledger fields per sink; signing or hash-chaining the ledger itself (tamper
evidence is its own future requirement); retention and archival of old entries; export of
anything other than the ledger.

## Decisions

- **A sink is a cursor over the ledger, not a copy of it.** `audit_sinks(id, name UNIQUE,
  kind, subscriber_id, cursor_position, batch_size, enabled, created_at, updated_at)` is
  folded into `V1__init.sql` (the repo keeps a single migration; every environment builds
  the schema from scratch). `cursor_position` is the id of the last ledger entry handed to
  that sink. No entry is ever copied into a per-sink queue — the ledger is the queue, and
  the cursor is the only per-consumer state. Replay is therefore a write to one column.
  The alternative, per-sink outbox rows, was rejected: it duplicates an append-only table
  and makes replay mean "re-materialise history".

- **The push sink reuses #27 wholesale rather than re-implementing delivery.** Creating a
  webhook sink creates an ordinary `webhook_subscribers` row (URL through the same
  allowlist, same `SecureRandom` `whsec_` secret returned exactly once) whose event filter
  is the single event `audit.export`, and an `audit_sinks` row pointing at it. The exporter
  then calls `WebhookDeliveryRepository.enqueue(subscriberId, "audit.export", payload)` —
  from there the existing dispatcher signs, POSTs, backs off, retries and records exactly as
  it does for lifecycle events, and the existing Webhooks page shows the attempts. Two
  consequences are deliberate: `audit.export` is *not* in `WebhookEvent.ALL`, so an operator
  cannot subscribe a lifecycle receiver to audit batches through `POST /api/webhooks`, and a
  `*` lifecycle subscriber never receives them either, because `WebhookService.emit` is only
  ever called with lifecycle names.

- **Cursor advance is enqueue-based, which is what makes it at-least-once.** A pass reads
  `WHERE id > cursor AND ts <= cutoff ORDER BY id LIMIT batch_size`, enqueues one delivery
  containing that batch, and advances the cursor to the last id in it. The delivery row is
  durable before the cursor moves, so a crash between the two re-sends the batch (duplicate,
  acceptable) rather than skipping it. If the dispatcher ultimately marks the delivery
  `failed`, the operator sees it on the Webhooks page and replays by resetting the cursor —
  the exporter does not silently rewind, because a poisoned batch would then loop forever.

- **A commit-settling lag closes the `BIGSERIAL` skip window.** `nextval` is assigned before
  commit, so entry 101 can become visible while 100 is still in flight; a naive
  `id > cursor` reader would advance past 100 and never see it. Rather than introduce a
  logical sequence table or `pg_current_snapshot()` bookkeeping, both export paths ignore
  entries with `ts > now() - lag` (`skills-gateway.audit-export.lag`, default 5s). Ledger
  appends are single-statement inserts that commit in milliseconds, so a five-second settling
  window is several orders of magnitude of headroom, and the cost is bounded export latency
  rather than correctness. The pull endpoint applies the same cutoff, so both paths make the
  same promise.

- **The pull sink is a stream, not a page object.** `GET /api/audit/export` produces
  `application/x-ndjson` via `StreamingResponseBody`, writing one compact JSON object per
  line and flushing every chunk, with the ledger read in fixed-size chunks internally so
  memory is bounded by the chunk, not by `limit`. `?after=` defaults to 0 and `?limit=`
  defaults to 1000 (max 10000) — a poller walks forward by repeating with the cursor from
  the `X-Skills-Gateway-Audit-Cursor` response header until a response is empty. NDJSON is
  what Splunk/Elastic/Sentinel ingest natively; a JSON array would force the consumer to
  buffer the whole response, and CSV would lose the null/int distinction.

- **Reads never touch the ledger's append-only invariant.** `FetchLogRepository` gains a
  typed `AuditEntry` record and two `SELECT`s; it still offers no update or delete. The
  export endpoints live in a new `AuditController` under `/api/audit/**` rather than growing
  `AdminController`, and the existing `GET /api/audit` is untouched so the portal table and
  its tests keep working.

- **Portal surface stays minimal, on the page that already exists.** The Audit log page gains
  a download link to `/api/audit/export` (same-origin, session-authenticated) and a sinks
  table showing each sink's target, cursor, and how far behind the ledger head it is, plus
  the create form and a reset-cursor control. No new nav entry: this is audit, and it belongs
  on the audit page.

- **Follow-on sinks (out of scope here).** Kafka: an `audit_sinks.kind = 'kafka'` row with
  broker/topic config and the same cursor advance, batch → producer send instead of delivery
  enqueue. Syslog/CEF: `kind = 'syslog'`, a formatter over the same batch. Both need only a
  new dispatch arm behind the cursor contract this change fixes, which is why `kind` exists
  in the table from day one even though only `webhook` is accepted in v1.

## Risks / Trade-offs

- [At-least-once means duplicates after a crash or a replay] → every exported entry carries
  its ledger `id`; receivers de-duplicate on it, and the webhook delivery id header remains
  the transport-level de-duplication key.
- [The settling lag makes exports up to 5s stale] → compliance feeds are not real-time
  alerting; the alternative (skipping an entry) is unacceptable, and the lag is configurable.
- [A permanently failing push sink stalls at a cursor with a `failed` delivery behind it] →
  deliberate: the operator sees the failure on the Webhooks page and replays with an explicit
  cursor reset, instead of the exporter re-queueing a poisoned batch forever.
- [Bulk ledger read is available to any authenticated portal user] → unchanged from
  `GET /api/audit`, which already returns the full table; per-role restriction is a future
  authorization requirement covering the whole admin surface, not this endpoint alone.
- [A large `limit` holds a response open] → the read is chunked and the limit is capped at
  10000 entries per request; back-pressure is the consumer's own polling rate.

## Migration Plan

Additive: `V1__init.sql` gains one table, no existing table or column changes, no endpoint
changes shape. With no sinks registered the exporter is one indexed query per poll interval.
Rollback is a revert plus dropping `audit_sinks`.

## Open Questions

(none)
