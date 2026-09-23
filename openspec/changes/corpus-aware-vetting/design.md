# Design: corpus-aware-vetting

## Context

ADR 0015 decides *where* a corpus question may be asked, and its "Owner
decisions" section settles what the first rule matches. This document decides how
the corpus is built and how the gate behaves.

Three existing properties constrain the shape:

- **A chain run is a pure function of (pinned content, chain identity)** — the
  two things `vetting_runs` records. Nothing here may add a third.
  `GW_VETTING_0039 — Corpus state is never an input to a vetting chain run`
  states it.
- **`ApprovalService` is a named trust boundary**, and its concurrency
  correctness lives in guarded single-statement updates rather than
  transactions. A check whose answer depends on *other* rows cannot be guarded
  that way, which is the one place this change departs from the pattern.
- **Absence of evidence is not evidence of safety.** A snapshot whose plugin
  inventory cannot be read cannot be shown not to collide, so it is refused.

## Goals / Non-Goals

**Goals**

- The approved estate's plugin names are queryable without a fan-out of JGit
  walks.
- A snapshot that would introduce a normalised plugin-name collision cannot be
  approved, and the refusal is accepted only through the existing waiver.
- The incumbent is never touched; this check can never withdraw served content.
- No change to `SnapshotUnderVetting`, to any vetter, or to what a run records.

**Non-Goals**

- Not a search index, not a catalog table, not edit distance.
- Not the registration-time near-miss warning, not the `mergePlugin` shadowing
  fix.

## Decisions

### 1. Two tables: the facts, and the plugin-name index

`snapshot_facts` holds one row per indexed snapshot: the facts map as JSONB and
when it was built. `snapshot_plugin_names` holds one row per plugin the manifest
declares — its name and its manifest location — and references
`snapshot_facts`, so a name cannot exist without its snapshot's row and a
snapshot with zero plugins is still distinguishable from one never indexed.

The two are built from different reads and fail differently. The plugin names
come from the manifest alone. The full facts also walk every file (bounded at
20 000) and read every `SKILL.md` (bounded at 256 KiB), and exceed a bound far
more easily. Tying the name index to the full facts would make every snapshot
over those bounds unapprovable once this gate exists, which today it is not. So:

- the row exists ⇔ the plugin names are indexed;
- `facts` is **null** when the full facts could not be built, and a reader then
  builds them itself — exactly today's behaviour, including the same fail-closed
  error for `PolicyGate`.

A failure to build is not stored as a reason. A deterministic failure (a bound)
recurs identically on every rebuild; a transient one (a storage read) would
otherwise be frozen into a permanent refusal.

### 2. The stored facts exclude the snapshot's state

`state` is the one field of the map that is not a function of the pinned commit.
Storing it would freeze a value that `held → approved → revoked → approved`
changes, so it is left out and re-injected from the `snapshots` row on every read.
The CEL namespace is unchanged; only where the value comes from changes.

### 3. Written at ingestion; written once; the approval indexes what is missing

`IngestionService` records the facts after the snapshot row exists (the closure is
written with it) and before the chain runs. A failure is logged and never fails
the ingestion. Writes are `INSERT … ON CONFLICT DO NOTHING`, so nothing is ever
rewritten.

At approval the gate first makes sure the snapshot's own names are indexed,
building them if the row is missing, and refuses with a distinct
*inventory-unavailable* reason when it cannot. With the rule enabled that
makes **every approved snapshot indexed**: `decide`
is the only write that produces `approved`, and it now runs behind the index.

There is no backfill. The schema is pre-1.0 and every environment is rebuilt from
`V1`, so there is no approved snapshot from before this change.

### 4. The normalised keys are computed, not stored

The key depends on a vendored Unicode table; a stored key would go silently stale
the day the table is bumped. The gate loads the estate's names and normalises
both sides in memory. The estate is thousands of names and the gate runs at human
speed, once per approval.

The key: NFKC; remove format characters (general category Cf — zero-width
joiners and the like, invisible in a name a person reads); then the UTS #39
skeleton and case folding, alternately, until the string stops changing; then
remove `-`, `_`, `.` and whitespace. The alternation is needed because the table
maps some characters to capitals (`0` → `O`) and others away from them
(`I` → `l`), and iterating makes the key stable whichever way a name mixes them.

A capital I is genuinely ambiguous: `cIaude` needs it read as `l` (skeleton
before folding) and `CLAUDE-SKILLS` needs it read as `i` (folding first). No
single order serves both, so each name gets **two keys**, one per order, and two
names collide when they share one. For a name without capitals the two keys are
the same. This is still exact matching; it widens nothing beyond the two
readings of a capital letter.
Case folding is `toUpperCase` then `toLowerCase` in the root locale, the JDK's
closest approximation of full case folding.

The table is Unicode's `confusables.txt`, version 18.0.0, vendored verbatim with
its SHA-256 in a README under `src/main/resources/approval/`, as the
`agentskills-*.json` precedent does. No runtime download.

### 5. What is compared

For the snapshot seeking approval, in marketplace M:

- **Its plugin names**, never its skill names.
- **Only the new ones**: a name whose key some other approved, non-deleted
  snapshot of M already carries is not checked. A collision is raised once, when
  the name first arrives, and accepted once.
- **Against** every plugin name of every approved, non-deleted snapshot of any
  marketplace other than M. Superseded but still approved snapshots count: they
  are approved, and a client can still fetch them by SHA.

Each colliding new name is one finding: rule `plugin-name-collision`, severity
`HIGH`, located at `.claude-plugin/marketplace.json:<line>` — the manifest entry
that declares the name, so a waiver's path scope has something to match. The
message names the incumbents.

### 6. The race is closed inside a guarded transaction

The gate reads the estate and `decide` writes the row; two approvals of colliding
names in that window would both pass. The final check therefore runs **inside the
same transaction as the transition**, after taking one transaction-scoped
PostgreSQL advisory lock (`pg_advisory_xact_lock`) that every approval takes:
lock, re-evaluate, `decide`, commit. The second approval waits for the first to
commit, then sees it, and is refused as a collision with it. First come wins,
deterministically, and the loser is never recorded as approved at all.

Only that step is transactional — not `doApprove` — so every other gate's ledger
entry is still written as it was. One lock for all approvals rather than one per
key: approvals are human-speed and the locked section is a few queries, and a
single lock cannot deadlock.

The same evaluation runs first *outside* the lock, in the gate order below, so a
refusal is reported and ledgered where the other refusals are and a covering
waiver is known to the four-eyes rule. The in-transaction evaluation is the
authoritative one.

*Alternatives rejected:* re-checking after `decide` and undoing with the
publication-repair path (both racing approvals can lose, and a row reads
approved for content never served); a uniqueness constraint on the key (a waived
collision is allowed to coexist, and "new to the marketplace" is not expressible
as one).

### 7. Where it sits among the gates

Closure completeness, standing withdrawal, vetting (with its override), policy,
**name collision**, minimum release age, four-eyes, then the guarded transition.
After policy because it is also a question about content; before release age
because it is actionable and the wait is not; before four-eyes because a covering
waiver the reviewer wrote is a four-eyes conflict like any other waiver.

The administrative override of a blocked vetting outcome lifts the vetting gate
only; this gate runs regardless. A restore (`revoked → approved`) is the same
`doApprove` path and re-runs it: a snapshot whose name another marketplace
acquired while it was revoked is refused until the collision is waived.

### 8. Waivers

A covering waiver is any active waiver of the marketplace on rule
`plugin-name-collision` whose scope matches the finding — `Waiver.covers`,
unchanged. Suppressions are returned with the vetting ones, so they reach the
ledger as `waiver-applied` entries and the four-eyes rule.

Snapshot scope accepts every collision that commit has, and — because only new
names are checked — is needed once per name. Path scope on the manifest path
covers every future new collision of the marketplace until it expires; the API
accepts it, as it accepts any path, and the portal does not offer it for this
rule.

### 9. Configuration

`skills-gateway.approval.name-collision.enabled`, default `true`. Off, the gate
still tries to index the snapshot being approved (decision 3) but refuses
nothing, reports nothing, and approvals proceed exactly as before. A snapshot
approved while the rule is off and whose index could not be built is then an
incumbent the rule cannot see once it is switched back on; the log line says so.

### 10. Declarative estate and group mapping

Nothing here is estate-managed. The facts are derived from content, the rule is
code, and its switch is configuration. Waivers — the acceptance — are unchanged
and remain API-managed as they are today.

## Risks

- **The transition is now inside a transaction.** `decide` already was; the new
  part is the lock and the check inside it. The ledger entry for an in-lock
  refusal is written after the rollback, outside the transaction.
- **`SkillsGatewayProperties.Approval` gains a component.** Constructed only in
  the record's own compact constructor.
- **`V1__init.sql` and `ApprovalService` are also edited by the marketplace
  removal change (#477).** The edits here are additions in separate places; once
  both land, the estate query should also exclude removed marketplaces.
- **An operator can switch the rule off.** Stated in the configuration reference.
