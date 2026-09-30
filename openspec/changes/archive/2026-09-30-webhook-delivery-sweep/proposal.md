# Proposal: webhook-delivery-sweep

## Why

`webhook_deliveries` has no delete path: every delivery is kept forever with its payload, and for an
audit sink the payload is the exported batch, so the table quietly holds a second copy of the ledger.
Separately, three `fetch_log` reads and the delivery listing scan their tables sequentially. See #541
and finding 11 of `docs/analysis/2026-09-29-application-and-repository-review.md`.

## What Changes

- The existing six-hourly compaction pass (same lease, same `retention.enabled` switch, same
  `retention.batch-size` bound) removes `delivered` and `failed` webhook deliveries older than a fixed
  30 days, never `pending` ones, and writes one ledger entry per pass that removed anything.
- Indexes in `V1__init.sql` (no requirement; a performance change with no observable behaviour):
  `fetch_log (sha, marketplace, ts)`, `fetch_log (ts)` restricted to `upload-pack`, and
  `webhook_deliveries (subscriber_id, id)`.
- Docs: retention guide and reference, lifecycle-webhooks guide.

Stop rule: no configuration leaf, no scheduled job, no package, no estate object, no role. The age is a
constant, not a setting. `docs/manual/capability-map.md` absorbs this without a new capability because
it is a second duty of the retention pass already listed there (retention and the ledger trim sit in
the same row), and delivery history is already part of the lifecycle-webhooks capability.

## Capabilities

### New Capabilities

### Modified Capabilities

- `snapshot-retention`: adds GW_RETENTION_0011 — Delivered and failed webhook deliveries are removed
  after a bounded age.

## Impact

`RetentionService`, `RetentionScheduler`, `WebhookDeliveryRepository`, `V1__init.sql` (edited in
place, pre-1.0), `docs/manual/guides/snapshot-retention.md`, `guides/lifecycle-webhooks.md`,
`reference/retention.md`, `docs/reqstool/`.
