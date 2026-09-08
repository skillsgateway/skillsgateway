# Proposal: decompose-resolved-closure-requirement

## Why

Issue [#299](https://github.com/skillsgateway/skillsgateway/issues/299) asks the
project to decompose one multi-behaviour requirement per change, following the
pattern PR #324 established for `GW_INGEST_0026` — *Resource-bounded source
resolution*. That change's own design named `GW_INGEST_0030` — *The resolved
closure is recorded as an immutable domain object of the snapshot* — as the
highest-value candidate left in #299's list, and this change does exactly that
one requirement and nothing else.

`GW_INGEST_0030`'s single description sentence enumerated seven distinct,
independently verifiable guarantees behind one id and one SVC:

1. what a closure records, and that every value is a copy taken at ingestion;
2. the upstream commit is recorded on every snapshot, resolved or not;
3. the closure is written in the same transaction as its snapshot;
4. a closure is never modified, and is removed only when its snapshot is purged;
5. the closure appears in the snapshot's provenance;
6. the closure is exposed to the policy gate as facts;
7. the snapshots containing a given clone URL are answerable by one query.

These clauses have different audiences and different failure modes. A security
team asking "which approved snapshots include this repository" cares about
guarantee 7 and nothing else; a policy author writing a rule over external
content cares about 6; a reviewer reading a snapshot's provenance cares about
5. Each can regress independently of the rest, and one pass/fail verdict across
all seven cannot say which one broke.

## What Changes

**This change decomposes exactly one requirement and nothing else.**

- **`GW_INGEST_0030` keeps its id and gains seven children**,
  `GW_INGEST_0030.1` through `GW_INGEST_0030.7`, each linked to it by
  `references.requirement_ids`. The rule applied is **one child per
  independently-verifiable guarantee** — not one child per recorded field: the
  closure- and member-level fields enumerated in the original text stay
  together as a single child, `GW_INGEST_0030.1`, because they are one
  behaviour (what gets copied into the closure) rather than seven.
- **The parent's own text becomes the ontological claim that is true across the
  whole set and is not any single child's**: the resolved closure is a
  first-class domain object of the snapshot it belongs to, not metadata kept
  beside it — it exists only together with its snapshot, shares the snapshot's
  own immutability, and is what every later consumer reads to know what a
  snapshot actually serves. That claim was previously stated only in the
  requirement's rationale; it is now the parent's description.
- **One parent SVC and seven child SVCs.** `SVC_GW_INGEST_0030` keeps its id
  and now verifies the cross-cutting claim — that the closure exists exactly
  when its snapshot does, at creation and at purge; `SVC_GW_INGEST_0030.1`
  through `SVC_GW_INGEST_0030.7` each verify one guarantee.
- **Every annotation moves in the same commit.** Nine `@Requirements`
  annotations move across `SnapshotClosure`, `SnapshotClosureRepository`,
  `SnapshotRepository`, `IngestionService`, `ApprovalService` and
  `SnapshotFactsService`; sixteen `@SVCs` annotations move across
  `SnapshotClosureTests` and `SnapshotClosureDigestTests`; one JSDoc
  `@Requirements` tag moves in the portal's `marketplaces.tsx`. Prose
  mentions of the id in Java, SQL and TypeScript comments, and in the live
  `marketplace-ingestion` OpenSpec capability spec, move to the matching
  child.

Observable behaviour of the gateway is **unchanged**. No production code path,
no configuration key, no API response and no portal page changes. What the
change buys is that the traceability report now names which of the seven
guarantees regressed, rather than reporting one verdict over all of them.

## Capabilities

### Modified Capabilities

- `marketplace-ingestion`: `GW_INGEST_0030` — *The resolved closure is recorded
  as an immutable domain object of the snapshot* — is now a parent with seven
  children. No behaviour delta.

## Out of scope

- **The other candidates in #299 are not touched.** `GW_INGEST_0024` and five
  unevaluated candidates remain; this change does not resolve #299.
- **No sweep, no tooling.** Nothing enforces the decomposition convention
  automatically; the reference is the shipped `reqstool-conventions` skill and
  the archived `reqstool-decomposition-conventions` change.
