# Evidence: upstream-sync-modes

One final fresh run of every gate after the last code edit. Commit SHA recorded
at the bottom.

## Discipline notes (old-coder, Tier 3 on the inbound webhook)

- Spec approval: the OpenSpec artifacts were reviewed by a fresh-context agent
  (findings folded in: dedicated stateless security chain, per-marketplace
  ingest lock, test-controllability pattern) and the run was authorized by the
  owner's blanket "just continue" — an explicit per-spec approval was **not**
  obtained (autonomous run). The artifacts are the after-the-fact review
  surface.
- Trust-boundary mutants (both killed, then restored — verified via git diff):
  1. HMAC comparison disabled (`if (false && !MessageDigest.isEqual(...))`) →
     `SVC_GW_0058` test failed (wrong/missing-signature cases accepted).
  2. Webhook-mode gate removed (`.filter(... SYNC_WEBHOOK ...)`) →
     `SVC_GW_0058` test failed (non-webhook marketplace no longer 404).
- One real defect found by the suite before it ever ran green: the concurrency
  test deadlocked itself (`invokeAll` + latch); fixed to `submit` + bounded
  `get`. The subscriber-fixture NOT NULL violation was the only other red.

## Gates

### `openspec validate --all --strict`

```
✓ change/upstream-sync-modes
Totals: 15 passed, 0 failed (15 items)
```

### `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.41 seconds
```

### `./mvnw clean verify`

```
[INFO] BUILD SUCCESS
[INFO] Total time:  38.654 s
surefire aggregate: tests=63 failures=0 errors=0 skipped=0
```

### `(cd src/main/frontend && pnpm e2e)`

```
  8 passed (19.8s)
```

### `reqstool status local -p docs/reqstool`

```
60/60 complete · 0 incomplete · PASS
```

(Run against the final clean build — `clean verify` was re-run after the last
edit to `docs/reqstool/requirements.yml`, so every number above is from the
final source state.)

## Commit

`d781f26` (implementation; the archive commit follows it and changes no code)
