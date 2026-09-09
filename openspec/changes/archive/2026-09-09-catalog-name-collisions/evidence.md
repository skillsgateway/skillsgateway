# Evidence: catalog-name-collisions

One fresh run of every gate after the last edit, at commit `50e45be`.
Run on 2026-09-09.

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 622, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
```

622 rather than 621: one new case in `CatalogTests`.

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## 3. `(cd src/main/frontend && pnpm e2e)`

```
  13 passed (45.4s)
```

## 4. `reqstool status local -p docs/reqstool`

```
215/215 complete · 0 incomplete · PASS
```

215, up from 214: GW_FACADE_0029.

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed (36 items)
```

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 1.25 seconds
```

## The discrimination check

A test that passes against the defect it names proves nothing, so first-wins was
restored — `claimants.size() == 1` relaxed to `>= 1`, publishing the first
claimant exactly as the old rule did — and the suite re-run:

```
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.CatalogTests
```

Only the new case fails, and it fails on the assertion that the contested name is
absent. Reverted before the gate run above.

## What the gates do not cover

- **No collision existed in any fixture before this change**, so nothing in the
  suite was asserting the old behaviour and nothing had to be weakened to make
  this pass. The new case constructs the first colliding pair the project has
  ever had.
- **The failure this prevents is not reproduced end to end.** The test proves the
  name is withheld; it does not simulate a consumer installing `a-b-c`,
  receiving publisher B's content, and then receiving publisher A's after an
  unrelated registration. That is the harm, and it is argued rather than
  demonstrated.
- **Only the manifest is asserted, not what a client resolves.** The vendored
  trees of both claimants remain in the catalog commit by design, so a client
  that hard-codes a path rather than reading the manifest still reaches content.
  Withholding is an advertisement change; it is not a retraction.
