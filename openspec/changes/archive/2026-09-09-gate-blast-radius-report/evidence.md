# Evidence: gate-blast-radius-report

One fresh run of every gate, after the last code edit, at commit
`cb2820b` (`fix(auth): gate the blast-radius report behind approver scoping`).

Run on 2026-09-09. Each command below is the one in `CLAUDE.md`, with the tail
of its real output pasted underneath.

## 1. `./mvnw clean verify`

```
[INFO] Tests run: 619, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  07:07 min
```

The failure this run had to clear first is worth recording: the first attempt
failed `OpenApiContractTests.publishedDocumentMatchesTheServedOne`, because the
`@Operation` description and the added `@ApiResponse(403)` change the served
document. `src/main/frontend/openapi.json` and `src/api/types.gen.ts` were
regenerated with `pnpm gen:api-types`.

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Duration  3.27s (transform 0ms, setup 2.44s, import 1.01s, tests 1.32s, environment 0ms)
```

Needed a matching browser first: the cache held chromium build 1234 and
Playwright 1.63.0 wants 1243. `pnpm exec playwright install chromium`;
recorded in `~/agents/skillsgateway/env/` with an `undo.sh`.

## 3. `(cd src/main/frontend && pnpm e2e)`

```
  ✓   9 [chromium] › e2e/portal.spec.ts:425:1 › a_revoked_snapshot_shows_its_violation_and_who_had_already_fetched_it (6.5s)
  13 passed (44.5s)
```

Case 9 is the one that matters here: it drives the "Already fetched by" panel in
a real browser against the real jar. It still passes because that session holds
the admin role through the identity provider's group claim — which is the
positive half of this change, proved end to end rather than only in MockMvc.

## 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
187/187 complete · 0 incomplete · PASS
```

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed (36 items)
```

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 1.23 seconds
```

## What the gates do not cover

- **oasdiff does not see this change as breaking**, and cannot: adding a `403`
  response is additive, and no diff of the document can tell that a caller who
  received `200` now receives `403`. That is recorded in
  `docs/manual/reference/compatibility.md` rather than left to be rediscovered.
- **No test asserts the auditor's exclusion.** A global auditor who is not an
  approver of the marketplace is refused, by construction — `requireApproverOfSnapshot`
  composes admin ⊇ approver only. It is asserted for a no-role session and for a
  wrong-marketplace approver, which are the two directions that carry the risk.
