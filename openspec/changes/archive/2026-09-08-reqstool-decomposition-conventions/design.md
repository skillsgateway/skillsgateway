# Design: reqstool-decomposition-conventions

## Context

- `docs/reqstool/requirements.yml` held 159 requirements. None used dot notation
  and none used `references.requirement_ids`, so this is the first parent/child
  pair in the project and the first proof that the traceability gate accepts a
  dot in an id.
- The reqstool JSON schema shipped with reqstool 0.12.1 already supports both:
  `requirements.schema.json` defines `references.requirement_ids`, and ids are
  plain strings with no character restriction, so `GW_0158.1` and
  `SVC_GW_0158.1` are valid without any schema or tooling change.
- reqstool has no formal parent-child hierarchy. A parent is an ordinary
  requirement that children point at, and it still needs its own SVC with a
  passing test or the gate counts it incomplete.
- `reqstool status local -p docs/reqstool` reported `165/165 complete · PASS`
  before this change.

## Goals / Non-Goals

**Goals**

- One requirement decomposed properly, with a rule someone else can apply.
- The traceability report answers "which bound regressed", which it could not
  before.
- No behaviour delta anywhere in the gateway.

**Non-Goals**

- Decomposing the rest of #299's candidate list. Doing eight at once would put
  every one of them behind a single review and a single set of merge conflicts,
  which is exactly what the issue's "one requirement per change" instruction
  exists to avoid.
- Any tooling, lint or CI check that enforces the convention.
- Renumbering anything. Ids appear in archived OpenSpec changes and in commit
  messages, which are history and are not rewritten.

## Decision 1 — one child per configurable bound

GW_0158 — *Resource-bounded source resolution* is unusual among the candidates in
that its enumeration maps one-to-one onto a configuration record. The children
therefore follow `SkillsGatewayProperties.ResolutionBudgets` field for field:

| Child | Setting under `…external-sources.budgets` |
| --- | --- |
| `GW_0158.1` — a bound on the bytes one source may send | `max-received-bytes` |
| `GW_0158.2` — a bound on one source's decompressed size | `max-inflated-bytes` |
| `GW_0158.3` — a bound on the decompressed size of a manifest's sources together | `max-closure-bytes` |
| `GW_0158.4` — a bound on how far a source's content may expand | `max-inflation-ratio` |
| `GW_0158.5` — a bound on the number of objects one source contributes | `max-objects` |
| `GW_0158.6` — a bound on the largest single file one source contributes | `max-blob-bytes` |
| `GW_0158.7` — a bound on the depth of a source's directory tree | `max-tree-depth` |
| `GW_0158.8` — a bound on the time a whole resolution may take | `deadline` |

`max-redirects` is the ninth field of that record and is deliberately **not** a
child here: it belongs to GW_0157 — *Address, redirect and transport policy for
source resolution*, which is where redirect policy is stated and verified. The
record is a configuration grouping, not a requirement boundary.

Why this rule and not a coarser grouping (say, "byte bounds", "shape bounds",
"time bound"): the argument for decomposing at all is that an operator raises one
number and a regression breaks one check. A grouping that puts `max-objects` and
`max-blob-bytes` behind one id reintroduces exactly the ambiguity the change is
paying to remove, one level up.

Why not finer: nothing here nests. `GW_0158.4` carries both the ratio and the
floor below which the ratio is not judged, because the floor is not a separate
behaviour — it is the definition of when the ratio applies, and a child stating
the ratio without it would be false about what ships.

## Decision 2 — what the parent keeps

The convention says the parent describes the capability and must read on its own.
Left as an eight-clause enumeration it would have been a table of contents for
its children, which is the thing the convention is against. The parent now states
the two properties that are true across the whole set and are not any single
child's:

- content inside **every** bound is accepted — the set of bounds is a policy, not
  an obstacle course;
- a refusal names the plugin at fault **and** the bound exceeded — a legitimate
  marketplace that outgrows a default is led to a number rather than a mystery.

Both are verified by `SVC_GW_0158` against two existing tests. The parent SVC is
not a rubber stamp.

## Decision 3 — parent `@SVCs` stays at method level

`reqstool-decomposition-conventions.md` says placing the parent `@SVCs` at class
level is "a natural fit". This change does not, and the reason is mechanical
rather than stylistic: reqstool resolves an `@SVCs` annotation to a JUnit test
case by fully-qualified `Class.method` name, and the generated
`annotations-combined.yml` records a class-level annotation as
`elementKind: CLASS` with no method to match a result against. A class-level
parent SVC would therefore risk an unverified requirement and a failing gate,
which is a worse outcome than a slightly less elegant annotation.

`SVC_GW_0158` is instead annotated on the two `ResolutionBudgetTests` methods
that verify the parent's own statements — `content_inside_every_bound_is_accepted`
and `the_violation_names_the_plugin_and_the_bound_that_was_exceeded`. This also
satisfies the primary annotation convention (as close to the implementation as
possible) with no exception needed.

**This is the durable finding of the change**: on this project, a parent SVC goes
on the test methods that verify the parent's cross-cutting contract, and the
parent's text must therefore contain a cross-cutting contract worth verifying.

## Annotation moves

`@Requirements`:

| Element | Before | After |
| --- | --- | --- |
| `ExternalSourceResolver.resolve` | `GW_0155, GW_0157, GW_0158` | unchanged — it is the parent capability: it stops resolving and refuses the ingestion |
| `ResolutionBudget.accept` | `GW_0158` | `GW_0158, GW_0158.3` — composes the named violation, and accumulates the manifest total |
| `ResolutionBudget.reason` | (none) | `GW_0158.1, .2, .4, .5, .6, .7` — the per-source checks |
| `ResolutionBudget.maxReceivedBytes` | (none) | `GW_0158.1` — published so the transfer can stop at it |
| `ResolutionBudget.expired` | `GW_0158` | `GW_0158.8` |
| `GuardedHttpConnectionFactory.create` | `GW_0157, GW_0158` | `GW_0157, GW_0158.1` |
| `GuardedHttpConnectionFactory.Bounded.count` | (none) | `GW_0158.1` — where the transfer is actually aborted |

`@SVCs`: the twelve `ResolutionBudgetTests` methods and the two `GW_0158` tests
in `ExternalSourceResolutionTests` are redistributed one bound per child, with
the two cross-cutting tests keeping `SVC_GW_0158`. No test was added, removed,
weakened or renamed.

## The next candidates — inspection, with a judgement on each

#299 lists eight candidates longest-first. Length is not the test; three were
read for this change and the findings are recorded so nobody repeats the
analysis.

**1. GW_0164 — *The resolved closure is recorded as an immutable domain object of
the snapshot* (1475 chars). Decompose — highest value after GW_0158.** Its
clauses have genuinely different audiences and different failure modes: what a
closure member records (one atomic thing — splitting the field list would weaken
it); recording the closure in the snapshot's transaction; never modifying one and
removing it only on purge; including it in provenance; exposing it as facts to
the policy gate; and answering "which snapshots contain this clone URL" in one
query. The last two are read by a policy author and a security team respectively
and can regress without touching the first four. Note the field list stays whole:
this is not "one child per recorded field".

**2. GW_0156 — *Deterministic composite snapshot with a gateway-local manifest*
(1282 chars). Decompose, but carefully.** Four separable behaviours: the shape and
identity of the synthesised commit (atomic — its conditions must hold together);
determinism plus the recorded rewrite identity; the three manifest refusals
(reserved-directory collision, a plugin name that is not a single lowercase path
segment, two plugins sharing a name); and verifying the rewritten manifest
against the local-only policy. The three refusals are the clearest win — they are
independent inputs with independent tests today.

**3. GW_0157 — *Address, redirect and transport policy for source resolution*
(1130 chars). Should stay monolithic, apart from one thin clause.** This is the
counter-example the conventions warn about. Its content *is* that one policy
applies uniformly to every outbound request, including every redirect hop —
resolve the host, refuse unless every resolved address is permitted, and apply
the same checks again on each hop. Split into "the address classes", "the
redirect rules" and so on, each child could pass while the property the
requirement exists to state — that no request escapes the check — fails
unobserved. The one genuinely separable clause is the last sentence, the connect
and read timeouts, which is a transport concern rather than an address policy;
that is a single thin child and not worth the annotation churn on its own. Revisit
it only if the timeout behaviour grows.

Recommended order for the next changes: **GW_0164, then GW_0156.** Leave GW_0157.

## Risks

- **Merge conflicts.** Several branches append new ids to the end of
  `docs/reqstool/requirements.yml` and `software_verification_cases.yml`; this
  change edits the GW_0158 block in the middle of both. Resolution is the usual
  "keep both sides" — but two branches claiming the same `GW_` number would merge
  cleanly and parse fine, so the merge must be followed by a duplicate-id check.
- **No new top-level id was minted.** This change consumes no `GW_NNNN` number
  and cannot collide with a reserved range.
