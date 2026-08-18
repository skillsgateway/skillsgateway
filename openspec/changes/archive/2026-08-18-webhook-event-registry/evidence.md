# Evidence: webhook-event-registry

One fresh run of all five gates after the last code edit.

Commit: `38c4760d12549f96fb4b16f48e415d28bea563dd`

## `./mvnw clean verify`

```
    [INFO] ------------------------------------------------------------------------
    [INFO] Total time:  01:24 min
    [INFO] Finished at: 2026-08-18T11:32:06+02:00
    [INFO] ------------------------------------------------------------------------
    [INFO] Nisse property inliner cleanup of 1 inlined POMs
```

UI gate inside it: 12 test files, 39 tests — 5 new in `webhooks.test.tsx`
(wildcard on full selection, comma-delimited on partial, disabled with none
selected, type-ahead narrows without deselecting, unknown stored event marked).
Backend: `WebhookTests` 4, `RoleEnforcementTests` 5, `OpenApiDocsTests` 1.

## `(cd src/main/frontend && pnpm e2e)`

```
      11 passed (50.4s)
```

## `reqstool status local -p docs/reqstool`

```
    86/86 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
    ✓ change/webhook-event-registry
    Totals: 23 passed, 0 failed (23 items)
```

## `mkdocs build --strict`

```
    INFO    -  Documentation built in 1.23 seconds
```

## Notes

- `GET /api/webhooks/events` is registered in `RoleEnforcementTests.PRIVILEGED_READS`,
  whose walk asserts its list against the running route table — an unclassified
  endpoint fails the suite rather than shipping ungated.
- The backend test asserts the served list equals `WebhookEvent.ALL` **and**
  excludes `WebhookEvent.AUDIT_EXPORT`.
- One pre-existing Base UI console warning remains in
  `marketplace-detail.test.tsx`; verified present on `main` before this change.
  The warning the new checkbox introduced was fixed with `nativeButton={false}`.
