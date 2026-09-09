# Evidence: publication-reconciliation

One fresh run of every gate after the last edit, at commit `9194cfa`.
Run on 2026-09-09.

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 625, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
```

625, up from 622: three cases in `PublicationReconciliationTests`. Distinct
application contexts unchanged at 32 — the suite reuses the shared fixture.

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## 3. `(cd src/main/frontend && pnpm e2e)`

```
  13 passed (44.9s)
```

## 4. `reqstool status local -p docs/reqstool`

```
216/216 complete · 0 incomplete · PASS
```

216, up from 215: GW_APPROVAL_0014.

## 5. `openspec validate --all --strict`

```
Totals: 32 passed, 0 failed (32 items)
```

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 1.20 seconds
```

## The discrimination check

The repair was disabled — the "already served?" test replaced by an
unconditional skip — and the suite re-run:

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0 -- in dev.skillsgateway.server.PublicationReconciliationTests
```

Exactly one case failed, and it was the repair case. The report-only case still
passed, which is correct: it asserts that nothing is retracted, and a
reconciliation that does nothing retracts nothing. That is worth stating, because
it is also the limit of the check — a test that passes under a no-op is not
evidence of the no-op being wrong, and only the repair assertion carries weight
here. Reverted before the gate run above; the third case was added afterwards.

## Two things found by reading rather than by a test

**The first draft conflated "serving nothing" with "could not be read"**, both
returning an empty `Optional`. A marketplace serving nothing would have been
skipped — and that is exactly the state a double failure on a marketplace's
*first* publication leaves, so the change would have reported that case forever
without repairing it. The distinction now lives in the return type: an empty
`Set` is a known state, an empty `Optional` is not. The third test pins it.

**`ApprovalService.repair()`'s javadoc was the finding.** It named a startup
check that had never been built. It now names this class, and says so.

## What the gates do not cover

- **No test reaches the double failure through the public API**, because doing so
  needs two faults inside one call. All three cases arrange the resulting state
  directly, by removing refs behind the gateway's back. What is verified is that
  the reconciliation repairs *that state*, not that the double failure produces
  exactly it — the latter is argued from `repair()`'s code, which is short.
- **The startup path itself is not asserted.** The tests call `reconcile`
  directly; nothing verifies that `afterSingletonsInstantiated` runs before the
  web server accepts a request. That ordering comes from `SmartInitializingSingleton`
  and from `EstateBootstrap`'s identical, already-relied-upon use of it.
- **Nothing measures the cost on a large estate.** It is one query and one ref
  listing per marketplace; on the object-store backend the refs are a single
  manifest read. Argued, not measured.
