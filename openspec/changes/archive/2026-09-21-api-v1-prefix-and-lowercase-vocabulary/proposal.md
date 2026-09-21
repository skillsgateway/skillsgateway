# Proposal: api-v1-prefix-and-lowercase-vocabulary

## Why

PR C of the 2026-09-20 1.0.0 readiness run — findings **1**, **9** and **12**. All
three are things 1.0.0 is the last release that can do cheaply, and all three
rewrite `openapi.json`, so they ship together as one declared break.

**The paths are unversioned.** `compatibility.md` said so itself and deferred the
segment to "the change that introduces API versioning". This is that change. The
contract's own remedy for a future break is *move the prefix and ship a major*; with
no `v1`, the first break would produce `/api/v2/…` beside a bare `/api/…` that has
to be supported forever.

**The published vocabulary is two conventions.** `openapi.json` carried
`CLEAR`/`BLOCKED`, `PASS`/`WARN`/`FAIL`, `GLOBAL`/`DEFAULT` beside `held`,
`approved`, `admin`, `ingestion`. Every consumer would have had to encode both, and
renaming an enum value after 1.0.0 is a break.

**Two migration aids expire at this release.** The `RemovedProperties` startup
refusals for `skills-gateway.roles.enabled` and
`…object-store.cache.ref-freshness` are documented as "scheduled for removal at the
next major". 1.0.0 is the next major.

## What Changes

### 1. Every endpooint carries a version segment (finding 1)

`/api/v1/**` and `/status/v1/**`. Twenty controller mappings, `MeController`, the
`MachineApiRegistry` scope inventory, the portal's API calls, the e2e specs and 44
documentation pages.

**The security matchers keep matching `/api/**` and `/status/**`,** deliberately.
Narrowing them to `/api/v1/**` would mean an unversioned path fell through to the
catch-all chain; leaving them at the family keeps every path under `/api` gated
whatever it is called.

### 2. One lowercase vocabulary (finding 9)

Eleven enums. Where an enum already had a `stored()` returning the lower-case name
for its PostgreSQL column, that method becomes the wire form too via `@JsonValue` —
so the published value and the stored value are one definition rather than two that
can drift. The rest gained an equivalent `wire()`.

Seven `@Schema(allowableValues = …)` arrays were hand-written uppercase strings and
were corrected with the code, not instead of it: the webhook `VettingSummary` really
did emit `effect.outcome().name()`, so the documentation was accurate and the code
had to change for the document to become true.

### 3. The expired migration aids are removed (finding 12)

`RemovedProperties`, `RemovedPropertyGuard`, their tests, and the ordering
dependency `RoleBootstrapGuard` held on the guard.

**This retires a requirement, which is more than the finding asked for.**
`GW_AUTH_0027 — The removed enforcement property is refused rather than ignored` is
the requirement those refusals implement, and its own text says it "is scheduled for
removal at the next major, once no supported version has ever read the property".
Keeping the requirement while deleting its implementation would leave reqstool
tracing a requirement nothing satisfies; keeping both would ship a migration aid
into the supported surface of 1.0.0.

The **mechanism** is deleted rather than kept with an empty map. An empty framework
awaiting a future entry is the "commented-out config kept for later" the conventions
warn about, and git history holds it. The *policy* it embodied — removing a property
whose absence reverses an operator's stated intention needs a refusal, not silence —
is kept in `compatibility.md`, so the next removal has the argument without
inheriting dead code.

## Capabilities

### Modified Capabilities

- `admin-roles`: `GW_AUTH_0027` is **removed**. The property it governed no longer
  exists in any supported version, which is the condition its own text set for
  retirement.

Findings 1 and 9 change no requirement. Every requirement describes what an endpoint
does and what a value means, not the path it sits on or how the value is spelled;
`GW_API_0001`'s additive-within-major promise is about the contract's shape and is
unaffected by the release that establishes the prefix it refers to.

## Impact

- **API — BREAKING, twice.** Every path moves, and seven enum vocabularies change
  spelling. Declared in the PR title. This is the release where both are free.
- **Backend**: 23 files for the prefix, 11 enums, three deleted classes.
- **Frontend**: API calls, 9 files of enum literals, regenerated types.
- **Docs**: 44 pages. `compatibility.md`'s "the prefix does not exist yet" note is
  replaced by what the prefix is *for*, and its live-consequences paragraph now
  records that the two refusals are gone and why the policy behind them stands.
- **Schema**: none.
- **Not versioned**: `/git/**`, `/publish/**`, `/hooks/**` and `/docs`. The promise
  covers `/api/**` and `/status/**`; the git paths are a wire protocol and `/hooks`
  is a forge-facing receiver, neither described by the OpenAPI contract.
- **The stop rule**: nothing is added. Three surfaces are renamed, unified, or
  deleted.
