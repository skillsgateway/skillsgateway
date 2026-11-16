# Tasks: portal-pat-scopes

Not a trust-boundary change: enforcement, the token endpoints and who may read what do
not change, and a token can only be narrowed. The one place that touches a boundary
is the cleartext (tested) and the empty-choice rule (a limited choice that sent no
scopes would grant every marketplace), so that case gets an adversarial test.
Skills: `design-conventions`, `ui-screenshots`, `impeccable` (audit and harden on the
touched pages; the wizard and personal tokens page are not new pages, so no critique).

## 1. Requirements

- [x] 1.1 `GW_AUTH_0079`–`GW_AUTH_0082` and `SVC_GW_AUTH_0095`–`SVC_GW_AUTH_0101` added, `GW_AUTH_0063` amended (done at proposal time; verify with `reqstool status local -p docs/reqstool` after a `clean` build).

## 2. Backend: the catalog's name (`SVC_GW_AUTH_0101`, `GW_AUTH_0082`)

- [x] 2.1 Test first: the served revision carries the configured name (a non-default one); unauthenticated is still refused; disabled is still 404.
- [x] 2.2 `CatalogInfo.name` (`@Schema`), populated in `rebuild` and `served`; regenerate `openapi.json` and `types.gen.ts`.

## 3. Scope choice (`SVC_GW_AUTH_0095`, `SVC_GW_AUTH_0096`, `GW_AUTH_0079`)

- [x] 3.1 `lib/token-scope.ts` (the choice, the request's `scopes`, the reach sentence) and `useCatalogName`; `CreateToken` gains `scopes`.
- [x] 3.2 `MarketplaceScopePicker`: choice, filter, bounded checklist, chosen summary with count and Clear, hint, loading and error states; story with many marketplaces.
- [x] 3.3 Personal tokens page uses it; disabled until named; refusal shown as the API states it.
- [x] 3.4 Tests: default sends no scopes; two chosen send exactly them; none chosen is disabled and says why; filter; catalog offered by its name; 422 shown verbatim; a role-less session can use it.

## 4. Wizard (`SVC_GW_AUTH_0097`, `SVC_GW_AUTH_0099`, `GW_AUTH_0063`)

- [x] 4.1 Wizard uses the picker, starts the limited choice with its own marketplace ticked; the reach text follows the choice, before and after creation.
- [x] 4.2 Tests: default request has no scopes and the old sentence; limited request carries the names; text after creation names them. Update the e2e spec's wizard assertion only if the default wording changed (it must not).

## 5. List (`SVC_GW_AUTH_0098`, `GW_AUTH_0080`)

- [x] 5.1 *Reach* column in `TokenTable` per design D5; story with the four row kinds.
- [x] 5.2 Test: every marketplace, one, many (shortened, whole list reachable), publish scope only where present.

## 6. Cleartext (`SVC_GW_AUTH_0100`, `GW_AUTH_0081`)

- [x] 6.1 Test first (fails on `portal-token-homes`' page and wizard): after creation the mutation cache holds no record containing the cleartext while the component stays mounted.
- [x] 6.2 `create.reset()` once the page and wizard hold the value.

## 7. Docs, screenshots, gates

- [x] 7.1 `docs/manual/reference/portal.md` (personal access tokens, Connect a client) and `api/tokens.md` (catalog name field). `mkdocs build --strict`.
- [x] 7.2 `/impeccable audit` and `harden` on the personal tokens page and the wizard; findings fixed or dismissed with a reason in the PR body.
- [ ] 7.3 Before/after screenshots, fixture data only, left in a folder (the owner approves every image before upload).
- [ ] 7.4 All gates once after the last edit; `evidence.md` with commands, result tails and commit SHA.
- [ ] 7.5 `/opsx:archive` as the final commit.
