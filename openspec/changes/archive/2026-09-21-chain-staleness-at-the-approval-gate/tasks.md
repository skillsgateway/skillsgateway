## 1. Requirement and traceability

- [x] 1.1 Add GW_VETTING_0038 — Chain staleness is stated at the approval gate to
      `docs/reqstool/requirements.yml`: the system shall report, for a snapshot's
      latest chain run, whether it was produced by the chain its marketplace runs
      now, naming both identities when they differ and reporting the comparison
      as unknown when the run records no chain identity or no run exists.
- [x] 1.2 Add GW_VETTING_0038.1 (referencing GW_VETTING_0038): an approval whose
      evidence was produced by a different chain than the one in force at the
      moment of approval shall record that fact, naming both, and shall not be
      refused for it.
- [x] 1.3 Add SVC_GW_VETTING_0038 and SVC_GW_VETTING_0038.1 to
      `docs/reqstool/software_verification_cases.yml`.
- [x] 1.4 Confirm both ids are unclaimed in `openspec/changes/*/` as well as in
      `requirements.yml`.

## 2. The comparison

- [x] 2.1 Add the staleness comparison as a small value type carrying the run's
      chain, the current chain, and a three-valued verdict (current / stale /
      unknown), so callers cannot collapse unknown into either answer.
- [x] 2.2 Unit-test it directly, before wiring: identical chains, a vetter added,
      a vetter removed, a version bump, a mode change, an order change, a null
      chain, an empty chain, and no run.

## 3. The read states it

- [x] 3.1 `VettingController.VettingView` gains the staleness, computed from the
      run already loaded and `vettingService.chainIdentity(marketplaceId)`, with
      `@Schema` text saying what each value means.
- [x] 3.2 Regenerate `src/main/frontend/openapi.json` and `types.gen.ts`
      (`pnpm gen:api-types`); `OpenApiContractTests` must pass.

## 4. The approval record states it

- [x] 4.1 `ApprovalService` computes the comparison at the moment of approval and
      writes it into the approval event's ledger detail, naming both chains.
- [x] 4.2 The approval proceeds regardless. Assert this explicitly — a test that
      a stale approval still publishes is what stops a later change from
      quietly turning the marking into a gate.

## 5. A held snapshot's chain can be re-run

- [x] 5.1 Add the held path: run the chain, record the run, append a ledger entry
      naming the refreshed evidence. It must not classify for retraction, must
      not emit a revet-violation event, and must not unpublish.
- [x] 5.2 Expose it on the existing per-snapshot endpoint, which today refuses a
      non-approved snapshot. Rejected, revoked and deleted snapshots stay
      refused — this is for content still at the gate.
- [x] 5.3 Adversarial tests: a held snapshot whose refreshed chain now blocks is
      not published and not revoked; a rejected snapshot is still refused; the
      refresh emits no revet-violation event and no unpublish, in warn and
      enforce mode alike.

## 6. The portal

- [x] 6.1 `components/vetting-report.tsx` states the staleness against the
      verdicts it qualifies, naming both chains, with the unknown case distinct
      from the stale one.
- [x] 6.2 The refresh control sits with it, and is offered only for a snapshot
      that can be refreshed.
- [x] 6.3 Story coverage for current / stale / unknown / no-run, and the dark
      theme, run by `pnpm test:stories`.
- [x] 6.4 Component tests over MSW for the same states and the refresh call.

## 7. Tests (old-coder discipline — this is a trust boundary)

- [x] 7.1 Every test in sections 2, 4 and 5 is proven to fail before the code
      that makes it pass, and the evidence report says so.
- [x] 7.2 End-to-end: ingest, enable a vetter, observe the held snapshot's
      evidence reported stale, refresh it, observe it current, approve, and read
      the ledger entry naming both chains.
- [x] 7.3 Negative: approval of a stale snapshot is never refused, in any mode.

## 8. Documentation (same PR)

- [x] 8.1 `docs/manual/reference/portal.md`: what the marking says and what the
      refresh does.
- [x] 8.2 `docs/manual/reference/api/marketplaces.md`: the new field and the
      endpoint's widened state.
- [x] 8.3 `docs/manual/guides/approving-snapshots.md`: enabling a vetter does not
      re-run the held queue, what the gateway tells you instead, and how to
      refresh.

## 9. Gates

- [x] 9.1 Run all gates fresh after the last edit and write `evidence.md`,
      including the proof that each trust-boundary test failed first.
