# Tasks: publication-reconciliation

Finding F3 of `docs/analysis/2026-09-08-architecture-assessment.md`, taking the
option the finding itself offers: build the reconciliation, which makes the
reordering optional, rather than reorder.

## 1. Requirements (SSOT first)

- [x] 1.1 GW_APPROVAL_0014 — A recorded approval that is not served is repaired
      before the gateway serves again
- [x] 1.2 SVC_GW_APPROVAL_0014
- [x] 1.3 GW_APPROVAL_0012 left unchanged. Reversing the order would have forced
      an amendment to it; not reversing leaves it exactly as specified

## 2. The reconciliation

- [x] 2.1 `PublicationReconciler`, `SmartInitializingSingleton` — after Flyway,
      before the web server, following `EstateBootstrap`'s precedent
- [x] 2.2 Per marketplace: live approved rows against `refs/snapshots/*`
- [x] 2.3 Approved-but-not-served republished; idempotent by SHA
- [x] 2.4 The newest approved snapshot republished last after any repair, so the
      served tip is not left on an older one
- [x] 2.5 Served-but-not-approved reported, never retracted
- [x] 2.6 Serving nothing repaired; unreadable skipped — and the two kept
      distinguishable in the return type rather than collapsed into one empty
- [x] 2.7 Ledger entries under `publication-reconciler`, `ActorType.SYSTEM`
- [x] 2.8 Ref writes go through `storage.publish`, so `RefResultDisciplineTests`
      still holds — it fails any `updateRef(` in main sources outside
      `RefTransitions`

## 3. The comment that lied

- [x] 3.1 `ApprovalService.repair()`'s javadoc now names `PublicationReconciler`
      and says plainly that until it existed the sentence named nothing

## 4. Tests

- [x] 4.1 Approved-but-not-served: repaired, and the repair is on the ledger
- [x] 4.2 Served-but-not-approved: reported, and the refs are untouched
- [x] 4.3 Serving nothing at all: repaired rather than skipped — the case the
      first draft got wrong, found by re-reading rather than by a failing test
- [x] 4.4 The state is arranged by deleting refs behind the gateway's back,
      because no public API reaches it: the double failure needs two faults
      inside one call
- [x] 4.5 **Discrimination checked:** disabling the repair fails exactly one of
      the three, and the report-only test still passes, which is correct

## 5. Docs in the same PR

- [x] 5.1 The startup step, beside the estate reconciliation it follows
- [x] 5.2 The two ledger events and the new system-actor principal
- [x] 5.3 What an operator does about `publication-served-not-approved`

## 6. Gates

- [x] 6.1 `./mvnw clean verify`
- [x] 6.2 `pnpm test:stories`
- [x] 6.3 `pnpm e2e`
- [x] 6.4 `reqstool status local -p docs/reqstool` ends PASS
- [x] 6.5 `openspec validate --all --strict`
- [x] 6.6 `mkdocs build --strict`
- [x] 6.7 `evidence.md`
