# Evidence: upstream-github-app (Tier 3)

- **Spec approval:** not obtained (autonomous run). The design was settled by
  the owner's delegate. `acceptance.md` was committed before any test or
  implementation, at `61d42bf9`, and revised visibly under
  `## Revisions during implementation`.
- **Source state:** `f889f39b` on `feat/upstream-github-app`. Every gate below
  ran over that commit, after the last code edit. The commits after it change
  only `openspec/` text.
- **Mutation state:** the source under test is `c27ab82c` (the GREEN commit);
  `git diff c27ab82c f889f39b -- src/main` is empty. M1–M8, M10–M20 ran with
  the tests at `4dc62082`; M9, M11, M15, M17, M19, M21–M25 with the tests at
  `74b72301` (M9 had survived at `4dc62082`, see below).
- **Entry points:** the project gates below, and
  `openspec/changes/archive/2026-09-25-upstream-github-app/mutants.sh`
  (`mutants.sh` alone runs all 25; `mutants.sh M1- M7-` runs a subset).
- **Isolation:** a branch in the main checkout, not a worktree. The gauntlet
  needs the built `node_modules` and Docker.
- **Independent verification:** not performed.
- **New dependencies:** none. JWT signing uses nimbus-jose-jwt 10.9.1, already
  a compile dependency through `spring-boot-starter-oauth2-client`.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 938, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  23 passed (23)
[INFO]       Tests  185 passed (185)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:20 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  16 passed (16)
      Tests  79 passed (79)

$ (cd src/main/frontend && pnpm e2e)
  23 passed (1.2m)

$ uvx --from reqstool==0.12.0 reqstool status local -p docs/reqstool
313/313 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
exit 0
```

The first `pnpm test:stories` run after `mvnw clean` failed to import five
story files ("Failed to fetch dynamically imported module", 34/34 tests
passed, 5 files failed): the known harness flake. After removing
`node_modules/.cache/storybook` and `node_modules/.vite` again, the second run
above passed. No frontend file changes in this change.

`ConfigSurfaceBudgetTests` passes at 109 (unchanged; the `github-app` block is
inside a list element, which the counter does not descend into).
`ContextBudgetTests` passes unchanged: the integration suite adds its entries
to `AbstractExternalSourceTest`'s existing context.

## RED — the new tests, failing before the implementation

Run over `bc3d9a04`, where `GitHubAppTokens` was a stub:

```console
Tests run: 10, Failures: 6, Errors: 3  -- GitHubAppTokensTests
Tests run: 11, Failures: 0, Errors: 11 -- UpstreamGitHubAppIntegrationTests
Tests run: 13, Failures: 0, Errors: 0  -- UpstreamCredentialsTests (#498, unchanged behaviour)
Tests run: 9,  Failures: 0, Errors: 0  -- UpstreamCredentialsIntegrationTests (#498)
```

The one unit test that passed against the stub is
`the_properties_never_print_the_key`: the properties record's `toString` was
written with the plumbing, before the test. Mutant M18 (the key added to that
`toString`) proves the test can fail.

## Mutation — `mutants.sh`

Fail-closed, #498's runner: each mutant must apply exactly once, the named test
must fail with a report, and the restore is checked with `git diff`.

```console
M1-assertion-not-backdated killed
M2-assertion-too-long killed
M3-no-renewal-margin killed
M4-token-for-every-repository killed
M5-token-can-write killed
M6-api-redirect-followed killed
M7-never-renewed killed
M8-fresh-token-renewed-too killed
M9-refused-token-kept killed
M10-extra-path-segments killed
M11-any-name-reaches-the-api killed
M12-cleartext-api killed
M13-both-kinds-accepted killed
M14-pkcs1-unread killed
M15-not-selected-misread killed
M16-server-clock-ignored killed
M17-suspension-misread killed
M18-tostring-prints-key killed
M19-replaced-token-not-scrubbed killed
M20-key-quoted-in-error killed
M21-ledger-names-no-kind killed
M22-token-outside-prefix killed
M23-installation-always-looked-up killed
M24-unreachable-misread killed
M25-token-not-scrubbed-from-git-failure killed
```

25/25 killed. **M9 survived its first run** (at `4dc62082`): a token refused
after renewal stayed cached, and the API request count could not tell. The test
now asserts the next read's git requests carry only the new token
(`74b72301`), and M9 is killed. **Negative control:** `mutants.sh M99-` exits 5
with "M99-: no such mutant", so a mistyped selection cannot report success.

## SPEC mapping

| Scenario | Test |
|---|---|
| U1 | `GitHubAppTokensTests.github_issues_pkcs1_and_both_forms_load`, `an_unreadable_key_stops_startup_and_is_never_quoted` |
| U2 | `an_unusable_app_entry_stops_startup_naming_the_entry`, `a_usable_app_entry_loads_and_the_api_defaults_to_github` |
| U3 | `the_assertion_is_signed_by_the_app_key_and_short_lived` |
| U4 | `a_refusal_from_the_api_reads_as_its_cause` |
| U6 | `the_properties_never_print_the_key` |
| U7 | `only_a_plain_owner_and_repository_name_a_repository` |
| (cache margin) | `a_token_is_reused_until_five_minutes_before_it_expires`, `a_token_that_expires_within_the_margin_is_never_reused` |
| S1, S2, S7 | `UpstreamGitHubAppIntegrationTests.a_private_upstream_registers_and_ingests_with_a_minted_installation_token`, `a_configured_installation_is_minted_from_without_a_lookup` |
| S3 | the same test (three reads, one mint) |
| S4 | `a_token_close_to_its_expiry_is_renewed_for_every_read` |
| S5 | `a_refused_cached_token_is_renewed_once_and_only_once` |
| S6 | `a_redirect_to_another_port_carries_no_installation_token`, `a_redirect_to_another_path_carries_no_installation_token` |
| S8 | `a_redirect_from_the_api_is_not_followed` |
| S9 | `the_api_is_only_ever_the_configured_one` |
| S10 | `the_key_the_assertion_and_the_token_are_never_repeated` |
| S11 | `each_api_refusal_is_reported_with_its_own_reason` |
| S12 | `the_registration_ledger_entry_names_the_github_app_kind` |
| G12 (static entries unchanged) | #498's `UpstreamCredentialsTests` and `UpstreamCredentialsIntegrationTests`, green in RED and in the gate run; their only edit is the fourth constructor argument (`null`) |

## Layers not run, and known limits

- **No coverage tool** in this build; changed-line coverage is not measured.
  Mutation is the layer that checks the tests assert something.
- **No property-based tests.** The one parser (`repository`) is covered by an
  explicit table of accepted and refused paths.
- **No run against the real GitHub API**, because no real App key may be used
  here. The fake API follows GitHub's documented endpoints, status codes and
  error messages; a wording change at GitHub degrades clock-skew detection to
  "the GitHub API refused the App's key" (design, Risks).
- **Clock skew from the `Date` header** is unit-tested only (acceptance,
  Revisions).
- **Concurrent mints** for one repository are allowed and not tested (design,
  decision 5).
