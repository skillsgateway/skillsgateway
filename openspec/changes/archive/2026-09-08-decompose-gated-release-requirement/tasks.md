# Tasks: decompose-gated-release-requirement

## 1. Requirements (SSOT first)

- [x] 1.1 Rewrite GW_RELEASE_0003 in `docs/reqstool/requirements.yml` as a
      parent: the cross-cutting claim that every publicly-visible or
      irreversible release step answers to one continuous chain of evidence
- [x] 1.2 Add GW_RELEASE_0003.1 – GW_RELEASE_0003.7, one per independently
      verifiable link in that chain, each with `references: requirement_ids:
      ["GW_RELEASE_0003"]`, its own rationale and its own categories
- [x] 1.3 Rewrite SVC_GW_RELEASE_0003 in
      `docs/reqstool/software_verification_cases.yml` to verify that the
      workflow's job dependencies form one connected chain, rather than all
      seven links individually
- [x] 1.4 Add SVC_GW_RELEASE_0003.1 – SVC_GW_RELEASE_0003.7 (GIVEN/WHEN/THEN),
      each claiming only what the existing assertions in the one annotated
      test method actually check — narrowing the release-candidate claim to
      "offered", not "never promoted", since no assertion checks the latter

## 2. Move the test annotation (no test added, removed or weakened)

- [x] 2.1 `PackagingTests.
      releaseWorkflowIsDispatchOnlyPreviewsByDefaultAndGatesBeforePublishing`
      keeps `SVC_GW_RELEASE_0003` and gains `SVC_GW_RELEASE_0003.1` through
      `.7` — the one method verifies all eight, so no annotation moves to a
      different method

## 3. Spec and gates

- [x] 3.1 Update `openspec/specs/release-packaging/spec.md`: add
      GW_RELEASE_0003.1 – GW_RELEASE_0003.7 with their SVCs as scenarios
- [x] 3.2 Delta spec under `specs/release-packaging/` in this change:
      GW_RELEASE_0003 modified, GW_RELEASE_0003.1 – GW_RELEASE_0003.7 added
- [x] 3.3 Record the GW_VETTING_0020 monolithic judgement in this change's
      `design.md`, since it was inspected on this branch and needs nowhere
      else to live
- [x] 3.4 Run every gate one final time and record `evidence.md`
- [x] 3.5 Archive the change as its own commit
