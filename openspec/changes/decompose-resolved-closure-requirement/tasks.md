# Tasks: decompose-resolved-closure-requirement

## 1. Requirements (SSOT first)

- [x] 1.1 Rewrite GW_INGEST_0030 in `docs/reqstool/requirements.yml` as a
      parent: the ontological claim that the closure is a domain object of its
      snapshot, not detachable metadata
- [x] 1.2 Add GW_INGEST_0030.1 – GW_INGEST_0030.7, one per independently
      verifiable guarantee, each with `references: requirement_ids:
      ["GW_INGEST_0030"]`, its own rationale and its own categories
- [x] 1.3 Rewrite SVC_GW_INGEST_0030 in
      `docs/reqstool/software_verification_cases.yml` to verify the closure's
      existence bracketed by its snapshot's own lifecycle, rather than all
      seven guarantees
- [x] 1.4 Add SVC_GW_INGEST_0030.1 – SVC_GW_INGEST_0030.7 (GIVEN/WHEN/THEN),
      each claiming only what its annotated tests actually assert

## 2. Move the test annotations (no test added, removed or weakened)

- [x] 2.1 `SnapshotClosureTests`: `a_resolved_ingestion_records_the_closure_
      with_the_snapshot` → `SVC_GW_INGEST_0030, SVC_GW_INGEST_0030.1,
      SVC_GW_INGEST_0030.3`; `a_local_only_snapshot_records_no_closure_and_
      its_own_commit_as_upstream` and `a_rejected_resolution_records_no_
      closure` → `SVC_GW_INGEST_0030.2`; `the_provenance_carries_the_served_
      commit_the_upstream_commit_and_the_closure` → `SVC_GW_INGEST_0030.5`
      (alongside its existing `SVC_GW_INGEST_0004`); `the_policy_facts_carry_
      the_closure` → `SVC_GW_INGEST_0030.6`; `the_snapshots_containing_a_
      source_are_one_query_away` → `SVC_GW_INGEST_0030.7`; `the_same_closure_
      in_two_marketplaces_has_the_same_digest` → `SVC_GW_INGEST_0030.1`;
      `purging_the_snapshot_removes_its_closure` → `SVC_GW_INGEST_0030,
      SVC_GW_INGEST_0030.4`
- [x] 2.2 `SnapshotClosureTests` class javadoc records which method verifies
      which child
- [x] 2.3 `SnapshotClosureDigestTests`: all seven methods (six `@Test` plus the
      parameterized `every_member_field_is_an_input`) → `SVC_GW_INGEST_0030.1`;
      class javadoc id updated
- [x] 2.4 `marketplaces.test.tsx`: the untagged provenance-dialog test's
      comment updated to name `SVC_GW_INGEST_0030.5`

## 3. Move the implementation annotations

- [x] 3.1 `SnapshotClosure.digest` → `@Requirements({"GW_INGEST_0030.1"})`;
      class javadoc id updated
- [x] 3.2 `SnapshotClosureRepository.record` → `@Requirements({"GW_INGEST_
      0030.1", "GW_INGEST_0030.3"})`; `.findBySnapshot` → `@Requirements({
      "GW_INGEST_0030.1", "GW_INGEST_0030.4"})`; `.snapshotsContaining` →
      `@Requirements({"GW_INGEST_0030.7"})`; class javadoc id updated to `.4`
- [x] 3.3 `SnapshotRepository.create` (the `@Transactional` overload) →
      `@Requirements({"GW_APPROVAL_0010", "GW_FACADE_0009", "GW_INGEST_0030.2",
      "GW_INGEST_0030.3"})`; javadoc updated
- [x] 3.4 `IngestionService.ingestLocked` keeps `GW_INGEST_0030` alongside a
      new `GW_INGEST_0030.3`; `.serve` → `GW_INGEST_0030.1`; the private
      `closure` helper's javadoc → `GW_INGEST_0030.1`
- [x] 3.5 `ApprovalService.provenance` → `@Requirements({"GW_INGEST_0004",
      "GW_INGEST_0030.5"})`
- [x] 3.6 `SnapshotFactsService.build` → `@Requirements({"GW_APPROVAL_0007",
      "GW_INGEST_0030.6"})`; javadoc updated
- [x] 3.7 `marketplaces.tsx` `ProvenanceDialog` JSDoc → `@Requirements
      GW_INGEST_0030.5`
- [x] 3.8 `ExternalSourceResolver.Resolved` javadoc → `GW_INGEST_0030.1`;
      `V1__init.sql`'s `upstream_sha` column comment → `GW_INGEST_0030.2`;
      `snapshot_closures` table comment → `GW_INGEST_0030.1, .3, .4`

## 4. Spec and gates

- [x] 4.1 Update `openspec/specs/marketplace-ingestion/spec.md`: add
      GW_INGEST_0030.1 – GW_INGEST_0030.7 with their SVCs as scenarios
- [x] 4.2 Delta spec under `specs/marketplace-ingestion/` in this change:
      GW_INGEST_0030 modified, GW_INGEST_0030.1 – GW_INGEST_0030.7 added
- [ ] 4.3 Run every gate one final time and record `evidence.md`
- [ ] 4.4 Archive the change as the final commit of the PR
