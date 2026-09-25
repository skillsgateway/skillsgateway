# Evidence: ingest-failures-say-why (Tier 3)

- **Spec approval:** not obtained (autonomous run). The proposals in #489 were
  settled by the owner's delegate. `acceptance.md` was committed before any
  implementation, at `5858303a`, and revised once, visibly, under
  `## Revisions`.
- **Source state:** `e3e37c06` on `fix/ingest-failures-say-why`. Every gate and
  the mutation run below ran over that commit, after the last code edit. The
  only later commit changes `acceptance.md`, which is text.
- **Entry points:** the project gates below, and
  `openspec/changes/ingest-failures-say-why/mutants.sh`.
- **Independent verification:** not performed.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 803, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  21 passed (21)
[INFO]       Tests  182 passed (182)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:11 min

$ (cd src/main/frontend && pnpm test:stories)   # after rm -rf node_modules/.cache/storybook
 Test Files  14 passed (14)
      Tests  67 passed (67)

$ (cd src/main/frontend && pnpm e2e)
  21 passed (1.1m)

$ uvx --from reqstool==0.12.0 reqstool status local -p docs/reqstool   # the version CI pins
280/280 complete · 0 incomplete · PASS

$ openspec validate --all --strict      # also npx @fission-ai/openspec@1.3.1, the version CI pins
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
exit 0
```

- **Storybook.** The first run hit the known harness flake: the
  `vetting.stories.tsx` iframe "did not become ready within 60000ms". The
  second run passed whole.
- **reqstool.** The locally installed `reqstool` (a pipx venv) no longer
  imports, so the gate was run with the CI-pinned version through `uvx`.

## Spec → test mapping

| Scenario | Test | Status |
|---|---|---|
| S1 a missing repository is refused, nothing created | `UpstreamReachabilityTests.a_repository_that_does_not_exist_is_refused_and_nothing_is_created` | pass |
| S2 a 401 is refused the same way | `…a_repository_that_answers_401_is_refused_the_same_way` | pass |
| S3 an unreachable upstream is refused | `…an_upstream_that_cannot_be_reached_is_refused` | pass |
| S4 no default branch is refused | `…a_repository_with_no_default_branch_is_refused` | pass |
| S5 readable upstreams register over HTTP and file | `…a_readable_upstream_is_registered_over_http_and_file` | pass |
| Must NOT: a request refused without the network never contacts the upstream | `…a_request_refused_without_the_network_never_contacts_the_upstream` (regression armor; M9) | pass |
| S6 a failed ingest says why and is recorded (row, ledger, WARN) | `IngestFailureTests.a_failed_ingest_says_why_and_is_recorded_and_a_later_success_clears_it` | pass |
| S6 the first error stays attached | `IngestFailureTests.the_head_fetch_error_stays_attached_to_the_fallbacks` | pass |
| S7 a success clears the failure | S6's test, second half | pass |
| S8 the sweep's failure is recorded, by `scheduler` | `IngestFailureTests.an_automated_ingest_failure_is_recorded_the_same_way` | pass |
| S9 no credential repeated | `UpstreamReachabilityTests.a_credential_in_the_url_is_never_repeated` | pass |
| S10 a declared unreachable upstream is registered and reported | `EstateReconciliationTests.a_declared_marketplace_with_an_unreachable_upstream_is_registered_and_reported` | pass |
| S11 the portal states a failed last ingest | `marketplace-detail.test.tsx › a_failed_last_ingest_is_stated_with_when_and_why` | pass |
| U1–U4 translator | `UpstreamFailureTests` (5 tests) | pass |
| Must NOT: no SVC test weakened | Eight existing tests change only the URL they register (placeholder → local fixture). Two change only the expected URL value that follows from that. Assertions are otherwise unchanged. | pass |
| Must NOT: no config leaf or test context | `ConfigSurfaceBudgetTests` and `ContextBudgetTests` green and unchanged | pass |
| Must NOT: additive contract | `openapi.json` diff adds three nullable fields to `Marketplace` and `MarketplaceView`, a `502` on `POST /marketplaces`, and a longer `502` description on ingest. `OpenApiContractTests` green | pass |

## RED (observed failing before the fix, at `5858303a` plus the new tests)

```text
UpstreamFailureTests (5)          » UnsupportedOperation not implemented   (stub)
UpstreamReachabilityTests          6 of 7 failed: Status expected:<502> but was:<201>,
                                   and S5 failed on the probe never reaching /info/refs
IngestFailureTests (3)             JSON path "$.detail" / lastIngestOutcome absent / no suppressed error
EstateReconciliationTests          S10 failed (no "upstream" in the entry detail)
marketplace-detail.test.tsx        Unable to find [data-testid="marketplace-last-ingest-failed"]
```

`a_request_refused_without_the_network_never_contacts_the_upstream` passed
before the fix, because nothing contacted the upstream then. Mutant M9 shows it
can fail.

## Mutation (`mutants.sh`, manual; no mutation tool in the build)

```text
M1-probe-removed killed
M2-not-found-branch-removed killed
M3-probe-always-refuses killed
M4-failure-not-recorded killed
M5-success-not-recorded killed
M6-first-error-dropped killed
M7-redaction-removed killed
M8-estate-refuses killed
M9-probe-before-conflict-check killed
M10-portal-alert-removed killed
all mutants killed
```

The runner counts a kill only when the named test fails, so a run with no
report cannot score as a kill. It restores each mutant with `git checkout` and
proves the restore with `git diff --exit-code`.

## Adversarial pass

- **Credentials:** userinfo in the URL (S9, U4). Redacted in the response, the
  row, the ledger and the log.
- **Forge answers:** 404, 401 with a challenge, connection refused, and an empty
  repository are each covered by a test.
- **A 500 or a redirect from the upstream:** by inspection only. A 500 surfaces
  as JGit's "cannot open git-upload-pack" and translates to "the upstream fetch
  failed" with that root cause. Redirects are followed by JGit's default
  transport exactly as the ingestion fetch already did. This change adds no
  route.
- **A concurrent registration of one name while the probe runs:** the probe
  widens the window between the conflict check and the insert. The live-name
  unique index still decides, as before. Not separately tested.
- **Known limit:** the marketplace upstream path does not apply the external
  source address policy (SSRF guard). That is unchanged from before, and
  deliberate; see design.md, Non-Goals.

## Layers skipped

- **Coverage on changed lines:** there is no coverage tool in the build.
  Mutation stands in for it.
- **Property-based tests:** none. The translator's input space is a small, fixed
  set of exception shapes, and the unit tests enumerate them.
