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

### 5.4 — executable surface (`SurfaceComparison`)

`SurfaceComparisonTests` (19) were written against a skeleton returning
"unchanged". 14 failed (RED); the 5 that passed assert unchanged and are
proved by the always-same mutant below.

The first implementation failed 2/19: the manifest, which the strict reader
reads, counted as a covered component file, so no first snapshot could be
inert. That is a contradiction between D7 and S8.2, resolved fail-closed as R6
(a descriptive-key list for the manifest and `plugin.json`). Two tests were
added for R6, then all 21 passed.

Mutants, first by an exploratory script and then carried into the change's
`mutants.sh` (M35–M50; see 5.11), each applied, checked as applied, then
restored:

| Mutant | First run (21 tests) | After the tests below (28) |
|---|---|---|
| Always changed | 21 failed | 28 failed |
| Always unchanged | 15 failed | 22 failed |
| Mode check dropped | 1 failed | 1 failed |
| Mode-change check dropped | **survived** | 1 failed |
| Dependency manifest or lockfile check dropped | 1 failed | |
| Launch target check dropped | 1 failed | |
| Shebang check dropped | 1 failed | |
| Descriptive keys always accepted | 2 failed | |
| Descriptive keys always refused | 3 failed | |
| Covered data files treated as inert | 3 failed | |
| Every extension inert | 4 failed | |
| Hook problems ignored | **survived** | 1 failed |
| Unscanned hook modules ignored | **survived** | 1 failed |
| Oversize reads ignored | **survived** | **survived, equivalent** |
| Malformed frontmatter ignored | 1 errored | |
| Unparseable manifest ignored | **survived** (held, for the wrong reason) | 1 errored |
| `MAX_PLUGINS` unchecked | 1 failed | |
| `MAX_PLUGIN_ROOTS` unchecked | **survived** | 1 failed |
| Declarations never compared | 3 failed | |
| Removed declarations not compared | **survived** | 1 failed |

Seven tests were added, one for each survivor, each asserting the reason and
not only "changed". The oversize mutant is equivalent today: every read in
`PluginComponents` and `HookModules` already reports an oversize file as a
problem, and `tools` reports its own. The check stays, as the guard for a
reader added later that leaves an oversize file out silently.

### 5.1–5.3 — candidacy and the vetting predicate (`AutoApprovalEligibility`)

Both halves are pure functions over data. `AutoApprovalEligibilityTests` (28)
were written against a skeleton that threw, and all 28 failed (RED). After the
implementation they pass, with `RequiredVettersTests` (5, now also asserting
`everyVetter()`).

`required-vetters` unset means no verdict may be `DISABLED`, including a
verdict from a vetter that is not in the configured list. `everyVetter()`
carries that, because `names()` resolves "unset" to the known vetters. A
waived high or critical finding needs the served baseline's run to have the
same rule in the same blob. A finding with no blob, or no baseline at all,
never satisfies that.

Mutants, first by an exploratory script and then carried into `mutants.sh`
(M19–M34; see 5.11), each checked as applied and then restored. All 21 were
killed:

| Mutant | Failed |
|---|---|
| Deleted snapshot accepted | 1/33 |
| A snapshot that is not held accepted | 3/33 |
| An older snapshot accepted | 1/33 |
| A closure member accepted | 1/33 |
| Chain state ignored / only `SUPERSEDED` refused | 2/33 / 1/33 |
| Verdicts that did not run accepted / `PENDING` alone accepted | 3/33 / 1/33 |
| Unset `required-vetters` ignored | 1/33 |
| A named required vetter's `DISABLED` ignored | 1/33 |
| A required vetter absent from the chain accepted | 1/33 |
| A blocked effective outcome accepted | 1/33 |
| The waiver rule raised to critical only | 1/33 |
| Baseline always matches / ignores the blob / accepts a null blob | 1/33 each |
| Unread-surface findings accepted | 3/33 |
| Disabled vetters not named | 1/33 |
| Worst severity never set | 2/33 |
| Waiver ids not named | 1/33 |
| `everyVetter()` always false | 1/33 |

### 5.5–5.10 — the attempt, its triggers and the end-to-end class

`AutoApprovalService.attempt` runs the mode, the re-vetting check, the basis,
the candidacy, the vetting predicate and the surface, in D8's order, and then
calls `ApprovalService.approve` as `auto-approver`. The triggers are events
(acceptance R8). Shadow runs the six checks and not the approval gates
(acceptance R7).

The order of writing, stated plainly: one test,
`whenAPersonDecidesFirstTheAttemptRecordsASkipAndPublishesNothing` (S9.5), was
written first and failed (RED). The first implementation stored a race as a
skip through the held-only compare-and-set, which a decided row refuses, so
nothing was ledgered. A race is now ledgered without deduplication, since it
happens once per snapshot. The other seven `AutoApprovalServiceTests` (Mockito,
no context) and both `AutoApprovalTests` (one new Spring context, acceptance
R5) were written after the code they test. Their non-vacuity rests on mutants
instead of RED: 11 mutants of the orchestration and 6 of the end-to-end path,
all killed. They are now M51–M61 in `mutants.sh`.

Two existing tests were extended, and neither was weakened.
`RoleEnforcementTests` classifies the new
`GET /api/v1/snapshots/{id}/auto-approval` as an open read, beside
release-age. `OpenApiContractTests` now runs against the regenerated
`openapi.json` and `types.gen.ts`, and the diff only adds to them.

Regression after the wiring: 23 classes and 216 tests (every suite whose
constructor or route changed, plus the budgets and the API contract) passed.
After the race fix, a rerun of 4 classes and 19 tests also passed.

### 5.11 — `mutants.sh`

`mutants.sh` holds 62 mutants, one per condition: the URL-prefix matcher and
its startup refusals (M1–M4), `required-vetters` (M5–M7), the posture and
override expiry checks and four-eyes on the grant (M8–M18), the candidacy and
vetting predicate (M19–M34), the surface (M35–M50), the attempt (M51–M58) and
the end-to-end path (M59–M62). Each names the test method that must fail. A
kill counts only from that method's surefire report. `DRY=1` proves that every
mutant applies exactly once.

```text
$ DRY=1 openspec/changes/marketplace-auto-approval/mutants.sh | tail -1
61 mutants apply
$ openspec/changes/marketplace-auto-approval/mutants.sh | tail -4
M59-ingestion-does-not-trigger killed
M60-auto-approver-human killed
M61-closure-ignored-end-to-end killed
61 mutants run, all killed
```

M62 was added afterwards with its test
(`anAutomaticApprovalWritesTheDecisionEntryAndOneWebhookAsAPersonsDoes`, task
5.6, written after the code). It was run alone (`ONLY=M62`) and killed. The
first run of M1–M61 was at commit `2886ea79`, in about 20 minutes. There were no survivors. The
equivalent surface mutant from 5.4 (oversize reads ignored) is not in the
script, because no test can kill it.

### 6.1–6.2 — re-vetting recall and bulk withdrawal

Written first, three tests in `AutoApprovalTests`, all failing (RED) for the
right reason. Under `warn`, the automatic approval was classified a violation
and not revoked (`recalled.revoked()` false). Both withdrawal tests got 404,
because the route did not exist yet. After the implementation all six tests in
the class pass. The violation entry of an automatic approval says
`mode=ENFORCE`, the mode re-vetting acted in, and a person's says `mode=WARN`.

`AutoApprovalWithdrawalTests` (Mockito, one test) covers the race that an
end-to-end test cannot arrange: a snapshot someone else withdrew mid-request is
skipped. It was written after the code, and M73 proves it.

`RoleEnforcementTests` classifies `POST /api/v1/approval/auto/withdraw` as a
role-gated mutation. `openapi.json` and `types.gen.ts` were regenerated, with
additions only.

Regression: 22 classes and 140 tests (every re-vetting, revocation, posture,
role, contract and budget suite) passed.

Mutants M63–M73, run with `ONLY='M6[3-9]|M7[0-3]'`. On the first run M71 (the
up-front "nothing to return to" check dropped) **survived**. With one
marketplace, `RevocationService` refuses the same request by itself before
withdrawing. The check only matters when an earlier withdrawal in the order
belongs to a marketplace that could be honoured. The refusal test now arranges
that case and asserts that the honourable withdrawal is not made either. M71
was then killed, and every other mutant was killed on the first run.

### 7.1 — the declared posture

Two tests in `AutoApprovalTests` (the one context with an internal prefix)
were written after the configuration record and before the reconciler. The
main one failed (RED, posture `MANUAL` where `AUTO` was declared). The lapse
test passed vacuously, since nothing was applied yet, so M76 is its proof.
After the implementation both pass. `DeclaredApprovalBindingTests` (binder
only, no context) proves the documented property names bind, including an
attempted `override`. `DeclaredMarketplace` keeps its five-argument
constructor, so no existing estate test changed.

The override slot exists only to be refused. Spring drops an unknown key
silently, so without the slot a declared override would vanish instead of
failing its entry. `ConfigSurfaceBudgetTests` is unchanged, at 112, because
the keys sit inside the marketplace list's element (R4).

Mutants M74–M78, run with `ONLY='M7[4-8]'`, were all killed. The test gained
its "a changed reason is re-applied" step before M77 was written, because
nothing asserted it yet.

### 8.1–8.4 — the portal card

`types.gen.ts` was regenerated with the API in group 6, so 8.1 needed no new
work. `client.test.ts` gained a test that a refusal carries the server's
`nextStep`. It failed (RED) before `ApiError` had the field, and passes after.

The ten `ApprovalPosture` stories cover S15.1–S15.4 and pass under
`vitest --project storybook`, with axe on. Three hand-applied mutants of the
card were each killed by a story:

- a blank reason accepted, killed by `SavedOnlyWhenValid`;
- the next step not rendered, killed by `RefusedSave`;
- the 90-day maximum not checked, killed by `SavedOnlyWhenValid`.

The unit suite (`pnpm test`) gives 38 files and 284 tests, all passing.

`admin_sets_an_automatic_posture_with_an_override_and_revokes_it` (S15.5)
passes under `pnpm e2e` against the packaged jar. A mutant whose revoke left
the cached posture in place failed it at the assertion that "Administrator
override" is gone (`toHaveCount`). The source was then restored and the jar
rebuilt.

### 8.5 — `/impeccable audit` and `/impeccable harden`

The Settings page changed only by gaining the card, so both passes ran on
`approval-posture.tsx`. `impeccable detect --json` found nothing (`[]`). The
stories were captured at 1280 and 390 pixels, in light and dark.

| Dimension | Score | Note |
| --- | --- | --- |
| Accessibility | 4 | axe clean in all ten stories; every input labelled; hints bound with `aria-describedby`; refusals are `role="alert"` |
| Performance | 4 | one query and two mutations, no effects |
| Responsive | 4 | after the fix below |
| Theming | 4 | token classes only; dark checked |
| Implementation integrity | 4 | shadcn primitives, `SegmentedGroup`, `Timestamp`; copies the forms rules |

These findings were fixed. The two behavioural fixes were tested first, and
both tests failed (RED) before the fix:

- A posture that failed to load had no way to retry. It now has a "Try again"
  button (`a_posture_that_failed_to_load_can_be_retried`).
- A refused revocation dropped the server's next step. Both refusals now
  share one `Refusal` element
  (`a_refused_revocation_shows_the_servers_next_step`).
- `break-all` on "Set by" split the time ("5:28 P M") at 390 pixels. It is
  now `break-words`.
- The forms rule asks for a per-form component test, so
  `an_automatic_posture_is_saved_only_with_a_reason_that_is_not_blank_and_a_date`
  was added. It passed on arrival, and a mutant that accepts a blank reason
  fails it.

Two findings were dismissed, with reasons:

- **The earliest date is tomorrow,** while the server accepts any instant
  after now. A date input cannot say "later today", and the code says so in a
  comment. It is the D15 date-picker allowance.
- **A lapsed override cannot be shown as lapsed.** The API returns only the
  active override, so the card instead explains why the basis is none.

## Final run

(pending)
