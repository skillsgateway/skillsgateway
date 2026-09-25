# Proposal: ingest-failures-say-why

## Why

A trial deployment of `0.3.0-b1` found that a mistyped URL and a private
repository gave the same uninformative error (#489):

- **The cause is dropped.** `IngestionService` wraps every failure as
  "ingestion failed for marketplace '<name>'". The `502` carries only that
  wrapper. Nothing is logged, and nothing is recorded.
- **The 401 misleads.** For a repository that does not exist, a forge commonly
  answers 401, and JGit reports "Authentication is required".
- **The fallback hides the first error.** When the HEAD-refspec fetch fails, the
  `ls-remote` retry fails too, and only the second error survives.
- **Registration does not check reachability.** The typo surfaced only at the
  first ingest.

## What Changes

- **One failure-cause translator for ingestion and registration.**
  - It keeps the cause chain: the first error is attached to the fallback's.
  - It maps a not-found, 401 or 403 answer to "repository not found or requires
    authentication".
  - It logs at WARN.
  - It returns the root cause plus a next step, in the `502` problem detail
    (`reason`, `rootCause`, `nextStep`) and in the portal.
- **The last ingest is recorded.** Its time, outcome and reason are stored on
  the marketplace and exposed on the marketplace read (additive fields).
  - Every failed ingest also lands in the audit ledger as `ingest-failed`,
    whatever triggered it: manual, scheduler, webhook or push.
- **Registration checks the upstream.**
  - Before anything is created, it runs `ls-remote` through the same connection
    path ingestion fetches with, and resolves the pinned ref (the upstream
    default branch).
  - On failure it answers `502` with the translated cause and creates nothing.
- **Declared marketplaces are not refused.** An estate-declared marketplace
  whose upstream is unreachable is still registered. Reconciliation reports the
  failure in the entry's detail and records it in the ledger
  (`marketplace-upstream-unreachable`), and startup continues.
- **No "test connection" endpoint.** Registration and the recorded last-ingest
  status cover it.
- The connection path gets one seam where upstream credentials (#494) will
  attach later. This change adds no credentials.

Stop-rule check: no backend package, no estate object type, no role, no
scheduled sweep, no configuration leaf. `ConfigSurfaceBudgetTests` and
`ContextBudgetTests` are unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds
  - GW_INGEST_0038 — An upstream failure states its cause and a next step
  - GW_INGEST_0039 — The last ingest attempt is recorded on the marketplace
  - GW_INGEST_0040 — Registration refuses an upstream it cannot read
- `declarative-estate`: adds GW_INGEST_0041 — A declared marketplace with an
  unreadable upstream is registered and reported.
- `admin-api`: adds GW_AUDIT_0010 — A failed ingestion is recorded in the ledger
  with its reason.

## Impact

- **Server:**
  - `ingestion/UpstreamGit` (new): the one upstream fetch and `ls-remote` path.
  - `ingestion/UpstreamFailure` (new): the translator.
  - `IngestionService`, `MarketplaceRegistrationService`, `EstateReconciler`,
    `AdminController`, `MarketplaceRepository`, `Marketplace`.
  - A null-principal guard in `AdminAuditLogger`.
- **Schema:** `V1__init.sql` gains three `marketplaces` columns and a
  `marketplace_last_ingest_outcome` enum.
- **API contract:** additive. `MarketplaceView` and `Marketplace` gain
  `lastIngestAt`, `lastIngestOutcome` and `lastIngestReason`, and a `502` is
  documented on `POST /api/v1/marketplaces`. `openapi.json` and `types.gen.ts`
  are regenerated.
- **Behaviour:**
  - Registration now refuses unreachable, not-found, unauthorised and empty
    upstreams.
  - The marketplace upstream fetch gains a fixed idle timeout.
- **Portal:** the marketplace header shows a failed last ingest, and Settings
  shows the last ingest.
- **Tests:** existing registration tests that used unreachable placeholder URLs
  move to reachable local fixtures.
- **Docs:** registration and ingestion in the manual, the estate page, the
  portal reference, and the API reference.
