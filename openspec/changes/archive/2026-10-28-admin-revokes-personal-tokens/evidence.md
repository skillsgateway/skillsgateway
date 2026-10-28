# Evidence: admin-revokes-personal-tokens

Source state: `375dc873` (branch `fix/admin-revokes-personal-tokens`, on
`origin/main` `a056c258`). Every result below is from one fresh run after the
last code edit, on 2026-10-28.

Tier 3 (auth, credential lifecycle). Spec approval: the owner said to proceed
without stopping, to keep exposure short. Independent verification: not
performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_AUTH_0055) | Test | Seen failing first |
|---|---|---|
| An admin lists a principal's PAT and session credential without secrets, revokes both, both stop authenticating, the ledger records it, and a repeat revokes none | `PrincipalTokenAdministrationTests.an_administrator_lists_and_revokes_every_personal_token_of_a_principal` | yes: `Status expected:<200> but was:<404>` |
| A machine credential under the same principal is neither listed nor revoked | `…a_machine_credential_of_the_same_principal_is_left_alone` | yes: same |
| A no-role session is refused both routes, and the token stays live | `…a_session_without_the_admin_role_is_refused_and_revokes_nothing` | yes: `Status expected:<403> but was:<404>` |

**Order of work, recorded honestly:** the implementation was written before the
test. To get a genuine RED, the implementation files were stashed and the
controller moved out of the tree. The three tests were then run and failed as
shown, and the implementation was restored unchanged before the GREEN run.

**Tests changed outside the new class:**
- `RoleEnforcementTests` classifies the two routes: the DELETE as an always-admin
  mutation, the GET as an admin-only privileged read that the auditor walk
  skips and asserts `403` on.
- `RoleEnforcementTests` and `MachineApiScopeTests` request helpers fill
  `{principal}` as they already fill `{id}` and `{name}`.

No assertion was weakened.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1111, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:49 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `344/344 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.69 seconds` |
| API contract | `OpenApiContractTests` (in verify) | green after regenerating `openapi.json` and `types.gen.ts`; additive |
| Mutation | `openspec/changes/archive/2026-10-28-admin-revokes-personal-tokens/mutants.sh` | `4 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-list-unguarded killed
M2-revoke-unguarded killed
M3-revoke-takes-machine killed
M4-list-shows-machine killed
4 mutants run, all killed
```

## Known limits

- Validity is still not bound to the owner's standing: a leaver's tokens stop
  only when an administrator revokes them (design.md, Not in this change).
