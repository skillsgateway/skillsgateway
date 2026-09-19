# Evidence: administrative-revocation

Commit `bf98eb1f`, branch `feat/administrative-revocation`, stacked on
`feat/single-init-migration` (#426). One fresh run of every gate after the last
edit.

## Gates

```console
$ ./mvnw clean verify
[INFO] BUILD SUCCESS
[INFO] Total time:  05:40 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  9 passed (9)
      Tests  45 passed (45)

$ (cd src/main/frontend && pnpm e2e)
  20 passed (1.1m)

$ reqstool status local -p docs/reqstool
246/246 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
0 warnings, 0 errors
```

## The change's own tests

```console
$ ./mvnw test -Dtest=AdministrativeRevocationTests
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

Ten of the twelve are negative, which is the point of the `old-coder`
discipline on a trust boundary:

| What it attacks | Why it is the one worth writing |
| --- | --- |
| Withdrawal with a null, empty and blank reason | The reason is the whole accountability for a one-identity act |
| Withdrawal of a held, and of an already-withdrawn, snapshot | A second withdrawal must not unpublish twice |
| Two concurrent withdrawals | Asserts the content is unpublished **exactly once**, not merely that one caller threw |
| A return with nothing to return to | Asserts the snapshot is **still approved and still served** — an implementation that withdrew first and discovered the problem afterwards would pass on the exception alone |
| Withdrawing a snapshot that is not the served one | The served set must not move |
| Ordinary approval of a withdrawn commit | Refused, and the refusal carries reason, actor and time |
| The revoker reversing their own withdrawal | Refused unconditionally |
| Reversal with no reason | Refused |
| Reversal when nothing was withdrawn | Refused rather than ignored, so the marker's absence is never ambiguous |
| A waiver against a withdrawal | Does not lift it |
| Retention purging a withdrawn record | Refused, while a re-vetting-revoked record still purges |
| `EstateReconciler`'s dependencies | Structural: it is never handed the means to withdraw content |

## Failures seen and what they were

Two failures during the run, both resolved rather than worked around.

**`ContextBudgetTests` — a 26th Spring context, budget 25.** The new suite
declared its own `@TestPropertySource` for two administrators. Resolved by using
`root` and `alice`, which the shared `GatewayContext` already configures, so the
suite shares the cached context. The budget was **not** raised — it is a ratchet,
and raising it is the act a proposal has to argue for.

**`OpenApiContractTests` — the portal's `openapi.json` was stale.** Regenerated
with the command the test names, which also refreshed `types.gen.ts`.

**One flake, re-run.** `pnpm test:stories` failed once with
`The iframe ".../adoption.stories.tsx" did not become ready within 60000ms` — a
tester-initialisation timeout in a story this change does not touch. Passed on
re-run with all 9 files and 45 tests. `CatalogTests` also failed once in an
earlier full run and passed on the next; both are recorded rather than hidden.

## Two things deliberately not built

**`GW_INGEST_0032` was withdrawn during implementation.** The first draft
specified an ingestion gate on the premise that a revoked commit would reappear
as held on the next sync. That premise is false: `IngestionService` already
returns the existing row for a `(marketplace, sha)` in any state. The real gap
was retention, and `GW_RETENTION_0008` closes it.

**Reaching clients that already hold withdrawn content** is
[#427](https://github.com/skillsgateway/skillsgateway/issues/427), and is out of
scope here by design.
