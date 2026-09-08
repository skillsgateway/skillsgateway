# Design: external-source-closure

This is the executable specification for the change — the artifact to review in
place of the implementation (`.claude/skills/old-coder`, Tier 3: it changes what
`ApprovalService` will publish and what ingestion persists).

## The requirements this change works against

| Id | Title | Role here |
| --- | --- | --- |
| GW_INGEST_0030 | The resolved closure is recorded as an immutable domain object of the snapshot | added |
| GW_APPROVAL_0013 | Approval requires a complete closure | added |
| GW_INGEST_0004 | Snapshot provenance | unchanged; its response grows additively |
| GW_APPROVAL_0007 | Policy facts | unchanged; the fact set grows |
| GW_INGEST_0024 | Deterministic composite snapshot with a gateway-local manifest | unchanged; the closure is a second, queryable record of what its commit message already says |
| GW_INGEST_0027 | A failed resolution leaves the snapshot rejected and nothing half-resolved | unchanged; a rejected resolution records no closure |
| GW_RETENTION_0004 | Compaction | unchanged; the closure goes with the purge by cascade |

## Context

- `ExternalSourceResolver.resolve` returns `Resolution(List<Resolved>, violation)`
  where `Resolved(pluginName, cloneUrl, sha, tree)`; it also measures each source
  (`ResolutionBudget.Measurement`: received bytes, inflated bytes, objects,
  largest blob, depth) and discards the measurement once the budget has accepted
  it.
- `ManifestRewriter.rewrite` grafts each `Graft(pluginName, cloneUrl,
  resolvedSha, tree)` at `_plugins/<pluginName>` and stamps
  `Transformer-Version` into the commit message.
- `IngestionService.ingestLocked` pins the served commit, dedupes on
  `(marketplace, sha)`, inserts the row with `SnapshotRepository.create`, vets if
  held. Nothing records the upstream SHA; ADR 0011 lists that as a deviation to
  be closed "when the closure tables arrive".
- `ApprovalService.doApprove`: state machine → (if decidable) waiver evaluation
  and the vetting gate, with the GW_VETTING_0028 override, → policy gate → release age →
  four-eyes → `decide` → `publish`. Refusals from the release-age and four-eyes
  gates are written to the ledger as `snapshot-approval-refused` before they are
  raised.
- `RetentionService` garbage-collects a quarantine repository after purging.
  After scaffolding refs are pruned the *external commit object* is reachable
  from nothing — only its tree is, through the composite — so anything that
  later needs to verify a member must not depend on that commit still existing.
- `CelPolicy` declares every fact variable as `map<string, dyn>`; adding keys is
  additive and needs no declaration change.
- The schema is a single `V1__init.sql` by project convention.

## Goals / Non-Goals

**Goals**

- The closure is rows, not text: immutable value copies, written with the
  snapshot, gone with the snapshot, queryable by clone URL and resolved commit.
- `ApprovalService` verifies, rather than assumes, that the closure it publishes
  is the closure the commit holds — on every path, before every other gate.
- Every surface that already shows a snapshot's origin shows its closure:
  provenance, the policy facts, the portal's provenance dialog.
- Everything else is unchanged by construction. The snapshot's identity is still
  the composite; vetting, the facade, retention and the ledger read nothing new.

**Non-Goals**

- No closure-aware re-vetting in `RevetService`. The query it needs
  (`snapshotsContaining`) ships and is tested; the wiring is the fast-follow the
  ADR's increment table names, and it is a change to what the sweep *does*, which
  deserves its own spec.
- No closure sharing across snapshots. See decision 2.
- No SSRF work: the egress proxy and connect-time pinning are #17's third item.
- No `git`/`git-subdir`, no declared `ref`/`sha` pinning. The member columns for
  a declared pin exist so the schema does not move when that increment lands;
  they are `NULL` until it does.

## Decisions

1. **Schema.**

   ```sql
   -- on snapshots
   upstream_sha TEXT NOT NULL      -- = sha unless a composite was synthesised

   CREATE TABLE snapshot_closures (
       id BIGSERIAL PRIMARY KEY,
       snapshot_id BIGINT NOT NULL UNIQUE REFERENCES snapshots (id) ON DELETE CASCADE,
       digest TEXT NOT NULL,
       upstream_sha TEXT NOT NULL,
       transformer_version TEXT NOT NULL,
       created_at TIMESTAMPTZ NOT NULL
   );
   CREATE TABLE snapshot_closure_members (
       id BIGSERIAL PRIMARY KEY,
       closure_id BIGINT NOT NULL REFERENCES snapshot_closures (id) ON DELETE CASCADE,
       parent_member_id BIGINT REFERENCES snapshot_closure_members (id),
       plugin_name TEXT NOT NULL,
       source_type TEXT NOT NULL,
       declared_source TEXT NOT NULL,
       declared_ref TEXT,
       declared_sha TEXT,
       clone_url TEXT NOT NULL,
       resolved_sha TEXT NOT NULL,
       tree_sha TEXT NOT NULL,
       graft_path TEXT NOT NULL,
       object_count BIGINT NOT NULL,
       inflated_bytes BIGINT NOT NULL,
       UNIQUE (closure_id, graft_path)
   );
   CREATE INDEX idx_snapshot_closures_digest ON snapshot_closures (digest);
   CREATE INDEX idx_snapshot_closure_members_source
       ON snapshot_closure_members (clone_url, resolved_sha);
   ```

   Against the review's field list: `closures.closure_digest` → `digest`;
   `closure_nodes` → `snapshot_closure_members` with every named field (plugin
   name, source type, declared url/ref/sha, resolved sha, size/object counts) plus
   `tree_sha` and `graft_path`, which the assertion in GW_APPROVAL_0013 needs;
   `closure_edges` → `parent_member_id`, the adjacency form of the same
   relation: the closure is a tree by construction (each member is grafted at
   exactly one path), so adjacency is exact, it is `NULL` for every member at
   depth 1, and it does not change shape if depth ever does. *Policy version* is
   not an input to the digest, for the reason #257 recorded for the composite
   SHA: the transformer version decides the output bytes, the admission
   configuration does not, and hashing it in would make provenance change when
   an operator allowlists an unrelated host.

2. **One closure per snapshot, owned by it — so the foreign key is on the
   closure, not on `snapshots`.** The review sketched `snapshots.closure_id`,
   which presumes closures shared between snapshots and deduplicated by digest.
   Sharing buys a few rows and costs a lifecycle: a shared row cannot cascade
   from a purge, and "immutable" would have to survive one of its owners being
   deleted. Ownership makes `ON DELETE CASCADE` the whole retention story and
   keeps `RetentionService` untouched. The digest is still recorded and indexed:
   it is the *query* key for "same closure, different marketplace" and the value
   a future signature is over — it is not a storage key.

3. **No row is the empty closure.** A local-only snapshot, and a rejected
   resolution recorded at the upstream commit, get no `snapshot_closures` row.
   That is also the state of every snapshot that predates this change, which is
   why it must mean "nothing was resolved" rather than "unknown": the
   completeness gate then treats such a snapshot as complete exactly when its
   served manifest declares nothing under `_plugins/` and its tree has no
   `_plugins/` entry — true for every pre-existing snapshot, false for a
   composite whose closure row is missing.

4. **`upstream_sha` is `NOT NULL` and equals `sha` unless a composite was
   synthesised.** The column ADR 0011 §2 asked for and #257 deferred. For a
   rejected resolution the row is already recorded at the upstream commit, so
   the two are equal there as well; only a held composite has them differ, and
   there the parent commit is the same value, so the column and the git object
   are two records of one fact.

5. **The closure is written in the snapshot's transaction.** `SnapshotRepository
   .create` takes the upstream commit and an optional `SnapshotClosure`, and
   inserts the row, the closure and its members under one `@Transactional`. A
   `DuplicateKeyException` from the race the existing code already handles rolls
   the whole thing back. A composite snapshot without its closure is therefore
   not a state the gateway can produce — which is exactly the state GW_APPROVAL_0013
   exists to refuse if anything else ever produces it.

6. **Members record the grafted tree, and the gate compares trees.** A member's
   `resolved_sha` is provenance: which commit of the external repository this
   is. Its `tree_sha` is what the composite actually holds at `graft_path`. The
   gate never resolves `resolved_sha` in quarantine — after scaffolding is
   pruned and retention's garbage collection runs, that commit object may
   legitimately be gone, and a gate that then refused a correct snapshot would
   be a gate nobody could keep enabled.

7. **The completeness assertion is a bijection, not a presence check.** Let *M*
   be the plugins whose `source` in the served manifest is
   `./_plugins/<name>`, *C* the closure's members by `graft_path`, and *T* the
   entries of the pinned commit's top-level `_plugins` tree. Complete means:
   every *M* has exactly one *C* at `_plugins/<name>`; every *C* is in *M*; every
   *C*'s path is in *T* as a tree whose id equals `tree_sha`; every *T* is some
   *C*; every *C* has a well-formed `resolved_sha`. The refusal names every
   discrepancy found rather than the first, so a reviewer looking at a tampered
   estate sees the shape of the tampering. The review asked for "every closure
   row has a `resolved_sha`" — the schema makes a `NULL` impossible, so the gate
   checks the stronger thing, that it parses as an object id.

8. **The gate runs first inside the decidable branch, on every path.** Before
   waiver evaluation, so before the GW_VETTING_0028 override can lift anything; the
   override lifts the *vetting* gate and nothing else. A revoked snapshot being
   re-approved passes through the same branch. The state-machine check stays
   ahead of it, for the reason the existing comment gives: a snapshot that is not
   decidable is refused for a reason that has nothing to do with its closure.

9. **Refusal is a ledger entry and a dedicated exception.**
   `ClosureIncompleteException(snapshotId, List<String> discrepancies)`, mapped
   to 409 with title *Snapshot closure is incomplete* and the discrepancies as a
   property; the ledger gets `snapshot-approval-refused` with detail
   `closure-incomplete: <discrepancies>` before the exception is raised, exactly
   as the release-age and four-eyes refusals do. There is no override for it.

10. **Provenance is extended, not renamed.** `Provenance.upstreamSha` was
    populated from `snapshot.sha()`, which was the upstream commit until #257 and
    has been the served commit for a composite since — a latent inaccuracy the
    field's own schema description (`upstream commit SHA`) contradicts. It is now
    populated from `snapshots.upstream_sha`, which is the same value for every
    snapshot that existed before this change and the correct value for a
    composite. `sha` (served) and `closure` (nullable object: digest, upstream
    commit, transformer version, members) are added. `Snapshot` gains
    `upstreamSha`. Nothing is removed or renamed; the contract stays additive.

11. **Facts.** `snapshot.upstreamSha` (string), `snapshot.externalSources`
    (int), and on each plugin `origin` (`local` | `external`), `upstreamUrl`
    (clone URL or `""`) and `resolvedSha` (or `""`), matched from the plugin's
    `source` to a member's `graft_path`. That is the review's "risk tiers arrive
    as facts" item at the size it can be delivered here: a rule such as
    `plugins.exists(p, p.origin == "external" && !p.upstreamUrl.startsWith("https://github.com/acme/"))`
    is now expressible. Skill-frontmatter capability extraction is not this
    change.

12. **Estate.** Closures are derived runtime state produced by ingestion. An
    operator declares marketplaces; the gateway derives closures. No estate
    object, no `skills-gateway.estate.*` change, no new role.

13. **The schema change is folded into `V1__init.sql`.** The project's standing
    convention, stated in `code-conventions`: one migration until the owner says
    otherwise, because the test database is recreated every run and there is no
    deployed schema to migrate forward. The task's "reversible data migration for
    snapshots that already exist" is answered by decisions 3 and 4: a row without
    a closure is the empty closure, and `upstream_sha` is the snapshot's own
    commit unless a composite was synthesised. When V1 is ever split, the forward
    step is `ALTER TABLE snapshots ADD COLUMN upstream_sha TEXT; UPDATE snapshots
    SET upstream_sha = sha; ALTER ... SET NOT NULL` — no composite snapshot can
    predate #257, and every composite since carries its upstream commit as its
    parent, from which the closure is recoverable — and the reverse is dropping
    three objects.

## Failure model (Tier 3)

| Mode | How it hurts | The layer that catches it |
| --- | --- | --- |
| Closure written without the snapshot, or the snapshot without its closure | provenance names content that was never pinned, or a composite is served with no record of what it contains | decision 5 (one transaction); `ClosureCompletenessTests` (no closure ⇒ refused) |
| A member row removed, altered or re-pathed after ingestion | approval publishes content whose provenance is wrong | `ClosureCompletenessTests`: each tampering refused; the refusal on the ledger |
| The snapshot row re-pointed at a commit lacking the grafts | the closure claims trees the served commit does not hold | `ClosureCompletenessTests`: refused |
| A `_plugins/` entry no member accounts for | served content outside the recorded closure | the *T ⊆ C* direction of decision 7; mutation on that branch |
| The gate consults a commit object garbage collection may have removed | a correct snapshot becomes unapprovable | decision 6; the gate reads only trees reachable from the composite |
| The override path bypasses the gate | an administrator publishes unrecorded content | `ClosureCompletenessTests`: refused under `ApprovalOverride.ofVettingFailure` |
| Purge leaves closure rows behind | unbounded growth, provenance for content that no longer exists | `ON DELETE CASCADE`; `SnapshotClosureTests` purge case |
| The digest silently stops depending on a field | two different closures look identical | `SnapshotClosureDigestTests` per field; mutation |
| `upstreamSha` in provenance regresses to the served commit | the field lies for composites | `SnapshotClosureTests` provenance case; `ApprovalTests` unchanged for local-only |
| Facts omit a plugin's origin | a rule over external content cannot see it | `SnapshotClosureTests` facts case |

## Spec ↔ test mapping

| Requirement | Test(s) |
| --- | --- |
| GW_INGEST_0030 — closure recorded, immutable, queryable, surfaced | `SnapshotClosureDigestTests` (unit), `SnapshotClosureTests` (integration) |
| GW_APPROVAL_0013 — approval requires a complete closure | `ClosureCompletenessTests` (integration, tamper-based) |
| GW_INGEST_0004 — provenance (unchanged for local-only) | `ApprovalTests.provenanceOfApprovedSnapshotIsRetrievable`, unmodified |
| GW_INGEST_0024, GW_INGEST_0027 — unchanged | `ExternalSourceResolutionTests`, unmodified |

## Setup plan

Branch `feat/external-source-closure` from `main`, in this worktree. No new
dependency: SHA-256 from the JDK, JGit tree walks already in use, `JdbcClient`
for the tables. The negative tests tamper through `JdbcClient` and through the
snapshot row, because production code cannot construct the states they need.
`mutants.sh` is added beside this design, in the shape of #257's runner.

## Open questions for the owner

1. **Closure sharing.** Decision 2 owns the closure per snapshot. If cross-
   marketplace dedupe of closure *rows* matters later, the digest index is the
   join key and the change is a `closure_id` on `snapshots` plus a reference
   count — but it should be its own decision.
2. **Blast-radius wiring.** `SnapshotClosureRepository.snapshotsContaining` is
   the query; making `RevetService` use it (a manual "re-vet everything that
   contains X" endpoint, or a hook on a compromised-source list) is the
   fast-follow. Is it the next increment of #17 or its own issue?
