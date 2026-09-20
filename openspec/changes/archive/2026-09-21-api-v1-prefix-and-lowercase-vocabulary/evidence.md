# Evidence: api-v1-prefix-and-lowercase-vocabulary

One fresh run of every gate after the last edit. Commit `44d96baf`.

| Gate | Command | Result |
| --- | --- | --- |
| Java + UI | `MAVEN_OPTS="-Xmx3g" ./mvnw clean verify` | `Tests run: 693, Failures: 0, Errors: 0, Skipped: 9`; `Tests 104 passed (104)`; BUILD SUCCESS |
| Stories | `pnpm test:stories` | `Test Files 9 passed (9)`, `Tests 45 passed (45)` |
| e2e | `E2E_GATEWAY_PORT=18500 pnpm e2e` | `20 passed (1.1m)` |
| reqstool | `reqstool status local -p docs/reqstool` | `247/247 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `31 passed, 0 failed` |
| MkDocs | `mkdocs build --strict` | built clean |

**247, not 248.** One requirement fewer because `GW_AUTH_0027` was retired; the
count moving down is the change, not a gap.

**e2e ran on port 18500.** `e2e/run-e2e.sh` defaults the gateway to 8081, which is
React Native Metro's default and was held by another project on this machine. Filed
as a follow-up rather than fixed here.

## What the published document shows

Verified programmatically against `src/main/frontend/openapi.json` after
regeneration:

```
uppercase enum values in the published document: NONE
unversioned paths: ['/docs', '/docs/scalar.js', '/hooks/{marketplace}']
```

Those three are deliberate: `/docs` is the Scalar reference UI, and `/hooks` is
the forge-facing receiver. The contract promise covers `/api/**` and
`/status/**`, and both carry the segment.

## Three defects the gates caught that review would not have

1. **The documentation was right and the code was wrong.** Seven
   `allowableValues` arrays said `CLEAR`/`BLOCKED`, and the webhook summary
   really did emit `effect.outcome().name()`. Lowercasing the arrays alone would
   have made the contract lie; `VettingService` had to change too.
2. **The docs sweep over-reached.** `/api/` also appears in documentation *file*
   paths, so the first pass turned `reference/api/tokens.md` into
   `api/v1/tokens.md` across 20 pages. Reverted; `mkdocs --strict` is what would
   have caught it had the diff not been read first.
3. **A blanket case sweep inverted a normalisation.** `audit-status.ts` lower-cased
   its `switch` labels while still calling `.toUpperCase()` on the input, so every
   ledger row silently read as neutral. A UI test caught it; the fix was to normalise
   the direction the module's own comment already documented.
