# Tasks: portal-mutable-token-scopes

Trust-boundary-adjacent (token authority): tier 3 of `.claude/skills/old-coder`. Each test
is written first and watched failing for the right reason; the race test is watched failing
against the unfixed rotation. Skills: `code-conventions`, `design-conventions`,
`documentation`, `ui-screenshots`, `impeccable` (audit and harden on both pages; no new page,
so no critique). Reqstool ids `GW_AUTH_0083`–`GW_AUTH_0089` and `SVC_GW_AUTH_0102`–`SVC_GW_AUTH_0109`.

## 1. Requirements

- [x] 1.1 `GW_AUTH_0083`–`GW_AUTH_0089` and `SVC_GW_AUTH_0102`–`SVC_GW_AUTH_0109` added (done at proposal time; verify with `reqstool status local -p docs/reqstool` after a `clean` build).

## 2. Backend: registry and routes (`GW_AUTH_0084`, `SVC_GW_AUTH_0103`)

- [x] 2.1 Test first: `MachineApiRegistryTests` fails once the two `PATCH` routes are mapped and not classified (watch it fail with a stub mapping).
- [x] 2.2 `patch(...)` helper and the two unreachable entries in `MachineApiRegistry`.

## 3. Backend: personal edit (`GW_AUTH_0083`, `SVC_GW_AUTH_0102`, `SVC_GW_AUTH_0103`)

- [x] 3.1 Tests first: narrow, widen, catalog name, empty means every, unknown 422, missing list 422, facade refuses a dropped marketplace at once, another user's id 404, machine id 404, revoked 409, expired 409.
- [x] 3.2 `TokenRepository.updateScopes` (single statement, design D5), `TokenService.updateScopes`, `PATCH /api/v1/tokens/{id}` with `@Tag/@Operation/@ApiResponse/@Schema`.

## 4. Backend: machine edit (`GW_AUTH_0084`, `SVC_GW_AUTH_0104`)

- [x] 4.1 Tests first: narrow, widen, empty 422, unknown 422, wildcard 422, non-admin 403, fetch scope stays empty, authenticates with exactly the new scopes, personal id 404, revoked and expired 409.
- [x] 4.2 `TokenRepository.updateApiScopes`, `TokenService.updateApiScopes`, `PATCH /api/v1/tokens/machine/{id}`.

## 5. Strict bodies (`GW_AUTH_0087`, `SVC_GW_AUTH_0107`)

- [x] 5.1 Tests first: `pushScopes`, `apiScopes` (personal), `scopes` (machine), `expiresAt`, `name` each refused and nothing changed.
- [x] 5.2 `@JsonIgnoreProperties(ignoreUnknown = false)` on both request records; fall back per design D3 if the mapper ignores it.

## 6. Nothing else moves; rotation; race (`GW_AUTH_0085`, `SVC_GW_AUTH_0105`)

- [x] 6.1 Tests first: whole-row comparison; rotation after an edit carries the edited scopes (personal and machine); edit of the rotated predecessor is 409; concurrent edit and rotate many times (watch it fail on the unfixed rotation).
- [x] 6.2 `TokenRepository.revoke…` returns the revoked row; both rotations build the successor from it.

## 7. Audit (`GW_AUTH_0086`, `SVC_GW_AUTH_0106`)

- [x] 7.1 Tests first: one entry per change with actor, token, before and after; none for a refused change; two concurrent edits form a chain.
- [x] 7.2 `@Transactional` services record `token-scopes-changed` and `machine-credential-scopes-changed`.

## 8. API contract

- [x] 8.1 `OpenApiContractTests` red then green: `cp target/openapi.json src/main/frontend/openapi.json && (cd src/main/frontend && pnpm run gen:api-types)`; the breaking-change gate stays green.

## 9. Portal: personal tokens (`GW_AUTH_0088`, `SVC_GW_AUTH_0108`)

- [x] 9.1 Tests first (vitest): disabled when invalid, unchanged or in flight with the reason bound by `aria-describedby`; empty list never sent for a limited choice; `[]` sent for *Every marketplace*; widening warned; 422/409 shown verbatim; list shows the new reach; the update mutation holds no cleartext.
- [x] 9.2 `useUpdateTokenScopes`, `scopesForUpdate`, `EditTokenScopesDialog`, `TokenTable.onEdit`; stories for the dialog states.

## 10. Portal: system tokens (`GW_AUTH_0089`, `SVC_GW_AUTH_0109`)

- [x] 10.1 Tests first: disabled with none ticked or unchanged, reason stated; added scopes warned about; refusal verbatim; list shows the new scopes.
- [x] 10.2 Extract `ApiScopeChecklist`; `useUpdateMachineTokenScopes`; edit dialog; `SystemTokenTable.onEdit`; stories.

## 11. e2e (`SVC_GW_AUTH_0108`)

- [x] 11.1 Real browser: a token fetching two marketplaces is narrowed from the list and the facade then refuses it for the marketplace it no longer names.

## 12. Docs, impeccable, screenshots, gates

- [x] 12.1 `docs/manual/reference/portal.md`, `api/tokens.md`, `api/audit.md`; `mkdocs build --strict`.
- [x] 12.2 `/impeccable audit` and `harden` on both pages; findings fixed or dismissed with a reason in the PR body.
- [x] 12.3 Before/after screenshots, fixture data only, left in a git-ignored folder under `.claude/worktrees/`, not uploaded.
- [x] 12.4 Every gate once after the last edit; `evidence.md` with commands, result tails and commit SHA.
- [ ] 12.5 `/opsx:archive` as the final commit.
