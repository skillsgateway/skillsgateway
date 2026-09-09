# Tasks: gate-blast-radius-report

Finding F4/F6 of `docs/analysis/2026-09-08-architecture-assessment.md` — this
change is F6. One line of production code, one requirement amendment, and the
test reclassification that keeps it from regressing.

## 1. Requirements (SSOT first)

- [x] 1.1 Amend GW_AUTH_0011 — Per-marketplace approver scoping to include the
      fetcher report among the approver-scoped operations; revision 0.1.0 → 0.2.0
- [x] 1.2 Amend SVC_GW_AUTH_0011 to assert the report reads on the held
      marketplace and is refused on the other; revision "0.1.0" → "0.2.0"
- [x] 1.3 **No new requirement and no new SVC.** GW_AUTH_0010 — Scoped admin
      roles with deny-by-default enforcement already refuses every fetch-ledger
      read to a no-role session, and this is one, so its text stands unchanged.
      GW_VETTING_0016 — Re-vetting violations are announced with their affected
      consumers defines what the report contains, not who reads it, and stands
      unchanged too. Reqstool is 1:1 requirement-to-SVC in this project;
      splitting the access guarantee out would have needed a child requirement
      for a guarantee two existing SVC walks already verify

## 2. The guard

- [x] 2.1 `RevetController.fetchers` takes `Authentication` and calls
      `roleService.requireApproverOfSnapshot(authentication, id)` as its first
      statement — the idiom the two sibling POSTs on this controller already use
- [x] 2.2 `@Operation` description states the access level
- [x] 2.3 `@ApiResponse(403)`, following `SnapshotPreviewController` — the other
      controller guarded by `requireApproverOfSnapshot`, and the right precedent.
      The auditor-read controllers declare none, but none of them uses this
      guard. Its wording names the two roles rather than the removed
      `skills-gateway.roles.enabled` flag that three existing 403 strings still
      speak of; there is no single house string (six phrasings are in use)
- [x] 2.4 Regenerate `src/main/frontend/openapi.json` and `src/api/types.gen.ts`
      with `pnpm gen:api-types` — **not** the `pnpm exec openapi-typescript`
      command the code-conventions skill documents, which fails on this
      workspace because it resolves TypeScript 7. The package script pins 5.9.3
- [x] 2.5 `@Requirements` stays `{"GW_VETTING_0016"}`, matching those same
      controllers: the guard's traceability lives on `RoleService`

## 3. Stop it regressing

- [x] 3.1 `RoleEnforcementTests` — the no-role walk asserts 403 where it
      asserted 200, moved out of the "browsing surface stays open" block
- [x] 3.2 The approver walk asserts 200 on marketplace A and 403 on B, so the
      confused-deputy direction is pinned as well as the no-role one
- [x] 3.3 Confirm no other test exercised the route (`grep` — none did)

## 4. Docs in the same PR

- [x] 4.1 `reference/api/marketplaces.md` — the "every GET stays open" sentence
      gains its second exception; the endpoint entry gains a 403
- [x] 4.2 `reference/portal.md` — the "Already fetched by" panel's error state,
      in the shape the license pane and adoption page already use
- [x] 4.3 `guides/re-vetting.md`, `reference/api/audit.md`,
      `guides/delegated-administration.md`, `architecture.md` — access level
- [x] 4.4 `reference/api/tokens.md` — `snapshots:read` now intersects a role

## 5. Gates

- [x] 5.1 `./mvnw clean verify`
- [x] 5.2 `pnpm test:stories`
- [x] 5.3 `pnpm e2e`
- [x] 5.4 `reqstool status local -p docs/reqstool` ends PASS
- [x] 5.5 `openspec validate --all --strict`
- [x] 5.6 `mkdocs build --strict`
- [x] 5.7 `evidence.md` with the pasted tails and the commit SHA
