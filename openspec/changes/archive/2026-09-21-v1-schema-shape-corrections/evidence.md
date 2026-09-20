# Evidence: v1-schema-shape-corrections

One fresh run of every gate after the last edit. Commit `f5ceeb66`.

## `./mvnw clean verify`

```
MAVEN_OPTS="-Xmx3g" ./mvnw clean verify
```

```
[INFO] Tests run: 709, Failures: 0, Errors: 0, Skipped: 9
[INFO]       Tests  104 passed (104)
[INFO] BUILD SUCCESS
```

Includes the `openapi.json` equality test, oasdiff, Spotless and Checkstyle.

## Storybook story tests

```
(cd src/main/frontend && pnpm test:stories)
```

```
 Test Files  9 passed (9)
      Tests  45 passed (45)
```

**Second attempt.** The first run after `mvnw clean` failed with
`5 failed | 4 passed (9)` and only 13 of 45 tests executing — the known cold-cache
flake recorded for this harness, not a defect of this change. Re-ran unchanged and
it passed complete. Noted rather than quietly re-run.

## Real-browser e2e

```
(cd src/main/frontend && E2E_GATEWAY_PORT=18500 pnpm e2e)
```

```
  20 passed (1.3m)
```

**Not on the default port.** `e2e/run-e2e.sh` defaults the gateway to 8081, which
is also React Native Metro's default; another project's Expo dev server held it on
this machine, and 8099 was taken too. Ran on a verified-free port instead. The
default is worth changing, and is filed as a follow-up rather than fixed here.

## reqstool

```
reqstool status local -p docs/reqstool
```

```
248/248 complete · 0 incomplete · PASS
```

## OpenSpec

```
openspec validate --all --strict
```

```
Totals: 32 passed, 0 failed (32 items)
```

## MkDocs

```
mkdocs build --strict
```

```
INFO    -  Documentation built in 6.60 seconds
```

## What the contract shows

`openapi.json` was regenerated, and every multi-valued field is an array:

```
CreateSubscriberRequest.events             array string
CreatedSubscriber.events                   array string
EventRegistry.events                       array string
SubscriberView.events                      array string
VettingOverrideRecord.blockingVetters      array string
VettingOverrideRecord.uncoveredFindings    array string
```

The change is declared breaking (`feat(api)!`) because three payloads changed
shape. That was a deliberate reversal mid-implementation: the first cut kept the
payloads delimited and joined at the boundary, which the owner correctly called
out as compatibility reasoning that does not apply before 1.0.0.

## The negative tests

`SchemaShapeTests`, eight cases, all against the database rather than the record.
The one that mattered most needed fixing before it proved anything: an empty
`api_scopes` on a credential with no expiry is refused by
`machine_credentials_expire` first, so the test passed for the wrong reason and
never reached the constraint it was written for. With an expiry set, the row
satisfies every pre-existing constraint and only `scope_lists_are_never_empty`
stands between it and a machine credential that grants nothing.
