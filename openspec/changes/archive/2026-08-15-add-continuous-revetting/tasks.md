# Tasks: add-continuous-revetting

## 1. Traceability (SSOT first)

- [x] 1.1 Add GW_0049–GW_0055 to `docs/reqstool/requirements.yml`.
- [x] 1.2 Add SVC_GW_0049–SVC_GW_0055 to `docs/reqstool/software_verification_cases.yml`.

## 2. Schema

- [x] 2.1 `snapshots.state` check gains `revoked`; add `revoked_at` and `revoked_by`.
- [x] 2.2 `vetting_runs` gains `chain`; document the re-vetting triggers on the `trigger` column.
- [x] 2.3 Partial index for the sweep's queue.

## 3. Domain and classification

- [x] 3.1 `Snapshot.REVOKED` and `Snapshot.decidable()`.
- [x] 3.2 `SnapshotRepository.revoke` (conditional on `approved`), `dueRevet`, `approvedByMarketplace`;
      `decide` accepts `revoked` and clears the revocation marks.
- [x] 3.3 Retention queries name their deletable states explicitly.
- [x] 3.4 `RevetVerdict.classify` — the pure VIOLATION / INCONCLUSIVE / CLEAR rule.
- [x] 3.5 `VettingConnector.version` and `VettingService.chainIdentity`; `VettingService.run(trigger)`.
- [x] 3.6 `GitStorage.unpublish` — both published refs, tip-conditional on main.
- [x] 3.7 `FetchLogRepository.fetchersOf`.

## 4. Service, schedule and API

- [x] 4.1 `RevetService`: sweep, marketplace pass, single snapshot, violation record, quarantine.
- [x] 4.2 `SkillsGatewayProperties.Revet` and `RevetMode`, warn by default.
- [x] 4.3 `RevetScheduler`.
- [x] 4.4 `RevetController`: snapshot revet, marketplace revet, fetchers.
- [x] 4.5 `ApprovalService.approve` gates a revoked snapshot; approve/reject docs updated.
- [x] 4.6 `snapshot.revet_violation` and `snapshot.revoked` webhook events.

## 5. Portal

- [x] 5.1 Shared `SnapshotStateBadge` with `revoked`, replacing the two duplicated copies.
- [x] 5.2 `RevetPanel`: **Re-vet now**, revocation note, already-fetched-by list.
- [x] 5.3 **Re-approve** on a revoked snapshot in the marketplaces table.
- [x] 5.4 `useRevetSnapshot` / `useSnapshotFetchers`; regenerate `openapi.json` and `types.gen.ts`.
- [x] 5.5 MSW fixtures for the revoked snapshot, the fetchers and the revet endpoint.

## 6. Tests

- [x] 6.1 `RevetTests` (warn context): sweep selection and ordering, warn never unpublishes
      (verified with a real `git clone`), clean re-vet, waiver-after-violation, non-approved refused.
- [x] 6.2 `RevetEnforceTests` (enforce context): revoke + facade 404 + ref-by-name refused +
      quarantine intact + re-approval only through a fresh recorded decision; connector error never
      revokes, plus the exhaustive pure classification rule; blast radius excludes ref-only clients;
      the full ledger trail; retention's treatment of `revoked`.
- [x] 6.3 Frontend: revoked snapshot shows its violation and who fetched it.
- [x] 6.4 Playwright `SVC_GW_0055`: waive → approve → revoke waivers → re-vet → revoked, with the
      violation and the affected list on the page. E2E gateway runs in `enforce`.

## 7. Documentation

- [x] 7.1 New guide `guides/re-vetting.md`; added to `mkdocs.yml` nav.
- [x] 7.2 `reference/configuration.md`: the `revet` block.
- [x] 7.3 `reference/api/marketplaces.md`: the three endpoints; approve/reject status codes.
- [x] 7.4 State machine updated in `concepts/snapshots-and-ledger.md`; `concepts/lifecycle.md`,
      `concepts/vetting.md`, `concepts/trust-boundaries.md`, `concepts/glossary.md`.
- [x] 7.5 Every "there is no revocation state" claim retracted: `guides/approving-snapshots.md`,
      `guides/lifecycle-webhooks.md`, `reference/retention.md`, `reference/api/index.md`.
- [x] 7.6 `reference/portal.md` re-vetting panel; `reference/api/audit.md` event catalogue.
- [x] 7.7 `ARCHITECTURE.md` §5 recall paragraph records what is implemented and what is deferred.

## 8. Gates

- [x] 8.1 `./mvnw clean verify`
- [x] 8.2 `(cd src/main/frontend && pnpm e2e)`
- [x] 8.3 `reqstool status local -p docs/reqstool` → PASS
- [x] 8.4 `openspec validate --all --strict`
- [x] 8.5 `mkdocs build --strict`
- [x] 8.6 Record all five in `evidence.md`.
