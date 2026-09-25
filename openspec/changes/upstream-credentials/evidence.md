# Evidence: upstream-credentials (Tier 3)

- **Spec approval:** not obtained (autonomous run). The proposals in #494 were
  settled by the owner's delegate. `acceptance.md` was committed before any
  implementation, at `c092aeec`. It was revised visibly under
  `## Revisions during implementation`.
- **Source state:** `64ff9f73` on `feat/upstream-credentials`, stacked on
  `fix/ingest-failures-say-why` (#497). Every gate below ran over that commit,
  after the last code edit.
  - The mutation run ran over `31c399a5`.
  - `git diff 31c399a5 64ff9f73 -- src` is empty. The commit in between
    changes only documentation and `tasks.md`.
- **Entry points:** the project gates below, and
  `openspec/changes/upstream-credentials/mutants.sh`.
- **Isolation:** a branch in the main checkout, not a worktree. The gauntlet
  needs the built `node_modules` and Docker.
- **Independent verification:** not performed.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 825, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  21 passed (21)
[INFO]       Tests  182 passed (182)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:08 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  14 passed (14)
      Tests  67 passed (67)

$ (cd src/main/frontend && pnpm e2e)
  21 passed (1.1m)

$ uvx --from reqstool==0.12.0 reqstool status local -p docs/reqstool   # the version CI pins
286/286 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
exit 0
```

The first two `pnpm test:stories` runs after `mvnw clean` failed to *import*
three story files. The error was "Failed to fetch dynamically imported module",
with 40/40 tests passed and 3 files failed. That is the known harness flake.
Removing `node_modules/.cache/storybook` alone did not clear it. Removing
`node_modules/.vite` as well did, and the run above followed. No frontend file
changes in this PR.

## RED — the new tests, failing before the implementation

Run over `c036def6`, where `UpstreamCredentials` and `UpstreamFailure.scrub`
were stubs:

```console
Tests run: 13, Failures: 7, Errors: 3  -- UpstreamCredentialsTests
Tests run: 9,  Failures: 5, Errors: 0  -- UpstreamCredentialsIntegrationTests
Tests run: 7,  Failures: 1, Errors: 0  -- UpstreamReachabilityTests (userinfo now 400)
```

Some tests passed against the stubs.

- **The stub sends no credential at all:** the three redirect tests, and "an
  upstream under no prefix is read without a credential". Stub passes of this
  kind are vacuous, so each redirect test was proven against a mutant. Mutant
  M1 is the rejected design, JGit's own `CredentialsProvider`, and all three
  redirect tests fail against it (below).
- **Already true before the change:** `no_entries_is_the_anonymous_gateway`,
  and `the_properties_never_print_the_token` (the `toString` override was
  written with the record). These are kept as regression armour. Mutant M18
  shows the `toString` test can fail.

## Scenario → test

| Scenario | Test |
|---|---|
| U1 segment matching | `UpstreamCredentialsTests.a_prefix_matches_whole_path_segments_only`, `a_trailing_slash_on_the_prefix_means_nothing` |
| U2 longest prefix | `UpstreamCredentialsTests.the_longest_prefix_wins_whatever_the_order` |
| U3 non-canonical URLs | `UpstreamCredentialsTests.a_non_canonical_request_url_is_never_covered`, `a_url_with_dot_segments_or_userinfo_selects_nothing` |
| U4 per-request factory | `UpstreamCredentialsTests.the_connection_factory_attaches_the_credential_only_under_the_prefix`, `the_jdk_is_never_allowed_to_follow_a_redirect_with_the_credential` |
| U5 startup refusal | `UpstreamCredentialsTests.an_unusable_entry_is_refused_naming_the_entry_and_never_the_token`, `two_entries_for_one_prefix_are_refused`, `https_and_loopback_http_entries_load`, `no_entries_is_the_anonymous_gateway` |
| U6 `toString` | `UpstreamCredentialsTests.the_properties_never_print_the_token` |
| S1 private upstream | `UpstreamCredentialsIntegrationTests.a_private_upstream_registers_and_ingests_with_the_longest_prefix_credential` |
| S2 anonymous | `…an_upstream_under_no_prefix_is_read_without_a_credential` |
| S3 refused token | `…a_refused_token_reads_as_not_found_or_authentication` |
| S4 other port | `…a_redirect_to_another_port_of_the_same_host_carries_no_credential` |
| S5 other path | `…a_redirect_to_another_path_of_the_same_host_carries_no_credential` |
| S6 other credentialed prefix | `…a_redirect_into_another_credentialed_prefix_does_not_switch_tokens` |
| S7 never repeated (responses, `marketplaces`, `fetch_log`, captured log, a server-quoted token) | `…the_token_is_never_repeated` |
| S8 scrub | `UpstreamCredentialsTests.a_token_a_server_quotes_is_scrubbed_from_the_failure` |
| S9 userinfo | `…a_clone_url_with_userinfo_is_refused_before_the_upstream_is_contacted`, `UpstreamReachabilityTests.a_credential_in_the_url_is_never_repeated` |
| S10 ledger prefix | `…the_registration_ledger_entry_names_the_prefix_and_never_the_token` |
| Invariant: anonymous path unchanged | `UpstreamReachabilityTests`, `IngestFailureTests` and `EstateReconciliationTests` are green unchanged, except the one userinfo status. |
| Invariant: address policy not extended | No change to `SourceAddressPolicy` or its call sites. `GuardedHttpConnectionFactory` only moved its forwarding into `ForwardingHttpConnection`, and `ExternalSourceResolutionTests` is 19/19. |
| Invariant: no schema, contract or role change | No change to `V1__init.sql` or `openapi.json`. `OpenApiContractTests` is green in `verify`. |
| Invariant: one leaf | `ConfigSurfaceBudgetTests` measures 109 against a budget of 109. `ContextBudgetTests` is unchanged at 27. |

## Mutation (manual, `mutants.sh`, fail-closed)

The runner applies each mutant alone. It requires the *named* test to fail, and
treats a run with no report as INVALID, never as a kill. It restores the
source and proves the restore with `git diff --exit-code`.

```console
M1-jgit-credentials-provider-a_redirect_to_another_port_of_the_same_host_carries_no_credential killed
M1-jgit-credentials-provider-a_redirect_to_another_path_of_the_same_host_carries_no_credential killed
M1-jgit-credentials-provider-a_redirect_into_another_credentialed_prefix_does_not_switch_tokens killed
M2-every-request-covered killed
M3-no-segment-boundary killed
M4-first-prefix-wins killed
M5-dot-segments-allowed killed
M6-encoded-dots-allowed killed
M7-jgit-may-set-authorization killed
M8-jdk-follows-redirects killed
M9-placeholder-accepted killed
M10-cleartext-anywhere killed
M11-duplicates-accepted killed
M12-port-ignored killed
M13-credential-never-installed killed
M14-failure-not-scrubbed killed
M15-scrub-does-nothing killed
M16-userinfo-accepted killed
M17-ledger-silent killed
M18-tostring-prints-token killed
all mutants killed
```

That is 20/20. The runner's fail-closed path was exercised for real. Its first
run stopped with `INVALID (no test report)`, because the mutant's id contained
a `/`, which made the log path a missing directory. It did not report a kill.
The id was fixed and the run repeated.

## Layers skipped or reduced

- **Mutation tool:** none is configured for this project (no PIT). The manual
  runner above takes its place.
- **Changed-line coverage:** not measured. The project has no coverage gate.
  Every changed branch in `UpstreamCredentials` has a mutant above, except the
  proxy branch of `Factory.create`. JGit passes a proxy only when one is
  configured, and none is in the tests.
- **Property-based tests:** not added. The matcher's domain is finite enough
  that the listed spellings are the adversarial set (U1, U3). A fuzzer over
  URL spellings would be the next layer, if one is wanted.
- **Real execution against a real forge:** not performed. No real token may be
  used, and tests do not reach the internet. The transport is exercised
  against an in-process git smart-HTTP server built on JGit's own
  `UploadPack`.

## Known limits

- **DNS:** the token goes to whatever the prefix's host name resolves to. That
  is the operator's configuration, and the address policy is not applied to
  marketplace upstreams (#497's decision, kept).
- **Forge metadata:** the REST lookup at registration, and external plugin
  sources, are anonymous. A private repository's metadata stays empty.
- **Rotation:** a rotated token needs a restart.
