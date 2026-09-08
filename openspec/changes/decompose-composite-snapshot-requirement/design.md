# Design: decompose-composite-snapshot-requirement

## Context

- `docs/reqstool/requirements.yml` already carries two worked examples of the
  decomposition convention: `GW_INGEST_0026` — *Resource-bounded source
  resolution* — split into eight children by PR #324, and `GW_INGEST_0030` —
  *The resolved closure is recorded as an immutable domain object of the
  snapshot* — split into seven children by PR #333, archived as
  `decompose-resolved-closure-requirement`. That change's own design named
  `GW_INGEST_0024` as the next candidate the issue lists, and this change is
  that one.
- Both files carried 187 ids before this change, unchanged since PR #333
  merged.

## Goals / Non-Goals

**Goals**

- One requirement decomposed properly, following the established pattern.
- The traceability report can name which of the seven guarantees regressed,
  which it could not before.
- No behaviour delta anywhere in the gateway.

**Non-Goals**

- Decomposing any other remaining #299 candidate.
- Any tooling, lint or CI check that enforces the convention.
- Renumbering anything.

## Decision 1 — one child per independently-verifiable guarantee, not one per named refusal

`GW_INGEST_0024`'s description read as four sentences: what the composite
commit's tree and identity are, that it is deterministic and self-describing,
that three named conditions refuse it, and that its own output is checked
before use. The temptation with the third sentence — which names three
distinct refusal conditions in one clause — is to split it into three
children, one per condition. That was rejected, for the same reason PR #324's
design gave for not splitting `GW_INGEST_0026`'s bound fields one by one: a
reserved-directory collision, a malformed name and a duplicate name are one
behaviour — refuse, synthesise no commit — read from three angles, not three
behaviours. Splitting them would weaken the requirement into three ids that
could each be "complete" while a fourth way a manifest-supplied name could
misdirect content went unnoticed, because nothing would any longer claim the
refusal path as a whole. `GW_INGEST_0030`'s design made the same call for its
seven-field member record as a single child, `.1`; the three refusals here get
the same treatment, as one child:

| Child | Guarantee |
| --- | --- |
| `GW_INGEST_0024.1` | The composite's tree holds the upstream content, the grafted sources, and a manifest that points only inside it |
| `GW_INGEST_0024.2` | The snapshot is identified by the synthesised composite commit |
| `GW_INGEST_0024.3` | The upstream commit is the synthesised commit's parent, byte-exact and reachable |
| `GW_INGEST_0024.4` | The same upstream commit, resolved commits and rewrite implementation produce the same composite |
| `GW_INGEST_0024.5` | The commit records the upstream commit, each source, and the rewrite implementation's identity |
| `GW_INGEST_0024.6` | A reserved-directory collision, a malformed plugin name, or a duplicate plugin name is refused with no commit synthesised |
| `GW_INGEST_0024.7` | The rewritten manifest is verified against the local-only manifest policy before the synthesised commit is used |

Why this grouping and not something coarser (say, "shape" versus "safety"):
each of these seven is read by a different audience and fails independently
in the actual code. `.2` and `.3` both concern the composite's relationship to
other commits but are verified by opposite operations — which commit the
snapshot *is* versus which commit is its *parent* — and a regression in either
is invisible to a test of the other. `.5` and `.7` sit in the same method
(`ManifestRewriter.rewrite`) but at opposite ends of it: `.5` is what goes into
the message before the tree is composed, `.7` is the last check run against
the fully rewritten manifest before the commit is returned, and a mutation
that broke the ordering between them — running the local-only check before the
rewrite finished, say — would pass a test of either guarantee alone.

## Decision 2 — what the parent keeps

The convention says the parent must read on its own and state what is true
across the whole set. The original requirement's rationale, not its
description, already contained that idea: the resolved closure — read here as
the composite commit itself, since `GW_INGEST_0024` predates the closure's own
decomposition and used the same "not metadata beside it" language `GW_INGEST_
0030` later reused for the closure's database record — "is not metadata beside
the snapshot, it is the snapshot." Read as a whole, the seven clauses are
really saying one thing from seven angles: the synthesised commit is not a
side artifact that happens to accompany a snapshot recorded some other way; it
*is* the snapshot, so every trust-boundary component that ever asks "what does
this snapshot serve" gets its answer by reading that one commit and nothing
else. That is now the parent's description:

> The system shall treat the commit it synthesises for a manifest with
> resolved external plugin sources as the snapshot itself rather than as
> metadata kept beside a separate snapshot identity: the commit that is
> pinned in quarantine, vetted, approved and served is that one synthesised
> commit, and every component at or behind a trust boundary reads the
> snapshot through it and through nothing else.

This is a real, separately-stated claim, not a placeholder restatement of the
children's titles — and like `GW_INGEST_0030`'s parent, this parent's claim is
architectural rather than a fifth operational behaviour. That changes what its
SVC can honestly test; see Decision 3.

## Decision 3 — the parent SVC brackets the claim end to end, not a fifth operational claim

`GW_INGEST_0026`'s parent SVC was straightforward because the parent stated two
genuinely new operational behaviours with tests that exercised exactly those
two things. `GW_INGEST_0024`'s parent makes an ontological claim instead —
"this commit is the snapshot, not a side artifact beside it" — which is not an
eighth independent behaviour a test can exercise in isolation; every child's
test already demonstrates one facet of it. Two tests bracket the whole claim
rather than illustrating a slice of it: that at ingestion, the composite —
never the upstream commit — is what quarantine pins as the snapshot
(`an_admitted_source_becomes_a_held_composite_whose_manifest_is_gateway_local`,
in `ExternalSourceResolutionTests`), and that at the far end of the pipeline, a
real client cloning the approved snapshot receives exactly that same commit
with nothing external left in it
(`a_client_cloning_the_approved_snapshot_receives_a_manifest_with_no_external_url`,
same class). Together they show that one commit, and not a database row or an
upstream SHA kept beside it, is what every point in the pipeline —
quarantine's pin, the approval copy, the facade's served content — agrees is
the snapshot. `SVC_GW_INGEST_0024` is retargeted to describe exactly that pair
and stays on those two test methods, alongside their own child SVCs (`.1`/`.2`
on the first) — following the same mechanical rule PR #324 and PR #333
established: reqstool resolves `@SVCs` to a JUnit `Class.method`, so the parent
SVC sits on methods, never on the class.

A third test, `a_graft_for_a_plugin_the_manifest_does_not_declare_is_refused_
with_no_commit` (in `ManifestRewriterTests`), also keeps the parent SVC alone,
with no child. It refuses a caller-contract violation — resolved content
offered for a plugin the manifest never declared as an external source — that
none of the seven guarantees names: it is not one of the three refusals
`GW_INGEST_0024.6` enumerates, and it does not assert any of `.1` through `.7`
positively. What it protects is the precondition every other guarantee is
stated in terms of: a composite that graf­ted content nothing in the manifest
pointed at would not be a faithful rewrite of that manifest, so none of the
seven guarantees about the composite's shape, identity or content could be
trusted to describe the commit that resulted. Tagging it as a fifth or sixth
child, forcing a title onto a behaviour the requirement's text never named,
was rejected in favour of leaving it exactly where a test of the parent's own
architectural integrity belongs.

This was considered and rejected: giving the parent no SVC at all, on the
theory that the ontological claim is not independently testable. Rejected for
the same reason PR #333 rejected it — reqstool's own convention requires every
requirement, parent included, to carry at least one SVC for full traceability
— and because the two bracketing tests above are not a rubber stamp; they are
the two points in the actual pipeline, ingestion and serving, where "this
commit is the snapshot" is either true or it is not.

## Annotation moves

`@Requirements`:

| Element | Before | After |
| --- | --- | --- |
| `ManifestRewriter.rewrite` | `GW_INGEST_0021, GW_INGEST_0024` | `GW_INGEST_0021, GW_INGEST_0024.1, GW_INGEST_0024.3, GW_INGEST_0024.4, GW_INGEST_0024.5, GW_INGEST_0024.6, GW_INGEST_0024.7` — the one method that implements nearly the whole capability; `.2` (identification) is excluded because that act happens in `IngestionService`, not here |
| `IngestionService.ingestLocked` | `..., GW_INGEST_0024, ...` | `..., GW_INGEST_0024, GW_INGEST_0024.2, ...` — kept alongside the parent: it is the orchestration point where the composite's sha is what gets pinned as the snapshot's reference and written into its row |
| `IngestionService.serve` | `..., GW_INGEST_0024, ...` | `..., GW_INGEST_0024.1, ...` — decides whether to call the rewriter and takes its result as the served commit |

Prose mentions of the bare id move to the matching child in
`ManifestRewriter`'s class javadoc (`.1`), `SkillsGatewayProperties.
ExternalSources`'s javadoc (`.1`), and `V1__init.sql`'s `snapshots.sha` column
comment (`.2`).

`@SVCs`: the twelve `ManifestRewriterTests` methods and the eight
`ExternalSourceResolutionTests` methods carrying `SVC_GW_INGEST_0024` are
redistributed one guarantee per child, with the two pipeline-bracketing tests
described in Decision 3 keeping `SVC_GW_INGEST_0024` alongside their own child
SVCs, and the caller-contract test keeping `SVC_GW_INGEST_0024` alone. No test
was added, removed, weakened or renamed.

## Risks

- **Merge conflicts.** Both `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` grow ids near where other in-flight
  branches might append; the usual "keep both sides" resolution applies,
  followed by a duplicate-id check.
- **No new top-level id was minted.** This change consumes no `GW_INGEST_NNNN`
  number and cannot collide with a reserved range.
