# Tasks: ingest-as-a-job

Concurrency with data-loss stakes (a stuck marker refusing every later ingest),
so this change is worked under `.claude/skills/old-coder`: each new test is
shown failing before the code it guards exists.

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0066 and GW_INGEST_0067 with SVC_GW_INGEST_0066 and
  SVC_GW_INGEST_0067 to `docs/reqstool/` (revision 0.5.0). Verify with
  `openspec validate ingest-as-a-job --strict`.

## 2. Schema and repository

- [x] 2.1 `V1__init.sql`: `marketplace_ingest_stage` enum; `ingest_attempt`,
  `ingest_stage`, `ingest_started_at`, `ingest_heartbeat_at` with an all-or-none
  CHECK; `last_ingest_snapshot_id` (FK, `ON DELETE SET NULL`, added after
  `snapshots`); `last_ingest_failure JSONB` folded into the last-ingest CHECK.
- [x] 2.2 `MarketplaceRepository`: `claimIngest`, `startIngest`, `advanceIngest`,
  `heartbeat`, `recordIngest` (finished record + conditional clear),
  `ingestStatus`. `Marketplace` gains the new columns.

## 3. Job and stages (SVC_GW_INGEST_0066, SVC_GW_INGEST_0067)

- [x] 3.1 `IngestJobTests`: a POST answers 202 with `queued`/running status and a
  Location before the upstream answers (a forge held on a latch), shows
  `fetching` and then `vetting` while held, and ends with `last` succeeded, the
  snapshot id and `held`; a rejected manifest ends `rejected`; a second POST
  while running answers the same attempt and records one snapshot; an
  unreadable upstream ends `failed` with reason, rootCause and nextStep in
  `last.failure`; a scheduled sweep's ingest is visible in the status; a running
  row whose heartbeat is older than the bound reads `interrupted` and a POST
  claims over it; an attempt cannot clear another attempt's progress. Watch each
  fail.
- [x] 3.2 Implement `IngestJobs` and the stage/heartbeat path in
  `IngestionService` (`@Requirements GW_INGEST_0066, GW_INGEST_0067`); controller
  POST → 202, new GET; machine scope; OpenAPI annotations.
- [x] 3.3 Re-point the HTTP ingest call sites in existing tests (IngestFailure,
  AdminAudit, Webhook, FourEyes, RoleEnforcement, MarketplaceRemoval,
  EstateReconciliation, ClaimRoleMapping, UpstreamCredentials, UpstreamGitHubApp)
  to 202 plus a poll helper, asserting the same facts. No SVC assertion is
  dropped: the 0038 problem fields are asserted on `last.failure`.
- [x] 3.4 Regenerate `openapi.json` and `types.gen.ts`; confirm the only
  breaking diff is the POST's response.

## 4. Portal (SVC_GW_INGEST_0066)

- [x] 4.1 `useIngest` / `useIngestStatus` with polling; stage steps with elapsed
  time and the outcome on `marketplace-detail.tsx`; MSW handlers; component test
  and story (axe-clean); e2e ingest step waits on the status.
- [x] 4.2 Before/after screenshots (`.claude/skills/ui-screenshots`); run
  `/impeccable audit` on the changed page.

## 5. Docs

- [x] 5.1 `reference/api/marketplaces.md`, `guides/registering-a-marketplace.md`,
  `reference/portal.md`, and every `curl … /ingest` example; compatibility note
  on the break.

## 6. Gates and evidence

- [x] 6.1 All gates fresh after the last code edit; `evidence.md` with the
  commands, result tails and commit SHA.
- [ ] 6.2 `/opsx:archive` as the final commit.
