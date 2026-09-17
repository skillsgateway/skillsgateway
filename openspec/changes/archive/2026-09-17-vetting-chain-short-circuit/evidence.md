# Evidence: vetting-chain-short-circuit

Trust boundary: this change alters how the approval gate's evidence is produced,
so it carries the `old-coder` discipline — adversarial and negative tests first,
then the gauntlet, then this report. Commit under test:
`d67375cdbfa4e035aa25a42b157374e60d39c53b`.

## Spec ↔ test mapping

| Requirement | What is verified | Test(s) |
| --- | --- | --- |
| GW_VETTING_0032 — An administrator can stop the vetting chain after a blocking failure | the chain runs no vetter after the first failure and still records one verdict per vetter | `VettingChainSettingsTests.the_chain_stops_after_a_failure_and_records_what_it_never_reached`; `VettingChainShortCircuitTests.a_stopped_chain_aggregates_blocked_and_never_clear` (SVC_GW_VETTING_0032) |
| GW_VETTING_0032.1 — The chain mode resolves per marketplace, then globally, then to running every vetter | all three resolutions, each naming its source | `VettingChainSettingsTests.the_mode_and_the_order_resolve_per_marketplace_then_globally_then_by_default` (SVC_GW_VETTING_0032.1) |
| GW_VETTING_0032.2 — A vetter the chain stopped short of is recorded as not reached, and is not a conclusion | the state is its own, names the stopping vetter, is told apart from `DISABLED`, and never clears | `VettingChainSettingsTests.the_chain_stops_after_a_failure_and_records_what_it_never_reached`; `VettingChainShortCircuitTests.not_reached_is_neither_clearing_nor_blocking_and_is_its_own_state` (SVC_GW_VETTING_0032.2) |
| GW_VETTING_0032.3 — A run that stopped early is blocked until a complete run replaces it | a waiver that really suppresses the stopping finding still leaves the run blocked and the approval refused; a fresh complete run then decides it | `VettingChainSettingsTests.waiving_the_stopping_finding_does_not_clear_a_short_circuited_run`; `VettingChainShortCircuitTests` (3 cases) (SVC_GW_VETTING_0032.3) |
| GW_VETTING_0032.4 — Changing the chain mode is an audited, administrator-only act | 403 for a non-administrator on both the read and the write, the ledger entry on success, nothing on a refusal | `VettingChainSettingsTests.neither_setting_is_reachable_by_a_non_administrator_and_a_refusal_records_nothing` (SVC_GW_VETTING_0032.4) |
| GW_VETTING_0033 — An administrator can order the vetting chain | the arrangement is the order the chain actually runs, and decides which vetter stops it | `VettingChainSettingsTests.an_order_decides_which_vetter_stops_the_chain`; `…the_mode_and_the_order_resolve…` (SVC_GW_VETTING_0033) |
| GW_VETTING_0033.1 — The resolved vetter order is total and deterministic, and an order naming an unknown vetter is refused | named-first-then-configured, ties broken by name, nothing dropped by omission; 422 for an unknown name, a duplicate and an empty order | `VettingChainShortCircuitTests.the_resolved_order_is_total_names_first_and_drops_nothing`; `VettingChainSettingsTests.neither_setting_is_reachable…` (SVC_GW_VETTING_0033.1) |
| GW_VETTING_0033.2 — The chain mode and the resolved vetter order are part of a run's recorded chain identity | three runs, three distinct identities; changing the mode and changing the order each change it | `VettingChainSettingsTests.a_run_records_the_mode_and_the_order_it_used` (SVC_GW_VETTING_0033.2) |
| GW_VETTING_0034 — Portal control of the chain mode and the vetter order, and a chain that stopped early | the mode is set and the chain reordered by keyboard alone; the flow marks the unreached vetters and says the run stopped early | `portal.spec.ts::an_admin_sets_the_chain_mode_and_order_and_a_stopped_run_says_so` (SVC_GW_VETTING_0034); Storybook `Vetting/ChainSettings` (6 stories) and `Vetting/VettingFlow` `ShortCircuited*` (3 stories), axe-as-error, both themes |

### The adversarial cases (the guardrails)

These are the reason the change is Tier 3. Each was written to fail against the
implementation before the rule it names existed.

- **A waiver cannot clear a truncated run.** `waiving_the_stopping_finding_does_not_clear_a_short_circuited_run`
  asserts the waiver genuinely suppresses (so this is not a test of a waiver that
  failed to match), that the effective outcome is still `BLOCKED`, that
  `approvalService.approve` raises `VettingBlockedException`, and that only a
  fresh run reaching every vetter changes the answer.
- **A not-reached verdict's own bookkeeping finding cannot be waived into a pass.**
  `waiving_a_not_reached_verdicts_own_finding_does_not_promote_it` — the same trap
  `DISABLED` already had a guard for.
- **The mode cannot make a run clear that the verdicts do not.** `a_stopped_chain_aggregates_blocked_and_never_clear`
  covers a stop at the first vetter, a stop after a pass, and a run of nothing but
  absences.
- **The rule bites only on a truncated run.** `a_complete_run_is_unaffected_by_the_rule`
  is the regression armour against over-blocking.
- **A non-administrator reaches neither setting** — 403 on all four endpoints —
  **and a refused change writes nothing**: the ledger count before and after the
  six refusals is asserted equal, and the marketplace's order source is unchanged.

### What an existing test caught, and what it changed

`RevetEnforceTests.aVetterErrorDuringRevettingNeverRevokesTheSnapshot` iterates
every `VerdictState` and failed on the first full run: `NOT_REACHED` classified as
a `VIOLATION`, which would have let a chain that merely stopped **retract already
published content**. `RevetVerdict.classify` now treats it as naming no fault, for
the same reason `ERROR`, `PENDING` and `DISABLED` do. That is the sharpest defect
this change could have shipped, and it was found by a test written for a different
feature — recorded here rather than quietly fixed.

Two other ratchets did their job on the same run and were extended rather than
weakened: `MachineApiRegistry` / `RoleEnforcementTests` (the new routes are
classified `admin-only`, unreachable by any machine-credential scope) and
`NativeEnumColumnTests` (the enum's value set).

`WaiverTests.evaluationNeverClearsAVerdictThatHasNothingToWaive` was **tightened**,
not relaxed: its per-state loop now demands `BLOCKED` rather than
`CLEAR_WITH_WAIVERS` for `NOT_REACHED`.

## The one design decision the tests forced

The first implementation stopped the chain on any `FAIL`. The integration test
then showed that a waived finding still stopped the chain on every later run, so
the "re-vet to get a complete answer" remedy could never be reached and the gate
would have stayed shut permanently. The stop now applies the waivers active at
that moment (`WaiverEvaluation.stillObjects`, the same `Verdict.of` re-derivation
the effective outcome uses), so what stops the chain and what gates the approval
cannot disagree. Recorded as decision 3 in `design.md`.

## Design audit (impeccable)

The bundled detector reports no findings on `marketplace-vetting-chain.tsx`,
`vetting-flow.tsx` or `lib/vetting-flow.ts`. Two accessibility defects found by
hand in the reordering were fixed and are now asserted by the
`OrderReordered` story:

- Moving a vetter to an end disabled the button that had just been pressed, and a
  disabled control cannot hold focus — a keyboard user was dropped to the top of
  the document mid-task. Focus now follows the vetter.
- The renumbering had no non-visual equivalent. A polite live region announces the
  vetter and its new position.

A third, smaller one: a not-reached node showed "1 finding", which read as a
result. The bookkeeping finding is no longer counted on the node.

Theming, density and control sizes follow `design-conventions` and DESIGN.md
(32px controls, tokens only, no `shadow-*`); both themes are exercised by
stories, and axe violations are build errors.

## Gates — one fresh run, after the last code edit

```
$ git rev-parse HEAD
d67375cdbfa4e035aa25a42b157374e60d39c53b

$ MAVEN_OPTS="-Xmx2g" ./mvnw clean verify
[INFO] Tests run: 674, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  06:26 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  8 passed (8)
      Tests  35 passed (35)

$ (cd src/main/frontend && pnpm e2e)
  19 passed (1.2m)

$ reqstool status local -p docs/reqstool
INCOMPLETE (0)
239/239 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 32 passed, 0 failed (32 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.35 seconds
```

One flake, recorded rather than hidden: the Storybook run immediately after
`mvnw clean verify` failed to import five of the eight story files
("Failed to fetch dynamically imported module"), and a second attempt stalled on
an iframe that never became ready. Both are the vitest-browser cache under memory
pressure, not the change: clearing
`src/main/frontend/node_modules/.cache/storybook` and re-running gave 35/35, and
the same suite had passed twice before on this code. The tail above is that run.

## Portal screenshots

Captured from the stories at 2× and kept out of the repository, in
`~/agents/skillsgateway/pr-391-screenshots/`:

- `chain-mode-run-all-light.png`, `chain-mode-stop-after-fail-light.png`,
  `chain-mode-stop-after-fail-dark.png`
- `chain-order-light.png`, `chain-order-reordered-light.png`,
  `chain-order-dark.png`
- `snapshot-flow-stopped-early-light.png`, `snapshot-flow-stopped-early-dark.png`

## Not covered

- No webhook event for either setting; the ledger is the record. Stated as a
  non-goal in `design.md` rather than left as an omission.
- Neither setting is in the declarative estate. That is a deliberate, stated
  decision following the vetter toggle's own precedent, not an oversight — see
  `design.md`, "Estate integration".
- The scheduled re-vetting sweep is not exercised against `stop-after-fail`; the
  manual re-vet path is, and the two share `VettingService.run` exactly.
