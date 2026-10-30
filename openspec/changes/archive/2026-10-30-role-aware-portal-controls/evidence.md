# Evidence: role-aware-portal-controls

One fresh run of every gate after the last code edit, at commit
`bcf2c53228a3fb55d8e4bd5de7134cb03bb35ce0` (this file and the archive follow it
and change no code).

## Gates

| Gate | Command | Result |
| --- | --- | --- |
| Java + UI + jar | `./mvnw clean verify` | exit 0; surefire 1179 tests, 0 failures, 0 errors, 9 skipped; vitest 241 tests, 0 failures |
| Stories | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 22 passed (22)`, `Tests 116 passed (116)` (first attempt hit the known "no tests" Storybook cache flake; caches cleared, rerun) |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)`, exit 0 |
| Traceability | `reqstool status local -p docs/reqstool` | exit 0; 1203 passed, 0 failed, 0 skipped; 0 SVCs missing tests, 0 missing MVRs; GW_AUTH_0064, GW_AUTH_0065, GW_AUTH_0066 green |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.54 seconds` |

## The tests fail without the change

Each mutation was applied alone to `src/main/frontend` and
`pnpm exec vitest run --project unit src/pages/role-aware.test.tsx` rerun:

| Mutation | Red |
| --- | --- |
| `accessOf().approves` always true | SVC_GW_AUTH_0072, SVC_GW_AUTH_0073, SVC_GW_AUTH_0076 |
| `accessOf().isAuditor` always true | SVC_GW_AUTH_0074, SVC_GW_AUTH_0075 |
| 403 no longer invalidates `["me"]` | SVC_GW_AUTH_0076 |

## Design harness

`impeccable detect` over the changed portal files reported one `side-tab`
finding, at `pages/marketplaces.tsx:193`. That line predates this change and is
untouched by it, so the finding is dismissed. Harden pass: a long marketplace name now
wraps in both role statements (commit `bcf2c532`).

## Not done

No e2e for a non-admin principal: the mock IdP issues the admin group claim on
every token (`compose.e2e.yaml`), so a non-admin login needs a second IdP
mapping. Each persona is covered by vitest against the real components instead.
