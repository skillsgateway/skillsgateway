# Evidence: vetting-chain-flow

All six gates run in the worktree, one after another, after the last code edit,
on the branch rebased onto `origin/main` at `2b08fb4` (#378–#381).

- **Commit**: `b76d9f16ed5db2501f136373fb2ab609dd4cab74`
- **Branch**: `feat/vetting-chain-flow`

This is the second full run: the first (at `9bdff05`) was green, and the design
polish pass that followed — the flow headline, the stage treatment and the
marketplace chain's state sources — re-ran every gate against the polished tree.
The numbers below are the polished run.

## `./mvnw clean verify`

```text
$ MAVEN_OPTS="-Xmx2g" ./mvnw clean verify
[INFO] Tests run: 649, Failures: 0, Errors: 0, Skipped: 9
...
[INFO] --- frontend:2.0.2:pnpm (pnpm-verify) @ skills-gateway-server ---
[INFO]  Test Files  13 passed (13)
[INFO]       Tests  73 passed (73)
...
[INFO] Spotless.Java is keeping 343 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
```

The 13 UI test files include the new `src/lib/vetting-flow.test.ts` (22 cases over
the derivation: chain order taken from the run's recorded positions, every state
as a word, the gate opening and closing, a waived finding keeping the verdict it
reached, a skipped and a pending connector as absences, the configured chain drawn
for a snapshot with no run, the empty case being blocked rather than unknown, and —
from the polish pass — the headline naming the step the chain stopped at and the
worst severity behind it, never taking the pass tone for a chain that only cleared
on an acceptance or a marketplace chain that has been narrowed, the stage
designations, and the per-node state source).

The Java tests include the new
`ConnectorToggleTests.the_effective_chain_names_each_connectors_state_and_the_setting_that_decided_it`
(`SVC_GW_VETTING_0029.5`), which asserts the three sources, the note and acting
administrator of the deciding setting, a 404 for an unknown marketplace, and that
a non-administrator is refused the read. `OpenApiContractTests` confirms the
committed `openapi.json` is the document the gateway serves, and
`MachineApiRegistryTests` that the new route is classified.

## `pnpm test:stories`

```text
$ (cd src/main/frontend && pnpm test:stories)
 ✓ |storybook (chromium)| src/components/vetting-flow.stories.tsx (7 tests)
 ✓ |storybook (chromium)| src/components/markdown-view.stories.tsx (2 tests)
 ✓ |storybook (chromium)| src/pages/tokens.stories.tsx (1 test)
 ✓ |storybook (chromium)| src/pages/adoption.stories.tsx (3 tests)

 Test Files  4 passed (4)
      Tests  13 passed (13)
```

Seven new stories — clear, blocked, with waivers, skipped-and-pending, the node
detail, the marketplace chain and its node detail — all axe-clean (violations are
errors). Each asserts the headline text as well as the per-node state, so a state
that regressed to colour alone, or a headline that disagreed with the drawing,
fails the build.

## `pnpm e2e`

```text
$ (cd src/main/frontend && pnpm e2e)
Running 14 tests using 1 worker
  ✓   7 › portal.spec.ts:377 › vetting_verdicts_are_shown_and_a_blocked_snapshot_cannot_be_approved (1.3s)
  ✓   8 › portal.spec.ts:402 › the_vetting_chain_is_drawn_as_a_flow_and_a_node_opens_its_evidence (1.7s)
  ✓   9 › portal.spec.ts:438 › a_finding_is_waived_from_the_review_surface_and_the_waiver_is_listed (6.6s)
  14 passed (55.1s)
```

## The design pass

`/impeccable polish` against `DESIGN.md`, on the rendered stories rather than on
the source: four stories captured in both themes, one batch of fixes, one
confirming capture. The mechanical detector returns clean:

```text
$ node .claude/skills/impeccable/scripts/detect.mjs --json \
    src/main/frontend/src/components/vetting-flow.tsx \
    src/main/frontend/src/components/marketplace-vetting-chain.tsx \
    src/main/frontend/src/lib/vetting-flow.ts
[]
```

What the rendered pass found and the polish fixed: no statement of the result
above the drawing; every node at equal weight with no stage identity; chevron
glyphs floating between detached chips rather than a connector joining stages;
the terminal indistinguishable from the steps; and, on the marketplace chain, the
source of each connector's state reachable only by opening the node. `oxlint`
reports the same 7 pre-existing warnings as `main`, none in the changed files.

## `reqstool status local -p docs/reqstool`

```text
$ reqstool status local -p docs/reqstool
INCOMPLETE (0)
220/220 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```text
$ openspec validate --all --strict
Totals: 32 passed, 0 failed (32 items)
```

## `mkdocs build --strict`

```text
$ mkdocs build --strict
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 1.24 seconds
```

## Not a clean first run

An earlier run, before the rebase, failed with three failures — all of them the
build catching exactly what it exists to catch, and all fixed in the commit
`fix(api): classify the effective-chain read for machine credentials`:

- `MachineApiRegistryTests` — the new route was unclassified for machine
  credentials. It is now on the unreachable list, beside the settings list it
  resolves.
- `OpenApiContractTests` — the hand-written `openapi.json` used while the Java
  build was queued behind another agent's differed from the served document in
  how springdoc renders an enum reference. Replaced with `target/openapi.json`
  and the types regenerated with `pnpm gen:api-types`.
- `ConnectorToggleTests` — a `$[*].name` JSON-path assertion that does not compare
  the way it reads; replaced with per-index assertions.

The rebase onto `origin/main` was conflict-free, and the resulting
`openapi.json` / `types.gen.ts` diff against `origin/main` is purely additive
(122 and 96 added lines, none removed), so #381's own regeneration is carried
intact.
