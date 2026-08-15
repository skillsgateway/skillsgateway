# Tasks: add-vetting-chain

## 1. Requirements SSOT

- [x] 1.1 Add GW_0037 (ordered chain at ingestion), GW_0038 (fail-closed aggregation), GW_0039 (secret scanner), GW_0040 (prompt-injection scanner), GW_0041 (approval gate and override), GW_0042 (portal surface), GW_0043 (ledger) to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0037–SVC_GW_0043 (automated-test) to `docs/reqstool/software_verification_cases.yml`

## 2. Persistence

- [x] 2.1 Fold `vetting_runs`, `vetting_verdicts`, `vetting_findings` (+ the latest-run index) into `V1__init.sql`
- [x] 2.2 Add the nullable `detail` column to `fetch_log`; extend `FetchLogRepository.append` and `AuditEntry`
- [x] 2.3 `VettingRepository`: start a run, record a verdict with its findings, finish a run with its outcome, read the latest run for a snapshot, stamp an override

## 3. Connector SPI and chain

- [x] 3.1 `VettingConnector` SPI, `SnapshotUnderVetting` (sha, marketplace, path/bytes walk), `Verdict`, `VerdictState`, `Finding`, `Severity`
- [x] 3.2 `VettingChain.aggregate(...)`: pure fail-closed aggregation — cleared only when non-empty and every state is PASS or WARN — `@Requirements({"GW_0038"})`
- [x] 3.3 `VettingService.vet(snapshot)`: run every connector in order, wrap each in try/catch plus a per-connector timeout producing an ERROR verdict, persist the run, verdicts and findings — `@Requirements({"GW_0037","GW_0038"})`
- [x] 3.4 `QuarantineSnapshotAccess`: read-only walk over the pinned commit tree with the `max-file-bytes` cap reported as an INFO finding
- [x] 3.5 `skills-gateway.vetting.*` on `SkillsGatewayProperties` (`timeout`, `max-file-bytes`)

## 4. Built-in connectors

- [x] 4.1 `SecretScanConnector`: rule set (AWS key id and secret, PEM private-key block, GitHub/Slack/Google tokens, JWT, assignment-shaped high-entropy value) — `@Requirements({"GW_0039"})`
- [x] 4.2 `PromptInjectionConnector`: rule set over Markdown instruction content (instruction override, system-prompt disclosure, credential paths, concealment, pipe-to-shell, invisible/bidi Unicode) — `@Requirements({"GW_0040"})`

## 5. Ingestion, approval and the ledger

- [x] 5.1 `IngestionService.ingest`: run the chain for a newly recorded held snapshot — `@Requirements({"GW_0037"})`
- [x] 5.2 `ApprovalService.approve(id, reviewer, overrideReason)`: refuse a blocked snapshot without a reason, stamp the override on the run — `@Requirements({"GW_0041"})`
- [x] 5.3 `AdminController.approve`: optional request body, 409 `ProblemDetail` naming the blocking connectors
- [x] 5.4 Ledger entries for the run outcome, each verdict, and the override with its reason — `@Requirements({"GW_0043"})`
- [x] 5.5 `WebhookEvent.SNAPSHOT_VETTED` in `ALL`, emitted after a chain run

## 6. API

- [x] 6.1 `VettingController`: `GET /api/snapshots/{id}/vetting` returning the latest run with its verdicts and findings, `@Tag`/`@Operation`/`@ApiResponse`, `@Schema` on the DTOs

## 7. Portal (GW_0042)

- [x] 7.1 Regenerate `src/main/frontend/src/api/types.gen.ts`; add `useSnapshotVetting`, extend `useDecideSnapshot` with the override reason, add MSW handlers
- [x] 7.2 `marketplace-detail.tsx`: vetting card per snapshot — outcome badge, per-connector verdicts, findings — JSDoc `@Requirements GW_0042`
- [x] 7.3 `marketplaces.tsx`: approving a blocked snapshot opens a dialog demanding a reason — JSDoc `@Requirements GW_0042`

## 8. Tests

- [x] 8.1 Ingestion records a run with a verdict per connector in chain order — `@SVCs({"SVC_GW_0037"})`
- [x] 8.2 Adversarial: a connector that throws yields an ERROR verdict, a blocked outcome, and a still-held, unserved snapshot; plus exhaustive aggregation over every state combination — `@SVCs({"SVC_GW_0038"})`
- [x] 8.3 Planted AWS key and PEM block are found with their paths; clean content passes — `@SVCs({"SVC_GW_0039"})`
- [x] 8.4 Planted injection markers (override phrasing, credential path, zero-width character) are found; clean content passes — `@SVCs({"SVC_GW_0040"})`
- [x] 8.5 Adversarial: approve without a reason is refused and publishes nothing; with a reason it approves and records it — `@SVCs({"SVC_GW_0041"})`
- [x] 8.6 Ledger holds the run outcome, the verdicts, and the override with its reason — `@SVCs({"SVC_GW_0043"})`
- [x] 8.7 Playwright: verdicts shown, approval blocked until a reason is given — `@SVCs SVC_GW_0042`

## 9. Documentation

- [x] 9.1 New `docs/manual/concepts/vetting.md`: connector model, verdict lifecycle, fail-closed semantics, the override and its hand-off to waivers, honest limits of the heuristic scanners, Mermaid diagram of the chain inside the ingestion flow; add to `mkdocs.yml` nav
- [x] 9.2 `reference/configuration.md`: `skills-gateway.vetting.*` block
- [x] 9.3 `reference/portal.md`: vetting card and the override dialog
- [x] 9.4 `reference/api/marketplaces.md` and `reference/api/audit.md`: the vetting endpoint, the approve body, the ledger `detail` field
- [x] 9.5 `concepts/lifecycle.md` and `concepts/glossary.md`: the chain in the lifecycle, connector/verdict/finding terms

## 10. Gates and archive

- [x] 10.1 `./mvnw clean verify`, `(cd src/main/frontend && pnpm e2e)`, `reqstool status local -p docs/reqstool`, `openspec validate --all --strict`, `mkdocs build --strict`
- [x] 10.2 Write `openspec/changes/add-vetting-chain/evidence.md` with the fresh gate run, then archive the change
