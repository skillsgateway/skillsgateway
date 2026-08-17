# Evidence: adoption-dashboards-otel

One final fresh run of every gate after the last code edit. Commit SHA at the
bottom.

## Discipline notes

- Non-vacuity mutant (killed, then restored — verified by re-running the
  suite): the staleness comparison inverted to skip marketplaces that stopped
  serving (`tip.isEmpty() || …` instead of `tip.isPresent() && …`) →
  `SVC_GW_0076` failed exactly on the after-retraction assertion
  (`containsExactlyInAnyOrder(alice, bob)` found nothing). All three backend
  tests passed on their first full run otherwise; the mutant is the proof the
  load-bearing retraction assertion can fail.
- Every counted fetch in the tests is a real `git clone` through the facade
  with a PAT, so the aggregated rows are the rows production writes; the
  out-of-window case is planted directly on the ledger (the repository offers
  no backdated append, deliberately) and asserted both excluded at 30 days and
  included at 365.
- The two new reads are classified in RoleEnforcementTests'
  `PRIVILEGED_READS`; its deny-walk and auditor tests passed unchanged apart
  from that classification. No existing SVC test was weakened.
- Impeccable (`audit` + detector + `harden`/`critique` review of the new
  page): the detector reported zero findings; one convention-drift finding was
  fixed in its own commit (timestamps rendered localized where the portal
  convention is raw ISO-8601). No findings dismissed.
- `arconia.otel.enabled=false` and the opt-in `observability` profile are
  untouched; `SVC_GW_0077` asserts the meters exist under exactly that default.

## Gates

### `./mvnw clean verify`

```
[INFO] BUILD SUCCESS
[INFO] Total time:  46.111 s
surefire aggregate: tests=81 failures=0 errors=0 skipped=0
```

### `(cd src/main/frontend && pnpm e2e)`

```
  9 passed (21.4s)
```

### `reqstool status local -p docs/reqstool`

```
76/76 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 19 passed, 0 failed (19 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 0.45 seconds
```

## Commit

`6d650c0` (implementation; the archive commit follows it and changes no code)
