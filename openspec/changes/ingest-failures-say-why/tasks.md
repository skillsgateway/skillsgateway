# Tasks: ingest-failures-say-why

This change touches the registration trust boundary, so the work is done under
`.claude/skills/old-coder` (Tier 3). Each new test is written first and shown
failing against the unfixed code. Those failures are recorded in `evidence.md`.

## 1. Requirements (reqstool)

- [ ] 1.1 Add GW_INGEST_0038 — An upstream failure states its cause and a next step, with SVC_GW_INGEST_0038
- [ ] 1.2 Add GW_INGEST_0039 — The last ingest attempt is recorded on the marketplace, with SVC_GW_INGEST_0039
- [ ] 1.3 Add GW_INGEST_0040 — Registration refuses an upstream it cannot read, with SVC_GW_INGEST_0040
- [ ] 1.4 Add GW_INGEST_0041 — A declared marketplace with an unreadable upstream is registered and reported, with SVC_GW_INGEST_0041
- [ ] 1.5 Add GW_AUDIT_0010 — A failed ingestion is recorded in the ledger with its reason, with SVC_GW_AUDIT_0010

## 2. Tests first (shown failing)

- [ ] 2.1 `UpstreamFailureTests` (plain JUnit): U1–U4
- [ ] 2.2 `GitHttpFixture`: an `unauthorized(prefix)` mode that answers 401 with a Basic challenge
- [ ] 2.3 `UpstreamReachabilityTests`: S1–S5 and S9 (registration)
- [ ] 2.4 `IngestFailureTests`: S6–S8 (ingest failure recorded, cleared, and recorded on the sweep)
- [ ] 2.5 `EstateReconciliationTests`: S10
- [ ] 2.6 `marketplace-detail.test.tsx`: S11
- [ ] 2.7 Existing registration tests that register placeholder URLs and expect success move to reachable local fixtures, with their assertions unchanged

## 3. Implementation

- [ ] 3.1 `UpstreamFailure` (the translator, with redaction), `UpstreamException`, and `IngestionException` carrying an optional failure
- [ ] 3.2 `UpstreamGit`: `probe` and `fetchDefaultBranch`, one `connect` seam, a 60 s idle timeout, and the first error kept as suppressed
- [ ] 3.3 `IngestionService`: use `UpstreamGit`; record the last ingest; ledger `ingest-failed`; WARN log
- [ ] 3.4 `V1__init.sql`: `marketplace_last_ingest_outcome` enum and three columns with a consistency CHECK; `Marketplace` and `MarketplaceRepository.recordIngest`
- [ ] 3.5 `MarketplaceRegistrationService`: probe last, with `Reachability.REFUSE` or `REPORT`; `marketplace-upstream-unreachable` ledger entry on REPORT
- [ ] 3.6 `EstateReconciler`: register with REPORT and carry warnings into the entry detail
- [ ] 3.7 `AdminController`: problem properties on the `502`; `MarketplaceView` last-ingest fields; `@ApiResponse(502)` on registration
- [ ] 3.8 `AdminAuditLogger`: null-principal guard
- [ ] 3.9 Regenerate `openapi.json` and `types.gen.ts` (additions only)

## 4. Portal

- [ ] 4.1 Marketplace header: an alert for a failed last ingest (when and why); a Last ingest row in Settings; refresh the marketplaces after a failed ingest

## 5. Docs

- [ ] 5.1 Manual: registration (reachability check, 502 shape), ingestion (last-ingest record, `ingest-failed`), estate (unreachable declared upstreams), portal reference, audit event list

## 6. Gauntlet and close

- [ ] 6.1 `mutants.sh` (manual mutants M1–M8), all killed
- [ ] 6.2 All gates, `evidence.md`, then `/opsx:archive` as the final commit
