# Tasks: decompose-release-age-requirement

## 1. Requirements (SSOT first)

- [x] 1.1 Rewrite GW_APPROVAL_0004 in `docs/reqstool/requirements.yml` as a
      parent: the cross-cutting claim that the age gate is a self-clearing
      control every consulting path agrees on
- [x] 1.2 Add GW_APPROVAL_0004.1 – GW_APPROVAL_0004.5, one per independently
      verifiable guarantee, each with `references: requirement_ids:
      ["GW_APPROVAL_0004"]`, its own rationale and its own categories
- [x] 1.3 Rewrite SVC_GW_APPROVAL_0004 in
      `docs/reqstool/software_verification_cases.yml` to verify that the
      report and the gate agree, rather than all five guarantees
- [x] 1.4 Add SVC_GW_APPROVAL_0004.1 – SVC_GW_APPROVAL_0004.5
      (GIVEN/WHEN/THEN), each claiming only what its annotated test(s)
      actually assert

## 2. Move the test annotations (no test added, removed or weakened)

- [x] 2.1 `ReleaseAgeGateTests`: `eligibility_turns_over_exactly_at_the_
      boundary_instant` and `a_zero_minimum_is_no_gate_at_all` →
      `SVC_GW_APPROVAL_0004.2`; `the_remaining_time_is_rendered_as_a_
      reviewer_reads_it` → `SVC_GW_APPROVAL_0004.4`
- [x] 2.2 `MinimumReleaseAgeTests`:
      `a_snapshot_the_gateway_saw_moments_ago_cannot_be_approved_and_nothing_
      is_published` → `SVC_GW_APPROVAL_0004.3, SVC_GW_APPROVAL_0004.5`;
      `a_snapshot_that_has_cleared_the_window_is_approved_and_its_age_is_on_
      the_ledger` → `SVC_GW_APPROVAL_0004.5`; `a_backdated_commit_does_not_
      buy_its_way_past_the_window` and `re_ingesting_the_same_commit_does_
      not_restart_the_clock` → `SVC_GW_APPROVAL_0004.1`; `rejection_is_never_
      gated_by_age` → `SVC_GW_APPROVAL_0004.2`; `the_eligibility_endpoint_
      answers_what_the_gate_will_do` keeps `SVC_GW_APPROVAL_0004` alongside
      `SVC_GW_APPROVAL_0004.4` (the parent's bracket)

## 3. Move and add the implementation annotations

- [x] 3.1 `ReleaseAgeGate.evaluate(Snapshot)` → `.1`; the package-private
      pure `evaluate(long, Instant, Instant, Duration)` gains
      `@Requirements({"GW_APPROVAL_0004.2"})` — **new annotation, none
      existed before**; `.require` → `.3`; class javadoc → `.1`
- [x] 3.2 `ApprovalService.requireReleaseAge` gains
      `@Requirements({"GW_APPROVAL_0004.3", "GW_APPROVAL_0004.5"})` — **new
      annotation, none existed before**; `EVENT_REFUSED` and the `Approved`
      record javadoc → `.5`; `.releaseAge` → `.4`; both `approve(...)`
      overloads keep the bare parent id (orchestration)
- [x] 3.3 `AdminController`: the `/snapshots/{id}/release-age` endpoint → `.4`;
      `snapshotTooYoung` exception handler gains `@Requirements({"GW_APPROVAL_
      0004.3"})` — **new annotation**; the `approve` endpoint's annotation
      list gains `.5` (the success-path ledger entry is written there); the
      inline ledger comment → `.5`
- [x] 3.4 `SnapshotTooYoungException`'s class javadoc → `.3`;
      `SkillsGatewayProperties.minimumReleaseAge`'s javadoc → `.1` and `.2`
- [x] 3.5 Frontend: `queries.ts`'s `useSnapshotReleaseAge` JSDoc → `.4`;
      `marketplaces.tsx`'s `ApproveDialog` JSDoc → `.4`;
      `marketplaces.test.tsx`'s untagged-on-purpose comment → `.4`

## 4. Spec and gates

- [x] 4.1 Update `openspec/specs/snapshot-approval/spec.md`: add
      GW_APPROVAL_0004.1 – GW_APPROVAL_0004.5 with their SVCs as scenarios
- [x] 4.2 Delta spec under `specs/snapshot-approval/` in this change:
      GW_APPROVAL_0004 modified, GW_APPROVAL_0004.1 – GW_APPROVAL_0004.5 added
- [x] 4.3 Run every gate one final time and record `evidence.md`
- [x] 4.4 Archive the change as its own commit
