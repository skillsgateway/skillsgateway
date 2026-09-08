# Tasks: decompose-claim-role-mapping-requirement

## 1. Requirements (SSOT first)

- [x] 1.1 Rewrite GW_AUTH_0015 in `docs/reqstool/requirements.yml` as a
      parent: the cross-cutting claim that a mapping is validated once and,
      once valid, is exact and reportable at every point it is consulted
- [x] 1.2 Add GW_AUTH_0015.1 – GW_AUTH_0015.3, one per independently
      verifiable guarantee, each with `references: requirement_ids:
      ["GW_AUTH_0015"]`, its own rationale and its own categories
- [x] 1.3 Rewrite SVC_GW_AUTH_0015 in
      `docs/reqstool/software_verification_cases.yml` to bracket
      validation-then-consumption rather than restate all three guarantees
- [x] 1.4 Add SVC_GW_AUTH_0015.1 – SVC_GW_AUTH_0015.3 (GIVEN/WHEN/THEN), each
      claiming only what its annotated test(s) actually assert

## 2. Move the test annotations (no test added, removed or weakened)

- [x] 2.1 `ClaimRoleMapperTests`: `a_claim_list_a_lone_string_and_a_nested_
      path_all_resolve`, `a_delimited_string_is_one_value_and_is_never_
      split`, `duplicate_and_repeated_claim_values_yield_one_role_each`,
      `a_credential_that_is_not_an_identity_provider_session_yields_nothing`
      and `arbitrary_claim_payloads_never_throw_and_never_grant_without_an_
      exact_match` → `SVC_GW_AUTH_0015.1`; `a_malformed_mapping_refuses_
      construction` → `SVC_GW_AUTH_0015, SVC_GW_AUTH_0015.2` (the parent's
      bracket)
- [x] 2.2 `ClaimRoleMappingTests`: `a_mapped_admin_claim_grants_the_admin_
      surface_with_no_grant_row` → `SVC_GW_AUTH_0015, SVC_GW_AUTH_0015.1,
      SVC_GW_AUTH_0015.3` (the parent's other bracket half); `a_mapped_
      approver_claim_acts_only_on_its_own_marketplace_including_through_
      bare_ids`, `a_mapped_auditor_claim_reads_the_ledger_and_is_refused_
      every_mutation`, `claim_values_that_only_resemble_a_mapping_grant_
      nothing` and `a_credential_without_identity_provider_claims_derives_
      nothing` → `SVC_GW_AUTH_0015.1`; `claim_roles_union_with_configured_
      admins_and_stored_grants` → `SVC_GW_AUTH_0015.1, SVC_GW_AUTH_0015.3`;
      `a_mapping_naming_an_unregistered_marketplace_starts_and_matches_
      nothing` → `SVC_GW_AUTH_0015.1, SVC_GW_AUTH_0015.2, SVC_GW_AUTH_0015.3`
      (the estate-reconciler test on the same fixture keeps its own
      `SVC_GW_ESTATE_0003`, untouched)
- [x] 2.3 `ClaimMappedRoleIsEnforcedTests.a_mapped_auditor_reads_the_ledger_
      and_is_refused_an_admin_mutation` → `SVC_GW_AUTH_0015.1,
      SVC_GW_AUTH_0015.3`
- [x] 2.4 e2e `portal.spec.ts`'s `the_session_holds_an_admin_role_derived_
      from_the_identity_providers_group_claim` → `SVC_GW_AUTH_0015.3,
      SVC_GW_AUTH_0025`

## 3. Move the implementation annotations

- [x] 3.1 `ClaimRoleMapper.validated` → `GW_AUTH_0015, GW_AUTH_0015.2` (keeps
      the bare parent — see design.md Decision 2); `.rolesFrom` → `.1`; class
      javadoc → `.1`
- [x] 3.2 `RoleService.effectiveRoles` → `GW_AUTH_0013, GW_AUTH_0015.1,
      GW_AUTH_0015.3, GW_AUTH_0028`; its javadoc split accordingly
- [x] 3.3 `MeController.me` → `GW_AUTH_0013, GW_AUTH_0015.3, GW_AUTH_0016`
- [x] 3.4 `SkillsGatewayProperties.ClaimMapping`'s javadoc → `.1` and `.2`;
      `docs/manual/reference/configuration.md`'s config example comment →
      `.1`

## 4. Spec and gates

- [x] 4.1 Update `openspec/specs/admin-roles/spec.md`: add GW_AUTH_0015.1 –
      GW_AUTH_0015.3 with their SVCs as scenarios
- [x] 4.2 Delta spec under `specs/admin-roles/` in this change:
      GW_AUTH_0015 modified, GW_AUTH_0015.1 – GW_AUTH_0015.3 added
- [x] 4.3 Run every gate one final time and record `evidence.md`
- [x] 4.4 Archive the change as its own commit
