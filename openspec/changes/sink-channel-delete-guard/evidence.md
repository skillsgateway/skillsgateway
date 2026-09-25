# Evidence: sink-channel-delete-guard (Tier 3)

- **Spec approval:** obtained. The acceptance spec (`acceptance.md`) was put to
  the owner and answered "Approve" before any implementation. It was committed
  at `45368557`, and revised once, visibly, under `## Revisions`.
- **Source state:** `f0184156` on `fix/sink-channel-delete-guard`, stacked on
  #485. Every gate and the mutation run below were run over that commit, after
  the last edit.
- **Entry points:** the project gates (below) and
  `openspec/changes/sink-channel-delete-guard/mutants.sh`.
- **Independent verification:** not performed.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 787, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  21 passed (21)
[INFO]       Tests  181 passed (181)
[INFO] BUILD SUCCESS
[INFO] Total time:  08:10 min

$ (cd src/main/frontend && pnpm test:stories)   # after rm -rf node_modules/.cache/storybook
 Test Files  14 passed (14)
      Tests  67 passed (67)

$ (cd src/main/frontend && pnpm e2e)
  21 passed (1.1m)

$ reqstool status local -p docs/reqstool        # older local install; CI pins 0.12.0
REQUIREMENTS: 275
GW_WEBHOOK_0004  implementation 2 · tests 3 passed
GW_WEBHOOK_0011  implementation 7 · tests 7 passed
Tests: 792 passed · 0 failed · 0 skipped · 0 SVCs missing tests · 0 SVCs missing MVRs

$ npx @fission-ai/openspec@1.3.1 validate --all --strict   # the version CI pins
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
exit 0
```

- **Storybook.** The first run hit the known import flake ("Failed to fetch
  dynamically imported module"); the second passed whole.
- **openspec.** The globally installed CLI was upgraded to 1.13.2 during the
  session, not by this change. Under `--strict` it fails eight *existing* main
  specs whose `## Purpose` is still the placeholder `openspec archive` writes
  (adoption-reporting, audit-export, git-storage, license-compliance, lifecycle-webhooks, observability, persistence-schema, snapshot-retention).
  1.3.1, which CI installs, passes. This is recorded here, not fixed.

## Spec → test mapping

| Scenario | Test | Status |
|---|---|---|
| S1 the API refuses to delete a sink's channel, nothing changes | `SinkChannelGuardTests.the_webhooks_api_refuses_to_delete_a_sinks_channel_and_changes_nothing` | pass |
| S2 a lifecycle subscriber is still deleted | `…a_lifecycle_subscriber_is_still_deleted` | pass |
| S3 a declared webhook named like a sink fails only its entry | `…a_declared_webhook_named_like_a_sink_fails_its_entry_and_leaves_the_channel_alone` | pass |
| S4 deleting a sink removes both rows | `…deleting_a_sink_removes_the_sink_and_its_channel` | pass |
| S4b sink removal is atomic | `…removing_a_sink_is_all_or_nothing` (a real database trigger fails the second delete; no mocks) | pass |
| S5 storage refuses a raw delete | `…storage_refuses_a_raw_delete_of_a_sinks_channel` | pass |
| S6 the listing marks channels | `…the_listing_marks_a_sinks_channel_with_its_sink` | pass |
| S7, S8 the portal hides the channel and labels its deliveries | `webhooks.test.tsx › a_sinks_channel_is_not_listed_as_a_subscriber_and_its_deliveries_lead_to_the_sink` | pass |
| Must NOT: no SVC test weakened | the only existing test edited is `NativeEnumColumnTests.the_audit_sink_kind_round_trips`, whose `finally` cleanup now removes the sink before its channel; its assertions are unchanged | pass |
| Must NOT: the listing still returns every subscriber | S6 reads the channel from `GET /api/v1/webhooks` | pass |
| Must NOT: the contract stays additive | `openapi.json` diff: +`auditSink` (nullable), +`409` on `DELETE /api/v1/webhooks/{id}`; `OpenApiContractTests` green in `verify` | pass |
| Must NOT: the estate never deletes | unchanged code path; `EstateReconciliationTests.objects_absent_from_the_declaration_are_never_touched` green | pass |

## RED (observed failing before the fix, at `45368557`)

```text
Tests run: 7, Failures: 4
the_webhooks_api_refuses_to_delete_a_sinks_channel_and_changes_nothing  Status expected:<409> but was:<204>
a_declared_webhook_named_like_a_sink_fails_its_entry_…                  expected: "failed"
storage_refuses_a_raw_delete_of_a_sinks_channel                         Expecting code to raise a throwable.
the_listing_marks_a_sinks_channel_with_its_sink                         Expecting actual: []
webhooks.test.tsx › a_sinks_channel_is_not_listed_…                     expect(element).not.toBeInTheDocument()
```

S2, S4 and S4b passed before the fix: they are regression armor. Each was shown
to be non-vacuous by a mutant (T1, T2 and M4 below).

## Mutation (manual; the build has no mutation tool)

`openspec/changes/sink-channel-delete-guard/mutants.sh`, at `f0184156`:

```text
M1-guard-removed            killed   (the API guard gone: the database refusal surfaces as 500)
M2-estate-guard-removed     killed
M3-fk-cascade               killed
M4-no-transaction           killed   (also proves S4b non-vacuous)
M5-auditsink-null           killed
M6-portal-filter-removed    killed
T1-guard-refuses-everything killed   (proves S2 non-vacuous)
T2-channel-left-behind      killed   (proves S4 non-vacuous)
all mutants killed
```

- **How a kill is counted.** Only when the named test fails (on an assertion or
  an error). A run that produced no test report counts as INVALID, not as a
  kill. After every mutant, the restore is checked with `git diff --exit-code`,
  and a trap restores on any exit.
- **Negative control of the runner.** A comment-only mutant was reported
  `CONTROL-equivalent SURVIVED`, and the run exited 1.
- **Found by running the runner.** Its first version counted only assertion
  failures, and on its first run it exited without restoring the mutant. Both
  were fixed (`e9f8e982`) before the reported run.
- **Equivalent mutant, not counted.** Removing only the guard's first call is
  equivalent to the original: the storage refusal still reaches the fallback,
  which answers the same 409. M1 removes the whole guard.

## Real execution

The packaged jar was run against a fresh database, and the API was called
directly:

```text
POST /api/v1/audit/sinks {"name":"siem"}      → sink 1, channel 1 (auditSink "siem" in the listing)
DELETE /api/v1/webhooks/1                     → 409  "subscriber 1 is the delivery channel of audit sink 'siem' and is
                                                      removed or changed only with its sink: DELETE /api/v1/audit/sinks/1"
DELETE /api/v1/webhooks/99999                 → 404
DELETE /api/v1/audit/sinks/1                  → 204; the webhook listing is then empty
```

## Found and fixed during the gauntlet

- **Message formatting.** `.formatted(...)` bound to the second string literal
  only, so the 409 message carried raw `%d`/`%s`. S1 and S3 caught it.
- **Leftover subscribers.** A `*`-filtered subscriber left behind by a test
  crowds the dispatch batch of later test classes; `WebhookTests` guards
  against this. Two sources:
  - The new tests themselves. They now remove everything they register.
  - `NativeEnumColumnTests`. Its cleanup deleted the channel before the sink,
    which RESTRICT now refuses. It now deletes the sink first.

## Adversarial pass and known limits

- **Deleting a channel id that does not exist:** still 404 (real execution
  above).
- **Two concurrent deletes of the same sink:** both run in their own
  transaction; the second finds no rows and still answers 204. Nothing is lost
  or orphaned. Only the second status is inaccurate. **Known limit, not
  fixed.**
- **Coverage layer:** skipped. There is no coverage tool in the build; mutation
  stands in for it.
- **Mobile layout:** not checked. The portal change is a filter and a link in an
  existing table.
