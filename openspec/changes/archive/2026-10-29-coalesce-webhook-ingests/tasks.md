# Tasks: coalesce-webhook-ingests

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0076 and SVC_GW_INGEST_0076 in `docs/reqstool/`.

## 2. Coalesce webhook ingests (SVC_GW_INGEST_0076)

- [x] 2.1 `WebhookIngestCoalescingTests`: with the executor busy, 50 triggers
  for alpha and one for beta. Alpha ingests once, beta runs, and a trigger
  after alpha's ingest has started queues another. Watch it fail first.
- [x] 2.2 `SyncService.queueWebhookIngest` keeps one queued future per
  marketplace id, cleared as the ingest starts (`@Requirements GW_INGEST_0076`).

## 3. Documentation

- [x] 3.1 `reference/api/marketplaces.md` and `guides/upstream-sync.md`
  describe the coalescing. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
