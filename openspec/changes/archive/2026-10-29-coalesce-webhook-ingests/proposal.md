# Proposal: webhook triggers for one marketplace coalesce

## Why

Every validly signed `POST /hooks/{marketplace}` queued a full ingest on the
one single-threaded webhook executor. A forge delivering in bursts, or the same
delivery sent again, therefore queued one ingest per request. Each ingest
fetches the upstream, stamps the attempt, writes a ledger row and emits a
lifecycle event. Every other webhook-mode marketplace waited behind all of
them, though for an unchanged upstream all of them but the first ingest
nothing new.

## What Changes

- New `GW_INGEST_0076 — Webhook triggers for one marketplace coalesce into at
  most one queued ingest`:
  - A trigger that arrives while that marketplace's ingest is queued and not
    yet started joins it, and still answers `202`.
  - The mark clears as the ingest starts, so a trigger that arrives during the
    ingest queues exactly one more. A push to a newer tip is never lost.
- The queue therefore holds at most one ingest per webhook-mode marketplace.
- New `SVC_GW_INGEST_0076` covers 50 triggers for one marketplace and one for
  another, queued while the executor is busy:
  - the first marketplace ingests once and the second still runs;
  - a trigger after the first ingest has started queues a new one.
- Not **BREAKING**: no API, schema or configuration change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds `GW_INGEST_0076`.

## Impact

- `SyncService.queueWebhookIngest`.
- Docs: `reference/api/marketplaces.md` and `guides/upstream-sync.md`.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
