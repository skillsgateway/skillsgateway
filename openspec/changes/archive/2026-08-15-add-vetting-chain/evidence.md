# Evidence: add-vetting-chain

All five gates run fresh, in order, against commit `c3389f8`
("feat: pluggable snapshot vetting chain (#10)") — the implementation commit
this change archives on top of. Working tree clean apart from this file.

Environment: macOS 15 (darwin 25.3.0), Java 25, Docker for Testcontainers and
the e2e compose stack. Port 8081 was free before the e2e run.

---

## 1. `./mvnw clean verify`

Java tests (Arconia/Testcontainers PostgreSQL), Spotless, Checkstyle, CycloneDX
SBOM, the UI gate (typecheck, oxlint, vitest, Storybook a11y), and the packaged
jar.

```
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  29.118 s
[INFO] Finished at: 2026-08-15T11:35:37+02:00
```

The seven new Java verification tests are in `VettingTests`:

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in io.github.jimisola.skillsgateway.VettingTests
```

UI gate, including the new `approving_a_blocked_snapshot_shows_the_findings_and_requires_a_reason`:

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
Running 6 tests using 1 worker

  ✓  1 [chromium] › e2e/portal.spec.ts:26:1 › admin_registers_ingests_and_approves_a_marketplace_in_the_portal (2.4s)
  ✓  2 [chromium] › e2e/portal.spec.ts:63:1 › token_cleartext_is_shown_once_and_revocation_marks_it_revoked (848ms)
  ✓  3 [chromium] › e2e/portal.spec.ts:89:1 › webhooks_page_lists_subscribers_and_delivery_attempts (1.1s)
  ✓  4 [chromium] › e2e/portal.spec.ts:126:1 › snapshot_soft_delete_and_restore_in_the_portal (930ms)
  ✓  5 [chromium] › e2e/portal.spec.ts:155:1 › audit_page_exports_the_ledger_and_lists_sinks (688ms)
  ✓  6 [chromium] › e2e/portal.spec.ts:180:1 › vetting_verdicts_are_shown_and_a_blocked_snapshot_needs_a_reason (2.2s)

  6 passed (8.8s)
```

Test 6 registers a fixture whose `SKILL.md` carries planted injection markers,
confirms the chain blocked it, reads the verdicts in the approve dialog,
asserts the confirm control is disabled without a reason, and approves only
after one is entered.

## 3. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
43/43 complete · 0 incomplete · PASS
```

GW_0037–GW_0043 are covered by SVC_GW_0037–SVC_GW_0043 (six Java tests plus one
Playwright test for the portal surface).

## 4. `openspec validate --all --strict`

```
✓ spec/snapshot-approval
✓ spec/snapshot-retention
Totals: 12 passed, 0 failed (12 items)
```

## 5. `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.40 seconds
```

Run in a scratchpad virtualenv from `docs/requirements.txt`. `--strict` turns
broken internal links and nav warnings into failures, so the new
`concepts/vetting.md` and its cross-links are verified.

---

## Adversarial coverage

The approval gate is a trust boundary, so the tests attack it rather than only
demonstrating it:

| Attack | Test | Outcome |
| --- | --- | --- |
| A connector crashes mid-chain | `aConnectorThatThrowsBlocksTheSnapshotAndLeavesItUnserved` | `ERROR` verdict recorded, run blocked, snapshot still `held`, published repository not serving, approval refused |
| Aggregation talked into clearing | `aggregationClearsOnlyWhenEveryConnectorAnsweredWithoutObjecting` | Exhaustive over every 1- and 2-verdict state combination, plus empty and null lists — none of which clear |
| Credentials smuggled into content | `theSecretScannerFindsPlantedCredentialsAndClearsCleanContent` | Planted AWS key and PEM block found with their paths; the finding does not echo the secret |
| Injected instructions in a skill | `thePromptInjectionScannerFindsPlantedMarkersInSkillInstructions` | Override phrasing, credential path and a zero-width character all found |
| Approving past a blocked chain | `aBlockedSnapshotIsApprovedOnlyWithARecordedReason` | Refused before the state transition; nothing published; the second attempt records reviewer and reason |
| Clicking past the gate in the portal | Playwright test 6 | Confirm disabled without a reason; server refuses independently with 409 |
