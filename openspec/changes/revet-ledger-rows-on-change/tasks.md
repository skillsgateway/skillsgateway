# Tasks: revet-ledger-rows-on-change

## 1. Requirements

- [x] 1.1 In `docs/reqstool/requirements.yml`, amend `GW_VETTING_0017 — Re-vetting
      and revocation are audit-logged` with the conditional verdict-row rule and
      the changed count on the run-completed entry; bump its revision to 0.3.0.
      Narrow "every vetter verdict" in `GW_VETTING_0006 — Vetting actions are
      audit-logged` (0.4.0) and `GW_VETTING_0022 — A vetting ledger entry is
      self-describing and correctly attributed` (0.3.0) to the verdicts
      `GW_VETTING_0017` requires. Verify: `reqstool status` lists the ids.
- [x] 1.2 In `software_verification_cases.yml`, add `SVC_GW_VETTING_0017.1` (an
      unchanged re-vet appends exactly the run-completed entry with a zero
      changed count and the re-vet outcome entry, and no verdict entries) and
      `SVC_GW_VETTING_0017.2` (a re-vet in which one vetter's verdict changed
      appends a verdict entry for that vetter only and a changed count of one).
      Existing SVCs stand unchanged.

## 2. Implementation

- [x] 2.1 `VettingRepository.previousRun(snapshotId, runId)`: the snapshot's
      latest finished run with a lower id, with its verdicts and findings; and
      a static `isRevet(trigger)` that `Run.revet()` delegates to.
- [x] 2.2 `VettingService.run`: after `startRun`, on a re-vet read the previous
      run; write each `vetting-verdict` row only when the verdict differs
      (state, finding count, worst severity, or `name@version` absent from the
      previous chain); append `changed=<k>` before `run=` in a re-vet's
      `vetting-completed` detail. The `vetting_verdicts` insert stays
      unconditional. Add `GW_VETTING_0017` to the method's `@Requirements`.
- [x] 2.3 `RevetTests`: `SVC_GW_VETTING_0017.1` — ingest (per-vetter rows plus
      completed, no `changed=`), approve, re-vet: the re-vet adds exactly
      `vetting-completed` (`changed=0`) and `revet-clear`, and the run's
      `vetting_verdicts` still hold every vetter. `SVC_GW_VETTING_0017.2` —
      disable one vetter for the marketplace, re-vet: exactly one
      `vetting-verdict` (that vetter, `disabled`) and `changed=1`. Prove both
      fail against the unmodified `VettingService` first. Verify with
      `./mvnw test -Dtest=RevetTests`, then `VettingTests`, `RevetEnforceTests`,
      `VettingChainSettingsTests`, `LicensePolicyTests` stay green.
- [x] 2.4 `RevetVerdictChangeTests` (no Spring context, `SVC_GW_VETTING_0017.2`):
      the change rule case by case — same verdict unchanged; state, count, worst
      severity, new version, missing verdict, no previous run each a change.
      Verify: removing any one condition from the rule fails a case.

## 3. Documentation

- [x] 3.1 Update every page that says per-vetter rows are written on every run:
      `reference/api/audit.md` event table (`vetting-verdict`, `vetting-completed`
      with `changed=` and `run=`), `guides/exporting-the-audit-ledger.md` "What a
      vetting entry carries", `guides/re-vetting.md` ledger table,
      `concepts/vetting.md`, `guides/adding-an-external-vetter.md`; grep
      `docs/manual` for any other site. Verify: `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 Run all gates fresh after the last code edit (`./mvnw clean verify`,
      `pnpm test:stories`, `pnpm e2e`, `reqstool status local -p docs/reqstool`,
      `openspec validate --all --strict`, `mkdocs build --strict`) and write
      `evidence.md` with the commands, result tails and the commit SHA.
