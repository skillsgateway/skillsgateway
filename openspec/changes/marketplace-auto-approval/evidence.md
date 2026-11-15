# Evidence — marketplace-auto-approval

- Tier: 3 (old-coder). SPEC: `acceptance.md`, approved by the owner on
  2026-11-15 ("yes. approved"), with revisions R1–R3.
- Final gate results go in "Final run" below, from one fresh run after the last
  code edit. The development log records each test watched failing (RED), and
  each characterization test proven non-vacuous by a throwaway mutant.

## Development log

### 2.1 — the human approval's evidence, pinned

`ApprovalEvidenceTests` describes existing behaviour, so it passed on its first
run (3/3). Proven non-vacuous with throwaway mutants of `AdminController.approve`,
each restored with `git checkout`, and `git diff --exit-code -- src/main` clean
afterwards:

| Mutant | Result |
|---|---|
| The `SNAPSHOT_APPROVED` emission skipped | 3/3 tests failed |
| `waiverService.recordUse` removed | 1/3 failed: `a_persons_approval_writes_its_waiver_decision_staleness_and_conflict_entries_and_one_webhook` |

### 2.2 and 2.3 — the refactors

Checkpoints run the suites a change can reach; the full gate run is the final
one below.

- 2.2, the move into `ApprovalRecorder`: 19 suites, 103 tests, all passing,
  `ApprovalEvidenceTests` among them, with no test file changed.
- 2.3, `UpstreamCredentials.Target` extracted as `UrlPrefix`: the credential,
  redirect, GitHub App and external-source suites, 65 tests, all passing, with
  no test file changed.

## Final run

(pending)
