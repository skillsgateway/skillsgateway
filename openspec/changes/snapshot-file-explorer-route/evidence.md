# Evidence: snapshot-file-explorer-route

One fresh run of every gate after the last edit.

| Gate | Command | Result |
| --- | --- | --- |
| Java + UI + jar | `./mvnw clean verify` | `Tests run: 713, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` (05:39 min) |
| Storybook | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 10 passed (10)` · `Tests 52 passed (52)` |
| Real-browser e2e | `(cd src/main/frontend && E2E_GATEWAY_PORT=18500 pnpm e2e)` | `20 passed (1.1m)` |
| Requirements | `reqstool status local -p docs/reqstool` | `252/252 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 32 passed, 0 failed (32 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.31 seconds` |

## What the gates caught that review did not

**The deep link 404'd on a cold load — the change's own deciding requirement,
broken.** `SpaController` forwards an explicit list of client routes to the SPA
entry, and the new route was not on it. Every jsdom test passed, because they
drive an in-memory router that never asks the server anything; the defect was
invisible until the Playwright suite did `page.goto(deepLink)` on a fresh
browser context. `SpaRoutesTests` now derives the list from the router's own
`path:` declarations rather than restating it, so the two cannot drift silently
again. Proven by reverting the one-line fix: both its tests fail with
`Status expected:<200> but was:<404>`, and pass with it restored.

**`reqstool status` rejected a requirement category `mvnw verify` accepted.**
GW_INGEST_0032 was first written with `usability`; the CLI validates against the
schema's ISO 25010:2023 vocabulary, where that is `interaction-capability`. The
Maven plugin does not check categories, so only the dedicated gate found it.

## Notes on the runs

- **`E2E_GATEWAY_PORT=18500` was required**, as it was throughout the 1.0.0
  readiness run: an unrelated process on this machine holds 8081, and the
  gateway failed to start with `Web server failed to start. Port 8081 was
  already in use.` after the compose stack was already up. That is exactly the
  failure [#448's sibling issue #458](https://github.com/skillsgateway/skillsgateway/issues/458)
  describes; its fix is in flight as
  [#461](https://github.com/skillsgateway/skillsgateway/pull/461) and is not on
  this branch.
- **The first e2e run after a code change must repackage.** `run-e2e.sh` takes
  the newest jar under `target/`, so a fix made after `mvnw verify` is not in
  the jar the suite runs; the SPA-route fix appeared to not work until
  `./mvnw -DskipTests package` had been run. Worth knowing before concluding a
  fix failed.
- **The Storybook harness flake reproduced, and has a deterministic cure.**
  After `mvnw clean verify` rebuilds the frontend, the Storybook vitest cache
  under `src/main/frontend/node_modules/.cache/storybook` is stale, and
  `pnpm test:stories` fails with `Failed to fetch dynamically imported module`
  or `The iframe … did not become ready within 60000ms` — on story files this
  change does not touch. Retrying does not help; deleting that directory and
  re-running does, first time. The tabled run is that one.

## Design harness

`/impeccable audit`, `/impeccable harden` and `/impeccable critique` were all
run, the last because this adds a new page. The detector
(`scripts/detect.mjs`) reports **no findings** over the six changed UI files —
verified as a real run, not a silent no-op, by planting a canary file with a
known-bad pattern and confirming it is flagged. Note the detector's regex engine
cannot see contrast, occlusion or overflow in `.tsx`; those need a browser, and
no interactive browser inspection was performed.

Findings fixed in this change, and the ones dismissed, are listed in the PR body.

## Commit

`71895bee` — the implementation commit these gates ran against. This report is
the commit after it, so that the SHA it cites is one that exists.
