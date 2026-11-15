# Evidence — marketplace-auto-approval

- Tier: 3 (old-coder). SPEC: `acceptance.md`, approved by the owner on
  2026-11-15 ("yes. approved"), with revisions R1–R3, and R4, a correction of
  fact made during implementation.
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

### 3.2 and 3.3 — configuration, startup refusals, internal sources

`InternalSourcesTests` (36) and `RequiredVettersTests` (5) were written
against stubs that validated nothing and classed nothing as internal: 27 of
the 41 failed (RED). The 14 that passed were the "not internal" negatives,
which an always-false stub satisfies trivially. The mutants below are what
prove those negatives. After the implementation, all 41 pass, along with
`UpstreamCredentialsTests` (13), `PropertyRecordGuardTests` (4),
`ConfigSurfaceBudgetTests` at 112, and `ApprovalEvidenceTests` (3) on the
edited schema. `ContextBudgetTests` is unchanged.

| Mutant (restored after each run) | Result |
|---|---|
| A prefix matches on host alone | 6/36 failed |
| A prefix matches without a segment boundary (`acme` covers `acme-evil`) | 4/36 failed |
| The multi-tenant host refusal removed | 2/36 failed (gitlab.com, bitbucket.org; github.com is also the `github-base-url` host) |
| The `github-base-url` host refusal removed | 1/36 failed |
| A hosted marketplace is not internal | 2/36 failed |
| An empty `required-vetters` list means "every vetter" | 1/5 failed |
| An unknown `required-vetters` name is accepted | 2/5 failed |

The refusals are proven at the constructors that Spring calls at startup. A
refused boot under real configuration belongs to the real-execution run.

`ConfigSurfaceBudgetTests` rose by three, not six. See revision R4 in
`acceptance.md`.

### 4.1–4.4 — posture, override, four-eyes on the grant

`ApprovalPostureTests` (17, four-eyes `warn`) and `ApprovalPostureEnforceTests`
(4, the property sources of `FourEyesEnforceTests`, so it shares that context)
were written against a skeleton with no controller and throwing methods. 19
of the 21 failed (RED). Two passed:

- the unknown-marketplace 404, vacuous until the endpoint existed;
- S4.4, "approvals' four-eyes behaviour is unchanged", a characterization test,
  which mutant 2 below proves.

After the implementation all 21 pass. Mutants, each restored after its run:

| Mutant | Result |
|---|---|
| The reconciler's exemption from the grant check removed | 1/4 enforce failed |
| The reconciler added to `NON_HUMAN_ACTORS` (leaks into approvals) | 1/4 enforce failed (S4.4) |
| `awaiting-second-administrator` never computed | 2/4 enforce failed |
| The override's cap on the posture's expiry removed | 1/17 failed |
| An attempt does not record a lapsed override | 1/17 failed |
| A lapsed but unrecorded override still counts as active | **survived**; the test read only after an attempt had recorded the lapse. A read-before-attempt check was added to the test, and the mutant then failed 1/17 |
| A lapsed posture still takes effect | 1/17 failed |
| The 90-day limit unchecked | 2/17 errored, on the database `CHECK` (500, not 400) |
| `manual` keeps the override | 1/17 failed |
| The registration check skipped | 1/17 failed (the marketplace was created) |
| The grant's four-eyes conflict not recorded | 2/17 and 1/4 failed |

`RoleEnforcementTests` failed on the three new routes, as its walk is built
to. They are classified there, admin-only, with the GET excluded from the
auditor reads. `src/main/frontend/openapi.json` and `types.gen.ts` were
regenerated, additions only. The suites the change reaches, 17 classes and 134
tests, pass. `ContextBudgetTests` is unchanged.

## Final run

(pending)
