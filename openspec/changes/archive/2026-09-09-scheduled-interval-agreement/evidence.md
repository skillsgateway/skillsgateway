# Evidence: scheduled-interval-agreement

One fresh run of every gate after the last code edit.

Commit under test: `f7f81db` (rebased onto main after GW_FACADE_0030 merged as #341; tree unchanged, gates re-run)
Branch: `test/scheduled-interval-agreement`

## Gates

```
./mvnw clean verify                          → BUILD SUCCESS, 0 Checkstyle violations
(cd src/main/frontend && pnpm test:stories)  → 6 tests passed
(cd src/main/frontend && pnpm e2e)           → 13 passed (46.2s)
reqstool status local -p docs/reqstool       → 217/217 complete · 0 incomplete · PASS
openspec validate --all --strict             → 31 passed, 0 failed
mkdocs build --strict                        → built in 1.15s
```

## The measurement

Nine placeholders, all agreeing with their bound defaults:

```
skills-gateway.audit-export.poll-interval        30s
skills-gateway.mirror.sweep-initial-delay         1m
skills-gateway.mirror.sweep-interval             15m
skills-gateway.retention.compaction-interval      6h
skills-gateway.retention.poll-interval            1h
skills-gateway.sync.poll-interval                10m
skills-gateway.vetting.revet.interval             6h
skills-gateway.vetting.waiver-sweep-interval      1h
skills-gateway.webhooks.poll-interval             5s
```

**No live disagreement was found.** This is a drift guard, and saying so is the
point — a test presented as a repair when it repaired nothing would misdescribe
the risk.

## Mutation results — both sides of the duplication

| Mutation | Result |
| --- | --- |
| `Webhooks.pollInterval` default changed to 5s → 7s (record side drifts) | FAILED: "defaults to 5s in the @Scheduled annotation and PT7S in SkillsGatewayProperties" |
| `${…webhooks.poll-interval:5s}` changed to `:9s` (annotation side drifts) | FAILED: "defaults to 9s in the @Scheduled annotation and PT5S in SkillsGatewayProperties" |

Both directions are caught, which matters because the two copies fail
differently: an annotation that drifts changes the tick, a record that drifts
changes the lease.

## What the gates do not cover

- **Only defaults are compared.** A deployment that sets the property configures
  both sides at once, so there is nothing to disagree — but that also means this
  test says nothing about any configured value.
- **Only `Duration` leaves.** A placeholder for a non-duration property would be
  reported as "scheduled from a placeholder but is not a bound property" rather
  than compared; no such placeholder exists today.
- **The placeholder regex is the contract.** It matches the one shape the sweeps
  use (`${skills-gateway.x.y:30s}`). A placeholder written another way — a nested
  default, a SpEL expression — would not be seen at all, and the count assertion
  is the only thing that would notice.
- **Nothing asserts the lease duration equals the schedule at runtime.** This
  compares the two declarations. That `runIfLeader` is passed the same value the
  scheduler ticks on is read from the code, not observed.
