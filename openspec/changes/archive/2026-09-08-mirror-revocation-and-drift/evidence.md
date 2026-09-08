# Evidence: mirror-revocation-and-drift

Trust boundary: this is the revocation path's reach into an outbound
integration, and it adds an **automatic, unattended deletion** on the far side of
it — so the old-coder discipline applies at Tier 3. This report records what was
executed, on what, and what was deliberately not.

**Commit under test:** `8f21434df5dcfeca903e3a894507df541009b804` (`fix(mirror): report a refusal as a failed attempt rather than a new outcome value`), branched from
`331cf68` — the tip of `main` at the time of the run.

**Spec approval: not obtained (autonomous run).** `proposal.md` and `design.md`
stand as the spec and were written and committed before the implementation; the
correlation-breaking human review of the spec never happened, so this report
claims correspondingly lower confidence and the spec is the artifact to review
after the fact. One substantive spec revision *was* made mid-task and is visible
in the history: the credibility guard (design decision 3) was added to GW_0190
after a reviewer pointed out that a scheduled deletion turns a degraded read of
published storage into a silent total wipe.

All six gates below were run **fresh, in order, after the last code edit**.

## Failure model (Tier 3), and where each mode is caught

| Mode | Caught by |
| --- | --- |
| A revocation's push exhausts its retries and the mirror keeps the withdrawn snapshot | `ForgeMirrorSweepTests` phase C — the forge is broken *before* the revocation, restored, and only the sweep is allowed to run |
| Published storage answers successfully but short, and the reconciliation deletes everything | `requireCredible`; `ForgeMirrorSweepTests` phase B, and mutant 1 |
| A repair erases the fact that the mirror had diverged | `mirror-drift-repaired` as its own ledger event; phase C assertion, and mutant 3 |
| A reconciliation on a timer buries the audit ledger | the no-change reconciliation records nothing; phase B assertion, and mutant 4 |
| A refusal is filed as success, so silence reads as agreement | it reports as `failed` with an `error` beginning `refused: `, so every existing client already fails closed on it (design decision 3a); phase B assertion, and mutant 5 |
| The sweep is wired but never actually scheduled | `the_reconciliation_is_registered_on_the_applications_schedule`, asserted against the container's `ScheduledTaskHolder` rather than the annotation source |
| The sweep throws and takes the schedule with it | caught inside the scheduled method; the `@Scheduled` contract is a framework convention we do not own |
| The sweep contacts a forge on a gateway that has no mirror | `ForgeMirrorDisabledTests` |
| The new endpoint becomes an enforcement path | it reads published storage and writes the forge only; `RoleEnforcementTests` and `MachineApiRegistry` keep it administrator-only and scope-unreachable |
| Metric cardinality explosion, or the credential reaching telemetry | phase C asserts every `skills_gateway.mirror.*` meter carries no tags at all and no meter name contains the marketplace or the SHA |
| Two replicas sweeping at once | cannot corrupt: both compute the same served set from the same published storage and force-update to the same values. Not tested, stated in `design.md` decision 6 |

## Spec ↔ test mapping

| Requirement | What is proved | Test(s) |
| --- | --- | --- |
| GW_0190 — Bounded mirror staleness | a revoked reference the revocation's own push could not remove is gone after only the sweep runs; the reconciliation is genuinely on the application's schedule; a gateway with no mirror sweeps nothing; **and** a readable-but-short published repository makes the reconciliation refuse rather than empty the mirror, recoverably | `ForgeMirrorSweepTests` (2 cases, SVC_GW_0190), `ForgeMirrorDisabledTests` (SVC_GW_0190) |
| GW_0191 — Mirror divergence is observable without being asked | `stale_refs` is above zero while the mirror holds the withdrawn snapshot and zero after the repair; `reachable`, `seconds_since_success` and both outcome counters move; no mirror meter carries any tag, and no meter name carries the marketplace or the commit | `ForgeMirrorSweepTests` (SVC_GW_0191) |
| GW_0192 — An administrator can reconcile the mirror on demand | drift written straight to the forge is gone after the request, the answer is the fresh comparison and carries no credential, a non-administrator is refused, and a gateway with no mirror answers rather than failing | `ForgeMirrorSweepTests` (SVC_GW_0192), `ForgeMirrorDisabledTests` (SVC_GW_0192), `RoleEnforcementTests` (role-gated mutation walk), `MachineApiRegistryTests` |
| GW_0193 — A mirror repair is never silent | the publication-triggered entry names the references pushed; the sweep-triggered one is `mirror-drift-repaired` and names what it deleted; a reconciliation that changed nothing writes no row at all | `ForgeMirrorSweepTests` (SVC_GW_0193) |

## What the adversarial suite actually does

`ForgeMirrorSweepTests` pushes to a **bare repository on disk over `file://`**,
through the real JGit transports. Nothing is mocked and **no test reaches the
network**. Two things in it are worth naming, because they are the difference
between this suite and a happy path:

- **The forge is broken by moving the repository aside on disk.** The URL still
  parses, the credential is still configured, and JGit fails exactly where it
  would against a host that stopped answering. Breaking it *before* the
  revocation is the point: the revocation's own push then exhausts its retries
  for real, and the mirror is genuinely left holding a snapshot the gateway has
  withdrawn — the state this whole change exists to end. Only then is the sweep
  allowed to run, with no approval, no revocation and no restart in between.
- **Published storage is degraded rather than made unreachable.** The served tip
  is deleted from the mirrored marketplace's published repository while the
  database still holds its approved snapshot. That is the case a reconciliation
  cannot tell from a marketplace that legitimately stopped serving, and its
  honest conclusion would be to empty the mirror. The assertion is that the
  mirror comes through with **every reference it had**, that the ledger carries
  `mirror-reconciliation-refused`, and that the report says `failed` with an
  `error` beginning `refused: ` rather than `ok`. The tip is then restored and the next reconciliation shown to succeed, so
  the guard is proved to be a refusal and not a permanent jam.

The credential is configured in the passing suite (`sweep-bot` / `sweep-secret`)
precisely so that "the response and the ledger do not contain it" means
something.

## Mutation testing

No mutation tool is configured in this project, so the manual procedure was used:
five plausible bugs, introduced **one at a time** against a restored tree, each
run individually, each restored before the next. Every mutant was executed — the
maven exit code and the failing assertion are recorded below, so no kill is
claimed that was not observed.

```sh
SRC=src/main/java/dev/skillsgateway/server/mirror
cp $SRC/ForgeMirrorService.java /tmp/FMS.orig.java
cp $SRC/MirrorReconciliationSweep.java /tmp/MRS.orig.java
# ...apply one mutation, then:
./mvnw -o test -Dtest='ForgeMirrorSweepTests' -DfailIfNoTests=false \
    -Dsurefire.failIfNoSpecifiedTests=false
# ...restore both files from /tmp before the next.
```

| # | Mutation | Result |
| --- | --- | --- |
| 1 | `requireCredible(served);` removed from `reconcile()` | **killed** — `ForgeMirrorSweepTests:198 [a short read of published storage must never empty the mirror]` |
| 2 | `MirrorReconciliationSweep.sweep()` returns before queueing | **killed** — `ForgeMirrorSweepTests:199` (the withdrawn reference survives) |
| 3 | the repair recorded under `EVENT_UPDATED` instead of `trigger.event()` | **killed** — `ForgeMirrorSweepTests:247` |
| 4 | the `if (!done.changed()) return;` early return removed | **killed** — `ForgeMirrorSweepTests:215 [a reconciliation that changed nothing must not write a ledger row]` |
| 5 | a refusal recorded as `MirrorReport.OK` with no error | **killed** — `ForgeMirrorSweepTests:204` |
| 6 | the refusal's `refused: ` error prefix dropped, leaving it indistinguishable from a failed push | **killed** — `ForgeMirrorSweepTests:205` |

6/6 killed. Both source files were verified byte-identical to their originals
(`diff`) after the last restore, before the gate run below.

Mutants 5 and 6 were run **after** the refusal was folded into the `failed`
outcome (design decision 3a), so they cover the shape that actually ships:
mutant 5 that a refusal cannot be filed as a success, and mutant 6 that the
`refused: ` prefix is what carries the distinction from a push failure. Mutants
1–4 were run against the same logic they still guard, which the fold did not
touch.

Mutant 1 is the one that matters: it is the direct proof that the guard is
load-bearing rather than decorative, and that without it the suite would have
observed the mirror being emptied by a bad read.

## Honest deviations

- **The tests were written after the code they cover, not proved red first.**
  The mutation table above is what stands in for RED, and it is weaker in one
  specific way: it proves each assertion can fail, not that it failed before the
  implementation existed.
- **The credibility guard was added mid-task, after review**, not designed in
  from the start. `design.md` decision 3 and the added clause in GW_0190 record
  it; the change is visible in the commit history rather than smoothed over.
- **Two existing suites gained one property each**
  (`skills-gateway.mirror.sweep-enabled=false` in `ForgeMirrorTests` and
  `ForgeMirrorFailureTests`), with the reason written where they set it: both
  seed drift and then assert on it, and the recurring reconciliation exists
  precisely to repair that, so leaving it on would put a second actor inside
  their assertions. **No assertion in either suite was changed, broadened or
  removed**, and both still pass unmodified.
- **`RoleEnforcementTests` was modified, and only to make it stricter**:
  `POST /api/mirror/reconcile` was added to the role-gated mutation walk, so a
  no-role session and an auditor are both now asserted to be refused it.
- **`ForgeMirrorSweepTests` drives `sweep()` by hand** with the interval set to a
  day, so each phase observes exactly the reconciliation it caused. That the
  method is on a real schedule is asserted separately against the container's own
  task registry rather than being assumed.

## Gate results

All commands run from the worktree root at `8f21434df5dcfeca903e3a894507df541009b804`.

### 1. `./mvnw clean verify`

```
[INFO] Tests run: 589, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 316 files clean - 0 needs changes to be clean, 316 were already clean, 0 were skipped because caching determined they were already clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  02:27 min
[INFO] Finished at: 2026-09-08T02:48:15+02:00
```

### 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Duration  2.17s (transform 0ms, setup 1.51s, import 616ms, tests 710ms, environment 0ms)
```

### 3. `(cd src/main/frontend && pnpm e2e)`

```
  ✓  12 [chromium] › e2e/portal.spec.ts:581:1 › preview_pane_shows_tree_inert_skill_md_and_diff_vs_served (5.9s)
  ✓  13 [chromium] › e2e/portal.spec.ts:665:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (330ms)
  13 passed (45.6s)
```

### 4. `reqstool status local -p docs/reqstool`

```
  GW_0171             skills-gateway
  GW_0172             skills-gateway
  GW_0190             skills-gateway
  GW_0191             skills-gateway
  GW_0192             skills-gateway
  GW_0193             skills-gateway

INCOMPLETE (0)
169/169 complete · 0 incomplete · PASS
```

### 5. `openspec validate --all --strict`

```
✓ spec/vetting-waivers
✓ spec/virtual-catalog
Totals: 31 passed, 0 failed (31 items)
```

### 6. `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: /Users/r04755/dev/clones/other/github/skillsgateway/.claude/worktrees/mirror-drift/site
INFO    -  Documentation built in 0.78 seconds
```

**No retry was needed on this run.** The container-runtime blips this machine
produces under parallel load (`proxy already running`, a whole-suite
`Failed to load ApplicationContext`) did not appear; the gate mutex the run was
serialised behind is why. Every gate above is from a single execution — none was
rerun, and no number here comes from an earlier attempt.

The two container-backed gates ran while holding a FIFO mutex shared with six
other agents; the four that need no containers ran outside it.

## Not covered, and why

- **A real code host.** Every test pushes to a local bare repository. What is
  verified is the gateway's behaviour over the git transport, not any particular
  host's authentication — which this increment still deliberately does not use.
- **A restart with work queued.** Still not directly tested, and now less
  important: the sweep is what bounds the loss, and phase C exercises the same
  end state (a mirror left holding a withdrawn reference with nothing pending)
  by a route that does not need a restart.
- **Two replicas reconciling at once.** Argued in `design.md` decision 6 rather
  than tested: both read the same served set from the same published storage and
  force-update to the same values, so the worst outcome is a redundant push and
  a duplicate ledger row.
- **A degraded read that is short rather than empty** — a served set missing some
  `refs/snapshots/*` while still carrying the tip. The guard does not catch it
  and it is not tested. `design.md` decision 3 says why a proportional cap was
  rejected instead of added: no threshold separates that from a legitimate bulk
  revocation, and refusing a legitimate one would leave revoked content on the
  mirror.
- **Portal surfacing of drift.** Named out of scope in the proposal; the metrics
  and the two endpoints are this change's operator surface.
