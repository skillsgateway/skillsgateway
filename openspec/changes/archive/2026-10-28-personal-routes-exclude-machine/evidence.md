# Evidence: personal-routes-exclude-machine

Source state: `55440bc3` (tree `c0630645`), built on #748. Rebased onto `main`
after #748 merged, the tree is unchanged. Every result below is from one fresh
run after the last code edit, on 2026-10-28.

Tier 3 (credential administration). Spec approval: the owner said to proceed
without stopping, to keep exposure short. Independent verification: not
performed. Both are declared downgrades.

## Spec → tests

| Scenario (SVC_GW_AUTH_0057) | Test | Seen failing first |
|---|---|---|
| A login named like a machine credential does not see it listed, gets `404` rotating and revoking it, and the credential still authenticates | `PersonalRoutesExcludeMachineTests.a_login_named_like_a_machine_credential_cannot_list_revoke_or_rotate_it` | yes: the listing contained the machine credential's id |

**A refactor during the work.** With the predicate added, `listByPrincipal`
became the same query as `listPersonalByPrincipal` from #735. The duplicate was
removed, and the administrative listing now uses `listByPrincipal`.
`PrincipalTokenAdministrationTests` passed unchanged.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1155, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:28 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)`, on the second run; the first hit the known harness flake |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.9m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `359/359 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.59 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-personal-routes-exclude-machine/mutants.sh` | `3 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-list-includes-machine killed
M2-rotate-finds-machine killed
M3-revoke-takes-machine killed
3 mutants run, all killed
```
