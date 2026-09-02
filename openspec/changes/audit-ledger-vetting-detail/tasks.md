## 1. Establish the current behaviour before changing it

- [x] 1.1 Confirm the audit path: `VettingService.run` emits `vetting-verdict`
      (one per connector) and `vetting-completed`, both under the `vetting`
      principal, through `AdminAuditLogger.record`.
- [x] 1.2 Confirm the actor mislabel: `vetting` is absent from
      `AdminAuditLogger.SYSTEM_ACTORS`, so vetting entries type as `HUMAN`; the
      portal reads `SELECT *` `/api/audit`, which carries `actor_type`.
- [x] 1.3 Confirm the null pass detail: `VettingRepository.detailOf` returns null
      when a verdict has no findings, and `secret-scan` / `prompt-injection`
      return `Verdict.of(findings)` with an empty list on a clean pass.

## 2. A clean pass records its coverage (GW_0143)

- [x] 2.1 Add a `summary` component to `Verdict` and an `of(findings, summary)`
      factory; keep `of(findings)` delegating with a null summary.
- [x] 2.2 `VettingRepository.detailOf` falls back to the verdict summary when
      there are no findings; update the `VerdictView.detail` schema description.
- [x] 2.3 `SecretScanConnector`, `PromptInjectionConnector` and
      `LicenseScanConnector` count what they examine and return a non-empty
      summary; annotate each `@Requirements({..., "GW_0143"})`.
- [x] 2.4 Test: a snapshot that trips no rule yields a non-null, coverage-stating
      `detail` on each passing verdict and in the ledger (`SVC_GW_0143`).

## 3. Verdict entries become self-describing and correctly attributed (GW_0142)

- [x] 3.1 `VettingService.verdictDetail` builds `connector=state; findings=N;
      worst=SEV; run=<id>`, appending the coverage summary for a clean pass; keep
      the `connector=state` lead so existing SVC_GW_0043 substrings still match.
- [x] 3.2 The `vetting-completed` detail gains `run=<id>`.
- [x] 3.3 Declare `VettingService.VETTING_ACTOR` and use it at both call sites;
      add it to `AdminAuditLogger.SYSTEM_ACTORS`.
- [x] 3.4 Annotate the enrichment `@Requirements({"GW_0043"})` and the actor
      classification path `@Requirements({..., "GW_0128"})`.
- [x] 3.5 Test: verdict entries carry the count, worst severity and run id; the
      completed entry carries the run id; every vetting entry types as `system`
      (`SVC_GW_0142`).
- [x] 3.6 Confirm the existing SVC_GW_0043 ledger test still passes unchanged.

## 4. Requirements and traceability

- [x] 4.1 Author `GW_0142`, `GW_0143` in `docs/reqstool/requirements.yml`.
- [x] 4.2 Author `SVC_GW_0142`, `SVC_GW_0143` in
      `docs/reqstool/software_verification_cases.yml`.
- [x] 4.3 Annotate the new tests with `@SVCs`.
- [ ] 4.4 `reqstool status local -p docs/reqstool` ends PASS after
      `./mvnw clean verify`. (Deferred: full verify not run under shared podman
      contention — see PR "Gates" note.)

## 5. Documentation

- [x] 5.1 `docs/manual/reference/audit-ledger.md` — the enriched vetting entry
      shape and the actor-kind row.
- [x] 5.2 The vetting guide notes a clean pass now states its coverage.

## 6. Gates and evidence

- [ ] 6.1 `./mvnw clean verify` (deferred — shared podman; ran the vetting/audit
      slice instead).
- [x] 6.2 Regenerate `openapi.json` (VerdictView.detail description change).
- [ ] 6.3 `(cd src/main/frontend && pnpm test:stories)` (frontend untouched;
      deferred).
- [ ] 6.4 `(cd src/main/frontend && pnpm e2e)` (deferred).
- [x] 6.5 `openspec validate --all --strict`.
- [ ] 6.6 `mkdocs build --strict` (run if docs toolchain present).
- [x] 6.7 Write `evidence.md`.
