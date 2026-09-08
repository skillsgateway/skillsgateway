# Design: decompose-resolved-closure-requirement

## Context

- `docs/reqstool/requirements.yml` already carries one worked example of the
  decomposition convention: `GW_INGEST_0026` — *Resource-bounded source
  resolution* — split into eight children by PR #324, archived as
  `reqstool-decomposition-conventions`. That change's own design named
  `GW_INGEST_0030` as the next candidate, ahead of `GW_INGEST_0024`, and this
  change is that one.
- `docs/reqstool/requirements.yml` and `software_verification_cases.yml` each
  carried 180 ids before this change: 173 after PR #324, plus seven added by
  intervening PRs, all still domain-prefixed by the
  `refactor(reqstool): domain-prefix every requirement and SVC id` rename that
  renumbered nothing and added no requirement.

## Goals / Non-Goals

**Goals**

- One requirement decomposed properly, following the established pattern.
- The traceability report can name which of the seven guarantees regressed,
  which it could not before.
- No behaviour delta anywhere in the gateway.

**Non-Goals**

- Decomposing `GW_INGEST_0024` or any other remaining #299 candidate.
- Any tooling, lint or CI check that enforces the convention.
- Renumbering anything.

## Decision 1 — one child per independently-verifiable guarantee, not one per field

`GW_INGEST_0030`'s description read as one sentence enumerating what a closure
records, followed by six more `shall` clauses about how it behaves. The
temptation with an enumeration is to split it field by field, as if each
closure-level or member-level field were its own requirement; that was
explicitly rejected. A single member's eleven fields — plugin name, source
type, declared source, declared ref, declared sha, clone URL, resolved sha,
tree, graft path, object count, inflated bytes — are one behaviour: what gets
copied into the closure as a value. Splitting them would weaken the
requirement into eleven ids that could each be "complete" while the closure as
a whole silently dropped a field nothing individually claims. `GW_0158`'s
design made the same call for its budget fields taken together as
`GW_0158.1`–`.7`; the field list here gets the same treatment, as one child:

| Child | Guarantee |
| --- | --- |
| `GW_INGEST_0030.1` | What a closure records — every closure- and member-level field — and that each is a value copy taken at ingestion, never a reference into mutable configuration |
| `GW_INGEST_0030.2` | The upstream commit is recorded on every snapshot, resolved or not, equal to the snapshot's own commit when nothing was resolved |
| `GW_INGEST_0030.3` | The closure is recorded in the same transaction as its snapshot |
| `GW_INGEST_0030.4` | A recorded closure is never modified, and is removed only when its snapshot is purged |
| `GW_INGEST_0030.5` | The closure, the served commit and the upstream commit appear in the snapshot's provenance |
| `GW_INGEST_0030.6` | The resolved source count and each plugin's origin, clone URL and resolved commit are presented as facts to the policy gate |
| `GW_INGEST_0030.7` | The snapshots whose closure contains a given clone URL are answerable by one query |

Why this grouping and not something coarser (say, "storage" versus
"consumers"): each of these seven is read by a different audience and fails
independently in the actual code. `.5` and `.6` share almost no implementation
— one is `ApprovalService.provenance`, the other `SnapshotFactsService.build`
— and a regression in either is invisible to a test of the other. `.3` and
`.4` both concern the closure's lifecycle relative to its snapshot but are
verified by opposite operations (creation versus purge) and sit in different
methods, so collapsing them would hide which half of "born and dies with its
snapshot" broke.

## Decision 2 — what the parent keeps

The convention says the parent must read on its own and state what is true
across the whole set. The original requirement's rationale, not its
description, already contained that idea: value copies are what separate "the
mutable source... from the immutable artifact that was actually vetted and
approved." Read as a whole, the seven clauses are really saying one thing from
seven angles — the closure is not detachable metadata that happens to sit
beside a snapshot; it is part of what the snapshot *is*, so it can only exist,
change and disappear exactly as the snapshot does. That is now the parent's
description:

> The system shall treat the closure a snapshot's resolved external plugin
> sources form as a first-class domain object of that snapshot rather than
> metadata kept beside it: existing only together with the snapshot it belongs
> to, carrying the snapshot's own immutability, and read by every later
> consumer... as the record of what that snapshot actually serves.

This is a real, separately-stated claim, not a placeholder restatement of the
children's titles — and unlike `GW_INGEST_0026`'s parent (two operational
guarantees: accept inside every bound, name the bound on refusal), this
parent's claim is architectural. That changes what its SVC can honestly test;
see Decision 3.

## Decision 3 — the parent SVC verifies existence, not a fifth operational claim

`GW_INGEST_0026`'s parent SVC was straightforward because the parent stated
two genuinely new operational behaviours (accept inside every bound; name the
bound on refusal) with tests that exercised exactly those two things and
nothing else. `GW_INGEST_0030`'s parent makes an ontological claim instead —
"this is a domain object, not detachable metadata" — which is not a fifth
independent behaviour a test can exercise in isolation; every child's test
already demonstrates one facet of it. Two of those facets bracket the whole
claim rather than illustrating a slice of it: that the closure comes into
being together with its snapshot (`a_resolved_ingestion_records_the_closure_
with_the_snapshot`, in `SnapshotClosureTests`) and that it goes out of being
only when its snapshot does (`purging_the_snapshot_removes_its_closure`, same
class). Together they show the closure's existence is bounded exactly by the
snapshot's own lifecycle — born with it, gone with it — which is what "domain
object of the snapshot, not metadata beside it" actually cashes out to as an
observable behaviour. `SVC_GW_INGEST_0030` is retargeted to describe exactly
that pairing and stays on those two test methods, alongside their own child
SVCs (`.1`/`.3` on the first, `.4` on the second) — following the same
mechanical rule PR #324 established: reqstool resolves `@SVCs` to a JUnit
`Class.method`, so the parent SVC sits on methods, never on the class.

This was considered and rejected: giving the parent no SVC at all, on the
theory that the ontological claim is not independently testable. Rejected
because reqstool's own convention requires every requirement — parent
included — to carry at least one SVC for full traceability, and because the
two tests above are not a rubber stamp; they are the one pair of assertions in
the suite that actually brackets a snapshot's and its closure's shared
lifecycle rather than any single guarantee within it.

## Annotation moves

`@Requirements`:

| Element | Before | After |
| --- | --- | --- |
| `SnapshotClosure.digest` | `GW_INGEST_0030` | `GW_INGEST_0030.1` |
| `SnapshotClosureRepository.record` | `GW_INGEST_0030` | `GW_INGEST_0030.1, GW_INGEST_0030.3` — writes every recorded field, inside the snapshot's transaction |
| `SnapshotClosureRepository.findBySnapshot` | `GW_INGEST_0030` | `GW_INGEST_0030.1, GW_INGEST_0030.4` — reads the recorded value back, including its absence after a purge |
| `SnapshotClosureRepository.snapshotsContaining` | `GW_INGEST_0030` | `GW_INGEST_0030.7` |
| `SnapshotRepository.create` (the `@Transactional` overload taking a closure) | `..., GW_INGEST_0030` | `..., GW_INGEST_0030.2, GW_INGEST_0030.3` — records the upstream commit on every snapshot it creates and writes the closure in the same transaction |
| `IngestionService.ingestLocked` | `..., GW_INGEST_0030` | `..., GW_INGEST_0030, GW_INGEST_0030.3` — kept as the parent capability alongside `.3`: it is the orchestration point where the closure and its snapshot come into being together |
| `IngestionService.serve` | `..., GW_INGEST_0030` | `..., GW_INGEST_0030.1` — builds the closure value from the resolved sources |
| `ApprovalService.provenance` | `GW_INGEST_0004, GW_INGEST_0030` | `GW_INGEST_0004, GW_INGEST_0030.5` |
| `SnapshotFactsService.build` | `GW_APPROVAL_0007, GW_INGEST_0030` | `GW_APPROVAL_0007, GW_INGEST_0030.6` |
| `marketplaces.tsx` `ProvenanceDialog` (JSDoc) | `GW_INGEST_0030` | `GW_INGEST_0030.5` |

Prose mentions of the bare id in comments move to the matching child in
`SnapshotClosureRepository`'s class javadoc (`.4`), `SnapshotClosure`'s class
javadoc (`.1`), `ExternalSourceResolver.Resolved`'s javadoc (`.1`), and
`V1__init.sql`'s `upstream_sha` column comment (`.2`) and `snapshot_closures`
table comment (`.1`, `.3`, `.4`). `AbstractExternalSourceTest`'s javadoc, which
names the general test arrangement every closure test shares rather than one
guarantee, keeps the bare parent id.

`@SVCs`: the eight `SnapshotClosureTests` methods and the seven
`SnapshotClosureDigestTests` methods (six `@Test` plus the parameterized
`every_member_field_is_an_input`) are redistributed one guarantee per child,
with the two lifecycle-bracketing tests in `SnapshotClosureTests` keeping
`SVC_GW_INGEST_0030` alongside their own child SVC. No test was added,
removed, weakened or renamed.

## Risks

- **Merge conflicts.** Both `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` grow ids near where other in-flight
  branches might append; the usual "keep both sides" resolution applies,
  followed by a duplicate-id check.
- **No new top-level id was minted.** This change consumes no `GW_INGEST_NNNN`
  number and cannot collide with a reserved range.
