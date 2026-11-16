# Tasks: portal-token-homes

Not a trust-boundary change (no authentication, approval or allowlist logic is
altered), but the scope-list route and the cleartext handling sit next to one:
include negative tests (non-admin 403, machine credential refused) and the
cleartext-persistence check. Skills: `design-conventions`, `ui-screenshots`,
`impeccable` (audit/harden on the touched pages).

## 1. Requirements

- [x] 1.1 Add `GW_AUTH_0071` to `GW_AUTH_0078`, amend `GW_AUTH_0024`, and add `SVC_GW_AUTH_0086` to `SVC_GW_AUTH_0094` to `docs/reqstool/` (done at proposal time; verify with `reqstool status local -p docs/reqstool` after a `clean` build).

## 2. Backend: scope list (`SVC_GW_AUTH_0092`, `GW_AUTH_0076`)

- [x] 2.1 Test first: an administrator reads the scope names and the routes each reaches; a non-admin gets 403; a machine credential cannot reach it; the list equals the registry's keys.
- [x] 2.2 `GET /api/v1/tokens/machine/scopes` in `MachineTokenController` (`requireAdmin`, `@Requirements GW_AUTH_0076`, OpenAPI annotations); regenerate `types.gen.ts`.

## 2b. Backend: machine credential lifetime (`SVC_GW_AUTH_0093`, `GW_AUTH_0077`, `GW_AUTH_0024`)

Trust-boundary-adjacent: apply the `old-coder` discipline (tests fail first, adversarial cases).

- [x] 2b.1 Tests first: neither expiry nor `neverExpires` refused; `neverExpires: true` accepted only with `max-ttl` unset and refused with it configured; expiry beyond 365 days refused with no cap; a configured cap still wins; rotation of a never-expiring credential keeps no expiry; a machine credential cannot reach the create route. Update the `GW_AUTH_0024` SVC's cap boundary from 90 to 365 days and say so in the PR.
- [x] 2b.2 `CreateMachineCredentialRequest.neverExpires`, `validateMachineTtl`, `DEFAULT_MACHINE_MAX_TTL` to 365 days, rotate path, OpenAPI text; add `V2__machine_credentials_may_never_expire.sql` dropping `machine_credentials_expire` (never edit `V1`; there are users, so it must migrate); update `docs/manual/reference/configuration.md` (the "always capped" warning) and `api/tokens.md`.

## 3. Navigation (`SVC_GW_AUTH_0086`, `GW_AUTH_0071`)

- [x] 3.1 Move `TokensPage` to `/security/tokens`; `/security` redirects to it; `/tokens` redirects to it; breadcrumb "Security · Personal access tokens"; page states it manages personal tokens and links to System access tokens for administrators only.
- [x] 3.2 User menu: replace "Your tokens" with "Security". Remove the Overview *Access tokens* card and its `useTokens` use.
- [x] 3.3 Tests: menu shows Security for a no-role session; Overview has no tokens card; `/tokens` lands on the new page; update `app-layout`, `overview` and `tokens` tests.

## 4. System access tokens page (`SVC_GW_AUTH_0087`, `SVC_GW_AUTH_0088`, `GW_AUTH_0072`)

- [x] 4.1 Sidebar item *System access tokens* under Configuration, `needs: "admin"`; route `/configuration/tokens` wrapped in `RoleRequired`; breadcrumb. Tests: admin sees it, approver/auditor/no-role do not and get the role statement at the address.
- [x] 4.2 List (`GET /api/v1/tokens/machine`): name, principal, owner, scopes, expiry, last used, revoked; never a secret.
- [x] 4.3 Create form: principal, name, scopes from 2.2 (no "all"), lifetime 30 days / 90 days / 180 days / 1 year / never with none preselected (`neverExpires` for never); server 422 shown verbatim (unknown scope, no lifetime, beyond a configured cap, never under a cap). The list says "never expires" for such a credential.
- [x] 4.4 Rotate and revoke, each behind a confirmation dialog.
- [x] 4.5 Page says it manages system access tokens (API: machine API credentials) and links to Personal access tokens, and the reverse.
- [x] 4.6 Tests: role gating, create / rotate / revoke flows, each refusal.

## 4b. Personal token lifetimes (`SVC_GW_AUTH_0094`, `GW_AUTH_0078`)

- [x] 4b.1 `token-lifetimes.ts`: 30 days, 90 days, 180 days, 1 year; default 30 days; drop 7 days and "No expiry". Update the tokens-page and wizard tests and docs (`portal.md`).

## 5. Cleartext once and markers (`SVC_GW_AUTH_0089`, `SVC_GW_AUTH_0090`, `GW_AUTH_0073`, `GW_AUTH_0074`)

- [x] 5.1 Extract the personal-token reveal into a shared component (copy control, cannot-be-shown-again warning) used by both pages; `gcTime: 0` on the mutations.
- [x] 5.2 Test: after the dialog closes the secret is absent from the DOM, the query cache, `localStorage`, `sessionStorage` and the URL.
- [x] 5.3 Markers per design D6 with a test on each boundary.

## 6. Status view (`SVC_GW_AUTH_0091`, `GW_AUTH_0075`)

- [x] 6.1 Confirm the fields in design D8 exist in `GET /api/v1/marketplaces`; if one is missing stop and re-open D8.
- [x] 6.2 Status panel on the marketplace page: served now, newer waiting and state, who decides, nothing-served reasons. Tests per state (held, rejected, revoked, ingest failed, up to date, never approved, withdrawn) from a no-role session payload.
- [x] 6.3 Negative test: no snapshot contents, file or finding read is issued by the panel.

## 7. Docs, screenshots, gates

- [x] 7.1 Update `docs/manual/reference/portal.md` (Navigation, User menu, Access tokens, new System access tokens, status view) and `docs/manual/reference/api/tokens.md` (scopes route; replace the "minted by driving the API" note). `mkdocs build --strict`.
- [x] 7.2 Storybook stories for the new page and panel; before/after screenshots for the PR body (`.claude/skills/ui-screenshots`; owner sees each before upload). `impeccable` audit.
- [x] 7.3 Run all gates once after the last edit; write `evidence.md` with commands, result tails and commit SHA.
- [x] 7.4 `/opsx:archive` as the final commit.
