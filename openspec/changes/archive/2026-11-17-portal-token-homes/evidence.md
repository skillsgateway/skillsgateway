# Evidence — portal-token-homes

- Issue: #886. Tier: not a full `old-coder` change (no `acceptance.md`, no
  `mutants.sh`), but it sits next to the credential trust boundary: it changes
  what a machine credential's lifetime may be. So the lifetime rules were written
  test-first with negative cases, and the migration was proven necessary by
  removing it. The scope-list route and the status panel were written alongside
  their tests, not before: where that is so, it is said below.
- Final gate results are in "Final run", from one fresh run after the last code
  edit (`870c1e38`).

## Development log

### Machine credential lifetime (`GW_AUTH_0077`, `SVC_GW_AUTH_0093`)

The new tests in `MachineCredentialLifecycleTests` and `TokenTtlCapTests` were
written first against a service with no `neverExpires`: the test sources did not
compile (RED, `createMachineCredential` has no six-argument form). After the
implementation they pass: lifecycle 10, cap 2, admin 3, registry scope 9.

The `V2` migration was proven necessary: with
`V2__machine_credentials_may_never_expire.sql` moved away (and its stale copy in
`target/classes` removed — a first attempt passed because the old copy was still on
the classpath, so it proved nothing), the never-expires test fails with a
`DataIntegrityViolationException` on the `INSERT INTO access_tokens`, from the
`machine_credentials_expire` check. With the migration restored it passes. That is
the only test of the migration; there is no upgrade-from-`V1` test with data in it,
because dropping a constraint repairs no rows.

Existing test `a_lifetime_beyond_the_built_in_cap_is_refused_rather_than_shortened`
(`SVC_GW_AUTH_0024`) was not edited: it reads `DEFAULT_MACHINE_MAX_TTL`, so its boundary
moved from 90 to 365 days with the constant. This is the owner-approved reversal of
part of `GW_AUTH_0024 — A machine credential must expire and is administered
centrally`, not a weakened test.

### Scope-list route (`GW_AUTH_0076`, `SVC_GW_AUTH_0092`)

Written together with its test, not before it, so there is no RED for the route
itself. `RoleEnforcementTests` did go red on it: every route has to be classified,
and the new `GET /api/v1/tokens/machine/scopes` was not. It was added to the
privileged reads, with the auditor walk skipping it as it skips the machine list.
`OpenApiContractTests` went red until `openapi.json` and `types.gen.ts` were
regenerated, then passed.

### A defect the portal tests caught (`GW_AUTH_0073`, `SVC_GW_AUTH_0089`)

The first version of the cleartext test failed: after the show-once dialog closed,
TanStack Query's mutation cache still held the created token (variables and result)
because the page that made the mutation was still mounted, and `gcTime: 0` only
counts once the observer lets go. Fixed by resetting the mutation as soon as the
page has taken the value, for creation and rotation; the test then passes and covers
both. The personal token page and the client set-up wizard have the same retention
and were deliberately left alone (out of scope).

### Portal tests

`system-tokens.test.tsx` (9), `system-token-marks.test.ts` (4),
`marketplace-status.test.tsx` (11), `routes.test.tsx` (3) and additions to the
tokens, overview, app-layout and role-aware tests: 317 unit tests in all, passing.
The status panel takes no query or router: its test renders it with neither, so it
cannot make a read.

### `impeccable` audit

`impeccable detect` on the changed files: no findings. On the live page it reported
contrast 4.2:1 for white on the purple `--primary` token and a `height` transition;
both come from shared design-system tokens and components that predate this change
and were left alone. A manual pass found four things in this change, fixed in
`870c1e38` with tests: "never expires" greyed out like an absent value, "never"
repeated above "never used", scope routes reachable only by hover, and a `<label for>`
pointing at a non-control. Open, not fixed: the status panel repeats the header's
*Serving* and *Last ingest failed* lines and pushes the review card down on the
approver's page, and the portal shell is not responsive at 390 px (as on `main`).

## Final run

One fresh run after the last code edit, at commit `870c1e38`
(`fix(portal): say a never-expiring credential plainly and make scope routes
reachable`), on 2026-11-17. The last commit before this file and the archive is
that one.

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | BUILD SUCCESS. `Tests run: 1372, Failures: 0, Errors: 0, Skipped: 9`; frontend `Test Files 43 passed (43)`; total 04:54 min |
| `pnpm test:stories` | `Test Files 26 passed (26)`, `Tests 139 passed (139)` |
| `pnpm e2e` | `32 passed (2.0m)` |
| `reqstool status local -p docs/reqstool` | `412/412 complete · 0 incomplete · PASS` (run after the clean build and the e2e run) |
| `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| `mkdocs build --strict` | built; one notice for an existing link in `api/tokens.md` (`#post-apitokenssession`) that this change does not touch |

Notes for the reader: the nine skipped tests are all in the object-store
conditional-write fidelity suite (real AWS S3, issue #151), which this change does
not touch. The
`verify` and `e2e` runs started before another session's lock convention was
agreed and may have overlapped its Docker runs; both passed.
