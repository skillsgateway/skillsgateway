# Evidence: withdrawal-survives-reject

Source state: `4a232220` (tree `a15a1d6d`), built on #754. When rebased onto
`main` after #754 merges, the tree must be identical, and that is checked before
the PR opens. Every result below is from one fresh run after the last code
edit, on 2026-10-29.

Tier 2 (approval). Spec approval: the owner said to proceed without stopping,
to keep exposure short. Independent verification: not performed. Both are
declared downgrades.

## Spec → tests

| Scenario (SVC_GW_APPROVAL_0036) | Test | Seen failing first |
|---|---|---|
| An administratively withdrawn snapshot is not rejected, and the withdrawal still stands against the commit | `WithdrawnSnapshotRejectTests.an_administratively_withdrawn_snapshot_is_not_rejected` | yes: the reject succeeded |
| A snapshot revoked by re-vetting is rejected | `…a_snapshot_revoked_by_re_vetting_is_still_rejected` | no: a control. M2 fails it |

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1172, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 06:59 min` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` on the second run; the first hit the known harness flake. No frontend file changed. |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.7m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `365/365 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.59 seconds` |
| Mutation | `openspec/changes/archive/2026-10-29-withdrawal-survives-reject/mutants.sh` | `2 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-reject-allowed killed
M2-every-revocation-kept killed
2 mutants run, all killed
```

## Known limits

- Rows rejected before this change, from an administrative withdrawal, are not
  repaired. Pre-1.0 there is no deployed data to repair (AGENTS.md, "The
  schema is one migration").
