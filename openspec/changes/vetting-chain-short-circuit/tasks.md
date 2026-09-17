## 1. Requirements (reqstool SSOT)

- [x] 1.1 Add GW_VETTING_0032 and GW_VETTING_0032.1–0032.4, GW_VETTING_0033 and
      GW_VETTING_0033.1–0033.2, and GW_VETTING_0034 to `docs/reqstool/requirements.yml`
      with titles, descriptions, rationales and categories
- [x] 1.2 Add SVC_GW_VETTING_0032, SVC_GW_VETTING_0032.1–0032.4,
      SVC_GW_VETTING_0033, SVC_GW_VETTING_0033.1–0033.2 and SVC_GW_VETTING_0034 to
      `docs/reqstool/software_verification_cases.yml` as GIVEN/WHEN/THEN cases

## 2. Schema and the not-reached verdict state

- [x] 2.1 Write `V6__vetting_chain_settings.sql`: add `'not_reached'` to the
      `vetting_verdict_state` enum; create `vetting_chain_modes` and
      `vetting_chain_orders` shaped like `vetter_toggles`
- [x] 2.2 Add `VerdictState.NOT_REACHED` — neither clearing nor blocking — and
      document why it is distinct from `DISABLED` (GW_VETTING_0032.2)
- [x] 2.3 Add `Verdict.notReached(vetter, stoppedBy)` carrying the informational
      finding that names the vetter that stopped the chain (GW_VETTING_0032.2)

## 3. The chain settings: storage, resolution, audit, API

- [x] 3.1 Add `ChainMode` (`RUN_ALL`, `STOP_AFTER_FAIL`), `ChainModeSetting` and
      `ChainOrderSetting` records with `@Schema` annotations
- [x] 3.2 Add `VettingChainSettingsRepository` — upsert and read per scope, both
      tables, `NULLS NOT DISTINCT` on `marketplace_id`
- [x] 3.3 Add `VettingChainSettingsService`: `resolveMode`/`resolveOrder` by the
      per-marketplace-then-global-then-default rule, returning the deciding setting
      and its `ChainSource` (GW_VETTING_0032.1, GW_VETTING_0033.1)
- [x] 3.4 `VettingChainSettingsService.setMode`/`setOrder`: refuse an unknown
      vetter name or a duplicate (422) and an unknown marketplace (404); write the
      ledger entry naming principal, scope, value and note (GW_VETTING_0032.4,
      GW_VETTING_0033.1)
- [x] 3.5 Add `VettingChainSettingsController` — `PUT /api/vetting/chain-mode`,
      `PUT /api/vetting/chain-order`, `GET /api/vetting/chain-settings`,
      `GET /api/marketplaces/{name}/vetting-chain-settings` — all
      `roleService.requireAdmin`, with `@Tag`/`@Operation`/`@ApiResponse`
- [x] 3.6 Add `ChainSettingsView` with the resolved mode, the resolved order and
      the source, note, administrator and time of whichever setting decided each

## 4. The chain itself

- [x] 4.1 `VettingService`: resolve the order per marketplace before the loop
      (override-named first in the order given, then the rest by `order()` then
      `name()`), replacing the constructor-fixed list (GW_VETTING_0033.1)
- [x] 4.2 `VettingService.run`: under `STOP_AFTER_FAIL`, record `NOT_REACHED` for
      every vetter after the first blocking `FAIL` instead of running it; a
      disabled vetter still records `DISABLED` (GW_VETTING_0032, GW_VETTING_0032.2)
- [x] 4.3 `chainIdentity(marketplaceId)`: `mode=<mode>;<vetter@version>,…` in
      resolved order, stamped on every run (GW_VETTING_0033.2)
- [x] 4.4 `VetterToggleController.vettingChain`: return the vetters in the
      resolved order rather than the built-in order
- [x] 4.5 Add the ledger detail for a `NOT_REACHED` verdict so the audit row says
      which vetter stopped the chain

## 5. The effective outcome (trust boundary)

- [x] 5.1 `WaiverEvaluation.effectiveState`: `NOT_REACHED` is returned unchanged,
      so waiving its bookkeeping finding cannot promote it to `PASS`
- [x] 5.2 `WaiverEvaluation.evaluate`: a run carrying any `NOT_REACHED` verdict has
      a `BLOCKED` effective outcome whatever the waivers say, and those vetters are
      named in `blockingVetters` (GW_VETTING_0032.3)

## 6. Tests (old-coder discipline: prove each fails first)

- [x] 6.1 `VettingChainTests` / `WaiverEvaluationTests`: pure-function coverage of
      `NOT_REACHED` across every combination — it never clears, a run carrying one
      never has a non-blocked effective outcome
- [x] 6.2 Adversarial: a waiver covering the finding that stopped the chain does
      **not** clear the run; the snapshot cannot be approved (SVC_GW_VETTING_0032.3)
- [x] 6.3 Adversarial: `stop-after-fail` cannot make a run clear that the verdicts
      do not — a chain stopping at its first vetter is blocked, and a run of
      nothing but `NOT_REACHED` and `DISABLED` is blocked
- [x] 6.4 Negative: a non-administrator cannot set or read the mode or the order
      (403), an unknown vetter name in an order is refused (422), a duplicate name
      is refused (422), an unknown marketplace is refused (404)
- [x] 6.5 Resolution: per-marketplace beats global beats default, for both
      settings, and the deciding setting is reported (SVC_GW_VETTING_0032.1)
- [x] 6.6 Order: an override reorders built-in and external vetters, unnamed
      vetters follow in built-in order, the result is total and deterministic, and
      the run's recorded positions match (SVC_GW_VETTING_0033.1)
- [x] 6.7 Chain identity: the recorded `chain` carries the mode and the resolved
      order, and changing either changes it (SVC_GW_VETTING_0033.2)
- [x] 6.8 Ledger: every mode and order change writes its audited entry; a refused
      change writes none (SVC_GW_VETTING_0032.4)
- [x] 6.9 Add `@Requirements`/`@SVCs` annotations on the implementing methods and
      verifying tests

## 7. Portal

- [x] 7.1 Regenerate `openapi.json` and `src/api/types.gen.ts`; add the queries and
      mutations for the chain settings to `src/api/queries.ts`
- [x] 7.2 `lib/vetting-flow.ts`: word and tone for `NOT_REACHED` ("not reached"),
      and a snapshot headline that says the chain stopped early and at which step
      (GW_VETTING_0034)
- [x] 7.3 `vetting-flow.tsx`: the not-reached node's icon and its detail copy —
      why it did not run and what that costs the reviewer
- [x] 7.4 `marketplace-vetting-chain.tsx`: the mode control (segmented, with the
      optional reason and the audit hint) reading and writing the resolved mode
- [x] 7.5 `marketplace-vetting-chain.tsx`: a keyboard-operable ordered list with
      move-up/move-down buttons carrying accessible names, a "Save order" and a
      "Discard changes" control, and the pending order reflected in the flow
- [x] 7.6 Stories per state — run-all, stop-after-fail, a reordered chain, a
      short-circuited snapshot flow — axe clean, both themes, reduced motion
- [x] 7.7 Playwright e2e covering SVC_GW_VETTING_0034 with a snake_case test title
      and the `@SVCs` JSDoc tag
- [x] 7.8 Run the impeccable audit discipline on the changed components and fix
      what it raises

## 8. Documentation (same PR)

- [x] 8.1 `docs/manual/concepts/vetting.md`: the mode, the `NOT_REACHED` state in
      the verdict-state table, what stopping early costs a reviewer, why a
      short-circuited run is not evidence of a clean chain, and the order rule
- [x] 8.2 `docs/manual/reference/api/marketplaces.md`: the four endpoints, their
      bodies, their refusals and the resolved-order behaviour of the existing
      vetting-chain read
- [x] 8.3 `docs/manual/concepts/glossary.md`: chain mode, not reached, chain
      identity wording; update Vetter/Chain identity/Effective outcome entries
- [x] 8.4 `docs/manual/reference/portal.md`: the mode control and the reordering
      on the marketplace vetting-chain card
- [x] 8.5 `docs/manual/reference/configuration.md` if any config leaf appears
      (none is expected — both settings are API-managed), and
      `docs/manual/capability-map.md` if the area's one-line description changes

## 9. Gates and evidence

- [x] 9.1 `./mvnw clean verify`
- [x] 9.2 `pnpm test:stories` and `pnpm e2e`
- [x] 9.3 `reqstool status local -p docs/reqstool` ends PASS
- [x] 9.4 `openspec validate --all --strict` and `mkdocs build --strict`
- [x] 9.5 Write `openspec/changes/vetting-chain-short-circuit/evidence.md` with the
      commands, the pasted result tails of one final fresh run, and the commit SHA
