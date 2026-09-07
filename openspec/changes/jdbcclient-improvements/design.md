# Design: jdbcclient-improvements

Issue [#314](https://github.com/skillsgateway/skillsgateway/issues/314) names four
independently droppable parts and asks that each be decided on evidence rather
than on a predetermined answer. Two were done, two were declined. This document
is the evidence for all four; it is the durable part of the change, because the
declines are only useful if the reasoning survives.

## The numbers, re-verified

The issue corrects two counts that were wrong during the ADR 0013 discussion.
Both were re-measured on this branch before any work was planned from them:

```
$ grep -rhoE '::[a-z_]+' --include='*.java' src/main/java | sort | uniq -c | sort -rn
```

185 matches, of which 164 are Java method references (`::map` 64, `::name` 10,
`::get` 8, `::new` 6, and a long tail). **21 are SQL enum casts**, distributed
exactly as the issue records: `::snapshot_state` 9, `::vetting_run_outcome` 2,
`::fetch_log_actor_type` 2, and one each of `::marketplace_origin`,
`::marketplace_push_policy`, `::marketplace_sync_mode`, `::audit_sink_kind`,
`::vetting_verdict_state`, `::vetting_finding_severity`,
`::vetting_waiver_scope_kind`, `::role_grant_role`. `grep -rn 'RETURNING'`
returns 25. The issue's numbers are correct; the ADR discussion's were not.

One thing the issue does not say, which matters for part 1: the schema declares
**12** enum types but only **11** ever appear in a cast. `webhook_delivery_state`
needs none, because `WebhookDeliveryRepository` writes its states as SQL
literals (`SET state = 'delivered'`) rather than as bound parameters, and
PostgreSQL resolves an unknown-typed literal to the column's type on its own.
The cast is required only where the value arrives as a bound `varchar`
parameter — which is what the driver sends for `setString` under its default
`stringtype=VARCHAR`.

## Part 4 — the N+1 (done)

`VettingRepository.verdicts()` read the run's verdicts and then issued
`SELECT * FROM vetting_findings WHERE verdict_id = ?` once per verdict. The cost
of reading one chain run therefore grew with the number of connectors in the
chain. ADR 0013 records this as the single place an ORM's fetch join would
genuinely have helped, and it is the only part of #314 that is a defect rather
than a question of taste.

It is now one join query for the whole run, grouped by verdict id in memory:

```sql
SELECT f.* FROM vetting_findings f JOIN vetting_verdicts v ON v.id = f.verdict_id
 WHERE v.run_id = :runId ORDER BY f.id
```

Two statements for any run, one for a run with no verdicts. Measured with
`VettingRunReadTests`, which counts statements at the JDBC `Connection`:

| verdicts in the run | statements before | statements after |
| ---: | ---: | ---: |
| 1 | 3 | 3 |
| 12 | 14 | 3 |

**A note on how that measurement is taken**, because the first attempt was
wrong in a way that would have shipped: counting by overriding
`JdbcTemplate.execute(PreparedStatementCreator, PreparedStatementCallback)`
counts nothing. The template's real funnel is a private three-argument overload,
so the public one is never called, the counter stays at zero, and the assertion
`0 == 0` passes against the unfixed code. The test now counts
`Connection.prepareStatement` through a proxy — independent of how Spring routes
a call — and carries a control assertion that the count is greater than zero, so
the same failure cannot recur silently.

Ordering and grouping are preserved rather than assumed: findings ascend by id
within a verdict exactly as the per-verdict query returned them, a verdict with
no findings keeps an empty list instead of borrowing its neighbour's, and the
join filters on `run_id` so a second run against the same snapshot cannot leak
in. `VettingRunReadTests` fixtures are built to make each of those fail if it
were wrong: interleaved findings, a findings-free verdict, positions recorded
out of order, and an earlier run whose findings carry the lower ids.

## Part 2 — the `DataClassRowMapper` spike (done)

Spiked on `MarketplaceRepository` against the real PostgreSQL dev service before
anything was changed. The four questions from the issue, and what the database
and the framework actually did:

| Question | Answer |
| --- | --- |
| Does `timestamptz` reach an `Instant` component with no help? | **Yes.** `Marketplace.createdAt` came back equal, to the microsecond, to what the hand-written `rs.getObject(col, OffsetDateTime.class).toInstant()` produced. This was the question that decided whether the idea worked at all. |
| Are nullable boxed types handled? | **Yes.** A `NULL::bigint` reaches a `Long` component as null; a set one as its value. A SQL NULL into a *primitive* component fails loudly rather than becoming 0 — which is why every nullable column in this schema is boxed. |
| Does a native enum column reach a Java enum component? | **No.** Storage is lower-case (`'pass'`) and Spring's string-to-enum conversion is case-sensitive, so it raises `ConversionFailedException`. A converter would be needed. |
| What about composite reads? | Surplus columns are **ignored**, so `SnapshotRepository.candidates` can map the `Snapshot` half with the shared mapper and hand-write only the `reason`. A component with **no** column fails loudly, so `SnapshotClosureRepository.Closure`, whose `members` come from a second query, stays hand-assembled. |

`Instant` conversion working unaided is what keeps this part alive; had it needed
a converter the win would have shrunk to nothing and the honest answer would have
been to close it. It did not, so it was rolled out.

All six answers are now pinned as regressions in `DataClassRowMapperTests`,
including the two failures. A Spring or driver upgrade that changes any of them
fails there, next to the reason, rather than in a repository where the reason is
not written down.

**Rolled out** to the ten repositories whose records map 1:1 —
`MarketplaceRepository`, `SnapshotRepository`, `TokenRepository`,
`RoleGrantRepository`, `AuditSinkRepository`, `WebhookSubscriberRepository`,
`WebhookDeliveryRepository`, `ConnectorToggleRepository`,
`SnapshotClosureRepository` (the members) and `PolicyRuleRepository`.

**Deliberately not rolled out** to `VettingRepository` and `WaiverRepository`
(Java enum components — the third spike answer), `FetchLogRepository`
(`AuditEntry` renders its timestamp as a string and derives a nullable token id;
the ledger aggregates have no matching record at all), and the two composite
reads named above.

`MarketplaceRepository.instant` moved to a package-private `Timestamps`, because
the class that gave it its name no longer maps anything by hand.

**No SQL text changed anywhere in this part.** The guarded
`UPDATE … WHERE <state predicate> RETURNING *` statements that ADR 0013 names as
this system's concurrency control keep their text, their predicates and their
plans; what changed is only how the row they return is materialised.

<!-- PART1 -->

<!-- PART3 -->

## What this change does not do

- No new requirement ids. The change introduces no new required behaviour; the
  new tests attach to SVC_GW_0037 and are otherwise framework regressions.
- No schema migration, no API change, no configuration change, no portal change.
- No batching (part 3) and no implicit casts (part 1), for the reasons above.
