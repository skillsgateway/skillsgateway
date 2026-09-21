# Evidence: chain-staleness-at-the-approval-gate

One fresh run of every gate after the last edit, against commit `cd9a1272`.

| Gate | Command | Result |
| --- | --- | --- |
| Java + UI + jar | `./mvnw clean verify` | `Tests run: 721, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` (05:41 min) |
| Storybook | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 10 passed (10)` · `Tests 50 passed (50)` |
| Real-browser e2e | `(cd src/main/frontend && E2E_GATEWAY_PORT=18500 pnpm e2e)` | `21 passed (1.2m)` |
| Requirements | `reqstool status local -p docs/reqstool` | `253/253 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 32 passed, 0 failed (32 items)` |
| Docs | `mkdocs build --strict` | built clean |

## Every trust-boundary test was proven to fail first

This change touches `ApprovalService`, so the `old-coder` discipline applies.
Each test below was written and run **before** the code that satisfies it, and
the recorded failure is what makes it evidence rather than decoration.

| Test | Failure before the code |
| --- | --- |
| `ChainStalenessTests` (5 cases) | `cannot find symbol: ChainStaleness` — the type did not exist |
| `ChainStalenessAtTheGateTests.a_chain_change_makes_held_evidence_superseded_and_the_approval_still_publishes` | `cannot find symbol: EVENT_APPROVED_ON_SUPERSEDED_CHAIN`, then `JSON path "$.chainStaleness.state" expected:<SUPERSEDED> but was:<IN_FORCE>` |
| `…a_held_snapshots_evidence_is_refreshed_in_place_and_becomes_current_again` | `Status expected:<200> but was:<409>` — the endpoint refused a held snapshot |
| `RevetEnforceTests.refreshing_a_held_snapshots_evidence_never_retracts_even_under_enforcement` | Written against the new path; asserted no violation and no announcement for the snapshot |
| `a_chain_change_marks_held_evidence_superseded_and_the_reviewer_refreshes_it` (e2e) | `element(s) not found — waiting for …different chain than this marketplace runs now` |

The negative claim is asserted directly, because it is the one a later change
could quietly reverse: a stale approval **publishes**, verified through a real
`git clone` rather than a state column, and an approval on current evidence
writes no ledger row at all — so a row is evidence rather than noise.

## What the tests caught that review did not

**1. The chain identity does not answer the question the change is about.**
The design assumed `chainIdentity(marketplaceId)` was sufficient and the
comparison a string equality. It is not: a vetter an administrator switches off
**stays in the chain** and is recorded as a `disabled` verdict, deliberately, so
the disablement is part of the run's evidence (GW_VETTING_0029.2 — A disabled
vetter is recorded on the run). The identity therefore does not move when a
vetter is toggled — the exact change this exists to catch. The first
implementation reported a toggled chain as current, and
`a_chain_change_makes_held_evidence_superseded…` failed with
`expected:<SUPERSEDED> but was:<IN_FORCE>`. Both sides are now described the
same way, by the same code, and compared whole. `design.md` records the
correction.

**2. A chain change left the marking invisible until something refetched.**
The staleness is derived per request, so a reviewer who changed the chain and
looked straight at a snapshot saw stale evidence reported as current — the
failure mode the change exists to remove, reintroduced in the client. No unit
test could see it: they mount one component with one fixture. The e2e failed at
the assertion after the mode change. `invalidateChain` now invalidates
`snapshot-vetting`.

**3. An existing test asserted the behaviour this change alters.**
`RevetTests.onlyApprovedSnapshotsCanBeRevetted` asserted that a held snapshot is
refused. It was rewritten, not deleted, to the constraint that still holds — a
*terminal* snapshot is refused — and its Javadoc records why the held half
changed and which tests now pin that a refresh never becomes a retraction.

**4. `reqstool` caught a requirement with a verification case and no
implementation.** GW_VETTING_0038.1 had its SVC and its test but no
`@Requirements` annotation on the endpoint that writes the record;
`mvnw verify` passed and `reqstool status` reported
`252/253 complete · 1 incomplete · FAIL`.

## Notes on the runs

- **The port override is no longer needed** (updated after rebase). The run
  tabled above needed `E2E_GATEWAY_PORT=18500`, because an unrelated process
  holds 8081. #461 and #464 have since merged and this branch was rebased onto
  them; the whole gate set was re-run on that base with no override:
  `21 passed (1.7m)` e2e, `50 passed (50)` stories, `Tests run: 733` Java, and
  `255/255 complete · PASS` for requirements — 255 rather than 253 because
  #464's two retention requirements are now in the base.
- **The jar bundles the portal.** `pnpm e2e` runs the newest jar under
  `target/`, so a frontend fix made after `mvnw verify` is not in it. The
  invalidation fix above appeared not to work until
  `./mvnw -DskipTests package` had been run.
- **The Storybook harness flake reproduced repeatedly** — `Failed to fetch
  dynamically imported module` and `The iframe … did not become ready within
  60000ms`, on story files this change does not touch. Deleting
  `src/main/frontend/node_modules/.cache/storybook` and re-running is the
  reliable cure; the tabled run is the passing one.
