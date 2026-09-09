# Proposal: scheduled-interval-agreement

## Why

Every scheduled interval is written down twice. A `@Scheduled` annotation cannot
read a bound record — the placeholder is resolved long before the binder runs —
so each interval appears as a `${property:default}` literal in the annotation
*and* as a default in `SkillsGatewayProperties`' compact constructor. Nine
placeholders, nine record defaults, and nothing forcing them to agree.

That was harmless while nothing read the record side. The schedule came from the
annotation and the record's copy was decoration, which is exactly what the
architecture assessment found when it called five of these properties "dead" and
proposed deleting them.

**`GW_FACADE_0030 — A scheduled background pass runs on one replica at a time`
made the record side live.** Every sweep's lease duration is read from the
record; the tick that takes the lease comes from the annotation. So the two
copies now describe the same interval from opposite ends of the same mechanism,
and a disagreement between them does not fail, log, or look wrong — it silently
makes the lease shorter or longer than the gap it exists to cover, which is the
one assumption the coordination design rests on. A lease shorter than its
interval lets a second replica start the pass; longer, and a pass is skipped.

**All nine agree today.** This is a guard against drift, not a repair.

## What Changes

`ScheduledIntervalAgreementTests`: scan main sources for `@Scheduled`
placeholders, bind a `SkillsGatewayProperties` against an empty environment to
get every default the record produces when nothing is configured, and assert the
two agree for each of the nine.

Binding rather than reading the constructor's source is deliberate — it is the
same call Boot makes, so the value compared is the one a deployment gets rather
than one a test re-derived.

The count is asserted too. A tenth placeholder added without a matching default,
or a sweep that stops declaring one, changes the shape this test is about and
should be seen rather than silently skipped.

## Impact

- **No production change at all.** One test file.
- **No configuration, no API, no requirement text.**

## Out of scope

Removing the duplication. Neither copy can go: the annotation's default is what
runs when no configuration is present, and the record's is what every reader of
the value gets. Deleting one would be better than testing both, and it is not
available — so the duplication stays and this makes it honest.
