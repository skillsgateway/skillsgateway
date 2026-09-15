# Tasks: vetting-chain-flow

The new read endpoint exposes connector settings, which GW_VETTING_0029.4 — Every
toggle is an audited, administrator-only action reserves to administrators, so it
carries a negative test that a non-administrator is refused alongside the positive
resolution test. Nothing else here crosses a trust boundary: the flow is a rendering
of reads the portal already performs.

## 1. Requirements (SSOT)

- [x] 1.1 `docs/reqstool/requirements.yml` — add GW_VETTING_0029.5 (decomposed under
  GW_VETTING_0029) and GW_VETTING_0031.
- [x] 1.2 `docs/reqstool/software_verification_cases.yml` — add SVC_GW_VETTING_0029.5
  and SVC_GW_VETTING_0031.

## 2. Server: the effective chain (covers GW_VETTING_0029.1, GW_VETTING_0029.5)

- [x] 2.1 `ConnectorToggleService.resolve(connector, marketplaceId)` returning the
  resolved state **and** its source; `enabled(...)` delegates to it so the rule lives
  in one place. `@Requirements GW_VETTING_0029.1`.
- [x] 2.2 `ChainSource` and `ChainConnectorView` in the vetting package.
- [x] 2.3 `GET /api/marketplaces/{name}/vetting-chain` on `ConnectorToggleController`,
  behind `roleService.requireAdmin`, 404 on an unknown marketplace, with `@Tag`,
  `@Operation`, `@ApiResponse`. `@Requirements GW_VETTING_0029.4, GW_VETTING_0029.5`.
- [x] 2.4 `VettingController.ConnectorView` gains `version` and `external` (additive).
- [x] 2.5 Test: the chain resolves per-marketplace over global over default, names each
  source, and a non-administrator is refused the read. `@SVCs SVC_GW_VETTING_0029.5`.
- [x] 2.6 Regenerate `src/main/frontend/openapi.json` and `src/api/types.gen.ts`
  (`pnpm gen:api-types`).

## 3. Portal: the flow (covers GW_VETTING_0031)

- [x] 3.1 `src/lib/vetting-flow.ts` — pure derivation of the node list from a
  `VettingView` and from a `ChainConnectorView[]`. `@Requirements GW_VETTING_0031`.
- [x] 3.2 `src/components/vetting-flow.tsx` — the flow, its nodes as buttons, and the
  node detail dialog. `@Requirements GW_VETTING_0031`.
- [x] 3.3 `src/components/vetting-report.tsx` — render the flow above the existing
  per-connector list; add `DISABLED` to `verdictIcon` / `verdictBadge`.
- [x] 3.4 `src/lib/vetting-flow.test.ts` — vitest over the derivation, including the
  disabled, pending and waived cases.
- [x] 3.5 `src/components/vetting-flow.stories.tsx` — one story per state: clear,
  blocked, with waivers, disabled connector, pending external connector, and the
  marketplace chain.

## 4. Portal: the marketplace chain (covers GW_VETTING_0029.5)

- [x] 4.1 `useMarketplaceVettingChain` and `useToggleConnector` in `src/api/queries.ts`,
  plus `useIsAdmin` derived from `GET /api/me`.
- [x] 4.2 `src/components/marketplace-vetting-chain.tsx` — the administrator card: the
  same flow without verdicts, each node opening its enable/disable control with a
  reason field. `@Requirements GW_VETTING_0029.5`.
- [x] 4.3 Mount it on `src/pages/marketplace-detail.tsx` for an administrator session
  only; the server enforces independently.
- [x] 4.4 MSW handlers for the new endpoint in `src/test/msw-handlers.ts`.

## 5. Acceptance

- [x] 5.1 `e2e/portal.spec.ts` — the flow is present on the review surface and a node
  opens its detail. `@SVCs SVC_GW_VETTING_0031`.

## 6. Docs (same PR)

- [x] 6.1 `docs/manual/reference/portal.md` — the flow subsection and the administrator
  chain card.
- [x] 6.2 `docs/manual/concepts/vetting.md` — one pointer to the portal surface.
- [x] 6.3 `docs/manual/reference/api/marketplaces.md` — the new endpoint.

## 7. Gates and archive

- [x] 7.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
  `reqstool status local -p docs/reqstool`, `openspec validate --all --strict`,
  `mkdocs build --strict`.
- [x] 7.2 `evidence.md` with the tails and the commit SHA.
- [x] 7.3 `openspec archive vetting-chain-flow --yes` as the final commit.
