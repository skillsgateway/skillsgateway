# Evidence: sweep-lease-coordination

One fresh run of every gate after the last code edit.

Commit under test: `37ad35f851e1aee8c8c417ce4be3dc891588fcea`
Branch: `feat/sweep-lease-coordination`

## Gates

### `MAVEN_OPTS="-Xmx3g" ./mvnw clean verify`

```
[INFO] Spotless.Java is keeping 332 files clean - 0 needs changes to be clean, 332 were already clean
[INFO] --- checkstyle:3.6.0:check (default) @ skills-gateway-server ---
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  07:42 min
```

### `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Duration  3.16s
```

### `(cd src/main/frontend && pnpm e2e)`

```
  13 passed (44.4s)
```

### `reqstool status local -p docs/reqstool`

```
  GW_FACADE_0030      skills-gateway

INCOMPLETE (0)
217/217 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 32 passed, 0 failed (32 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 1.20 seconds
```

## Mutation results — every new guard fails against the thing it names

A test that passes against the defect it is named for is decoration. Each
mutation below was applied to the shipped code, the suite was run, and the
source was restored.

| Mutation | Test that failed |
| --- | --- |
| `AuditSinkRepository.advanceCursor`'s `AND cursor_position = :expected` removed | `SweepCoordinationTests.anExportAdvanceCannotOverwriteACursorItDidNotRead:218` |
| `WaiverService` writes the ledger entry before the stamp again (the original order) | `WaiverTests.twoPassesOverOneBatchAnnounceTheLapseOnce:337` |
| `SyncScheduler.sweep()` calls `sweepNow()` directly, taking no lease | `SweepLeaseDisciplineTests.everyScheduledMethodTakesALease:44` |
| `SweepLeases.runIfLeader` ignores the claim's result and always runs the body | `SweepCoordinationTests.aHeldLeaseRefusesTheSweepWithoutWaitingForIt:68`, `.aSweepThatThrewKeepsItsTurn:145`, `.oneSweepsLeaseDoesNotHoldAnotherOut:117` |
| A `sync.enabled` refusal put back into `skills-gateway.replicaGate` | `PackagingTests.chartRefusesAStorageShapeTheGatewayCannotHonour:369` |

**One test was discarded for failing this bar.** The first version of the waiver
test ran two `sweepExpired` passes on two threads through a `CyclicBarrier` and
asserted a single ledger entry. It **passed against the unrepaired code** — the
threads did not overlap in the window that matters, so it proved nothing. It was
replaced by a deterministic one: the batch is read once and handed to
`recordExpiries` twice, which is exactly the state two overlapping replicas are
in and does not depend on winning a race. `WaiverService.recordExpiries` was
extracted for it, and is a real seam rather than a test hook — the read is where
two passes converge.

## What the gates do not cover

- **No test runs two JVMs.** Every lease assertion is one process writing rows a
  second replica would have written. The `sweep_leases` row is the entire
  coordination state and the claim is one statement, so the property under test
  is PostgreSQL's, not the application's — but "two pods, one estate" is
  asserted by construction here, not observed.
- **No test runs a scaled-out chart.** `PackagingTests` reads the template and
  asserts what it refuses and no longer refuses. That `replicaCount: 3` on
  object-store actually behaves in a cluster is unexercised, as it was before.
- **The webhook dispatcher's lease is untested behaviourally.** Its correctness
  under concurrency never depended on the lease — `WebhookDeliveryRepository.claim()`
  is per-delivery and already stopped two dispatchers sending the same payload.
  The lease stops them competing for the queue, which costs nothing observable.
- **Lease-holder identity under Kubernetes is assumed, not verified.**
  `InetAddress.getLocalHost().getHostName()` returning the pod name is a property
  of the runtime, not of this code. The fallback path (a random suffix when the
  host cannot be resolved) is not exercised by any test.
- **Clock skew between replicas is not modelled.** Every lease comparison uses
  the application's `Instant.now()`, not the database's. Two replicas whose
  clocks differ by more than a pass's interval could both consider a lease
  lapsed. This is a pre-existing property of every timestamp in the schema, and
  is not made worse here — but the lease is the first place it could cause a
  duplicate pass rather than a duplicate row.
- **The audit-export CAS does not deduplicate a delivery**, and no test claims it
  does. Two exporters that both read the same cursor have both enqueued before
  either writes. The lease is the mechanism that stops there being two, and that
  is asserted at the lease, not at the sink.
