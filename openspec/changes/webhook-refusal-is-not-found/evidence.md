# Evidence: webhook-refusal-is-not-found

Source state: `4609148815bec54a83bbfa3f5fd91f3980b1fafc` (branch
`fix/review-backend-defects`). Every gate below is one fresh run at that commit,
after the last code edit. The commits after it add this file and archive the
change; they touch no code.

Spec approval: not obtained (autonomous run; the owner delegated decisions for
issue #544). The approved-before-code artifact is this change's `proposal.md`,
`design.md` and `tasks.md`, committed first as `2216e475`.

## Gates

| Gate | Command | Result |
| --- | --- | --- |
| Java, UI, jar | `./mvnw clean verify` | `Tests run: 1005, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · 07:57 min |
| Stories | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 17 passed (17)` · `Tests 88 passed (88)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `24 passed (1.2m)` |
| Traceability | `reqstool status local -p docs/reqstool` | `324/324 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 31 passed, 0 failed (31 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.48 seconds` |
| Contract | `oasdiff breaking base.json rev.json --fail-on ERR` (docker `tufin/oasdiff`) | `No breaking changes to report`; changelog: `info [response-non-success-status-removed] … removed the non-success response with the status 403` |

The first `pnpm test:stories` run failed to import two story files (`Failed to
fetch dynamically imported module`, 0 test failures): the known harness flake.
`/tmp` was at 7.1 GiB, so the caches (`node_modules/.cache/storybook`, `.vite`)
were deleted and the run above is the clean re-run.

Budgets untouched: `ConfigSurfaceBudgetTests` and `ContextBudgetTests` pass
unchanged; no configuration leaf, Spring test context, dependency or schema
change was added.

## Behaviour to test

| Task | Test | RED observed |
| --- | --- | --- |
| 2 Webhook refusals alike (SVC_GW_INGEST_0012) | `SyncTests.every_webhook_refusal_answers_like_an_unknown_marketplace_and_ingests_nothing`, and the changed statuses in `SyncTests.webhook_endpoint_accepts_only_a_validly_signed_request_and_ignores_the_payload` | yes: `Status expected:<404> but was:<403>` |
| 3 Responses closed | existing `ExternalVettingConnectorTests`, `WebhookTests` | none: no test observes an unclosed JDK response |
| 4 No orphan pin | `RefRefusalTests.an_ingestion_whose_snapshot_row_cannot_be_written_leaves_no_pin` | yes: `Expecting empty but was: [Ref[refs/snapshots/fa6f…]]` |
| 5 Revet continues | existing re-vet suites (the loop now matches `sweep`) | none: no cheap way to fail one snapshot of several |
| 6 Narrowed catch | `VettingRunFailureTests` (2) | database case yes (`Expecting code to raise a throwable`); storage case passed first, kept as regression armour |
| 7 One query | `IngestionTests.theMarketplaceListCarriesEachMarketplacesOwnSnapshotsInOrder` | passed first (behaviour preserved); mutants L1–L2 below prove it bites |
| 8 Bounded forge body | `ForgeMetadataBoundTests` (2) | yes: the oversized answer returned metadata |
| 9 Zero guards | `PropertyRecordGuardTests.counts_at_zero_or_below_take_their_defaults` (0, −1), `a_positive_count_is_kept` | yes |
| 10 Redacted toString | `PropertyRecordGuardTests.records_carrying_a_secret_do_not_print_it` | yes |
| 11 Summary cache | `SummaryCacheTests`, and SVC_GW_APPROVAL_0028's `SnapshotBrowsingTests.the_diff_is_read_to_its_end_a_page_at_a_time_with_exact_totals_or_narrowed_to_a_path` | LRU test new; mutants C1–C3 below |

## Manual mutants (each applied alone, suite run, file restored and diffed)

| Id | Mutant | Killed by |
| --- | --- | --- |
| M1 | A bad HMAC answers `403` again | both webhook tests (`404` expected, `403`) |
| M2 | The body bound moved back after the lookup | oracle test: `Status expected:<413> but was:<404>` |
| M3 | Ingestion queued before the signature is checked | oracle test: a snapshot/sync attempt appeared |
| C1 | Path dropped from the summary cache key | `SnapshotBrowsingTests…narrowed_to_a_path:283` |
| C2 | LRU in insertion order, not access order | `SummaryCacheTests:19` |
| C3 | LRU bound off by one | `SummaryCacheTests:19` |
| L1 | Batched snapshot read ordered `DESC` | `IngestionTests…InOrder:65` |
| L2 | Marketplaces without snapshots not pre-seeded | two `IngestionTests` (NullPointerException) |

8/8 killed.

## Known limits

- Timing: an unknown name skips the HMAC, so latency can still separate some
  refusals. Not addressed; see design.md.
- Orphan-pin cleanup restores a pin if a row appeared between check and delete;
  that branch needs a second gateway instance racing the same commit and has no
  test.
- Item 3 (closing responses) and item 5 (revet loop) have no dedicated test,
  for the reasons in the table.
