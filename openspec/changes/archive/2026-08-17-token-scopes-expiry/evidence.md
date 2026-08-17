# Evidence: token-scopes-expiry

One final fresh run of every gate after the last code edit. Commit SHA at the
bottom.

## Discipline notes (old-coder Tier 3 — facade auth is a trust boundary)

- Spec approval: not obtained per-spec (autonomous run under the owner's
  standing "continue, stack PRs" authorization, and their direct confirmation
  that scoped tokens are the next roadmap item).
- Trust-boundary mutants (both killed, then restored — verified via git diff):
  1. Facade scope check disabled (`if (false && ...)`) → `SVC_GW_0064` failed
     (out-of-scope clone succeeded).
  2. Expiry filter removed from `findActiveByHash` → `SVC_GW_0065` failed
     (expired token authenticated).
- One pre-existing SVC test updated, not weakened: `AuthTests`' closed
  enumeration of the token view's fields (intent: no hash/secret leak) gained
  the three new public fields (`scopes`, `expiresAt`, `rotatedFrom`). The
  enumeration stays exact, so any accidental future field still fails it.
- Every access decision is verified with a real git client on the wire,
  including the no-existence-oracle property: the out-of-scope answer and the
  nonexistent-marketplace answer are compared line-by-line after masking
  run-specific values.

## Gates

### `./mvnw clean verify`

```
[INFO] BUILD SUCCESS
[INFO] Total time:  40.802 s
surefire aggregate: tests=72 failures=0 errors=0 skipped=0
```

### `(cd src/main/frontend && pnpm e2e)`

```
  8 passed (19.6s)
```

### `reqstool status local -p docs/reqstool`

```
67/67 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 17 passed, 0 failed (17 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 0.43 seconds
```

## Commit

`d52eef5` (implementation; the archive commit follows it and changes no code)
