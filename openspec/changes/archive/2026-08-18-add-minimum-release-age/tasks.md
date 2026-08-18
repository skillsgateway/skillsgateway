# Tasks: add-minimum-release-age

## 1. Traceability (SSOT first)

- [x] 1.1 Add GW_0073 to `docs/reqstool/requirements.yml`.
- [x] 1.2 Add SVC_GW_0073 to `docs/reqstool/software_verification_cases.yml`.

## 2. Backend

- [x] 2.1 `SkillsGatewayProperties.Vetting.minimumReleaseAge` (duration,
      default `0` = off).
- [x] 2.2 `approval/ReleaseAgeGate`: the pure `(firstSeen, now, minimum)`
      eligibility rule, the `Eligibility` view, and `require(snapshot)`;
      `@Requirements({"GW_0073"})`.
- [x] 2.3 `approval/SnapshotTooYoungException` naming the setting, the age and
      the time remaining.
- [x] 2.4 `ApprovalService.approve`: the age precondition after the vetting
      gate, inside the decidable branch; `Approved` carries the age it allowed;
      the refusal is appended to the ledger.
- [x] 2.5 `AdminController`: the 409 problem-detail handler, the age on the
      `snapshot-approved` ledger entry, and
      `GET /api/snapshots/{id}/release-age` with OpenAPI annotations.

## 3. API artifacts

- [x] 3.1 Regenerate `src/main/frontend/openapi.json` and `types.gen.ts`.

## 4. Tests (SVC_GW_0073)

- [x] 4.1 Boundary: the rule evaluated at `firstSeen + minimum` is eligible,
      and one nanosecond earlier is not.
- [x] 4.2 Gate on: a snapshot ingested moments ago is refused with the setting,
      its age and the remaining time; nothing is published; the refusal is on
      the ledger. An aged snapshot approves, with its age on the ledger.
- [x] 4.3 Adversarial: a commit backdated far beyond the window is refused
      exactly as a fresh one — the gate never reads committer dates.
- [x] 4.4 Re-ingestion of the same commit does not reset the first-sighting
      instant, so it cannot reset eligibility.
- [x] 4.5 Rejection is never age-gated; a revoked snapshot's re-approval is
      unaffected (its first sighting is long past).
- [x] 4.6 `GET /api/snapshots/{id}/release-age` agrees with the gate in both
      directions.
- [x] 4.7 The zero default disables the gate entirely (the shared fixture's
      existing approvals are the assertion).
- [x] 4.8 Portal: the approve control is disabled and names the remaining time
      when a snapshot is not yet eligible, and enabled when it is.

## 5. Documentation

- [x] 5.1 `reference/configuration.md`: the setting, the clock, and the
      no-override trade-off.
- [x] 5.2 `guides/approving-snapshots.md`: the wait, the 409, and the
      break-glass; the status table row.
- [x] 5.3 `concepts/vetting.md` cross-reference; ARCHITECTURE §5 note that the
      cooling-off window now exists for manual approval.

## 6. Gates and evidence

- [x] 6.1 `./mvnw clean verify`
- [x] 6.2 `(cd src/main/frontend && pnpm e2e)`
- [x] 6.3 `reqstool status local -p docs/reqstool` → PASS
- [x] 6.4 `openspec validate --all --strict`
- [x] 6.5 `mkdocs build --strict`
- [x] 6.6 `evidence.md` with the final commit SHA.
