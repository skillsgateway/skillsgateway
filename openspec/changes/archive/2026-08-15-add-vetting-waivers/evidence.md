# Evidence: add-vetting-waivers

All five gates run fresh, in order, on branch `feat/vetting-waivers` (based on
`feat/vetting-chain`, commit `819506b`). Working tree clean apart from this file.

Environment: macOS 15 (darwin 25.3.0), Java 25, Docker for Testcontainers and the
e2e compose stack. Port 8081 was free before the e2e run.

---

## 1. `./mvnw clean verify`

Java tests (Arconia/Testcontainers PostgreSQL), Spotless, Checkstyle, CycloneDX
SBOM, the UI gate (typecheck, oxlint, vitest, Storybook a11y), and the packaged
jar.

```
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
```

The five new Java verification tests are in `WaiverTests`:

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in io.github.jimisola.skillsgateway.WaiverTests
```

`VettingTests` still runs its full seven, with SVC_GW_0041 and SVC_GW_0043
adapted to the waiver mechanism rather than weakened:

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in io.github.jimisola.skillsgateway.VettingTests
```

UI gate, including the adapted
`approving_a_blocked_snapshot_shows_the_findings_and_offers_a_waiver`:

```
 Test Files  6 passed (6)
      Tests  10 passed (10)
```

Known pre-existing flake `RetentionTests.theLedgerRecordsEveryRetentionAction`
did not occur in this run (`RetentionTests`: 5/5).

## 2. `(cd src/main/frontend && pnpm e2e)`

Real browser against the packaged jar, PostgreSQL and the mock OIDC IdP
(`compose.e2e.yaml`), driven through the actual login redirect.

```
Running 7 tests using 1 worker

  ✓  1 [chromium] › e2e/portal.spec.ts:26:1 › admin_registers_ingests_and_approves_a_marketplace_in_the_portal (1.8s)
  ✓  2 [chromium] › e2e/portal.spec.ts:63:1 › token_cleartext_is_shown_once_and_revocation_marks_it_revoked (468ms)
  ✓  3 [chromium] › e2e/portal.spec.ts:89:1 › webhooks_page_lists_subscribers_and_delivery_attempts (1.1s)
  ✓  4 [chromium] › e2e/portal.spec.ts:126:1 › snapshot_soft_delete_and_restore_in_the_portal (728ms)
  ✓  5 [chromium] › e2e/portal.spec.ts:155:1 › audit_page_exports_the_ledger_and_lists_sinks (623ms)
  ✓  6 [chromium] › e2e/portal.spec.ts:203:1 › vetting_verdicts_are_shown_and_a_blocked_snapshot_cannot_be_approved (1.7s)
  ✓  7 [chromium] › e2e/portal.spec.ts:225:1 › a_finding_is_waived_from_the_review_surface_and_the_waiver_is_listed (6.6s)

  7 passed (13.4s)
```

Test 6 (SVC_GW_0042, adapted) confirms the reason field is gone and the confirm
control cannot be enabled while the outcome is blocked. Test 7 (SVC_GW_0047, new)
waives every blocking finding from the review surface with a justification and an
expiry, watches each become *waived by alice until …*, confirms the outcome badge
reads **vetting clear with waivers** rather than **vetting clear**, and only then
approves.

## 3. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
48/48 complete · 0 incomplete · PASS
```

GW_0044–GW_0048 are covered by SVC_GW_0044–SVC_GW_0048 (four Java tests plus one
Playwright test for the portal surface). GW_0041, GW_0042 and GW_0043 were
revised in place to revision 0.2.0 and remain covered.

## 4. `openspec validate --all --strict`

```
✓ change/add-vetting-waivers
✓ spec/snapshot-vetting
Totals: 13 passed, 0 failed (13 items)
```

## 5. `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.38 seconds
```

Run in a scratchpad virtualenv from `docs/requirements.txt`. `--strict` turns
broken internal links and nav warnings into failures, so the new
`guides/waiving-findings.md` and its cross-links from `concepts/vetting.md`,
`guides/approving-snapshots.md` and `reference/api/marketplaces.md` are verified.

---

## Adversarial coverage

A waiver is the one sanctioned way past a trust boundary, so the tests attack it
rather than only demonstrating it.

| Attack | Test | Outcome |
| --- | --- | --- |
| A waiver with no expiry at all | `aWaiverWithoutJustificationApproverOrAFutureExpiryIsRefused` | Refused; nothing written |
| A waiver whose expiry is already past | same | Refused; nothing written |
| A waiver with a blank justification or no approver | same | Refused; nothing written |
| A path scope naming nothing, or containing `..` | same | Refused; nothing written |
| A waiver for a **different rule** on the same content | `aWaiverSuppressesOnlyTheFindingItsScopeNames` | Still blocked |
| A waiver for the right rule pinned to a **different commit SHA** | same | Still blocked |
| A waiver for the right rule under an **unrelated path** | same | Still blocked |
| The path-prefix trap: `plugins/hell` against `plugins/hello/…` | same | Still blocked — matching is on a segment boundary |
| A waiver that has **expired** | `anExpiredOrRevokedWaiverStopsSuppressingItsFinding` | Effective outcome reverts to blocked, approval refused, snapshot still `held`, nothing published — with no scheduled pass having run |
| A waiver that has been **revoked** | same | Same reversion, immediately |
| Approving with only **some** blocking findings waived | `aBlockedSnapshotIsApprovedOnlyOnceWaiversCoverEveryBlockingFinding` | Refused before the transition, naming the finding still uncovered; nothing published |
| Waiving a verdict that has **no findings** (`PENDING`) | `evaluationNeverClearsAVerdictThatHasNothingToWaive` | Stays blocking — exhaustive over every verdict state, with and without a covering waiver |
| Waiving an **empty** or absent run | same | Stays blocked; no input clears without positive evidence |
| Clicking past the gate in the portal | Playwright test 6 | No reason field exists; confirm disabled while blocked; server refuses independently with 409 |

Two properties are asserted throughout rather than in one place: the **recorded
run is never rewritten** by a waiver (`recordedOutcome` stays `BLOCKED` while
`outcome` becomes `CLEAR_WITH_WAIVERS`), and a cleared-by-waiver outcome is
**never reported as `CLEAR`**.
