# Design: an unchanged re-vet writes two ledger rows

## Context

`VettingService.run(snapshot, marketplace, trigger)` is the one place the chain
executes. It opens a `vetting_runs` row, and for each vetter records the verdict
in `vetting_verdicts` and appends a `vetting-verdict` ledger row, then finishes
the run and appends `vetting-completed`. `RevetService` calls it with trigger
`revet-scheduled` or `revet-manual` (also for a held snapshot's evidence refresh)
and afterwards appends its own `revet-*` / `held-evidence-refreshed` rows.
`IngestionService` calls it through `vet(...)` with trigger `ingestion`.

The trigger string is therefore the whole of "is this a re-vet", and it is
already stored on the run (`VettingRepository.Run.revet()` reads it back).

The portal's vetting report and `/api/v1/snapshots/{id}/vetting` read
`vetting_verdicts`; the portal's Activity/Audit views render ledger rows
generically and parse only `outcome=` out of a `vetting-completed` detail
(`src/main/frontend/src/lib/audit-status.ts`). No code or test parses the
`vetting-verdict` count or any other `vetting-completed` field.

## Goals / Non-Goals

**Goals:** make the ledger copy of a verdict conditional on re-vets only; state
the omission with `changed=<k>` on `vetting-completed`.

**Non-Goals:** no change to what is stored in `vetting_verdicts`, to the ingestion
run's rows, to any `revet-*` row, to the webhook events, or to the schema.

## Decisions

1. **Compare in `VettingService.run`, not in `RevetService`.** The verdict row is
   written inside the chain loop; deciding there keeps one writer. `RevetService`
   would otherwise have to suppress and re-emit rows the chain already wrote.

2. **"Previous run" is the snapshot's latest *finished* run with a lower id than
   the one just started** — a new `VettingRepository.previousRun(snapshotId,
   runId)`, read once after `startRun`. Reading it after `startRun` and bounding
   by id makes it "the run before this one" even if another pass starts
   concurrently. An unfinished run (a crash mid-chain, or a pass still running) is
   skipped: its verdict set may be partial, and comparing with it would say
   "unchanged" about nothing. When there is no previous finished run, every
   verdict counts as changed, so a re-vet never writes fewer rows than it can
   justify.

3. **"Differs" is exactly the decided rule:** state, finding count, or worst
   severity differ, or `name@version` was not in the previous run's chain. Those
   are the fields the `vetting-verdict` row carries beyond the run id, so an
   unchanged verdict would have produced the same row. The previous run's chain
   membership is read from its stored identity (`a@1,b@2;mode=run-all`: the part
   before `;`, split on `,`), which `VettingService.chainIdentity` composes in
   the same class. Alternative rejected: storing a version per verdict row — a
   schema change for information the chain identity already holds.

   A vetter whose coverage summary changed but whose state, count and worst
   severity did not (an informational pass that skipped a different file) is not
   counted as changed. The pinned content is the same on every re-vet, so such a
   change comes from a new vetter version, which the `name@version` rule already
   catches.

4. **`changed=<k>` appears only on a re-vet's `vetting-completed`**, placed
   before `run=` so that `chain=`, whose value contains `;`, stays last. The
   ingestion run's detail is byte-for-byte what it was.

5. **The snapshot-access failure path is untouched.** When the content cannot
   be opened the run records a `snapshot-access` error verdict in the table and,
   as today, no `vetting-verdict` ledger row; `changed` counts only rows written.

## Risks / Trade-offs

- [A SIEM that counts `vetting-verdict` rows per run sees fewer after upgrade] →
  release note in the PR: count runs by `vetting-completed`; `changed=` says how
  many verdict rows the run wrote.
- [An auditor reading only the ledger cannot see an unchanged vetter's verdict
  for a given re-vet] → the verdict is the previous row for that vetter on the
  same SHA; `vetting_verdicts` holds every run's verdicts, and the REST vetting
  report serves them.
- [Two overlapping passes compare with the same previous run and may both write
  the same changed row] → acceptable: an over-report, never an omission.

## Migration Plan

None. No schema change; the behaviour takes effect on the next run.
