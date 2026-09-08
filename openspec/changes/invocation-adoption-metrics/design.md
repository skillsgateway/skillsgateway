# Design: invocation-adoption-metrics

## Context

- `AdoptionService` — GW_0075 — *Adoption reporting from the fetch ledger* and
  GW_0076 — *Staleness reporting against the served tip* — is two ledger
  aggregations plus a read of the served tip. It writes nothing, and its Javadoc says so as a property rather
  than an observation. `FetchLogRepository.adoptionSince` already returns
  `(marketplace, sha, fetches, identities, lastFetch)` per SHA — the report's
  granularity floor today is the snapshot.
- `SnapshotContentService.content(snapshotId)` — GW_0020 — *Snapshot content
  inventory* — already walks a
  snapshot's pinned commit tree and returns the manifest's plugins with the skills
  found under each plugin's `skills/` directory. It is exactly the inventory this
  change needs, and it exists.
- `SnapshotPreviewService` / GW_0080 — *Snapshot file inspection* reads file
  **bytes** out of the pinned commit and is gated to admin or the snapshot's
  approver, because those bytes are held quarantine content.
- GW_0031 — *Snapshot retention policy evaluation* can reclaim a snapshot's
  objects. The ledger entry for a fetch of that SHA survives; the tree behind it
  may not.
- ADR 0016 is the decision this change implements. Where the two disagree, the
  ADR wins and this document is wrong.

## Goals / Non-Goals

**Goals**

- Answer *which identities hold skill X* directly, from data no client can move.
- Make it impossible to read the report as a usage measure — in the payload, not
  in the docs.
- Make "the gateway ingests no client-reported data" a property something checks.

**Non-Goals**

- Any invocation figure, from any source. ADR 0016.
- A new store of snapshot content. See decision 3.
- Per-team anything. The gateway has no team concept (GW_0075's rationale).

## Decisions

### 1. Presence is derived, per request, from the ledger joined to the pinned tree

For each distinct SHA the ledger records a content-transferring fetch of in the
window, resolve the snapshot's plugins and skills through
`SnapshotContentService`, then re-key the existing per-SHA measures onto
`(marketplace, plugin, skill)`.

Nothing is recorded. That is not thrift — it is the property GW_0075 was written
to have, and a report that starts writing rows is a report that can disagree with
the ledger it claims to summarise.

The alternative — materialising a `snapshot_contents` table at ingestion time —
is faster and is **not** proposed for this increment. It adds a migration, a
backfill for every existing snapshot, and a second copy of an answer the commit
tree already holds authoritatively. Decision 3 explains why the derived path is
affordable.

### 2. The report must not become a way to enumerate held content

This is the adversarial point of the change and it is easy to miss, because the
new report is auditor-gated (like the ledger) while the tree it reads is
approver-gated (GW_0080, because it is quarantine content).

Two constraints keep those from colliding:

- **Only SHAs the ledger records a served fetch of are resolvable through this
  report.** The SHA set is the window's ledger rows, never a caller-supplied
  snapshot id. Content that was fetched from the facade was, by invariant 1,
  approved and served — so nothing here names anything the facade did not already
  hand to an authenticated reader.
- **Names and paths only; never bytes.** The report carries plugin name, skill
  name and the `SKILL.md` path. A caller who wants the content still goes through
  GW_0080 and is still gated as an approver.

An auditor who could name the skills inside a *held* snapshot would have crossed
GW_0080's boundary through a report that never mentions it. The negative test for
that belongs in the definition of done, not in a review comment.

### 3. Per-SHA content resolution is cached, and the cache never invalidates

A pinned commit's content is immutable — that is what pinning means. So a cache
keyed by `(marketplace, sha)` is correct forever and needs no eviction policy
beyond size.

That collapses the cost argument. The number of distinct SHAs in a 30-day window
is bounded by how often the estate *approves*, not by how often it *fetches*, and
a tree walk is paid once per SHA for the life of the process. A bounded LRU with
a size cap and a metric on hits is the whole mechanism.

If the cap is ever the wrong shape, decision 1's rejected alternative is the
escalation, and it should be taken with a measurement rather than a hunch.

### 4. A SHA whose tree is gone is reported as unresolvable, never dropped

Retention can reclaim the objects behind a SHA the ledger still records. The
report says so — the SHA appears with its ledger measures and an explicit
"content not resolvable" marker — because the alternative is a recall answer that
silently omits exactly the oldest installs, which are the ones most likely to be
running something that was later revoked.

Same rule for a snapshot whose manifest no longer parses: reported as
unresolvable, not as containing nothing. Absence of evidence is not evidence of
absence, and a recall query is precisely where that distinction bites.

### 5. The uniformity caveat is a field, not a footnote

Every skill in a snapshot shares that snapshot's fetch and identity counts,
because a git fetch is all-or-nothing. Nobody reading a table of skills with
per-skill numbers will infer that unaided; several people will infer the
opposite.

So the response carries it structurally — the per-skill measures are named for
what they are (*identities holding*, *snapshots delivering*), not `fetches` and
`usage`, and the payload states that counts are uniform within a snapshot. The
portal section says it in one line above the table. The API page says it in a
`!!! warning`, next to the existing "Identities, not teams" note, which is the
established precedent for exactly this kind of correction.

The measure that *is* discriminating between skills, and is worth surfacing for
that reason, is **how many distinct snapshots delivered a skill and over what
span** — a skill present since the first snapshot differs from one added last
week, and that difference is real.

### 6. GW_0215 is verified by a test that fails when someone adds an endpoint

A requirement stating that no surface accepts client-reported telemetry is only
worth having if something notices when one appears. The verification is a
structural assertion over the running application's mapped request handlers:
every `POST`/`PUT`/`PATCH` route outside the known operator, publisher and machine
surfaces is a failure, and OTLP's own paths (`/v1/metrics`, `/v1/logs`,
`/v1/traces`) are asserted absent by name.

That is a test that fails on a *future* change rather than on this one, which is
the point. A negative requirement with no such test is a comment.

### 7. Where correlation happens, if an organisation wants it

Outside the gateway. The report carries the marketplace, plugin and skill names as
served, which is the join key an operator would use against client telemetry in
their own metrics backend. Whether that join is currently possible is an upstream
question and ADR 0016 answers it: for plugin-delivered content it is not, because
the client redacts third-party plugin and skill names.

The gateway ships the half that is knowable and does not pretend about the other
half. It does not ship a Grafana dashboard, a recording rule, or a correlation
helper — those would be building the join the ADR just said cannot currently close.

## Risks / Trade-offs

- **The report will be read as a usage measure anyway.** Decision 5 is the whole
  mitigation and it is a naming discipline, not an enforcement. Accepted, because
  the alternative — not shipping presence — leaves the recall query answered by an
  operator with a spreadsheet.
- **Tree resolution puts an adoption read on the git storage path.** On the
  object-store backend a cold cache means object reads on a report request.
  Bounded by decision 3, but it is a new dependency for a report that previously
  touched only PostgreSQL and the served tips, and a storage outage now degrades
  the report rather than only the facade. It degrades to decision 4's
  unresolvable marker rather than to an error.
- **GW_0215 is a negative and negatives rot.** Decision 6 is the answer; if that
  test is ever weakened, the requirement is gone whatever the YAML says.
- **The upstream redaction could change without anyone noticing**, at which point
  ADR 0016's rejected option 3 becomes live. Nothing here detects that; it needs a
  human re-reading the vendor documentation, and the ADR names it as a reopening
  condition so there is somewhere for that to land.

## Migration Plan

None. Additive report, no schema change, no configuration change, no behaviour
change to any existing endpoint. A deployment that never calls the new path is
unaffected.

## Open Questions

- **Machine reach**: does the presence report sit under the existing
  `adoption:read` scope, or its own? It enumerates identities against named
  content, which is a slightly sharper capability than the marketplace-level
  report — argues for its own scope; but a second scope for one path argues for
  reuse. Decide in the implementing PR, and whichever way it goes, the estate
  declaration obligation in `CLAUDE.md` applies to a new grantable scope.
- **Window semantics**: GW_0075 windows and GW_0076 deliberately does not, because
  staleness is a property of an identity's latest state. Presence is arguably the
  same — *holds*, not *held during a window* — which would make the window
  optional rather than defaulted. Leaning window-free with an optional bound, but
  it is a product call.
