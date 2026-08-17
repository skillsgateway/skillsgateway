# Evidence: virtual-catalog

One final fresh run of every gate after the last code edit. Commit SHA at the
bottom.

## Discipline notes

- Spec approval: not obtained per-spec (autonomous run under the owner's
  standing "continue, stack PRs" authorization); the design follows the sketch
  the owner agreed to in issue #11's comments ("agreed. do that.").
- Non-vacuity mutant (killed, then restored — verified via git diff): the
  catalog rebuild hook removed from the revocation path
  (`RevetService.quarantine`) → `SVC_GW_0062` test failed (revoked
  marketplace stayed in the catalog). All four tests passed on their first
  full run otherwise; the mutant is the proof the load-bearing freshness
  assertion can fail.
- The empty-estate case runs in a dedicated Spring context (fresh database),
  ordered first; the revocation case reuses the enforce-mode pattern from
  RevetEnforceTests with a real git client, including SHA-by-name
  unreachability against the parentless catalog history.

## Gates

### `./mvnw clean verify`

```
[INFO] BUILD SUCCESS
[INFO] Total time:  38.484 s
surefire aggregate: tests=67 failures=0 errors=0 skipped=0
```

### `(cd src/main/frontend && pnpm e2e)`

```
  8 passed (19.8s)
```

### `reqstool status local -p docs/reqstool`

```
63/63 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 16 passed, 0 failed (16 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 0.42 seconds
```

## Commit

`1ff94db` (implementation; the archive commit follows it and changes no code)
