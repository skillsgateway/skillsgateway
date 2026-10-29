# Evidence: unfinished-run-blocks

Source state: `116e6cb0` (tree `7a999580`), built on #755. When rebased onto
`main` after #755 merges, the tree must be identical, and that is checked before
the PR opens. Every result below is from one fresh run after the last code
edit, on 2026-10-29.

Tier 2 (the approval gate). Spec approval: the owner said to proceed without
stopping, to keep exposure short. Independent verification: not performed.
Both are declared downgrades.

## Spec → tests

| Scenario | Test | Seen failing first |
|---|---|---|
| SVC_GW_VETTING_0070: an unfinished run whose only verdict passes is blocked | `UnfinishedRunTests.an_unfinished_run_with_only_passing_verdicts_is_blocked` | yes |
| SVC_GW_VETTING_0070: the same run, finished, is clear | `…the_same_run_once_finished_is_clear` | no: a control |
| SVC_GW_VETTING_0071: a hook command that downloads and runs code and holds a NUL is recorded blocked, with U+FFFD in the message, and its approval is refused | `NulInFindingTests.a_nul_in_a_hook_command_is_recorded_and_the_snapshot_stays_blocked` | yes: the ingest failed with `invalid byte sequence for encoding "UTF8": 0x00` |

**Before the fix.** A probe on the unchanged code ran the same fixture. The
ingest failed, the snapshot was left `held` with an unfinished run, and an
ordinary approval succeeded. That is the behaviour both requirements close.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1175, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 07:01 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.7m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `367/367 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.51 seconds` |
| Mutation | `openspec/changes/archive/2026-10-29-unfinished-run-blocks/mutants.sh` | `3 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-unfinished-clears killed
M2-nul-stored-raw killed
M3-message-raw killed
3 mutants run, all killed
```

## Known limits

- Only the finding message carries a NUL in a test. The verdict detail, the
  report link, the finding id and the location pass through the same
  `storable`, which M2 covers as a whole, but none has a NUL fixture of its own.
- Other tables that hold upstream text (the snapshot violation, the ledger
  detail) are not changed here. A NUL there fails its own write. Under
  `GW_VETTING_0067` a vetting run that stops for such a failure leaves the
  snapshot blocked rather than approvable.
