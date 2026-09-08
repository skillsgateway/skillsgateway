# Tasks: decompose-connector-toggle-requirement

## 1. Requirements (SSOT first)

- [x] 1.1 Rewrite GW_VETTING_0029 in `docs/reqstool/requirements.yml` as a
      parent: the cross-cutting claim that the switch never becomes a blanket
      approval
- [x] 1.2 Add GW_VETTING_0029.1 – GW_VETTING_0029.4, one per independently
      verifiable guarantee, each with `references: requirement_ids:
      ["GW_VETTING_0029"]`, its own rationale and its own categories
- [x] 1.3 Rewrite SVC_GW_VETTING_0029 in
      `docs/reqstool/software_verification_cases.yml` to verify the anti-bypass
      property (a disabled verdict never rescues a genuinely failing one, and
      pre-existing aggregation is unchanged), rather than all four guarantees
- [x] 1.4 Add SVC_GW_VETTING_0029.1 – SVC_GW_VETTING_0029.4 (GIVEN/WHEN/THEN),
      each claiming only what its annotated test(s) actually assert

## 2. Move the test annotations (no test added, removed or weakened)

- [x] 2.1 `ConnectorToggleTests.an_admin_disables_a_connector_per_marketplace_
      and_no_one_else_can` → `SVC_GW_VETTING_0029.1, SVC_GW_VETTING_0029.2,
      SVC_GW_VETTING_0029.4`
- [x] 2.2 `ConnectorToggleTests.disabling_every_connector_leaves_a_run_
      blocked_not_clear` → `SVC_GW_VETTING_0029.3`
- [x] 2.3 `VettingChainDisabledTests`: `a_disabled_verdict_does_not_block_
      when_something_else_clears`, `disabling_every_connector_blocks_rather_
      than_clears` and `disabled_is_neither_clearing_nor_blocking` →
      `SVC_GW_VETTING_0029.3`; `a_disabled_verdict_never_rescues_a_failing_
      one` and `the_pre_existing_rules_are_unchanged` keep `SVC_GW_VETTING_
      0029` alone (the parent's anti-bypass bracket)

## 3. Move and add the implementation annotations

- [x] 3.1 `ConnectorToggleController.toggles` → `GW_VETTING_0029.4`;
      `.toggle` → `GW_VETTING_0029.1, GW_VETTING_0029.4`; class javadoc → `.4`
- [x] 3.2 `ConnectorToggleRepository.set` and class javadoc → `.1`
- [x] 3.3 `ConnectorToggleService.enabled` → `.1`; `.set` → `.1, .4`; class
      javadoc, `EVENT_DISABLED`/`EVENT_ENABLED` and the `enabled` method
      javadoc redistributed accordingly
- [x] 3.4 `VettingChain.aggregate` → `GW_VETTING_0002, GW_VETTING_0029.3`;
      class javadoc and the inline "blocks" comment → `.3`
- [x] 3.5 `VerdictState`'s `DISABLED` constant javadoc → `.2`; `blocking()`
      javadoc → `.3`
- [x] 3.6 **New annotations, no prior `@Requirements` existed**:
      `VettingService.run` gains `GW_VETTING_0029.2` alongside its existing
      ids; `Verdict.disabled` gains `@Requirements({"GW_VETTING_0029.2"})` and
      an `import` for the annotation
- [x] 3.7 `ConnectorToggle`'s class javadoc → `.1`; `MachineApiRegistry`'s
      scope-list comment → `.4`

## 4. OpenSpec quirk

- [x] 4.1 No live `openspec/specs/*/spec.md` entry exists for
      GW_VETTING_0029 (added by the never-archived `admin-vetting-override`
      change) — update that change's own `specs/snapshot-vetting/spec.md`
      delta directly with the four children, alongside the parent it already
      declares
- [x] 4.2 This change's own `specs/snapshot-vetting/spec.md` declares the
      same four children as `ADDED`, without the parent, only to satisfy
      `openspec validate`'s one-delta-per-change requirement — reasoning in
      `design.md`

## 5. Gates

- [x] 5.1 Run every gate one final time and record `evidence.md`
- [x] 5.2 Archive this change as its own commit (the `admin-vetting-override`
      change stays open — archiving it is out of scope)
