# Tasks: decompose-composite-snapshot-requirement

## 1. Requirements (SSOT first)

- [x] 1.1 Rewrite GW_INGEST_0024 in `docs/reqstool/requirements.yml` as a
      parent: the ontological claim that the synthesised commit is the
      snapshot, not metadata beside a separate identity
- [x] 1.2 Add GW_INGEST_0024.1 – GW_INGEST_0024.7, one per independently
      verifiable guarantee, each with `references: requirement_ids:
      ["GW_INGEST_0024"]`, its own rationale and its own categories
- [x] 1.3 Rewrite SVC_GW_INGEST_0024 in
      `docs/reqstool/software_verification_cases.yml` to verify that the same
      composite commit is pinned at ingestion and served to a client at the
      end, rather than all seven guarantees
- [x] 1.4 Add SVC_GW_INGEST_0024.1 – SVC_GW_INGEST_0024.7 (GIVEN/WHEN/THEN),
      each claiming only what its annotated tests actually assert

## 2. Move the test annotations (no test added, removed or weakened)

- [x] 2.1 `ManifestRewriterTests`: `the_rewritten_manifest_points_only_
      inside_the_commit` and `the_grafted_content_is_present_under_the_
      reserved_directory` → `SVC_GW_INGEST_0024.1`; `the_upstream_commit_is_
      the_parent_and_its_manifest_stays_byte_exact` → `SVC_GW_INGEST_0024.3`;
      `the_commit_records_the_upstream_commit_each_source_and_the_
      transformer` → `SVC_GW_INGEST_0024.5`; `the_same_inputs_produce_the_
      same_commit`, `a_different_resolved_commit_produces_a_different_
      composite` and `the_transformer_version_is_an_input_to_the_composite_
      identity` → `SVC_GW_INGEST_0024.4`; `a_reserved_directory_already_
      present_upstream_is_refused_with_no_commit`,
      `a_plugin_name_that_is_not_a_single_lowercase_path_segment_is_refused_
      with_no_commit` and `two_external_plugins_sharing_a_name_are_refused_
      with_no_commit` → `SVC_GW_INGEST_0024.6`; `a_graft_for_a_plugin_the_
      manifest_does_not_declare_is_refused_with_no_commit` keeps
      `SVC_GW_INGEST_0024` alone; `the_composite_manifest_is_put_back_
      through_the_local_only_gate` → `SVC_GW_INGEST_0021,
      SVC_GW_INGEST_0024.7`
- [x] 2.2 `ManifestRewriterTests` class javadoc records which child (or the
      parent) each test verifies
- [x] 2.3 `ExternalSourceResolutionTests`: `an_admitted_source_becomes_a_
      held_composite_whose_manifest_is_gateway_local` → `SVC_GW_INGEST_0023,
      SVC_GW_INGEST_0024, SVC_GW_INGEST_0024.1, SVC_GW_INGEST_0024.2`;
      `the_composite_is_parented_on_the_upstream_commit_whose_manifest_stays_
      byte_exact` → `SVC_GW_INGEST_0024.3, SVC_GW_INGEST_0024.5`;
      `the_content_inventory_lists_the_external_plugins_skills_with_no_api_
      change` and `a_secret_planted_in_the_external_repository_is_found_by_
      vetting` → `SVC_GW_INGEST_0024.1`; `a_client_cloning_the_approved_
      snapshot_receives_a_manifest_with_no_external_url` →
      `SVC_GW_INGEST_0024, SVC_GW_INGEST_0024.1`; `re_ingesting_unchanged_
      content_produces_the_same_snapshot` and `an_external_repository_that_
      moves_on_produces_a_new_composite_and_a_new_snapshot` →
      `SVC_GW_INGEST_0023, SVC_GW_INGEST_0024.4`; `a_marketplace_that_
      already_uses_the_reserved_directory_is_refused` → `SVC_GW_INGEST_
      0024.6`

## 3. Move the implementation annotations

- [x] 3.1 `ManifestRewriter.rewrite` → `@Requirements({"GW_INGEST_0021",
      "GW_INGEST_0024.1", "GW_INGEST_0024.3", "GW_INGEST_0024.4",
      "GW_INGEST_0024.5", "GW_INGEST_0024.6", "GW_INGEST_0024.7"})`; class
      javadoc id updated to `.1`
- [x] 3.2 `IngestionService.ingestLocked` keeps `GW_INGEST_0024` alongside a
      new `GW_INGEST_0024.2`; `.serve` → `GW_INGEST_0024.1`
- [x] 3.3 `SkillsGatewayProperties.ExternalSources`'s javadoc → `GW_INGEST_
      0024.1`; `V1__init.sql`'s `snapshots.sha` column comment → `GW_INGEST_
      0024.2`

## 4. Spec and gates

- [x] 4.1 Update `openspec/specs/marketplace-ingestion/spec.md`: add
      GW_INGEST_0024.1 – GW_INGEST_0024.7 with their SVCs as scenarios
- [x] 4.2 Delta spec under `specs/marketplace-ingestion/` in this change:
      GW_INGEST_0024 modified, GW_INGEST_0024.1 – GW_INGEST_0024.7 added
- [x] 4.3 Run every gate one final time and record `evidence.md`
- [x] 4.4 Archive the change as the final commit of the PR
