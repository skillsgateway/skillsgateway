# Proposal: run an on-demand ingest as a job with visible progress

## Why

`POST /api/v1/marketplaces/{name}/ingest` fetches, evaluates the manifest and
runs the whole vetting chain inside the HTTP request (#595). The first ingest of
a large marketplace takes minutes with nothing on screen but "Ingesting…", and a
proxy with a shorter idle timeout answers 504 for an ingest the gateway goes on
to finish: the operator is told it failed when it worked.

## What Changes

- **BREAKING:** `POST /api/v1/marketplaces/{name}/ingest` answers `202 Accepted`
  with the marketplace's ingest status and a `Location` of the status resource,
  before anything is fetched; it no longer answers `201` with the snapshot or
  `502` with the upstream problem. The work runs on a gateway thread. A POST
  while an ingest of that marketplace is already running (whatever triggered it)
  answers `202` with that ingest's status and starts nothing.
- New `GET /api/v1/marketplaces/{name}/ingest`: the running ingest, if any
  (stage `queued`, `fetching`, `evaluating-manifest` or `vetting`, and when it
  started), and the last finished one: when it ended, its outcome, the snapshot
  it recorded with that snapshot's state (held or rejected), and for a failure
  the reason, root cause and next step that the `502` problem used to carry.
- The stage is recorded for every ingest, whatever triggered it (on demand,
  scheduled, webhook, hosted push). It lives in the database, so a poll that
  reaches another replica sees it. A running ingest renews a heartbeat. One
  whose replica stopped renewing it is reported as interrupted and does not
  block the next ingest.
- The marketplace read gains `lastIngestSnapshotId`, so the outcome kept on the
  marketplace names the snapshot it produced.
- Portal: the marketplace page's Ingest button starts the job and then follows
  it, showing each stage with the elapsed time. When the ingest ends, it states
  the outcome: held, rejected, or failed with the reason.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: new
  `GW_INGEST_0066 — An on-demand ingest runs as a job whose progress can be followed`
  and
  `GW_INGEST_0067 — An ingest whose replica stopped is reported as interrupted`,
  each with its `SVC_GW_INGEST_*` case. `SVC_GW_INGEST_0038` and
  `SVC_GW_INGEST_0039` are re-pointed from the synchronous response to the
  status resource. Their requirements are unchanged, and so is what the tests
  assert.

## Stop rule

The job reuses the marketplace's last-ingest record, as the issue offers, rather
than adding an object type: four columns on `marketplaces`, no new table, no new
package. It adds no grantable role, no scheduled estate sweep and no
configuration leaf; the heartbeat interval, the staleness bound and the size of
the worker pool are constants. `ConfigSurfaceBudgetTests` and
`ContextBudgetTests` do not move. The job is runtime state, not declared estate,
so `skills-gateway.estate.*` is untouched.

## Impact

- Code: `ingestion/IngestionService.java` (stages, heartbeat), a new
  `ingestion/IngestJobs.java` (claim, queue, run, audit, emit),
  `admin/AdminController.java` (202, `GET …/ingest`),
  `persistence/MarketplaceRepository.java` and `Marketplace.java`, `V1__init.sql`.
- API: breaking status change on the POST, a new GET, an additive
  `lastIngestSnapshotId`; `openapi.json` and `types.gen.ts` regenerated. The PR
  title carries `!` and the PR carries the `⚠️ BREAKING CONTRACT` label.
- Portal: `marketplace-detail.tsx`, `api/queries.ts`, MSW handlers, story and
  component tests, and the e2e ingest step.
- Docs: `docs/manual/reference/api/marketplaces.md`,
  `guides/registering-a-marketplace.md`, `reference/portal.md`, and every page
  showing a `curl … /ingest` that reads a snapshot from the response.
- Requirements: `docs/reqstool/` as listed above.
