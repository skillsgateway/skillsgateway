# Proposal: reqstool-decomposition-conventions

## Why

Issue [#299](https://github.com/skillsgateway/skillsgateway/issues/299) asks the
project to adopt the reqstool decomposition conventions — parent/child
requirements linked by `references.requirement_ids`, with dot-notation ids
(`GW_0158.1`, `SVC_GW_0158.1`).

Measured over `docs/reqstool/requirements.yml` before this change: **no
requirement used dot notation and none used the `references` field.** The
convention was not partially adopted; it was unadopted. There was therefore no
worked example in the repository for the next requirement that needs splitting,
and no evidence that the traceability gate tolerates a dot in an id at all.

The cost of leaving a multi-behaviour requirement collapsed is not stylistic:

1. **Verification granularity.** One id gets one SVC. GW_0158 — *Resource-bounded
   source resolution* enumerated eight configurable bounds and had one verdict
   across all of them. A resolver that still refuses an endless stream but no
   longer refuses a pack bomb is a materially different gateway, and one
   pass/fail cannot say which of the two it is.
2. **The gate cannot be partial.** Children may reasonably differ in
   `significance` or ship in different versions. Collapsed into one id,
   everything is `shall` and everything ships at once.
3. **Stakeholder readability.** Requirements are for stakeholders and must be
   self-contained without reading the SVCs; SVCs are for developers.

## What Changes

**This change decomposes exactly one requirement and nothing else.** The issue
prescribes an incremental adoption — "decompose one requirement per change, with
its annotations and SVCs moved in the same commit, starting with GW_0158 as the
worked example that establishes the pattern for the rest" — and that is what this
is.

- **GW_0158 — *Resource-bounded source resolution* keeps its id and gains eight
  children**, `GW_0158.1` through `GW_0158.8`, each linked to it by
  `references.requirement_ids`. The rule applied is **one child per configurable
  bound**: the parent's text was an enumeration of the settings under
  `skills-gateway.ingestion.external-sources.budgets`, and each of those settings
  can be raised, lowered or broken independently of the rest.
- **The parent's own text becomes what actually holds across the whole set** —
  content inside every bound is accepted, and a refusal names the plugin at fault
  and the number it exceeded. That is a real, separately verifiable contract, not
  a placeholder.
- **One parent SVC and eight child SVCs.** `SVC_GW_0158` keeps its id and now
  verifies the cross-cutting contract; `SVC_GW_0158.1` – `SVC_GW_0158.8` each
  verify one bound.
- **Every annotation moves in the same commit.** Fourteen `@SVCs` annotations
  across `ResolutionBudgetTests` and `ExternalSourceResolutionTests`, and five
  `@Requirements` annotations across `ResolutionBudget`,
  `GuardedHttpConnectionFactory` and `ExternalSourceResolver`.

Observable behaviour of the gateway is **unchanged**. No production code path,
no configuration key, no API response and no portal page changes. What the change
buys is that the traceability report now names the bound that regressed, and that
the repository has a pattern the next decomposition can copy.

## Two claims the decomposition removed

Writing each bound down separately made two over-claims in the old text visible.
Both are recorded here rather than quietly dropped, because they are decisions
the maintainer may want to revisit:

- The old text bounded *"the bytes it will accept in total across a manifest's
  sources"*. **There is no such bound.** The settings are received-bytes
  per source, decompressed per source, and decompressed accumulated across the
  manifest — there is no accumulated *received* bound in configuration or in
  code. The children state the three bounds that exist and are verified. If an
  accumulated received bound is wanted, it is a new child (`GW_0158.9`) and a new
  test, not a wording fix.
- The old text said the refusal happens *"without first materialising the content
  that exceeds them"*. That is true of the received-byte bound, which aborts the
  transfer mid-stream, and only derivatively true of the rest: decompressed size,
  object count, largest file and tree depth are all measured by walking a tree
  that is already in quarantine. The clause now lives on `GW_0158.1`, where it
  actually holds and where a test can hold it to account.

## Capabilities

### Modified Capabilities

- `marketplace-ingestion`: GW_0158 — *Resource-bounded source resolution* is now
  a parent with eight children. No behaviour delta.

## Out of scope

- **The other candidates in #299 are not touched.** The issue lists eight; this
  change does one. See `design.md` for the inspection of GW_0164, GW_0156 and
  GW_0157 and the priority order for whoever picks up the next one — including
  the finding that GW_0157 should largely stay monolithic.
- **No sweep, no tooling.** Nothing enforces the convention automatically; the
  reference is the shipped `reqstool-conventions` skill and now this change.
