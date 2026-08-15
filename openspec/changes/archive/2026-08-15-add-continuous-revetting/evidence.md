# Evidence: add-continuous-revetting

All five gates run fresh, in order, on branch `feat/continuous-revetting` (based on
`feat/vetting-waivers`, commit `f0f6122`). Working tree clean apart from this file.

Environment: macOS 15 (darwin 25.3.0), Java 25, Docker for Testcontainers and the
e2e compose stack. Port 8081 was free before the e2e run.

---

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in io.github.jimisola.skillsgateway.RevetTests
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in io.github.jimisola.skillsgateway.RevetEnforceTests
[INFO] Tests run: 56, Failures: 0, Errors: 0, Skipped: 0
[INFO] You have 0 Checkstyle violations.
[INFO] Spotless.Java is keeping 95 files clean
 Test Files  6 passed (6)
      Tests  11 passed (11)
[INFO] BUILD SUCCESS
```

## 2. `(cd src/main/frontend && pnpm e2e)`

```
Running 8 tests using 1 worker

  ✓  1 admin_registers_ingests_and_approves_a_marketplace_in_the_portal (1.8s)
  ✓  2 token_cleartext_is_shown_once_and_revocation_marks_it_revoked (551ms)
  ✓  3 webhooks_page_lists_subscribers_and_delivery_attempts (917ms)
  ✓  4 snapshot_soft_delete_and_restore_in_the_portal (887ms)
  ✓  5 audit_page_exports_the_ledger_and_lists_sinks (550ms)
  ✓  6 vetting_verdicts_are_shown_and_a_blocked_snapshot_cannot_be_approved (694ms)
  ✓  7 a_finding_is_waived_from_the_review_surface_and_the_waiver_is_listed (6.4s)
  ✓  8 a_revoked_snapshot_shows_its_violation_and_who_had_already_fetched_it (5.9s)

  8 passed (18.4s)
```

The e2e gateway runs with `SKILLSGATEWAY_VETTING_REVET_MODE=enforce` and the sweep
disabled: the acceptance suite has to see the retraction a deployment that opts in
would see, and no background pass may revoke a fixture out from under a test.

## 3. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
55/55 complete · 0 incomplete · PASS
```

## 4. `openspec validate --all --strict`

```
✓ change/add-continuous-revetting
✓ spec/snapshot-vetting
✓ spec/vetting-waivers
Totals: 14 passed, 0 failed (14 items)
```

## 5. `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.40 seconds
```

---

## Adversarial coverage

The feature's dangerous half is that the gateway takes content away on its own, so
the tests attack it from both sides: that it does retract when it must, and that it
refuses to retract when it must not.

| Attack | Test | Outcome |
| --- | --- | --- |
| Warn mode quietly unpublishes something | `warnModeRecordsTheViolationAndNeverUnpublishes` | A real `git clone` still succeeds at the same SHA after the violation; state stays `approved`; no `snapshot-revoked` entry |
| Enforced revocation leaves content reachable by clone | `enforcedRevettingRevokesTheSnapshotAndStopsTheFacadeServingIt` | `git clone` of the marketplace fails; `publishedIfServing` empty |
| Revoked commit still fetchable **by SHA** (`refs/snapshots/<sha>` is advertised in its own right) | same test | `git ls-remote refs/snapshots/<sha>` returns nothing |
| Revocation destroys the evidence | same test | The quarantine pin `refs/snapshots/<sha>` is still present |
| Revoked snapshot re-published without a new decision | same test | `approve` throws `VettingBlockedException`; state stays `revoked`; facade still serves nothing |
| Re-approval bypasses the gate | same test | Succeeds only after a scoped waiver covers the finding; records a new reviewer; clears the revocation marks; clone works again |
| A broken connector revokes a fleet | `aConnectorErrorDuringRevettingNeverRevokesTheSnapshot` | Quarantine made unreadable under **enforce**: classification `INCONCLUSIVE`, snapshot stays approved and cloneable, run still recorded blocked |
| The inconclusive rule is wrong for some verdict state | same test | The pure rule asserted over **every** `VerdictState`, plus an empty run and a missing run |
| Fail-closed weakened by the inconclusive rule | same test | The recorded run's outcome is still `BLOCKED`, so nothing can be published off it |
| An expired/withdrawn waiver silently stops mattering | `RevetTests` fixtures (approve under a waiver, withdraw it, re-vet) | The next re-vet reports a `VIOLATION` — issue #28's deferred path |
| A waiver clears the gate but the sweep revokes anyway | `aWaiverRecordedAfterAViolationClearsTheNextRevet` | Effective outcome `CLEAR_WITH_WAIVERS`; recorded run unchanged at `BLOCKED` |
| The sweep re-vets the whole estate, or the wrong snapshots | `theSweepRevetsTheLeastRecentlyVettedApprovedSnapshotsInBatches` | Only `approved` snapshots; `held` and `rejected` never queued; oldest-evidence-first ordering; batch bound honoured |
| Blast radius names people who never got the content | `aViolationNamesTheIdentitiesThatFetchedTheSnapshot` | The two identities that cloned are named; the one that only ran `ls-remote` is not |
| A retraction that cannot be reconstructed afterwards | `theLedgerRecordsTheRetroactiveViolationAndEveryTransition` | Run trigger + chain, violation with connectors/rules/mode, per-identity entries, revocation, unpublication, and the fresh approval |
| Retention silently inherits `revoked` through a `<> 'approved'` guard | `retentionTreatsARevokedSnapshotLikeARejectedOne` | `held-too-long` never selects it; an administrator can delete it; the states are named explicitly in SQL |
| Portal shows a revocation with no explanation | `revoked_snapshot_shows_the_violation_and_who_already_fetched_it`, `a_revoked_snapshot_shows_its_violation_and_who_had_already_fetched_it` | Badge, reason, revoking identity and the already-fetched-by list, in a real browser |

The invariants this change had to preserve, and did: `ApprovalService` remains the
**only** publisher — every path added here either leaves publication alone or removes
it; the quarantine repository is still never served and is never touched by a
revocation; every action lands in the append-only ledger; and no existing SVC test was
weakened. `WaiverTests` changed only where the `Run` record gained its `chain`
component, with its assertions intact.
