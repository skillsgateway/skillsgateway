# Proposal: invocation-adoption-metrics

!!! warning "Parked — and it is *not* a decision not to build"

    **Status: parked, 2026-09-09.** It cannot start until the owner accepts
    **ADR 0016**, which is still *Proposed*; its own task list says not to begin
    section 2 before that, because implementing first would make the ADR a
    description of something already built.

    **Read this before triaging it again.** The 2026-09-08 architecture
    assessment recommended archiving this as "a decision not to build". That
    reading does not survive the documents. ADR 0016 refuses *client-reported
    telemetry* — the metric is forgeable by the population being measured, and
    the join key arrives as a constant — but it explicitly **rejected** doing
    nothing, and chose to build the half the gateway can produce unforgeably:
    **presence**, which skills a served snapshot contains and therefore which
    identities hold them. Only two of the twenty-seven tasks encode the refusal;
    the rest are constructive work on that report.

    **The finished "no" is ADR 0016 itself**, which already stands alone in
    `docs/decisions/` and needs nothing from this change to survive.

    **One orphaned obligation lives only here:** `docs/manual/architecture.md`
    §9 still offers install-inventory enrichment "optionally … with client OTel
    telemetry", and §13 still lists client telemetry inventory. ADR 0016 says
    both become wrong **on its acceptance**, so they are correct today and must
    be rewritten the moment it is accepted. Nothing else tracks that.

    **Every `GW_NNNN` id below is a drafting artifact, not a reservation.** ADR
    0018 retired the flat sequence and names this change: whoever implements it
    mints a domain-prefixed id then. Nothing here is in `docs/reqstool/`, so
    there is no traceability to clean up — and section 1 of its task list, which
    tells an implementer to add ids in the old format, is stale on its face.

## Why

Issue [#85](https://github.com/skillsgateway/skillsgateway/issues/85) is Phase 3
of [architecture.md §13](https://github.com/skillsgateway/skillsgateway/blob/main/docs/manual/architecture.md),
and it carries a framing from an internal security review: **distribution ≠
invocation ≠ adherence.** The gateway measures distribution precisely —
GW_OBSERVABILITY_0001 — *Adoption reporting from the fetch ledger*,
GW_OBSERVABILITY_0002 — *Staleness reporting against the served tip*,
GW_OBSERVABILITY_0004 — *Adoption page in the admin portal* — and measures the other two not at
all. The issue asks for the middle one: ingest client OpenTelemetry so the
fetch-derived install inventory says who *uses* a skill, not only who *has* it.

Investigating that ask turned up two findings that change what should be built.
They are argued in full in
[ADR 0016](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0016-client-invocation-telemetry-is-not-ingested.md),
which this change accompanies; in short:

- **The number would be self-reported by the population being measured**, over a
  bearer credential that managed settings distribute to every measured machine,
  with every identifying attribute client-asserted. It is forgeable in both
  directions, and it would sit on the same page, under the same word, as figures
  no client can move.
- **The join key does not arrive.** Claude Code redacts *third-party* plugin and
  skill names to the literal `"third-party"` and emits `marketplace.name` only for
  official-marketplace plugins. A marketplace this gateway serves is a third-party
  marketplace by definition, so for plugin-delivered content there is no
  per-skill series to correlate against. Content consumed as plain `SKILL.md`
  directories is exempt, which makes a mixed-adapter estate report real names for
  one half and a constant for the other — a hole shaped exactly like the thing
  being measured.

So this change does not build ingestion. It builds the half only the gateway can
produce unforgeably — **presence**, which skills a fetched snapshot actually
contained and therefore which identities hold them — and writes the refusal down
as a requirement so it is checkable rather than a paragraph in a document.

## What Changes

- **Presence inventory at skill granularity (GW_0213).** A report that resolves,
  for each snapshot SHA the ledger records a content-transferring fetch of, the
  skills and plugins that SHA's pinned commit tree contains, and aggregates the
  existing ledger measures against them. Derived from two things the gateway
  already owns — the append-only ledger and the pinned commit tree that
  GW_INGEST_0015 — *Snapshot file inspection* already reads — and recording nothing new,
  exactly as GW_OBSERVABILITY_0001 records nothing new. Gated like the ledger: auditor or admin,
  because it enumerates identities.

  It answers the recall query §9 names — *all identities that hold `skill-x`* —
  without an operator first working out which marketplaces at which SHAs contained
  it.

- **Presence is labelled presence, in the payload (GW_0214).** A git fetch is
  all-or-nothing: every identity that fetched a snapshot received every skill in
  it, so per-skill counts are uniform across a snapshot's contents *by
  construction*. The report states that in its own response and in the portal
  rather than in a footnote, and carries the identifiers an external metrics
  backend would join on — the marketplace, plugin and skill names as served — so
  correlation with client telemetry is possible outside the gateway without the
  gateway holding either side of it.

- **No client-reported data, stated as a requirement (GW_0215).** The gateway
  exposes no surface that accepts client-reported usage telemetry, and no adoption
  figure it reports is derived from data a client asserted. A negative that is
  only prose decays; a negative with a verification case is something a later
  change is checked against.

## Capabilities

### New Capabilities

_None._ This is the existing adoption capability read at a finer granularity, plus
a boundary on the existing observability capability.

### Modified Capabilities

- `adoption-reporting`: gains a presence report at skill and plugin granularity
  (GW_0213) and the join-and-caveat contract that keeps it from being read as a
  usage measure (GW_0214). GW_OBSERVABILITY_0001, GW_OBSERVABILITY_0002 and GW_OBSERVABILITY_0004 are unchanged — the new
  report sits alongside them and shares their shape: ledger-derived, read-only,
  auditor-gated.
- `observability`: gains the standing refusal to ingest client-reported telemetry
  (GW_0215). GW_OBSERVABILITY_0003 — *Always-recorded gateway metrics and observations* is
  untouched; it governs telemetry flowing outward.

## Requirement ids

**GW_0213, GW_0214 and GW_0215 are reserved by this proposal and are deliberately
not yet written into `docs/reqstool/requirements.yml`.** A requirement entered on
`main` with no `@Requirements` annotation and no passing SVC turns the
traceability gate red, so the entries and their verification cases land in the
implementing PR alongside the code that satisfies them — task 1 below. Nothing in
`docs/reqstool/` is touched by this change.

GW_0216 and GW_0217 were reserved for this work and are **not consumed**; they
remain free.

## Out of scope (named, so the boundary is explicit)

- **Any inbound telemetry endpoint**, by any protocol, under any authentication.
  Refused rather than deferred — see ADR 0016, and GW_0215 which makes the refusal
  a checkable property.
- **A pull-based, aggregate-only query against an organisation's metrics
  backend.** The better shape of the two, and pointless today because the series
  it would read is keyed `"third-party"`. Recorded in ADR 0016 as the option to
  revisit if the upstream identifier changes.
- **Adherence** — whether guidance was followed. Permanently out of scope: it is
  unobservable from a system whose view ends at a repository being read. It is
  evidenced by artefacts CI and policy gates in consuming repos produce.
- **A portal chart of invocation.** There is no invocation figure to chart. The
  portal surface here is the presence report on the existing adoption page.
- **Per-team aggregation.** The gateway has no team concept, deliberately
  (GW_OBSERVABILITY_0001's rationale). Unchanged.

## Impact

- **DB**: none expected. The report is an aggregate over `ledger` joined to
  snapshot content resolved from the pinned commit tree; a content index is a
  performance decision for the implementing PR, not a behaviour change, and is
  argued in `design.md`.
- **Backend**: the adoption service and controller gain one report. Snapshot
  content resolution reuses the commit-tree reads GW_INGEST_0015 already performs.
- **API**: additive — one new path under `/api/adoption`, one new schema, one new
  machine scope value or a reuse of `adoption:read`. No existing path, DTO or
  field changes.
- **Portal**: the existing adoption page gains a section; no new page.
- **Trust boundary**: this reads the ledger and enumerates identities, so it
  inherits the auditor gate and the adversarial-test obligation that comes with
  it. It creates no write path and no inbound surface.
- **Docs** (implementing PR): `reference/api/adoption.md`,
  `reference/observability.md`, `concepts/trust-boundaries.md`,
  `reference/portal.md`, and `architecture.md` §9 — whose current bullet offers
  enrichment "optionally … with client OTel telemetry" and becomes wrong once
  ADR 0016 is accepted.
