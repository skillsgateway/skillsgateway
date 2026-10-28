# Evidence: redact-held-hook-text

Source state: `d7d4d170` (tree `b506adb3`), built on #749. When rebased onto
`main` after #749 merges, this change's diff must be identical; that is checked
before the PR leaves draft. Every result below is from one fresh run after the
last code edit, on 2026-10-28.

Tier 2 (a read boundary). Spec approval: the owner said to proceed without
stopping, to keep exposure short. Independent verification: not performed.
Both are declared downgrades.

## Spec → tests

| Scenario | Test | Seen failing first |
|---|---|---|
| SVC_GW_VETTING_0067: a session holding no role reads `/content` and `/vetting` for a held snapshot whose hook commands carry a GitHub token, one where the finding message is cut; no part of the token appears, the rest of the command does | `HeldHookTextRedactionTests.a_session_holding_no_role_never_reads_a_token_from_a_held_hook` | yes: `/content` returned the token |
| SVC_GW_VETTING_0068: each shape is masked with its surroundings kept | `SecretRedactionTests.each_credential_shape_is_masked_and_its_surroundings_kept` | no: written with the redactor; its mutants (M4, M5, M7) fail it |
| SVC_GW_VETTING_0068: text holding no credential, including a low-entropy assigned value, is unchanged | `SecretRedactionTests.text_holding_no_credential_is_returned_unchanged` | no: M6 fails it |

The straddling-token case was added after M3, in its first form, survived. Two
hooks on one JSON line share a `path:line`, so only one finding was reported.
Each hook now has a line of its own, and M3 moves the masking to after the cut.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Java suite, Spotless, Checkstyle, SBOM, jar | `./mvnw clean verify -Dskip.ui.verify=true` | `Tests run: 1158, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` |
| UI gate | `(cd src/main/frontend && pnpm verify)` | `Test Files 31 passed (31)` · `Tests 234 passed (234)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` (second run; the first hit the known harness flake, `Failed to run the test …vetting-flow.stories.tsx`) |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.7m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `360/360 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.59 seconds` |
| Mutation | `openspec/changes/archive/2026-10-28-redact-held-hook-text/mutants.sh` | `7 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Supply chain | no dependency added or changed | — |

```
M1-finding-unmasked killed
M2-inventory-unmasked killed
M3-masked-after-the-cut killed
M4-key-body-kept killed
M5-assignments-kept killed
M6-every-assignment-masked killed
M7-shapes-kept killed
7 mutants run, all killed
```

## Known limits

- The masking covers what the secret-scan rules recognise. A credential with
  no distinctive shape shows, just as it passes the scanner.
- Only hook text is masked. The other inventory fields are names and paths, and
  the raw declaration is still read only through the approver-gated preview.
