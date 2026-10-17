# Evidence: portal-accessible-names-contain-visible-text

One final run of the gates on `0d4cbf91` (the last code edit), on a machine with Docker. Later
commits touch only this file, `tasks.md` and the archive move.

## The failure it fixes (task 1.1)

With axe-core 4.14.0 installed from the lock file of #646 (not committed), on `main` before the fix:

```
pnpm test:stories
 Test Files  5 failed | 16 passed (21)
      Tests  27 failed | 84 passed (111)
```

All 27 are `label-content-name-mismatch`, in `chain-staleness`, `snapshot-file-tree`,
`vetting-report`, `vetting` and `vetting-flow` stories.

## After the fix

| Gate | Result |
| --- | --- |
| `pnpm test` (jsdom) | 31 files, 234 tests passed |
| `pnpm test:stories`, repository's lock file (axe-core 4.13) | 21 files, 111 tests passed |
| `pnpm test:stories`, #646's lock file (axe-core 4.14.0) | 21 files, 111 tests passed (run on the same source; only `e2e/`, docs and openspec files changed after it) |
| `pnpm e2e` against the freshly packaged jar | 27 passed |
| `tsc -b`, `oxlint` | clean; the three warnings are existing ones |
| `mkdocs build --strict` | built |
| `impeccable detect` on the four changed files | no findings |
| `openspec validate --all --strict` | 29 passed, 1 failed: this change, which has no deltas by design (`skip_specs: true`); archiving it is the last commit |

## Gates that did not give a clean local result

- **`./mvnw clean verify`** — `Tests run: 1102, Failures: 2, Skipped: 9`, BUILD FAILURE. Neither failure comes from
  this change, which touches no Java:
  - `RefAdvertisementTests.an_approved_snapshot_stays_fetchable_by_name` fails the same way on a
    clean `origin/main` on this machine (the advertisement carries a `redirecting` line where the
    test expects `HEAD`). It passes in CI, so this is the local git, not the branch.
  - `RoleEnforcementTests.a_no_role_session_is_refused_…` fails inside the full run and passes when
    run with `-Dtest=RoleEnforcementTests,RefAdvertisementTests`: the known order-dependent case.
- **`reqstool status local -p docs/reqstool`** — reports 0 tests, because it needs the surefire results of a
  green `clean verify`. Not reproduced locally. This change adds no requirement and no `@SVCs` tag and
  renames no test title, so CI is the arbiter.

## Not run

`pnpm test:stories` was not re-run after the very last edit to `tasks.md`/`design.md`, which are not
source. Before the fix the baseline was not run on `main` with the repository's own lock file (4.13): the
failure only exists under 4.14.

Commit: `0d4cbf91002aeff1ea7ebf8a33d7257a5b3efaf2`
