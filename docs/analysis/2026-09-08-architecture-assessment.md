# Architecture assessment — 2026-09-08

**Reviewed at:** `d4d5b63` (`main`)
**Scope:** whole-system read — `docs/manual/architecture.md`, 18 ADRs, all 26 backend
packages, `V1__init.sql`, `SecurityConfig`, the test-suite shape, the portal, the
OpenSpec backlog, and the git history. No code was changed.
**Status:** analysis only. Nothing here is a decision; the decisions are listed at
the end, each with a recommendation.
**Verification:** every load-bearing claim was re-checked adversarially against the
code after first drafting, and the pass changed this document materially:
**F5 was overturned** — the control I said was missing exists — **F4 was corrected
upward** (a live cross-site approval in Firefox and Safari, not the theoretical gap I
first wrote), and **F6 is new**. Corrections are marked in place rather than quietly
absorbed: where I was wrong is itself evidence for §2.

!!! note
    This file lives outside `docs_dir` (`docs/manual`), so it is not published to
    the docs site — same as `docs/decisions/` and `docs/reqstool/`.

---

## 1. Verdict

**The architecture is good. The design is not the problem — the inventory is.**

Two live security gaps (F4, F6) and one phantom control (F3) are the concrete debt.
All three are cheap to close. None is an architecture problem; all three are what
happens when there is more system than there is attention.

The core model is coherent and its invariants are load-bearing rather than
decorative. What has gone wrong is quantity: the system has grown past the point
where one person can hold it, and a small number of real defects have opened behind
the front line while attention was spent on breadth.

Measured shape at review time:

| | |
| --- | --- |
| Age | 26 days (first commit 2026-08-13) |
| Commits | 248 |
| Java | 191 files, 27,848 lines |
| Java tests | 132 files, 24,075 lines (~1:1) |
| Frontend | 54 files, 13,454 lines, 6 pages |
| Backend packages | 26 |
| Requirements / SVCs | 187 / 187 |
| Archived OpenSpec changes | 59 |
| In-flight OpenSpec changes | 8 (4 unstarted, 143 open tasks) |
| ADRs | 18 |
| Docs | 68 Markdown files, 16,268 lines |
| Config properties | 127 leaves |
| Deployments | 0 |
| Users | 0 |

Reproduce the counts:

```bash
find src/main/java -name '*.java' | wc -l
find src/main/java -name '*.java' -exec cat {} + | wc -l
grep -c '^  - id:' docs/reqstool/requirements.yml
ls openspec/changes/archive | wc -l
```

---

## 2. The real problem

> *"personally I think that the software has become far more complex than intended
> and I'm not following anymore."* — the owner, 2026-09-08

That sentence is the finding. Everything in §3 is downstream of it.

Here is the mechanism, and it is not carelessness — it is the opposite.

Every change in this repository went through a rigorous gate: an OpenSpec proposal
with a design, a reqstool requirement and SVC, an evidence report with pasted gate
output, a conventional commit, a PR. **Every one of those gates is local.** They ask
*"is this change well-specified, verified, and traceable?"* Not one of them asks
*"should this exist at all?"* or *"can the maintainer still hold the whole?"*

So the process did exactly what it was built to do, at a rate of roughly two
capability areas a week, and produced a system that is individually well-reasoned
everywhere and globally unfollowable. **Per-change rigour with no global budget is
an accumulation engine.** The rigour is why the code is good; the missing budget is
why there is too much of it.

Two pieces of direct evidence:

1. **The history is essentially additive.** Nine files have ever been deleted, none
   under `src/main/java` or `src/main/frontend/src`; deletions run about 7% of lines
   changed. The muscle is not absent — the `roles.enabled` off-switch was removed
   (#210), a dead method went (#101), and reserved id `GW_0074` was retired — but it
   has never once been used on a *feature*. Narrowing has precedent at the level of a
   flag; it has none at the level of a capability.
2. **`openspec/changes/` holds 143 unstarted tasks across four changes**
   (`corpus-aware-vetting` 0/44, `estate-import-export` 0/36, `virtual-catalogs`
   0/36, `invocation-adoption-metrics` 0/27) — a fifth, sixth, seventh and eighth
   capability area queued behind a system already past its comprehension limit.

**The corrective is not primarily refactoring. It is a stop rule, a map that fits in
one head, and permission to delete.** §5 and §6 are about that; §3 is the debt to
clear on the way.

### 2.1 A note in the process's favour

While reviewing the unstarted backlog I found two things I had missed in the code
and your own process had already caught:

- `virtual-catalogs` slice 1 is **not a feature** — its proposal states it closes two
  defects in the *shipped* catalog: the namespace map is not injective, and the
  collision rule "substitutes content across a publisher boundary". In a product
  whose entire premise is that a name denotes exactly one approved artifact, that is
  a correctness defect in live code, and it outranks most of §3.
- `invocation-adoption-metrics` reached the conclusion that the metric is **forgeable
  by the population being measured** and the join key never arrives (ADR 0016). That
  is a well-argued *decision not to build* — the healthiest artifact in the backlog.

Both are currently indistinguishable, in `openspec/changes/`, from feature work
nobody has started. That is what losing the thread actually costs: a live defect and
a finished "no" sit in the same queue as 80 tasks of new scope, and nothing in the
directory listing tells them apart.

---

## 3. Findings

Referenced as **F1–F9** throughout, numbered in the order they were originally ranked
by (risk × cost) ÷ value. **That is not the order to act in** — §5 gives that, with a
mapping table. If you read only two, read **F4** and **F6**: they are the live
security items, and together they are under a day of work.

### F1 — The object-store git backend is the clearest overbuild

`storage/objectstore/` is ~1,500 lines of hand-rolled distributed storage: a JGit DFS
`ObjectStoreObjDatabase`, an `ObjectStoreRefDatabase`, a write-ahead log, and a
per-repository `ManifestStore` with compare-and-swap.

`docs/manual/architecture.md` §12 admits both problems:

> Real AWS S3 has not yet been exercised against the conditional-write assertions

> What object storage does **not** make safe is the gateway's uncoordinated
> background singletons […] It unblocks multi-replica serving; the chart refuses a
> replica count that would duplicate a sweep.

Unverified against the real substrate, and it unblocks a scaling axis that a
*different* unsolved problem (F2) still blocks. Multi-replica serving was bought; no
deployment can run more than one pod. Leader election — the cheap half — was
deferred.

**Not proposing deletion.** It works, it has contract tests, and ripping it out costs
more than it returns. Proposing that it be *frozen*: no further investment, no real-S3
verification campaign, until a deployment exists that needs it.

### F2 — Seven scheduled sweeps, three raw executors, no coordination

Eight `@Scheduled` methods — `SyncScheduler`, `RevetScheduler`, `RetentionScheduler`
(×2), `WaiverExpirySweep`, `WebhookDispatcher`, `MirrorReconciliationSweep`,
`AuditExportScheduler` — plus `Executors` calls outside Spring's task management in
`MirrorConfiguration`, `SyncConfig` and `VettingService` (the last an unbounded
`newCachedThreadPool`).

**Correction after verification — "no coordination" was too strong.** There is no
ShedLock, advisory lock, `SKIP LOCKED` or `FOR UPDATE` anywhere, but three real
mechanisms exist: `WebhookDeliveryRepository.claim()` takes an atomic
conditional-`UPDATE` lease per delivery, so a second dispatcher matches no row and
moves on; ingestion is idempotent behind `UNIQUE (marketplace_id, sha)` plus a
`DuplicateKeyException` catch; and the Helm chart carries a `replicaGate` that
**refuses `replicaCount > 1`** unless the backend is object-store and all five
singleton flags are off. Others have nothing — `AuditSinkRepository`'s cursor advance
is an unconditional `UPDATE`, and two exporters would double-deliver.

That makes the real finding sharper, not weaker: **safety is per-sweep, uneven, and
undocumented.** There is no single place that says which of the seven are
multi-instance safe, so the only way to answer "can I run two replicas?" is to read
all seven and reason about each. That is precisely the kind of question a maintainer
should not have to re-derive — see §2.

**Fix:** either a Postgres advisory lock per sweep (uniform, ~1 day), or a documented
per-sweep safety table if the existing leases are judged sufficient. Uniformity is
worth more than cleverness here; take the lock.

### F3 — Non-transactional writes on the critical path

Five `@Transactional` annotations in the whole application.
`ApprovalService.doApprove()` is:

```
gates → snapshotRepository.decide(APPROVED) → storage.publish() → [on failure: repair()]
```

A hand-rolled saga on the most important operation in the product.

**Correction after verification — this is specified and tested, not overlooked.**
`GW_APPROVAL_0012 — An approval reports success only when the publication happened`
covers exactly this, and `PublicationIntegrityTests` and `RefResultDisciplineTests`
exercise it. The compensation is deliberate and the requirement even mandates
reporting a repair it could not perform rather than discarding it.

And `repair()`'s failure is not merely logged: it is logged at error *and* attached
with `addSuppressed` to the exception that propagates.

The residual gap is narrower than I first wrote: it is the **double failure** —
publication fails *and* `repair()` fails — leaving a row that says `approved` with
nothing served. The requirement's answer is "report it"; nothing later reconciles it.

**One thing verification did turn up here.** `repair()`'s Javadoc says that case is
"precisely the case the startup check comparing the served estate against the database
exists to catch." **No such startup check exists in the codebase.** A comment
referencing a control that was never built is worse than no comment: it retires the
concern in the reader's mind. Either build the reconciliation — it is genuinely the
right answer, and it makes the reordering below optional — or delete the sentence.

**Fix:** publication is idempotent by SHA, so reverse the order — publish the served
refs, *then* record the decision. The failure mode becomes "served but not recorded",
which a startup reconciliation repairs from storage, instead of "recorded but not
served", which nothing can. Same argument for the mirror event,
`catalogService.rebuildQuietly()`, and the vetting-override marker.

### F4 — CSRF is disabled on the privileged session surface

`auth/SecurityConfig.java`, `webChain` (`@Order(5)`) — the cookie-authenticated chain:

```java
.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
// Session-cookie API for the SPA; revisit CSRF with the portal.
```

CSRF is off for every `/api/**` route reached with an ambient session cookie —
approve, reject, mint PAT, grant role, register marketplace. And
`server.servlet.session.cookie.same-site` is set nowhere, so the application relies
entirely on browsers' default `Lax`.

**I understated this in my first draft, and verification corrected me upward.** I
wrote that browsers block it. They do not, uniformly:

- **Firefox and Safari do not default cookies to `SameSite=Lax`.** In those browsers a
  cross-site top-level form POST carries the session cookie today.
- Chromium applies Lax-by-default, but only outside its 2-minute Lax+POST window.
- The shield that *does* hold is the `application/json` content-type requirement, and
  there is no CORS config — so JSON-body routes are not reachable this way, and
  `PUT`/`DELETE` cannot come from a form at all.

What remains genuinely exposed is every **body-less mutating `POST`**, because a
plain HTML form can produce one. That set is:

`.../ingest`, **`.../snapshots/{id}/approve`** (its body is `required = false`),
`.../reject`, `.../revet`, `.../restore`, `/api/retention/evaluate`,
`/api/retention/compact`, `/api/catalog/rebuild`, `/api/estate/reconcile`,
`/api/mirror/reconcile`, `/api/tokens/{id}/rotate`.

**An approval is in that list.** A logged-in reviewer visiting a hostile page in
Firefox can be made to approve a snapshot. For a product whose single product
principle is *"nothing is served that a person did not approve"*, that is the one
control that must not be missing.

In fairness: it is a documented, deliberate TODO, not a hidden defect — the code
comment and `docs/manual/reference/api/index.md` both state it.

**Fix:** enable CSRF with a cookie token repository (the SPA reads the token) and set
`server.servlet.session.cookie.same-site: strict` explicitly. Either alone closes the
body-less-POST set; do both. The stateless PAT and bearer chains correctly keep
CSRF off and their comments justify it properly — leave those alone.

### F5 — ~~Authorization asymmetry~~ → privileged **reads** are hand-listed

**This finding was substantially wrong and verification overturned it. Recorded
rather than deleted, because how it was wrong is the useful part.**

I claimed the human session surface had no route-table completeness guard and that a
new endpoint could ship unguarded. False. `RoleEnforcementTests.java:490-508` derives
every non-`GET` `/api/**` route from `RequestMappingHandlerMapping` and asserts it
equals the hand-classified sets, then walks each role-gated route with a no-role
session expecting 403. **Mutations are covered by exactly the mechanism I said was
missing.** The only unguarded mutations are `POST /api/tokens`,
`POST /api/tokens/session`, `POST /api/tokens/{id}/rotate` and
`DELETE /api/tokens/{id}` — owner-scoped by design and classified as such.

The real gap is narrower and worth fixing. **The derivation covers non-`GET` routes.
Privileged `GET`s are hand-listed** in a `PRIVILEGED_READS` set. So a new *read*
endpoint that exposes something sensitive can ship open, and nothing fails the build.

F6 is a live instance of precisely that.

**Fix:** extend the same derivation to `GET`, forcing every read to be classified as
open-browsing or privileged. Half the work of what I originally proposed, because the
mechanism is already there and already excellent.

**Why I got it wrong, which is the point of §2:** I read 26 packages and 55
`roleService` call sites, saw imperative guards, and inferred absence of a systemic
control from the presence of a manual-looking one. The systemic control was 500 lines
into a test file. A system this size cannot be assessed by reading it — which is the
same wall the maintainer hit.

### F6 — `fetchers` leaks the blast-radius report past the auditor gate

Found while verifying F5, and the sharpest single thing in this document.

`GET /api/snapshots/{id}/fetchers` (`vetting/RevetController.java`) has **no
`roleService` guard** and returns `List<Fetcher>` — *named principals*, fetch counts,
and last-fetch timestamps. `RoleEnforcementTests` asserts it returns 200 to a session
holding **no role at all**, so this is a deliberate classification, not an oversight.

I think the classification is wrong.

The same information reached through `/api/audit` requires `requireAuditor`.

So "which named identities fetched this snapshot, and when" is auditor-gated through
one endpoint and open to every authenticated user through another. Whichever posture
is right, the two cannot both be.

**Fix:** gate it — `requireAuditor`, or `requireApproverOfSnapshot`. It is the blast
radius report; the architecture doc calls it exactly that. Then move it from the open
set to `PRIVILEGED_READS` in `RoleEnforcementTests`, which currently pins the open
behaviour in place.

One line of production code, one line of test classification. It is in step 1.

### F7 — The ledger is four problems wearing one table

The compliance story rests on this component.

1. **"Append-only" is a convention, not a constraint.** `V1__init.sql` has no
   trigger, no rule, no revoked `UPDATE`/`DELETE` grant, no chained digest. The
   application only appends; the database permits anything. The audit sinks are the
   real tamper-evidence mechanism — the docs should say that, rather than claiming a
   property the schema does not have.
2. **`fetch_log` does double duty** as fetch ledger and admin ledger, split by a
   `source` column. `FetchLogRepository.append()` takes nine positional arguments,
   several always null depending on the caller.
3. **Unbounded growth.** `RetentionService` compacts snapshots; nothing touches the
   ledger. No partitioning, no archival. At the "4,000 developers" scale the threat
   model invokes, this is the table that ends the deployment.
4. **Every facade fetch is a synchronous `INSERT` on the serving hot path**
   (`facade/FetchAuditHook`, via a plain autocommit `JdbcClient` insert with no
   `@Async`, queue or `try`/`catch`). Serving availability is coupled to Postgres
   availability on the one path that must never be down: a Postgres outage fails the
   fetch outright.
5. **The row is written before the pack bytes are sent.** An aborted transfer still
   records a fetch as received. The blast-radius report therefore over-reports, which
   for a recall query is the safe direction — but it is not what the ledger claims to
   mean, and F6's report inherits it.

**Fix:** (1) and (3) are cheap and should be done — a partitioning strategy plus an
honest docs correction. (2) is cosmetic; leave it. (4) is a deliberate trade — write it down as one
rather than leaving it implicit. (5) is a one-line docs correction.

### F8 — The config surface is half decoration

127 leaf properties. See §4 for the full analysis and the concrete cut.

### F9 — Minor: the prose has migrated into the code

21% comment lines overall; 29% in `ApprovalService`. Much of it is *argumentative*
rather than referential — paragraphs weighing alternatives, defending an ordering,
explaining why a control exists. Per the standing convention that belongs in the
commit and the PR, with a one-line residue in the file.

Style, not defect — but it is the visible signature of the spec being written into
the source, and it is part of why the codebase is hard to re-read: a 500-line service
reads like 800.

---

## 4. The config surface

**Answer: yes, roughly half of it can go, and the data says which half.**

Classifying all 127 leaves of `SkillsGatewayProperties`:

| Category | Count | What it is |
| --- | --- | --- |
| **Structural / policy** | 44 | Things that genuinely decide something: `storage.backend`, object-store endpoint and credentials, `oidc.issuer`, `roles.admins`, the `enabled` and `mode` switches, `mirror.url` |
| **Estate data** | 24 | `estate.*` and `roles.mappings.*` — the schema of a declarative desired state. A data model, not knobs. Legitimate. |
| **Tuning knobs** | 59 | Every interval, batch size, timeout, backoff, cache size, retention age and ingestion budget |

Now the load-bearing measurement. For those 59 tuning knobs:

| | |
| --- | --- |
| Set in **any** deployment artifact (`application.yaml`, `helm/values.yaml`, `compose*.yaml`, `Dockerfile`, CI) | **0** |
| Set only by a test (to shorten an interval or hit a boundary) | 21 |
| **Never given a non-default value by anything, anywhere** | **38** |

Every one of the 59 runs on its default in every environment that exists. Not one has
ever been tuned in anger, because there has never been an operator to tune it.

Each still costs: a line in `configuration.md` that must stay true, a combination the
test matrix could be asked to cover, a null-check in the record's compact
constructor, and a place a future reader must look before concluding the code does
what it appears to do. `mirror.sweepInitialDelay` carries a nine-line javadoc
justifying its own existence.

### What to remove

**Remove (promote to a constant in the class that uses it) — the 38 never-set knobs.**
Chiefly:

- `audit-export.{poll-interval, batch-size, default-page-size, max-page-size}`
- `retention.{poll-interval, compaction-interval, batch-size, staging-ref-max-age}`
- `vetting.{timeout, max-file-bytes, content-cache-bytes, waiver-sweep-interval, waiver-sweep-batch-size}`
- `vetting.revet.{interval, batch-size}`
- `sync.{poll-interval, batch-size, max-webhook-body-bytes}`
- `webhooks.{poll-interval, batch-size}`
- `storage.object-store.cache.*` (6) and `connection-{max-idle-time,time-to-live}`
- `ingestion.external-sources.budgets.*` (9) — see the caveat below
- `retention.marketplaces.*` (4) — per-marketplace overrides with no consumer

**Keep as configuration:**

- All 44 structural/policy leaves. These name an environment or state a policy; they
  must be settable.
- All 24 estate-data leaves. That is a desired-state schema, and shrinking it would
  shrink the declarative-estate capability, not the config surface.
- The 21 test-set knobs — **but change how tests reach them.** A test that shortens an
  interval to make a scheduler tick is using configuration to work around an untestable
  design. Most of these sweeps already have a callable method; call it. Where a knob
  survives only because one test sets it, that is a test smell, not a requirement.

**One caveat, and it cuts the other way.** The nine
`ingestion.external-sources.budgets.*` values are **security limits** — zip-bomb and
resource-exhaustion defences on the one outbound path. A security limit that any
operator can raise from a config file is arguably *worse* than a constant. Turning
these into constants both shrinks the surface and hardens the control. That is the
best-value item in this section.

### Expected result

127 → roughly 89 leaves, a materially shorter `configuration.md`, and a
`SkillsGatewayProperties` that drops from 1,151 lines to something a person can read
in one sitting. **No behaviour changes** — every removed value keeps its current
default, now as a constant beside the code that uses it.

---

## 5. Recommended order of work

Ordered by value, not by finding number. Mapping:

| Step | Finding | Effort | Why here |
| --- | --- | --- | --- |
| 0 | §2.1 | — | A defect in shipped code outranks everything on my list |
| 1 | F4 + F6 | ~half a day | Best severity-to-effort ratio in the repo; F6 is one line |
| 2 | F5 | ~half a day | Extend the existing derivation to `GET`; F6 is its live instance |
| 3 | F3 | 1 day | Removes the only unreconcilable state in the system |
| 4 | F2 | ~1 day | Delivers what F1 was built for |
| 5 | F8 / §4 | 1–2 days | Shrinks what must be understood |
| 6 | F7 (1, 3) | 1 day | Ledger honesty + the table that ends the deployment |
| 7 | F1 | 0 | A decision to freeze, not work |
| 8 | F9 | ongoing | Convention, applied on touch |

**Step 0 first:** land `virtual-catalogs` slice 1. It is a correctness defect in
published content, found by your own process, currently queued behind feature work.

Nothing on this list adds a capability area. That is the point.

---

## 6. Decisions needed from the owner

Each with a recommendation. None of these is mine to make.

**D1 — Disclosure. This repo is public. Do F4, F5 and F6 land before this document does?**
*Recommend: yes — and F4 is now urgent enough to fix regardless of this document.*
Verification showed a cross-site approval is reachable in Firefox and Safari today.
Fix F4 and F6 first — under a day together — then publish. Do not open a public issue
describing either before the fix lands; use a draft security advisory if you want a
record. A public file describing an unfixed CSRF gap on the approve endpoint, and an
unguarded endpoint naming who fetched what, is a disclosure — however small the
practical exposure.

**D2 — Issues, OpenSpec changes, or both?**
*Recommend: one tracking issue for the assessment, linking this file; then one
OpenSpec change per step, opened when that step starts — never all at once.* Project
convention already requires an OpenSpec change for any behaviour change, so that half
is settled. What is not settled is *when*: five open proposals nobody is implementing
is the accumulation pattern this document is about. Exception: F4, F5 and F6 under D1
should be a private security advisory or a straight PR, not a public issue that
describes the gap before it is closed.

**D3 — The four unstarted OpenSpec changes (143 tasks). What happens to them?**
*Recommend:*
- `virtual-catalogs` → **promote to step 0.** It is a defect fix.
- `invocation-adoption-metrics` → **archive as a decision not to build.** ADR 0016
  already reached that conclusion; the change should record the "no" and close, not
  sit as 27 open tasks.
- `corpus-aware-vetting` → **park.** T5 is the only uncovered threat row, but its own
  proposal says corpus state as a third input would break the purity that continuous
  re-vetting depends on. That is a hard design problem and it needs a user first.
- `estate-import-export` → **park.** Blocked on your decision on ADR 0014 anyway.

**D4 — Is there a stop rule?**
*Recommend: yes, and write it into `CLAUDE.md` where the other gates live.* Something
falsifiable — e.g. *"no new backend package or `skills-gateway.*` prefix without a
named deployment that needs it"*, or a standing cap on in-flight OpenSpec changes.
The process has eight gates that all say "how"; it needs one that can say "no".

**D5 — What is the map that fits in one head?**
*Recommend: a single page listing the ~20 capability areas, one line each, with owner
state (shipped / partial / parked) and the one invariant each protects.* Not
`architecture.md` (607 lines, design-doc register, describes unbuilt things
alongside built ones). A different artifact with a different job: *what exists, right
now, and why*. If you can't re-derive the system from one page, that is the metric
that has regressed — and it is the one worth defending from here on.

**D6 — Does the pilot happen before or after this list?**
*Recommend: during.* Steps 1–4 are ten days of work; finding a design partner takes
longer than that. Run them concurrently and let the pilot decide what happens to F1
and to everything in Phase 3.
