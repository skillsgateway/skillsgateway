# Evidence — marketplace-webhook-events

One fresh run of every gate after the last code edit.

- Commit: `96d1be025a4a28a8454b89a7fdb1f390a1618d2a`
- Branch: `feat/marketplace-webhook-events`
- Requirements: GW_WEBHOOK_0008 — Namespaced lifecycle event names,
  GW_WEBHOOK_0009 — Marketplace administration event webhooks
- Verification cases: SVC_GW_WEBHOOK_0008, SVC_GW_WEBHOOK_0009

## `./mvnw clean verify`

```
[INFO] Tests run: 651, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  07:24 min
```

`Spotless.Java is keeping 343 files clean`, `0 Checkstyle violations`.
`OpenApiContractTests` passes against the regenerated
`src/main/frontend/openapi.json`, which is the baseline the breaking-change gate
diffs against — the twelve `webhooks` entries it now carries are
`marketplace.registered`, `marketplace.updated`, `marketplace.vetter_toggled`
and the nine `marketplace.snapshot.*`.

The two new cases, in `WebhookTests`:

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.WebhookTests
```

## `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  4 passed (4)
      Tests  14 passed (14)
   Duration  4.66s
```

## `(cd src/main/frontend && pnpm e2e)`

```
  14 passed (46.5s)
```

## `reqstool status local -p docs/reqstool`

```
  GW_WEBHOOK_0008     skills-gateway
  GW_WEBHOOK_0009     skills-gateway

INCOMPLETE (0)
222/222 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
Totals: 32 passed, 0 failed (32 items)
```

## `mkdocs build --strict`

```
INFO    -  Documentation built in 1.25 seconds
```

## Notes

- The breaking half is declared, not inferred: the pull request title carries
  `!` and the pull request carries the `⚠️ BREAKING CONTRACT` label, which is
  what `docs/manual/reference/compatibility.md` — "The API contract" requires of
  a renamed event.
- `marketplace.removed` is deliberately absent; the reason is in design.md.
