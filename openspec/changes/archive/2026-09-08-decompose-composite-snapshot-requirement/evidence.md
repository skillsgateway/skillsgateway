# Evidence: decompose-composite-snapshot-requirement

Commit under test: **`7f2baa3a1e96857cd938b84467616fc9e7b9b0b4`** —
`docs(reqstool): decompose GW_INGEST_0024 into per-guarantee children`.

All gates below were run from the worktree
(`.claude/worktrees/docs-decompose-composite-snapshot/`) after the last code
edit, in the order the project's `CLAUDE.md` specifies. No source, test or
requirement file changed between any run and this report.

Environment for every container-backed gate:

```
TESTCONTAINERS_RYUK_DISABLED=true
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

`/var/run/docker.sock` was confirmed present on the host before the first run.

## 1. `./mvnw clean verify`

Succeeded on the first attempt:

```
[INFO] Tests run: 619, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  03:14 min
```

Exit code 0. `clean` is not optional here: the reqstool annotation processor
writes per-source-set files that incremental compilation truncates, and a
truncated file would report the moved `@Requirements` / `@SVCs` as missing.

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

Exit code 0. No retry.

## 3. `(cd src/main/frontend && pnpm e2e)`

The first two attempts were killed outright (exit 137 / SIGKILL, no test
output at all) — a host-level resource-contention symptom, not an assertion
failure: `podman ps -a` showed the compose stack's containers
(`…-postgres-1`, `…-oidc-1`) left running from the killed attempts (the
script's `trap cleanup EXIT` cannot run cleanup against `SIGKILL`), the host
had well under 100MB of free physical pages at the time
(`memory_pressure`/`vm_stat`), and several other long-lived Claude Code
sessions and browser processes were competing for the same machine. This
machine's `podman-machine-default` VM is fixed at 8GiB, independent of host
memory pressure.

Removed the two orphaned containers by name (`podman rm -f
docs-decompose-composite-snapshot-postgres-1
docs-decompose-composite-snapshot-oidc-1`) and retried once more:

```
Running 13 tests using 1 worker
  ✓  1..13 [chromium] › e2e/portal.spec.ts …
  13 passed (48.6s)
normalized classnames in .../test-results/playwright-junit.xml
```

Exit code 0 on the third attempt. `fullyParallel: false` and `retries: 0` are
already the project's Playwright configuration — no test-side flakiness, and
nothing about this change touches the e2e suite's fixtures or specs.

## 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
194/194 complete · 0 incomplete · PASS
```

Last line is `PASS`. 194, up from 187 before this change — the parent plus
seven children (`GW_INGEST_0024`, `GW_INGEST_0024.1` – `GW_INGEST_0024.7`) are
listed complete:

```
  GW_INGEST_0024      skills-gateway
  GW_INGEST_0024.1    skills-gateway
  GW_INGEST_0024.2    skills-gateway
  GW_INGEST_0024.3    skills-gateway
  GW_INGEST_0024.4    skills-gateway
  GW_INGEST_0024.5    skills-gateway
  GW_INGEST_0024.6    skills-gateway
  GW_INGEST_0024.7    skills-gateway
```

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed (36 items)
```

Exit code 0, including `change/decompose-composite-snapshot-requirement`.

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 0.81 seconds
```

Exit code 0.

## Independent id check

Because several branches can append ids to the same two files concurrently,
counted directly against the worktree's `docs/reqstool/requirements.yml` and
`software_verification_cases.yml` at the commit under test:

```
reqs 194 dupes []
svcs 194 dupes []
svc->unknown req []
reqs with no svc []
```

194 requirements, 194 SVCs, no duplicate id in either file, no SVC pointing at
a requirement that does not exist, and no requirement without an SVC. This
change minted no top-level `GW_INGEST_NNNN` id and renumbered nothing.
