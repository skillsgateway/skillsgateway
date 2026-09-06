# Evidence: external-source-closure

This change alters what `ApprovalService` will publish and what ingestion
persists, so the old-coder discipline applies at **Tier 3**. The failure model is
in `design.md`; this report records what was executed, what it produced, and
where the loop was not followed.

**Source state:** `03ee3b1b9828f596c606b4a13163ffd54fd97f77` (tree
`78035c44d8007b4a770dcac48399e30c967d75fd`) on branch `feat/external-source-closure`, the
requirement renumber, seven commits on top of `main` at `050d11f` (#278). It is
the last commit that touches anything a gate reads. **Every number in the gate
table below comes from one pass run against exactly that tree**, except the two
rows marked as carried over, which say so and why. Only this report follows it.

The numbers were first produced at `e515a22` (tree `91a3001`), before the
renumber; the four gates the renumber could affect were re-run against
`03ee3b1` and are reported here at their re-run values.

**Requirement ids.** GW_0164 — The resolved closure is recorded as an immutable
domain object of the snapshot, and GW_0165 — Approval requires a complete
closure. GW_0162 was the highest id on `main` and no in-flight change under
`openspec/changes/` reserved anything above it, so this change originally claimed
GW_0163 and GW_0164. [#283](https://github.com/skillsgateway/skillsgateway/pull/283)
had claimed GW_0163 in an unpushed worktree and was opened first, so this branch
renumbered both ids upward, preserving their order. The gate results below are
from the renumbered tree.

## Spec ↔ test mapping

| Requirement | What is proved | Test(s) |
| --- | --- | --- |
| **GW_0164 — The resolved closure is recorded as an immutable domain object of the snapshot** | the digest is SHA-256 hex, order-independent over members, sensitive to the upstream commit, the transformer version and every one of the eleven member fields, and framed so field boundaries cannot collide; a resolved ingestion records the closure with every field the review asked for, the grafted *tree* rather than the external commit, and `upstream_sha` equal to the composite's parent; a local-only snapshot and a rejected resolution record no closure and `upstream_sha = sha`; the provenance response carries `sha`, the true `upstreamSha` and the closure (`null` for local-only); the policy facts carry `snapshot.externalSources`, `snapshot.upstreamSha` and per-plugin `origin`/`upstreamUrl`/`resolvedSha`; the blast-radius query answers by URL, by URL and commit, and returns nothing for the wrong commit or URL; the same closure in two marketplaces has one digest; purging the snapshot removes the closure and its members | `SnapshotClosureDigestTests` (17 cases, SVC_GW_0164); `SnapshotClosureTests` (8 cases, SVC_GW_0164; the provenance case also SVC_GW_0009) |
| **GW_0165 — Approval requires a complete closure** | every tampering below is refused with `ClosureIncompleteException` naming the discrepancy, written to the ledger as `snapshot-approval-refused` / `closure-incomplete: …`, leaves the snapshot held with no `refs/heads/main` in published; the administrative override does not lift the gate; a revoked snapshot is checked again on re-approval; an untouched composite and a local-only snapshot still approve with no refusal on the ledger | `ClosureCompletenessTests` (12 cases, SVC_GW_0165) |
| **GW_0009 — Snapshot provenance** (unchanged for local-only) | `upstreamSha` still equals the snapshot's commit for a local-only snapshot | `ApprovalTests.provenanceOfApprovedSnapshotIsRetrievable`, **unmodified** |
| **GW_0156, GW_0161** (unchanged) | the composite, its parent, determinism and every failure-atomicity path | `ExternalSourceResolutionTests` (19 cases), **unmodified** |
| **GW_0034 — compaction** (unchanged) | purge still works with the cascade in place | `RetentionTests` (6 cases), **unmodified** |
| Portal: the provenance dialog | the dialog shows the served SHA, the upstream SHA, and each closure member's clone URL and resolved commit under an *External plugin sources* heading | `marketplaces.test.tsx` `provenance_dialog_lists_the_served_commit_and_the_resolved_closure` (vitest, jsdom; untagged because only Playwright results are matched to SVC ids) |

## The adversarial cases, one by one

Nothing in the gateway can produce an incomplete closure — the closure is
written in the snapshot's transaction — so every refusal was arranged by hand:
rows removed or rewritten through `JdbcClient`, and commits synthesised in
quarantine that the closure does not describe.

| Tampering | Result |
| --- | --- |
| Every member row deleted | refused, discrepancy names `tools` |
| The closure row deleted (members cascade) | refused, names `tools` |
| A member's `tree_sha` set to a real but wrong tree (the upstream root) | refused, names `_plugins/tools` and both tree ids |
| A member's `graft_path` moved to `_plugins/elsewhere` | refused, names `_plugins/elsewhere` |
| A member's `resolved_sha` set to `not-a-commit` | refused, names `not-a-commit` |
| The snapshot row re-pointed at the composite's parent (no `_plugins` tree, manifest still external) | refused, names `_plugins/tools` |
| A commit derived from the composite with a stray `_plugins/stray/README.md` | refused, names `_plugins/stray` — the *tree ⊆ closure* direction |
| A commit derived from the composite whose manifest grafts a `ghost` plugin nothing resolved | refused, names `ghost` — the *manifest ⊆ closure* direction, which no tree check can see |
| A commit derived from the composite whose manifest turns `tools` back into a local path while the closure and the tree still hold it | refused, names `_plugins/tools` — the *closure ⊆ manifest* direction |
| Members deleted, then approval requested with `ApprovalOverride.ofVettingFailure(reason)` | refused; the override lifts only the vetting gate |
| Approved, revoked, members deleted, approved again | refused; the snapshot stays `revoked` |

In every refusal: state unchanged, `decidedBy` null, exactly one
`snapshot-approval-refused` ledger entry whose detail starts with
`closure-incomplete:` and names the discrepancy, and `refs/heads/main` absent
from the published repository.

## Gates — one pass, at `e515a22`

| Gate | Command | Result, verbatim |
| --- | --- | --- |
| Java + UI + jar | `./mvnw clean verify` | `BUILD SUCCESS`, `Total time:  04:26 min` |
| Java tests | (inside `verify`) | `Tests run: 533, Failures: 0, Errors: 0, Skipped: 0` |
| Formatting | `spotless:check` (inside `verify`) | `Spotless.Java is keeping 282 files clean - 0 needs changes to be clean, 282 were already clean` |
| Style | `checkstyle:check` (inside `verify`) | `You have 0 Checkstyle violations.` |
| Portal unit gate | `pnpm verify` (inside `verify`) | `Test Files  11 passed (11)`, `Tests  46 passed (46)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files  3 passed (3)`, `Tests  6 passed (6)`, `Duration  2.97s` — **carried over from `e515a22`**: the renumber touched no story, and the only frontend files it changed are two JSDoc tags, which the unit gate inside `verify` re-ran |
| Real-browser e2e | `(cd src/main/frontend && pnpm e2e)` | `13 passed (48.1s)` — **carried over from `e515a22`**, for the same reason: no e2e spec, route, or served byte differs after the renumber |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `157/157 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` — 31 before the archive, 30 after it and after the renumber |
| Docs | `mkdocs build --strict` | `Documentation built in 1.34 seconds`, no strict failures |
| Mutation testing | `openspec/changes/archive/2026-09-06-external-source-closure/mutants.sh` | `=== killed 15, survived 0 ===` at `e515a22`; **not re-run after the renumber**, with its applicability re-verified instead — see below |

**Why the mutation run was not repeated.** The renumber rewrote annotation and
comment text inside every file `mutants.sh` patches, which could in principle
have broken a search string — and a search string that no longer matches makes
the runner abort rather than report a false kill, so the failure mode is safe
but the run would be worthless. Rather than assert that no mutant was affected,
each of the 15 search strings was re-checked against the renumbered sources and
each still occurs **exactly once**; none of them contains a requirement id (they
target expressions, and the ids appear only in three section-header comments).
The kills therefore still describe this tree. A full re-run would be the
stronger evidence and was not spent.

Two environmental notes, neither a change to a gate:

- On the `e515a22` pass, the first `pnpm test:stories` attempt wedged for ten
  minutes before spawning vitest (a `pnpm` process with no child) and was
  killed; the rerun reported
  `Port 63315 is in use, trying another one...`, ran all 6 stories and passed in
  under three seconds. Recorded as a wedged runner, not a flake in the suite.
- Surefire reported `The exit has elapsed 30 seconds after System.exit(0)` once,
  after the last Java test. It does not fail the build. The shared forge this
  change adds is closed by a shutdown hook calling `HttpServer.stop(0)`, so it
  is not the cause; the linger is in whatever else the fork's shutdown hooks
  wait on, and it was not investigated further.

## Gauntlet layers

| Layer | Result |
| --- | --- |
| Full test suite | 533 Java + 46 portal unit + 6 storybook + 13 e2e, zero failures. No pre-existing failures to baseline against |
| Static types | `javac` and `tsc -b` inside `verify`, 0 errors |
| Lint + format | Spotless 282/282 clean, Checkstyle 0 violations; oxlint inside `pnpm verify` reports three pre-existing warnings in files this change does not touch at those lines |
| Coverage on changed lines | **no coverage tool is configured in this project**, so this layer is not available as a gate. What stands in its place is mutation: 15 mutants across every new class and every changed method, all killed |
| Mutation testing | 15/15 killed — see below |
| Property-based tests | **skipped** — no property-based framework in the project. The digest's invariants (order independence, per-field sensitivity, framing) are enumerated directly in `SnapshotClosureDigestTests` |
| Complexity budget | every new method is single-purpose; the largest is `ClosureCompletenessGate.discrepancies`, one pass over three maps |
| Real execution | the e2e suite runs the packaged jar against a real browser and a mock IdP; `ExternalSourceResolutionTests` (unmodified) still clones an approved composite through the facade with the system git binary |
| Supply chain & secrets | no new dependency: SHA-256 from the JDK, `JdbcClient` and JGit already in use. No credential of any kind in the tree |
| Suite health | the three new suites were run five times through the loop (RED, GREEN, the extra gate tests, 15 mutant runs, the final pass) with no flake observed |

### Mutation testing

`mutants.sh` ships beside this report, in the shape of #257's runner: one
plausible bug at a time, run the test that should catch it, require a failure,
restore in a trap. It aborts if a search string is not found exactly once (the
mutant was never applied), exits non-zero on a survivor, and resolves the
repository root from its own location whether it sits in `openspec/changes/` or
`openspec/changes/archive/`.

| # | Mutant | Test | Result |
| --- | --- | --- | --- |
| 1 | the digest depends on member order | `SnapshotClosureDigestTests` | killed |
| 2 | the declared ref stops being an input to the digest | `SnapshotClosureDigestTests` | killed |
| 3 | null and the empty string frame identically | `SnapshotClosureDigestTests` | killed |
| 4 | the closure is never written with the snapshot | `SnapshotClosureTests` | killed |
| 5 | `upstream_sha` records the served commit instead of the upstream one | `SnapshotClosureTests` | killed |
| 6 | every plugin is a local plugin to the policy gate | `SnapshotClosureTests` | killed |
| 7 | the blast-radius query ignores the resolved commit | `SnapshotClosureTests` | killed |
| 8 | approval never consults the closure gate | `ClosureCompletenessTests` | killed |
| 9 | the ledger entry names no discrepancy | `ClosureCompletenessTests` | killed |
| 10 | a manifest graft with no closure member is accepted | `ClosureCompletenessTests` | killed |
| 11 | a closure member the manifest does not declare is accepted | `ClosureCompletenessTests` | killed |
| 12 | a member with no usable resolved commit is accepted | `ClosureCompletenessTests` | killed |
| 13 | a member whose recorded tree differs from the grafted tree is accepted | `ClosureCompletenessTests` | killed |
| 14 | content under the reserved directory that no member accounts for is accepted | `ClosureCompletenessTests` | killed |
| 15 | the served manifest is never read | `ClosureCompletenessTests` | killed |

**What the mutation analysis changed before it ran.** Planning mutants 10 and
11 showed that the original ten gate tests would not have killed them: every
tampering they covered was *also* caught by the tree checks, so dropping either
manifest-direction check changed nothing any test could see. Two tests were
added for exactly the states only those checks can see — a manifest grafting a
plugin nothing resolved, and a manifest that no longer declares a recorded
member — and the runner then killed both mutants. Those two tests passed on
first run; mutants 10 and 11 are the proof that they are not vacuous.

**Negative control for the portal test.** The vitest case passed on first run,
because the dialog and its MSW handler were written together. A throwaway
mutant (`{member.resolvedSha}` → `{""}`) was applied, the case observed failing
(`× provenance_dialog_lists_the_served_commit_and_the_resolved_closure`), and
the file restored byte-for-byte before the commit.

## RED, observed

Before any implementation existed, the three Java suites were run against
stubs whose methods throw `UnsupportedOperationException` and against a
`Snapshot` record that had gained `upstreamSha` but no closure behaviour:

- `SnapshotClosureDigestTests`: `Tests run: 17, Failures: 0, Errors: 17` — every
  case on the stub.
- `SnapshotClosureTests`: `Tests run: 8, Failures: 3, Errors: 5` — three on
  behaviour assertions (`upstreamSha` equalled the served commit, provenance
  carried no closure, the facts carried no origin), five on the stub.
- `ClosureCompletenessTests`: `Tests run: 10, Failures: 9` — every refusal case
  failed because nothing refused. The one passing case was the positive control
  (`an_untouched_composite_and_a_local_only_snapshot_still_approve`), which is
  pre-existing behaviour kept as regression armor.

## Where the old-coder loop was not followed, honestly

- **Spec approval: not obtained (autonomous run).** `design.md` was committed
  before the implementation, but no human approved it before code was written.
  It is the artifact to review after the fact, and this report claims
  correspondingly lower confidence.
- **Two gate tests and the portal test did not run red** on their own: they were
  added after the implementation existed (see the mutation section and the
  negative control above for how each was shown non-vacuous).
- **Independent verification: not performed.** A declared downgrade: the
  adversarial pass here is the author attacking their own work.
- **Changed-line coverage was not measured**, because the project configures no
  coverage tool. Recorded as unavailable rather than as passed.

## Deviations from the review's sketch, decided rather than absorbed

- `snapshot_closures.snapshot_id` (closure owned by its snapshot, cascading
  with the purge) instead of `snapshots.closure_id` (a shared, digest-deduped
  closure). The digest is indexed as the cross-snapshot query key.
- `closure_edges` is the nullable `parent_member_id` adjacency column; the
  closure is a tree by construction and every member is at depth one today.
- The policy version is not a digest input, for the reason #257 gave for the
  composite SHA.
- The review's "every closure row has a `resolved_sha`" is a `NOT NULL` column;
  the gate checks the stronger thing, that it parses as an object id.
- Members record the grafted **tree** in addition to the resolved commit,
  because retention's garbage collection may reclaim the external commit object
  once scaffolding refs are pruned.

## Known limits, deliberately not covered

- **No blast-radius re-vetting.** `snapshotsContaining` is the query; wiring it
  into `RevetService` is the ADR's next row.
- **No SSRF work.** The egress proxy and connect-time address pinning are #17's
  next item; nothing here changes the outbound path.
- **`declared_ref` / `declared_sha` are always `NULL`** until the increment that
  honours a declared pin lands; the columns exist so the schema does not move.
- **Closure depth is one.** `parent_member_id` is never populated.
- **The portal shows the closure only in the provenance dialog**; the origin
  badge on the marketplace detail page the original proposal sketched is not
  in this change.
