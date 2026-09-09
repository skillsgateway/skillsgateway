# Evidence: derive-privileged-reads

One fresh run of every gate after the last edit, at commit `072cb58`.
Run on 2026-09-09, rebased onto `main` after #335 and #336 merged.

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 621, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
```

Test count is unchanged at 621: this change adds assertions to existing cases
rather than cases. Distinct application contexts: 32, unchanged.

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## 3. `(cd src/main/frontend && pnpm e2e)`

```
  13 passed (44.3s)
```

## 4. `reqstool status local -p docs/reqstool`

```
214/214 complete · 0 incomplete · PASS
```

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed (36 items)
```

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 1.14 seconds
```

## The discrimination check

A completeness assertion that cannot fail proves nothing, so it was made to
fail on purpose. `"GET /api/snapshots/{id}/licenses"` was deleted from
`OPEN_READS` and the suite re-run:

```
java.lang.AssertionError:

Expecting actual:
  ["GET /api/adoption",
    "GET /api/adoption/staleness",
    ...
```

The assertion fails and prints the derived set against the classified one, so
the report names the unclassified route rather than only a count. Reverted
before the gate run above; the run above is of the unmutated tree.

## What this does not cover

- **It proves classification is exhaustive, not that each classification is
  right.** Nothing here would catch a genuinely privileged read deliberately
  put in `OPEN_READS` — that stays a review question. What it removes is the
  case where nobody made a decision at all.
- **Derivation reads the route table of the test context.** An endpoint
  registered only under a profile this context does not activate is invisible
  to it, exactly as it was for mutations.
