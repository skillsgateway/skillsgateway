# Evidence: role-grant-principal-validation

Source state: `723b93de`. Every result below is from one fresh run after the
last code edit, on 2026-10-30. This report and the archive commit change no
code.

Tier 3 (authorization). Spec approval: not obtained (autonomous run; the
owner asked for implementation). Independent verification: not performed.
Both are declared downgrades.

## Both observations reproduced before the fix

Run on `48d679d0` (tests added, rule stubbed, nothing wired in):

| Observation | Test | Result before the fix |
| --- | --- | --- |
| A padded, bidi, zero-width, control, separator, lone-surrogate or 256-character principal is granted | `RoleEnforcementTests.a_malformed_principal_is_refused_unrecorded_and_a_well_formed_one_is_granted_exactly` | `Status expected:<422> but was:<201>` |
| A padded `groups` value is granted the mapped admin role | `ClaimRoleMappingTests.claim_values_that_only_resemble_a_mapping_grant_nothing`, `ClaimRoleMapperTests.a_padded_or_altered_claim_value_confers_no_role_and_a_lone_exact_string_does` | `Status expected:<403> but was:<200>`; `Expecting empty but was: [EffectiveRole[role=admin, …]]` |
| `groups` as a plain string `"gw-admins"` is granted admin | same mapper test, positive half | Kept, by design: a lone string is one value (design.md). It still has to match exactly; a lone padded string grants nothing. |

## Spec → tests

| Scenario | Test | Seen failing first |
| --- | --- | --- |
| SVC_GW_AUTH_0063 — API refuses malformed principals unrecorded, grants well-formed ones exactly | `RoleEnforcementTests.a_malformed_principal_is_refused_unrecorded_and_a_well_formed_one_is_granted_exactly`, `RolePrincipalsTests.a_grant_refusal_is_422_and_never_repeats_the_value` | yes |
| SVC_GW_AUTH_0064 — declared malformed grant fails in isolation, also over a stored row, escaped on the ledger | `EstateReconciliationTests.a_declared_grant_with_a_malformed_principal_fails_in_isolation_even_over_a_stored_row`, `RolePrincipalsTests.display_escapes_every_invisible_code_point_and_is_bounded` | yes (`expected: 2 but was: 0` failed entries) |
| SVC_GW_AUTH_0065 — the rule agrees with an independent oracle | `RolePrincipalsTests` (named cases and a 20 000-case seeded sweep against a regex oracle) | yes (stub threw) |
| SVC_GW_AUTH_0066 — padded or altered claim values confer nothing, a lone exact string does | `ClaimRoleMapperTests.a_padded_or_altered_claim_value_confers_no_role_and_a_lone_exact_string_does`, `…arbitrary_claim_payloads_never_throw_and_never_grant_without_an_exact_match` (oracle no longer trims), `ClaimRoleMappingTests.claim_values_that_only_resemble_a_mapping_grant_nothing` | yes, all three |
| SVC_GW_AUTH_0067 — a padded or invisible configured claim value refuses startup | `ClaimRoleMapperTests.a_padded_or_invisible_configured_claim_value_refuses_construction` | yes (`Expecting code to raise a throwable`) |

Two existing `SVC_GW_AUTH_0015.1` assertions that expected a padded claim value
to match were tightened (design.md, "Existing tests that asserted trimming").
No assertion was loosened and no test was removed.

## Gauntlet

| Layer | Command | Result |
| --- | --- | --- |
| Java suite, Spotless, Checkstyle, UI gate, SBOM, jar | `./mvnw clean verify` | `Tests run: 1183, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 09:45 min` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | exit 0; `1205` passed, `0` failed, `0` SVCs missing tests; GW_AUTH_0060 and GW_AUTH_0061 each `12 12` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.78 seconds` |
| API contract | `OpenApiContractTests` (in verify) | passes with the regenerated `openapi.json`; only two descriptions changed |
| Mutation | `openspec/changes/archive/2026-10-30-role-grant-principal-validation/mutants.sh` (run in two halves with `ONLY`) | `12 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Property-style | seeded sweeps in `RolePrincipalsTests` (20 000 strings incl. adjacent lone surrogates that pair) and `ClaimRoleMapperTests` (2 000 payloads) | pass |
| Supply chain | no dependency added or changed | — |

```
M1-api-unchecked killed
M2-estate-checks-after-existence killed
M3-list-values-trimmed killed
M4-lone-value-trimmed killed
M5-configured-value-unchecked killed
M6-no-break-space-allowed killed
M7-trailing-space-allowed killed
M8-format-allowed killed
M9-lone-surrogate-allowed killed
M10-length-in-utf16-units killed
M11-refusal-echoes-value killed
M12-display-unescaped killed
```

## Adversarial pass

Tried against the finished rule: a no-break space and an ideographic space at
either end (refused), a byte-order mark (refused), U+0085 (refused, `Cc`), a
valid surrogate pair at the 255-code-point boundary (accepted; 256 refused), two
lone surrogates that pair into one assigned code point (judged as the pair, as
the sweep proves), a refused value echoed in the response (absent), and a
malformed declared principal over an existing row (still reported failed).

## Known limits

- Invisible characters outside `Cc`, `Cf`, `Zl`, `Zp` and `Cs` are accepted:
  variation selectors and the combining grapheme joiner (`Mn`), the Hangul
  fillers (`Lo`). So are confusable look-alikes (a Cyrillic `а` for `a`). Each
  yields a grant that matches nobody else; none widens privilege.
- `skills-gateway.roles.admins` is not checked by the rule (design.md,
  "Non-goals").
- Grant rows written before this change are not repaired; pre-1.0 there is no
  deployed data (AGENTS.md).
- RED-run note: the first run also failed
  `RoleEnforcementTests.a_no_role_session_is_refused_every_mutation_and_privileged_read_but_keeps_browsing_and_tokens`
  with a 404 on an open read. That run skipped the frontend build; the test
  passed in every later run, including the full verify.
