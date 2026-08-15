# Design: add-vetting-chain

## Context

ARCHITECTURE.md §4 already names the component ("Vetting orchestrator (connector-based)")
and the shape of its result (`{connector, snapshot, verdict, report-url, findings[]}`), and
§14.2 earmarks promptfoo for the LLM connector. What does not exist yet is any of it. This
change builds the orchestrator and two synchronous built-ins, and deliberately stops there:
the value of the foundation is that the async connector, waivers, and re-vetting can be added
without reshaping the contract.

The gate this feature guards is the one that publishes content, so every decision below is
resolved in the fail-closed direction, and ambiguity is treated as failure.

## Goals / Non-Goals

**Goals**

- A connector contract an external, asynchronous connector can satisfy later without a
  breaking change to the persisted model or to the aggregation rule.
- Verdicts recorded per (snapshot SHA, connector, run) and visible to the reviewer before the
  decision.
- Aggregation that cannot be talked into passing: no run, a crash, a timeout, or an
  unfinished async connector all block.
- Two honest built-in scanners, with their limits documented rather than oversold.

**Non-Goals**

- Auto-approval on a clean chain. The chain informs the reviewer; approval stays a human act.
  Tier-driven auto-approval (ARCHITECTURE.md §6) needs the manifest-analysis tiering that does
  not exist yet.
- Policy-as-code (which connectors are *required* per tier). v1 requires all of them.
- Malware scanning, LLM semantic review, and the external webhook connector.
- Blocking on `WARN`. A warning that blocks is a failure with a friendlier name; if a rule
  should block, it emits a high-severity finding.

## Decisions

### The SPI is content-in, verdict-out

```java
public interface VettingConnector {
    String name();                 // stable id, persisted; e.g. "secret-scan"
    int order();                   // ascending; ties broken by name for determinism
    Verdict vet(SnapshotUnderVetting snapshot);
}
```

`SnapshotUnderVetting` exposes `snapshotId()`, `marketplace()`, `sha()`, and
`walk(FileVisitor)` — a path/bytes walk over the pinned commit tree. Deliberately **not** a
`Repository`: a connector must not be able to write to quarantine, delete a ref, or read
another marketplace. The implementation opens the quarantine repo once per run and streams
blobs; files above `max-file-bytes` are skipped with an `INFO` finding rather than silently.

`Verdict` is `(VerdictState state, List<Finding> findings, String reportUrl)`;
`Finding` is `(String id, Severity severity, String location, String message)` where `id` is
the rule id (`aws-access-key-id`, `instruction-override`) — the identity #28 will waive
against, which is why it is a stable string and not an ordinal.

`VerdictState` is `PASS | WARN | FAIL | ERROR | PENDING`. `ERROR` is distinct from `FAIL`
because "the connector says this content is bad" and "the connector broke" are different
operational facts even though both block. `PENDING` is the async seam: an external connector
returns `PENDING` at trigger time and a later callback updates its verdict row; the
aggregation rule already blocks on it, so the gate is correct before the callback exists.

### Aggregation is a pure function, and it is the only place the rule lives

`VettingChain.aggregate(List<Verdict>)` → `Outcome.CLEAR | Outcome.BLOCKED`:

- `CLEAR` **iff** the verdict list is non-empty and every state is `PASS` or `WARN`.
- everything else — including an empty list — is `BLOCKED`.

Empty-is-blocked is what makes "no run at all" fail closed: a snapshot ingested before this
feature, or one whose chain never completed, cannot be approved without an explicit override.
The rule is a static pure function over states so it can be exhaustively unit-tested across
every state combination without a database, a repository, or a Spring context.

Running order is ascending `order()`, and **all** connectors run: short-circuiting on the
first `FAIL` would hide the second finding from the reviewer and make the recorded run depend
on connector order. The `ERROR` path is `try { … } catch (Throwable t)` around each
connector, plus a per-connector timeout enforced by submitting the call to a bounded executor
and `Future.get(timeout)`. A timed-out connector's thread is cancelled and, if it ignores the
interrupt, abandoned — documented, and the reason the executor is bounded and daemon.

### Persistence: run → verdict → finding

Three tables folded into `V1__init.sql` (the repo keeps a single migration):

| table | key columns | why |
| --- | --- | --- |
| `vetting_runs` | `snapshot_id`, `trigger`, `outcome`, `started_at`, `finished_at`, `override_by/at/reason` | one row per chain execution; #24 adds rows with `trigger='re-vet'` |
| `vetting_verdicts` | `run_id`, `connector`, `position`, `state`, `detail`, `report_url` | the per-(SHA, connector, run) record; `UNIQUE (run_id, connector)` |
| `vetting_findings` | `verdict_id`, `finding_id`, `severity`, `location`, `message` | what #28 waives against |

The snapshot's own row is untouched: vetting state is not snapshot state. A snapshot stays
`held` whatever the chain says — the chain gates the *approval*, and keeping the two apart is
what lets #24 re-vet an approved snapshot without inventing a state transition. The latest run
is read with `ORDER BY id DESC LIMIT 1` rather than denormalised onto `snapshots`, so a
re-vet is an insert and never an update to snapshot rows.

### The approval gate and the override

`ApprovalService.approve(id, reviewer, overrideReason)`:

1. read the latest run for the snapshot; aggregate is already stored as `outcome`;
2. if `blocked` and `overrideReason` is null/blank → `VettingBlockedException` → `409` with a
   `ProblemDetail` naming the blocking connectors;
3. otherwise proceed exactly as today, and when an override was used, stamp
   `override_by/at/reason` on the run and append a `snapshot-approve-override` ledger entry
   carrying the reason.

**Why a blanket override and not a proper waiver:** #28 is the waiver feature — scoped to a
finding, with an expiry and an approver. Building half of it here would mean building it
twice. The v1 override is the smallest thing that is auditable: it cannot be silent (the
reason is mandatory and both the run and the ledger record it), and #28 replaces it by making
the per-finding waiver the way a blocked snapshot becomes clear, leaving the override as the
last-resort path or removing it entirely.

### The ledger gains a `detail` column

An override reason has nowhere to go in `fetch_log` today (`ref` is the served ref; abusing it
would corrupt the export schema for SIEM consumers). One nullable `detail TEXT` column,
exported as a new `AuditEntry` field, is the honest fix and gives #28 and #24 somewhere to put
a waiver id or a revocation reason.

### Scanner honesty

Both connectors are pattern matchers. `secret-scan` has a low false-negative rate for
*shaped* credentials (AWS, GitHub, PEM) and a real one for anything unshaped; the entropy rule
catches assignment-shaped blobs and nothing else. `prompt-injection` is a triage signal: it
finds known markers and obfuscation, and an attacker who paraphrases walks past it. The docs
say exactly this, and say the LLM connector is the answer — because a scanner that is trusted
beyond its evidence is worse than no scanner.

## Risks / Trade-offs

- **A wedged connector leaks a thread.** Mitigated by a bounded daemon executor and a
  documented timeout; a connector that ignores interruption costs a thread until the JVM
  restarts. Accepted: the alternative (process isolation) is the sandbox-runner connector,
  which is a separate feature.
- **Vetting runs synchronously inside ingestion**, so a slow chain slows the ingest request.
  Two fast in-process connectors make this a non-issue today; the async seam (`PENDING`) is
  the escape hatch when it stops being one.
- **The override is blunt.** Deliberate; see above. It is audited, and it is the seam #28
  replaces.
- **False positives will block real snapshots.** That is the fail-closed trade, and the
  override with a reason is the pressure valve until waivers land.

## Migration Plan

Single deployment. `V1__init.sql` is edited in place (Testcontainers recreate the schema each
run; the project has not cut a release with data to migrate). Existing snapshots have no run
and therefore aggregate to `blocked`: approving one requires a reason, which is the intended
fail-closed behaviour and is documented in the guide.

## Open Questions

- Should a `WARN`-only chain require an acknowledgement (not a reason) from the reviewer?
  Deferred to #28, where "accepted risk" gets a real vocabulary.
- Where connector configuration (per-connector enable/disable, severity overrides) should
  live once there are more than two connectors — properties, or the policy engine of
  ARCHITECTURE.md §4. Deferred until the third connector exists.
