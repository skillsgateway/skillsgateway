# Evidence: bootstrap-admins-principal-rule

Source state: `08f66265` (last code edit in `de0084ec`; the later commits touch
only the mutation script and `tasks.md`). Every gate below is one fresh run on
that state, 2026-10-31. Spec approval: the task was set by the owner; the
OpenSpec proposal was not separately approved before implementation
(autonomous run). Tier 3 (startup refusal on a trust boundary).

## Spec → tests

`SVC_GW_AUTH_0085 — A malformed configured administrator refuses startup,
naming its position`, in `RoleBootstrapGuardTests`:

| Case | Test | Seen failing first |
| --- | --- | --- |
| leading space, trailing space, trailing tab, zero-width space, bidi override, blank, 256 code points, one bad entry after two good ones (position 2) | `a_malformed_configured_administrator_refuses_startup_naming_its_position` [1]–[8] | yes, each: context started |
| malformed entry with `dev-insecure-auth=true` | `a_malformed_configured_administrator_refuses_startup_under_the_escape_hatch_too` | yes: context started |
| well-formed entries incl. `Bob Example`, a non-ASCII name and exactly 255 code points; comma-delimited `ops, bob` | `well_formed_configured_administrators_start` | passes before and after (regression armor; M4 kills it) |

Each refusal asserts the message names `skills-gateway.roles.admins[i]`, is not
the "no administrator" refusal, and does not contain the entry's value.
Existing SVC tests (`SVC_GW_AUTH_0026`, `SVC_GW_AUTH_0063`…`0065`) are
unchanged and pass.

The first RED attempt used `withPropertyValues`, which trims values, so the
padded cases were not exercised; the tests bind through a `MapPropertySource`
instead, and every case was then seen failing.

## Gates

| Gate | Command | Result |
| --- | --- | --- |
| Java + UI + jar | `flock … ./mvnw clean verify` | `BUILD SUCCESS`; `Tests run: 1217, Failures: 0, Errors: 0, Skipped: 9`; vitest `283 passed (283)` |
| Story tests | `pnpm install --frozen-lockfile && flock … pnpm test:stories` | `Test Files 23 passed (23)`, `Tests 123 passed (123)` |
| e2e | `flock … pnpm e2e` | `30 passed (1.9m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | exit 0; `1274` passed, `0` failed, `0` SVCs missing tests, `0` missing MVRs; GW_AUTH_0060 `15 15` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.55 seconds` |
| Mutation | `openspec/changes/archive/2026-10-31-bootstrap-admins-principal-rule/mutants.sh` (on `2d99ad01`, same code) | `6 mutants run, all killed` |
| Mutation dry run | `DRY=1 mutants.sh` | `6 mutants apply` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |
| Leaked containers | `docker ps` after verify | none from this run (ryuk reaped its session) |

```
M1-admins-unchecked killed
M2-escape-hatch-skips-check killed
M3-length-unbounded killed
M4-boundary-off-by-one killed
M5-refusal-echoes-value killed
M6-wrong-position killed
6 mutants run, all killed
```

## Known limits

- The comma-delimited form trims each element as Spring binds it, so a padded
  entry there is corrected silently rather than refused; only the indexed or
  YAML-quoted form can carry one. Documented in `reference/configuration.md`.
- Independent verification: not performed.
