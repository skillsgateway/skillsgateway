## 1. Traceability

- [x] 1.1 Add `GW_VETTING_0035`, `GW_VETTING_0036` and `GW_VETTING_0037` to `docs/reqstool/requirements.yml`
- [x] 1.2 Add `SVC_GW_VETTING_0035`, `SVC_GW_VETTING_0036` and `SVC_GW_VETTING_0037` to `docs/reqstool/software_verification_cases.yml`

## 2. Server — clearing an override

- [x] 2.1 Add `deleteMode(marketplaceId)` and `deleteOrder(marketplaceId)` to `VettingChainSettingsRepository`, each reporting whether a row went away
- [x] 2.2 Add `delete(vetter, marketplaceId)` to `VetterToggleRepository`, likewise
- [x] 2.3 Add `clearMode`/`clearOrder` to `VettingChainSettingsService` and `clear` to `VetterToggleService`, each auditing `vetting-chain-mode-cleared` / `vetting-chain-order-cleared` / `vetter-toggle-cleared` only when a row actually went away, with `@Requirements({"GW_VETTING_0036"})`

## 3. Server — the global reads

- [x] 3.1 Add `GET /api/vetting/global-chain` to `VetterToggleController` returning `List<ChainVetterView>` resolved at the global scope, admin-only, with OpenAPI annotations
- [x] 3.2 Add `GET /api/vetting/global-chain-settings` to `VettingChainSettingsController` returning `ChainSettingsView` for the no-override case, admin-only, with OpenAPI annotations
- [x] 3.3 Give both a global-scope resolution path in the services so no rule is duplicated in a controller

## 4. Server — the bulk endpoint

- [x] 4.1 Add `VettingChainBulkService` — whole-request validation, correlation id, per-marketplace application delegated to the existing set/clear services
- [x] 4.2 Add the request/result records (`BulkRequest`, `BulkResult`, `BulkOutcome`) with `@Schema` descriptions
- [x] 4.3 Add `POST /api/vetting/chain-settings/bulk`, admin-only, answering `200` when every marketplace succeeded and `207` when any did not, with `@Requirements({"GW_VETTING_0037"})`
- [x] 4.4 Stamp `bulk=<correlationId>` onto every ledger entry the request causes

## 5. Server tests

- [x] 5.1 `VettingChainOverrideClearTests` — clearing returns the source to `GLOBAL`/`DEFAULT`; writing the default value as an override does not; clearing what is not there writes nothing to the ledger
- [x] 5.2 Bulk: a non-administrator is refused; an unknown vetter name and an unknown mode are refused whole with nothing written or audited
- [x] 5.3 Bulk: an unknown marketplace in a selection of several answers `207`, fails only that item, applies the rest
- [x] 5.4 Bulk: every affected marketplace gets its own ledger entry carrying the shared reason and the one correlation id
- [x] 5.5 The two global reads: admin-only, and they report `GLOBAL`/`DEFAULT` sources and never `MARKETPLACE`
- [x] 5.6 Tag the verifying tests with `@SVCs`

## 6. API contract

- [x] 6.1 Regenerate `src/main/frontend/openapi.json` from `OpenApiDocsTests` output
- [x] 6.2 Regenerate `src/main/frontend/src/api/types.gen.ts` with `pnpm gen:api-types`

## 7. Portal — shared controls

- [ ] 7.1 Generalise `ModeControls`, `OrderControls` and the vetter toggle control to an optional scope (`marketplace?: string`, absent meaning global), with copy that switches on the scope
- [ ] 7.2 Generalise `useMarketplaceChainSettings`, `useSetChainMode`, `useSetChainOrder` and `useToggleVetter` to the optional scope, and add `useGlobalVettingChain`, `useGlobalChainSettings` and `useBulkChainSettings`
- [ ] 7.3 Confirm the marketplace detail card still behaves identically

## 8. Portal — the Vetting page

- [ ] 8.1 Add `src/main/frontend/src/pages/vetting.tsx` with the default-chain section reusing `VettingFlow` and the generalised controls
- [ ] 8.2 Add the overrides section — one row per departing marketplace, what it overrides, and *clear override*
- [ ] 8.3 Add bulk edit — keyboard-operable selection and select-all, the action chooser, the confirm step listing affected marketplaces with before/after, and the reason field
- [ ] 8.4 Render the result per marketplace; a response with any failure renders as a failure
- [ ] 8.5 Add the non-admin refusal state
- [ ] 8.6 Register the route in `main.tsx`, the sidebar entry in the Governance group (admin-only) and the breadcrumb in `app-layout.tsx`
- [ ] 8.7 Link to the page from the marketplace detail vetting-chain card

## 9. Portal tests

- [ ] 9.1 Stories per state: default only, overrides present, bulk confirm, bulk partial failure, non-admin refusal — axe clean, both themes, reduced motion
- [ ] 9.2 Component tests for the selection, the confirm-before-apply rule and the partial-failure rendering
- [ ] 9.3 MSW handlers for the three new endpoints
- [ ] 9.4 Playwright spec with `@SVCs`: set a global default, then clear a marketplace's override
- [ ] 9.5 Run `/impeccable audit`, `/impeccable harden` and `/impeccable critique` on the new page and fix what they flag

## 10. Documentation

- [ ] 10.1 `docs/manual/reference/portal.md` — the new page and the navigation table
- [ ] 10.2 `docs/manual/reference/api/marketplaces.md` — the three new endpoints, the `207` contract, the clear semantics
- [ ] 10.3 `docs/manual/concepts/vetting.md` — one pointer to the page
- [ ] 10.4 `docs/manual/capability-map.md` and `docs/manual/guides/delegated-administration.md` if their lines change

## 11. Gates and evidence

- [ ] 11.1 `./mvnw clean verify`
- [ ] 11.2 `pnpm test:stories` and `pnpm e2e`
- [ ] 11.3 `reqstool status local -p docs/reqstool` ends PASS
- [ ] 11.4 `openspec validate --all --strict` and `mkdocs build --strict`
- [ ] 11.5 Write `openspec/changes/portal-vetting-governance/evidence.md` from one final fresh run
