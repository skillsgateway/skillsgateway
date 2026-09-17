# Evidence — portal-vetting-governance

One fresh run of every gate after the last code edit.

- **Commit:** `c35cfde7682a4890e90303eb5b60b3a97ba6536c`
- **Branch:** `feat/portal-vetting-governance`
- **Date:** 2026-09-17

## `./mvnw clean verify`

```
$ MAVEN_OPTS="-Xmx2g" ./mvnw clean verify
[INFO] Tests run: 682, Failures: 0, Errors: 0, Skipped: 9
[INFO] Attaching artifact: skills-gateway-server-0.3.0-138-SNAPSHOT-reqstool.zip
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  05:28 min
```

Includes the eight new server tests in `VettingChainEstateGovernanceTests`, the
three new routes added to `RoleEnforcementTests`' completeness walk and to
`MachineApiRegistry`'s unreachable set, Spotless, Checkstyle and the UI jsdom
gate (15 files, 102 tests).

## `pnpm test:stories`

```
$ (cd src/main/frontend && pnpm test:stories)
 ✓ |storybook (chromium)| src/pages/vetting.stories.tsx (10 tests)
 Test Files  9 passed (9)
      Tests  45 passed (45)
   Duration  6.45s
```

Axe runs with violations as errors, so the ten new stories — default only,
overrides present, an override whose marketplace is gone, bulk confirm, bulk
partial failure, the non-admin refusal, and the dark-theme twin of each — are
also the accessibility evidence.

**Recorded flake.** Immediately after `mvnw clean verify`, the first
`pnpm test:stories` failed nine suites with
`Failed to import … @storybook/addon-vitest/dist/vitest-plugin/setup-file-with-project-annotations.js`
and then, after clearing `node_modules/.cache/storybook` alone, with
`Failed to fetch dynamically imported module` on five suites. Clearing **both**
`node_modules/.vite` and `node_modules/.cache` and running twice (the first run
rebuilds the dependency optimiser) fixed it. The tail above is the second run.
Nothing in the change is implicated: the failures included suites this change
does not touch.

## `pnpm e2e`

```
$ (cd src/main/frontend && pnpm e2e)
  ✓  20 [chromium] › e2e/portal.spec.ts:946:1 › an_admin_sets_the_default_chain_and_clears_a_marketplaces_override (1.7s)

  20 passed (1.1m)
```

The new spec sets the global default from the Vetting page, reads the marketplace
that departs from it, clears that override, and confirms the marketplace then
resolves from the global setting — and that `/vetting` resolves on a direct
request, not only as a client-side hop.

## `reqstool status local -p docs/reqstool`

```
$ reqstool status local -p docs/reqstool
INCOMPLETE (0)
242/242 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
$ openspec validate --all --strict
Totals: 32 passed, 0 failed (32 items)
```

## `mkdocs build --strict`

```
$ mkdocs build --strict
INFO    -  Documentation built in 1.27 seconds
```

## Design harness

`/impeccable audit`, `harden` and `critique` were run against
`src/main/frontend/src/pages/vetting.tsx` with the project's own `PRODUCT.md`
and `DESIGN.md` as context. Five findings, all fixed in this change:

1. The override summary used the Badge pill for values rather than statuses;
   changed to the stat chip (`rounded-md border bg-muted`), which is how the
   system distinguishes a number from a state.
2. The bulk reorder used text "Up"/"Down" buttons where the marketplace card
   uses named icon buttons; unified on the icon silhouette.
3. The hint that explains why **Review the change** is disabled was bound only
   to the reason field; it is now bound to the control whose state it explains.
4. `set-order` could be submitted with an empty arrangement, which the server
   refuses with 422; the client now mirrors that invariant and says so.
5. Two `role="group"` controls were both named "When a vetter fails" — the
   default chain's and bulk edit's; the bulk one is now "The mode to apply".

Driving the page also found two defects the tests had not: `/vetting` 404ed on a
direct request because `SpaController` did not forward it, and the chain headline
said "for this marketplace" on the page that governs every marketplace with no
override. Both fixed, both now covered.

The page introduces no animation, so reduced motion has nothing to gate.

## Screenshots

Light and dark, captured against the real e2e stack, at
`~/agents/skillsgateway/pr-411-screenshots/`: `vetting-page-{light,dark}.png`,
`bulk-confirm-{light,dark}.png`, `vetting-non-admin-{light,dark}.png`.
