# Evidence — token-last-used

One fresh run of every gate, after the last code edit.

**Commit:** `23d22912b1de0ede2a5e73d1ef0d43df6da803b4`
**Branch:** `feat/token-last-used`, merged with `origin/main` at `06f4847`
(#400, which took `V3` for the webhook event namespace — this change's migration
was renumbered to `V4__access_token_last_used.sql`).

## `./mvnw clean verify`

```
[INFO] Tests run: 655, Failures: 0, Errors: 0, Skipped: 9
[INFO] Spotless.Java is keeping 344 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  06:30 min
```

The four `TokenLastUsedTests` cases are in that run: last use stamped by a facade
fetch and reported by `GET /api/tokens`; stamped by a machine API call and
reported by `GET /api/tokens/machine`; rewritten at most once a minute; and the
negative — an unknown secret, a revoked credential, an expired one and a
fetch-only token presented as a bearer credential all stamp nothing, with a
positive control on the same route so the assertion is not one-sided.

`AuthTests`' closed enumeration of a listed token's fields was extended
deliberately with `lastUsedAt`; it failed first with `the following elements were
unexpected: ["lastUsedAt"]`, which is the gate doing its job.

## `(cd src/main/frontend && pnpm test:stories)`

```
 ✓ |storybook (chromium)| src/pages/tokens.stories.tsx (2 tests) 459ms
 Test Files  4 passed (4)
      Tests  15 passed (15)
```

The new `LastUsed` story renders both column states under axe-as-error.

## `(cd src/main/frontend && pnpm e2e)`

```
  ✓   3 [chromium] › e2e/portal.spec.ts:159:1 › token_cleartext_is_shown_once_and_revocation_marks_it_revoked
  ✓  11 [chromium] › e2e/portal.spec.ts:531:1 › adoption_page_shows_a_real_facade_fetch_and_its_identity
  14 passed (50.1s)
```

Both carry the new assertions: a freshly minted token reads "never", and the
token that performed a real `git clone` through the facade no longer does.

## `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
223/223 complete · 0 incomplete · PASS
```

`GW_AUTH_0031` lists under COMPLETE.

## `openspec validate --all --strict`

```
Totals: 32 passed, 0 failed (32 items)
```

## `mkdocs build --strict`

```
INFO    -  Documentation built in 1.29 seconds
```
