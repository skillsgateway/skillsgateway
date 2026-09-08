# Evidence: decompose-resolved-closure-requirement

Commit under test: **`eebcf0f0ea0e5fb8320441f65d482b84442f02c4`** —
`docs(reqstool): decompose GW_INGEST_0030 into per-guarantee children`.

All gates below were run from the worktree
(`.claude/worktrees/docs-decompose-resolved-closure/`) after the last code
edit, in the order the project's `CLAUDE.md` specifies. No source, test or
requirement file changed between any run and this report.

Environment for every container-backed gate:

```
TESTCONTAINERS_RYUK_DISABLED=true
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

## 1. `./mvnw clean verify`

The first attempt, run without `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` set,
failed with 370 errors and 0 assertion failures, all traced to the same root
cause:

```
com.github.dockerjava.api.exception.InternalServerErrorException: Status 500:
{"cause":"operation not permitted","message":"failed to change selinux label:
insufficient permissions ...": lsetxattr(label=system_u:object_r:container_file_t:s0)
/var/run/docker.sock: operation not permitted","response":500}
Caused by: org.testcontainers.containers.ContainerLaunchException:
  Container startup failed for image floci/floci:1.6.0
```

That is Testcontainers trying to relabel the host-side `/var/run/docker.sock`
symlink when bind-mounting it into the Floci dev-service container — a
Podman-machine-on-macOS quirk, not a code regression: every failure was an
`Errors` (`ApplicationContext` load failure), never an assertion `Failure`,
and the symptom disappears once Testcontainers is told the socket path as
seen *inside* the Podman machine rather than the host-side symlink.
`podman info --format json` confirmed the in-VM path is
`/run/user/501/podman/podman.sock`; setting
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` to it and retrying succeeded outright:

```
[INFO] Tests run: 619, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
```

Exit code 0. `clean` is not optional here: the reqstool annotation processor
writes per-source-set files that incremental compilation truncates, and a
truncated file would report the moved `@Requirements` / `@SVCs` as missing.

The test classes this change touches:

```
Tests run: 8  -- in dev.skillsgateway.server.SnapshotClosureTests
Tests run: 17 -- in dev.skillsgateway.server.ingestion.SnapshotClosureDigestTests
```

(`SnapshotClosureDigestTests` is 6 plain `@Test` methods plus the parameterized
`every_member_field_is_an_input`, which expands to 11 cases — one per member
field.)

both with 0 failures and 0 errors.

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

Exit code 0. No retry.

## 3. `(cd src/main/frontend && pnpm e2e)`

```
  13 passed (53.0s)
normalized classnames in .../src/main/frontend/test-results/playwright-junit.xml
```

Exit code 0. No retry. Run before the reqstool gate in the same working tree,
as reqstool reads `src/main/frontend/test-results/`.

## 4. `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
187/187 complete · 0 incomplete · PASS
```

Last line is `PASS`. 187, up from 180 before this change — the parent plus
seven children (`GW_INGEST_0030`, `GW_INGEST_0030.1` – `GW_INGEST_0030.7`) are
listed complete:

```
  GW_INGEST_0030      skills-gateway
  GW_INGEST_0030.1    skills-gateway
  GW_INGEST_0030.2    skills-gateway
  GW_INGEST_0030.3    skills-gateway
  GW_INGEST_0030.4    skills-gateway
  GW_INGEST_0030.5    skills-gateway
  GW_INGEST_0030.6    skills-gateway
  GW_INGEST_0030.7    skills-gateway
```

## 5. `openspec validate --all --strict`

```
Totals: 36 passed, 0 failed (36 items)
```

Exit code 0, including `change/decompose-resolved-closure-requirement`.

## 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 0.83 seconds
```

Exit code 0.

## Independent id check

Because several branches can append ids to the same two files concurrently,
counted directly against the worktree's `docs/reqstool/requirements.yml` and
`software_verification_cases.yml` at the commit under test:

```
reqs 187 dupes []
svcs 187 dupes []
svc->unknown req []
reqs with no svc []
```

187 requirements, 187 SVCs, no duplicate id in either file, no SVC pointing at
a requirement that does not exist, and no requirement without an SVC. This
change minted no top-level `GW_INGEST_NNNN` id and renumbered nothing.
