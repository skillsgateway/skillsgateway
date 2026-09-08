# Design: decompose-connector-toggle-requirement

## Context

- `GW_VETTING_0029` — *Administrative enable and disable of a built-in vetting
  connector* — was 1086 characters, the fifth-longest candidate #299 listed.
- Its implementation spans two subsystems: the administrative toggle surface
  (`ConnectorToggleController`, `ConnectorToggleService`,
  `ConnectorToggleRepository`) and the chain's own treatment of a disabled
  connector (`VettingChain`, `VerdictState`, `Verdict`, and the skip decision
  in `VettingService.run`).
- Two test classes carry `SVC_GW_VETTING_0029`: `ConnectorToggleTests` (an
  integration-level, real-API adversarial suite — two methods) and
  `VettingChainDisabledTests` (a pure-function suite over `VettingChain
  .aggregate` — five methods).

## Decision 1 — four children, one per guarantee, not one per clause

The description reads as eight or so `shall` clauses, but several state the
same guarantee from two angles. Grouped by what can regress independently:

| Child | Guarantee | Implementation |
| --- | --- | --- |
| `GW_VETTING_0029.1` | effective-state resolution (per-marketplace, then global, then enabled) | `ConnectorToggleService.enabled`, `ConnectorToggleRepository.set` |
| `GW_VETTING_0029.2` | skip-and-record at chain execution | `VettingService.run`, `Verdict.disabled` |
| `GW_VETTING_0029.3` | neither clearing nor blocking, positive evidence still required | `VettingChain.aggregate`, `VerdictState.blocking`/`clearing` |
| `GW_VETTING_0029.4` | administrator-only, audited | `ConnectorToggleController`, `ConnectorToggleService.set`'s ledger write |

This is the same shape PR #324 used for `GW_INGEST_0026`: each child maps to a
class or method boundary that already exists in the code, rather than an
arbitrary split of the prose.

## Decision 2 — the parent SVC brackets the anti-bypass property, not a fifth guarantee

The four children each state one guarantee the switch has to keep true. The
parent's own text is not a summary of them — it is the property that the
switch, taken as a whole, must never become a bypass. That property is
exactly what two `VettingChainDisabledTests` methods check and none of the
four children individually assert:

- `a_disabled_verdict_never_rescues_a_failing_one` — a DISABLED verdict sitting
  beside a genuinely failing, errored or pending one still blocks. None of the
  four children state this: `.3` says a disabled verdict does not count
  *against* clearing, but does not say it cannot be *outvoted by* a real
  failure sitting beside it in the same run — that is a distinct claim about
  interaction between verdicts, not about DISABLED in isolation.
- `the_pre_existing_rules_are_unchanged` — every aggregation case that existed
  before DISABLED was added (empty run, all-pass, warned, failing) aggregates
  identically. This is regression armour for the whole aggregation function,
  not a claim about the switch's own behaviour, and no child names it either.

`SVC_GW_VETTING_0029` is retargeted to these two methods, following the same
mechanical rule PR #324, #333, #334 and this branch's `GW_RELEASE_0003` change
all used: reqstool resolves `@SVCs` to a JUnit `Class.method`, so the parent
SVC sits on methods, never on the class.

## Decision 3 — two new `@Requirements` annotations, not just moved ones

Unlike the prior three decompositions on this branch, two of this
requirement's guarantees were previously verified by test but had **no**
`@Requirements` annotation on their implementing method at all — only a prose
mention of `GW_VETTING_0029` in a comment:

- `VettingService.run` decides, per connector, whether to skip it and call
  `Verdict.disabled(...)` — the actual site of guarantee `.2` — but carried
  only `@Requirements({"GW_VETTING_0001", "GW_VETTING_0002", "GW_VETTING_0006",
  "GW_VETTING_0012"})`, with the disablement explained only in a `//` comment.
- `Verdict.disabled` — the factory that produces the DISABLED verdict — had a
  javadoc paragraph naming `GW_VETTING_0029` but no `@Requirements` annotation
  at all.

Both now carry `@Requirements({"GW_VETTING_0029.2"})`. This is the same kind
of gap PR #324 found and closed for `GuardedHttpConnectionFactory.Bounded
.count`: a requirement can be genuinely implemented and genuinely tested while
its code carries no annotation, if the original (undecomposed) requirement's
annotation lived on a different method that merely orchestrates the real one.
Decomposition is what makes the gap visible, because a real annotation site is
needed to answer "does this test actually verify this specific child" rather
than "does this test verify the whole multi-clause id".

**This is also the second time on this branch that a missing-field or
missing-annotation gap surfaced only once `reqstool status` was run against
the decomposed ids** (the first was `GW_RELEASE_0003.5`'s missing
`implementation: configuration`, fixed in a follow-up commit on this branch).
Decomposition earns its keep partly by surfacing exactly these gaps: a
multi-clause id can hide an unannotated implementation site behind clauses
that are annotated, and nothing before this exercise made that visible.

## Decision 4 — the OpenSpec quirk: no live spec entry exists

`git grep -rn "GW_VETTING_0029" openspec/specs/` returns nothing. Every other
candidate in this branch (`GW_RELEASE_0003`, and — checked ahead of their own
changes — `GW_APPROVAL_0004`, `GW_AUTH_0015`) has a live entry in
`openspec/specs/<capability>/spec.md`. `GW_VETTING_0029` does not, because it
was introduced by PR #228 ("feat(vetting): admin override of vetting
automation") via `openspec/changes/admin-vetting-override/`, which merged to
main but was **never archived** — its `specs/snapshot-vetting/spec.md` delta
still sits in the open `changes/` directory instead of having been folded into
`openspec/specs/snapshot-vetting/spec.md`.

This predates this branch and is not this change's problem to fix: archiving
an already-merged change that a different PR left unarchived is a separate
housekeeping task, and doing it here would mix an unrelated repair into a
requirements decomposition.

What this change does instead: it edits
`openspec/changes/admin-vetting-override/specs/snapshot-vetting/spec.md`
directly, adding the four children as further `ADDED Requirements` in the same
still-open change — so the parent and children are declared together, by the
one change that actually owns the parent id.

`openspec validate --all --strict` requires every change to carry at least one
delta of its own, so this change's `specs/snapshot-vetting/spec.md` declares
only the four children as `ADDED`, without re-declaring the parent a different
open change already owns — the file says so at the top, so a reader does not
mistake it for the sole source of truth on `GW_VETTING_0029`. The result is
two open changes both touching `snapshot-vetting`: `admin-vetting-override`
carries the parent and, now, the children too; this change's own delta is
almost redundant with that edit, kept only to satisfy the validator's
structural requirement. Archiving either change first will fold its children
into the live spec; archiving the other afterwards is a no-op for those four
ids since they already exist by then.

## Risks

- **Merge conflicts.** Both `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` grow ids near where other in-flight work
  on this same branch appends; resolved by ordering edits sequentially and
  running the independent id check after each.
- **No new top-level id was minted.** This change consumes no `GW_VETTING_NNNN`
  number and cannot collide with a reserved range.
- **The unarchived `admin-vetting-override` change remains unarchived.**
  Anyone reading `openspec/specs/snapshot-vetting/spec.md` looking for
  `GW_VETTING_0029` will still not find it there; they have to know to look in
  `openspec/changes/admin-vetting-override/`. Flagging this here so the next
  reader does not have to rediscover it.
