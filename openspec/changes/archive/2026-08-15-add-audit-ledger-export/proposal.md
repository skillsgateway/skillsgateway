# Proposal: add-audit-ledger-export

## Why

The append-only ledger already records every facade fetch (GW_0008) and every
administrative action (GW_0022), but it can only be read by an authenticated human
through `GET /api/audit`, which returns the whole table at once. Compliance teams do
not read portals: they want the ledger inside their SIEM (Splunk, Elastic, Sentinel),
continuously and without gaps. Issue #29 asks for a compliance export path — distinct
from OpenTelemetry, which carries operational signals and is explicitly not an evidence
store — with at-least-once delivery and a cursor so a consumer can resume or replay.

## What Changes

- **Audit export sinks**: a sink is a named consumer of the ledger with its own cursor
  (the id of the last ledger entry it has been given). Two v1 sink kinds:
  - **pull (NDJSON)**: `GET /api/audit/export` streams ledger entries as
    `application/x-ndjson`, one JSON object per line, ordered by ledger sequence, with
    `?after=<cursor>` and `?limit=<n>`. The response carries the cursor to resume from
    in a header, so a SIEM poller needs no state beyond that number.
  - **push (webhook)**: a registered sink whose batches are handed to the lifecycle
    webhook machinery added in #27 — the same `webhook_subscribers` row shape, the same
    HMAC-SHA256 signing, the same durable `webhook_deliveries` rows and the same
    retry/backoff dispatcher. No second delivery engine is introduced.
- **Cursor and replay**: each push sink stores its cursor; a scheduled exporter reads
  the next batch after the cursor, enqueues one signed delivery, and advances. Resetting
  the cursor (`PUT /api/audit/sinks/{id}/cursor`) re-delivers everything after that
  position — replay is a cursor operation, not a special mode. Delivery is at-least-once;
  every entry carries its ledger sequence so a receiver can de-duplicate.
- **Sequence safety**: the ledger's `BIGSERIAL` id is assigned before commit, so a
  concurrent append with a lower id can become visible after the cursor has passed it.
  Both export paths therefore exclude entries newer than a configurable commit-settling
  lag, which closes the skip window without a second sequence table.
- **Kafka and syslog sinks are out of scope for v1** and recorded as follow-ons in
  `design.md`; the sink abstraction is shaped so they slot in without changing the
  cursor contract.
- Portal: the existing Audit log page gains an export affordance (a download link for the
  NDJSON stream) and a table of registered sinks with their cursors and lag.
- New requirements GW_0027–GW_0030 with SVC_GW_0027–SVC_GW_0030.

## Capabilities

### New Capabilities

- `audit-export`: cursor-based export of the append-only ledger to external compliance
  systems — the NDJSON pull endpoint (GW_0027), push sinks reusing the signed webhook
  delivery machinery with at-least-once semantics (GW_0028), cursor replay (GW_0029),
  and the portal export surface (GW_0030).

### Modified Capabilities

(none — existing requirements are unchanged; the ledger is read, never altered, and no
existing endpoint changes shape)

## Impact

- New: `audit/` package (`AuditExportService`, `AuditExportScheduler`, `AuditController`),
  `AuditSink` + `AuditSinkRepository`.
- Changed: `V1__init.sql` (table `audit_sinks` — the repo keeps a single migration),
  `FetchLogRepository` (typed ledger entries and a cursor-bounded page query),
  `SkillsGatewayProperties` (exporter tuning), `WebhookEvent` (the `audit.export` event
  name used by sink subscribers), `ui/src/pages/audit.tsx`, generated OpenAPI types, MSW
  handlers, `docs/reqstool/*.yml`.
- The ledger becomes readable in bulk by any authenticated portal user, as `GET /api/audit`
  already is; no new authentication surface and no new egress path beyond the webhook
  egress already introduced by #27.
