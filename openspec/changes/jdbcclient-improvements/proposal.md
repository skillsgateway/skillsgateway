# Proposal: jdbcclient-improvements

## Why

[ADR 0013](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0013-data-access-stays-on-jdbcclient.md)
keeps `JdbcClient` and rejects JPA, resting partly on the claim that the
boilerplate JPA would remove has cheaper cures inside the current stack. It also
recorded, honestly, the one place JPA would genuinely have helped: an N+1 read of
a vetting run's findings. Issue
[#314](https://github.com/skillsgateway/skillsgateway/issues/314) is those cures,
in four independently droppable parts, with the corrected measurements the ADR
discussion got wrong twice.

The only part that is a defect rather than an aesthetic is the N+1: reading one
chain run issues one findings query **per verdict**, so its cost grows with the
number of connectors in the chain. Everything else in the issue is a question
about whether the layer's shape can be improved, and two of the four answers
turn out to be "no, and here is why" — which is the point of asking.

## What Changes

- **The N+1 in `VettingRepository.verdicts()` is closed (GW_0037 — Ordered
  vetting connector chain at ingestion).** Reading a run now costs two statements
  whatever the chain length: one for the verdicts, one join query for every
  finding of the run, grouped by verdict in memory. Ordering and grouping are
  preserved exactly — findings ascend by id within a verdict, verdicts by
  position then connector — and a verdict with no findings still reads back with
  an empty list rather than borrowing another verdict's.

- **Row mapping moves to `DataClassRowMapper` where the record maps 1:1
  (GW_0125 — Enumerated persisted values are database types).** A spike
  (see `design.md`) established that `timestamptz` reaches an `Instant` record
  component with no converter, that a nullable boxed component reads back null,
  that a SQL NULL into a primitive component fails loudly rather than silently
  becoming zero, and that surplus columns are ignored. Nine repositories drop
  their hand-written mappers. **No SQL is changed by this** — the guarded
  `UPDATE … WHERE <state predicate> RETURNING *` statements that ADR 0013 names
  as this system's concurrency control keep their text, their predicates and
  their plans; only how the returned row is materialised changes.

- **Two composite reads deliberately stay hand-written**:
  `SnapshotRepository.candidates` (a `Snapshot` plus a computed `reason` column)
  and `SnapshotClosureRepository.findBySnapshot` (a `Closure` whose `members`
  component has no column). Both now build on `DataClassRowMapper` for the part
  that maps 1:1 and hand-write only the composition.

- **Implicit enum casts are declined**, with reasons recorded in `design.md`.

- **Batching the two insert loops is declined**, with the measurement that
  decided it recorded in `design.md`.

Observable behaviour is unchanged. What the change buys is one fewer growth
curve in the vetting read path, ~110 fewer lines of mapper boilerplate, and two
questions closed with evidence instead of left open as folklore.

## Impact

- Affected specs: `snapshot-vetting` (GW_0037), `persistence-schema` (GW_0125).
- Affected code: `VettingRepository`, and the row mappers of
  `MarketplaceRepository`, `SnapshotRepository`, `TokenRepository`,
  `RoleGrantRepository`, `AuditSinkRepository`, `WebhookSubscriberRepository`,
  `WebhookDeliveryRepository`, `ConnectorToggleRepository`,
  `SnapshotClosureRepository`.
- No schema migration, no API change, no configuration change, no portal change.
- No new requirement ids: this change introduces no new required behaviour.
