# Proposal: bound-ledger-growth

## Why

The 2026-09-08 architecture assessment calls `fetch_log` "the table that ends the
deployment" (F7.3): `RetentionService` compacts snapshots, and **nothing touches
the ledger** — no partitioning, no archival, no supported way to remove a row
short of an operator typing `DELETE` by hand.

The volume is dominated by the row that answers no query. `info-refs` fires on
every `git fetch` whether or not anything transfers; `upload-pack` fires only
when content moves. At the threat model's 4,000 developers polling every half
hour that is ~190,000 rows a day, tens of GB a year with the two indexes, and the
`idx_fetch_log_adoption` comment in `V1__init.sql:233` already concedes the
point — the index is partial on `upload-pack` precisely because the advertisement
rows are not what the adoption and staleness reads want.

Unbounded is also the wrong answer for compliance, not just for disk. `principal`
is personal data and the ledger is the gateway's copy of it. A deployment under a
retention obligation cannot meet it today, because the gateway offers no
mechanism to age the ledger at all.

## What Changes

### 1. The existing compaction pass gains a ledger trim

No new `@Scheduled` component and no new lease. `RetentionScheduler.compact()`
already runs six-hourly under `COMPACT_LEASE`, already performs hard deletes, and
is already gated by `skills-gateway.retention.enabled` (default `false` — the
gateway never deletes its own content unless an operator asked). The ledger trim
is a second duty of that pass, for the same reason and behind the same switch.

### 2. An entry is eligible only when every enabled sink has already exported it

Eligibility is the conjunction of two conditions, and the second is the one that
matters: `ts` older than the configured age **and** `id` at or below the lowest
`cursor_position` across all enabled `audit_sinks`. The sinks are the real
tamper-evidence mechanism — that is what
`GW_AUDIT_0004 — Audit export sinks with at-least-once delivery` provides and
what #444 made the docs say — so a row no sink has taken is the only copy of that
evidence, and retention does not get to destroy it.

**The consequence is stated rather than hidden: a deployment that registers no
sink keeps its ledger forever.** With no enabled sink the lowest cursor is
undefined, nothing is eligible, and the trim is a no-op. The gateway cannot both
be the sole holder of audit evidence and bound its own growth; registering an
export destination is the documented price of bounding the table.

### 3. Administrative entries are never eligible

Only facade entries are trimmable. `GW_RETENTION_0008 — An administrative
revocation is never erased by retention` already draws this line for one kind of
act; this generalises it to the whole administrative half of the ledger, which is
the compliance-bearing half and is also low-volume, so exempting it costs
nothing. The predicate is written against the `event` vocabulary and **not**
against `source`: `source` holds `request.getRemoteAddr()` on a facade entry
(`GitFacadeConfiguration:116`) and the literal `admin` only on an administrative
one, so the assessment's "split by a `source` column" describes a column that is
a client IP most of the time.

### 4. Growth and export lag become observable

A gauge for ledger depth and one for the age of the oldest un-exported entry, so
"the table that ends the deployment" is visible long before it does and so the
no-sink deployment of point 2 is a number an operator can see rather than a
surprise. This is the half that helps every deployment, including the ones that
never enable retention.

### 5. Partitioning is rejected, with the reasoning recorded

The assessment recommends "a partitioning strategy". The design says why native
range partitioning is the wrong instrument here — in short, new partitions are a
recurring obligation that would have to be discharged by the very scheduled thing
this change is avoiding, cannot be tied to a pass that defaults to off without
turning a missed partition into a failed `INSERT` on the serving hot path, and
would scatter `idx_fetch_log_adoption` across every partition and so regress a
live read. Full argument, including the primary-key consequence, in `design.md`.

## Capabilities

### New Capabilities

None. The stop rule is satisfied by extending three surfaces that exist: the
retention pass, the export cursor's meaning, and the metrics registry.

### Modified Capabilities

- `snapshot-retention`: the compaction pass trims the ledger, bounded by the
  export cursor (`GW_RETENTION_0009`), and never trims an administrative entry
  (`GW_RETENTION_0010`).
- `audit-export`: `GW_AUDIT_0005 — Audit export cursor replay` gains the bound
  that trimming puts on it — replay reaches only what has not been trimmed, and a
  newly registered sink starting at cursor `0` does not receive trimmed history.
- `observability`: `GW_OBSERVABILITY_0003 — Always-recorded gateway metrics and
  observations` enumerates the instruments the gateway always records; the two
  gauges join that enumeration rather than arriving as a fifth requirement, so
  "recorded always, exported opt-in" stays one statement.

## Impact

- **Schema**: none. Every fact the predicate needs is already on `fetch_log` and
  `audit_sinks`; `V1__init.sql` is not edited.
- **Backend**: a repository method on `FetchLogRepository`, a pass on
  `RetentionService`, a gauge pair on `GatewayMetrics`. No new package.
- **Configuration**: **one leaf**, `skills-gateway.retention.ledger-max-age`,
  inside the area that already governs deletion and behind the switch that
  already gates it. It follows the house fail-safe idiom documented at
  `SkillsGatewayProperties:1141` — unset, zero or negative switches the trim off
  rather than making every entry instantly eligible, so the mis-typed value is
  never the one that deletes. `ConfigSurfaceBudgetTests` moves by one, and this
  paragraph is the argument the stop rule asks for.
- **The stop rule.** `docs/manual/capability-map.md` cannot absorb this: the map
  says what the gateway does, and the Retention row currently claims a capability
  the gateway has only for snapshots. The change adds no backend package, no
  estate object type, no grantable role and **no scheduled sweep**.
- **Declarative estate (#65) does not extend.** `ledger-max-age` is static
  configuration, not API-managed runtime state; `audit_sinks` is already the
  estate-managed object this reads, and its shape does not change.
- **Trust boundary.** This deletes audit evidence, so `.claude/skills/old-coder`
  discipline applies: the tests that matter are the negative ones — no sink, one
  lagging sink, a disabled sink, an administrative entry inside the eligible
  range — not a happy path that deletes old rows.
- **Docs**: `guides/snapshot-retention.md`,
  `guides/exporting-the-audit-ledger.md`,
  `concepts/snapshots-and-ledger.md`, `reference/observability.md`,
  `reference/configuration.md`, and the Retention row of `capability-map.md`.
- **Out of scope**: F7.2, the nine-argument `append()` and the double-duty
  `source` column. The assessment calls it cosmetic and it stays untouched —
  point 3 above works around it rather than fixing it.
- **Considered and rejected — stop recording `info-refs`.** It would remove ~95%
  of the growth by deletion rather than addition, which is what the stop rule
  prefers. It is rejected because the aggregate counter that would replace it is
  deliberately untagged by principal and marketplace, so a credential that
  enumerates without ever fetching would become invisible; that is a detection
  loss on the facade trust boundary in exchange for disk. Recorded in `design.md`
  so it is not rediscovered as a fresh idea.
