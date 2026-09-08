# Evidence: jvm-container-release

Branch `feat/jvm-container-release`, worktree `.claude/worktrees/jvm-container`.
Run 2026-09-08, after the last code edit.

Commit under test: `aaf48f5464533b2784c8cf38fd631a45c37ef8df`  (`style(test): apply spotless to the packaging test`)

## The environment, stated before the results

This machine could not produce a clean container-backed run on demand while this
change was being built, and the reason is worth recording because it is not the
change.

Seven other agents were running `./mvnw clean verify` against one rootless
podman machine (8 GiB, 5 CPUs). Every failure across the first four attempts
bottomed out in the same podman error on a container start:

```
com.github.dockerjava.api.exception.InternalServerErrorException: Status 500:
  {"cause":"something went wrong with the request: \"proxy already running\\n\"",
   "message":"something went wrong with the request: \"proxy already running\\n\"",
   "response":500}
```

That is podman's rootless port-forwarding proxy colliding, and it is not purely
cross-agent load. Surefire here declares no `forkCount` and no `parallel`
configuration, yet a single `verify` holds over thirty live containers at once:
`spring.test.context.cache.maxSize=8` against roughly thirty distinct context
property sets means contexts are evicted and rebuilt throughout the run, each
rebuild cycles its Floci and PostgreSQL containers, and each cycle cycles a
published port through that proxy. Overlapping start/stop cycles collide.

A cross-agent mutex was introduced part-way through and reduced it sharply, but
the intra-suite collision rate is not something a mutex can remove.

### Every attempt, in order

| # | Conditions | Tests run | Errors | Root cause of every error |
| --- | --- | ---: | ---: | --- |
| 1 | unlocked, ~48 live containers | 589 | 63 | `proxy already running` |
| 2 | unlocked | 589 | 3 | `proxy already running` |
| 3 | unlocked, peak contention | 571 | 92 | `proxy already running` |
| 4 | **under the mutex** | 589 | 6 (all `MinimumReleaseAgeTests`) | `proxy already running` |
| 5 | under the mutex | 589 | **0** | — (build failed later, on Spotless) |
| 6 | under the FIFO queue | 589 | **0** | — (`BUILD SUCCESS`) |

No failure in any attempt touched the `Dockerfile`, the workflow contract, the
migration runner, or `PackagingTests`. Attempts 1–4 are recorded because they
happened, not because they indicate anything about this change.

**Attempt 5 found a real defect and is why attempt 6 exists.** The suite came
back 589/0/0, and the build then failed in `spotless:check` on a line-wrap in
`PackagingTests.java`. That is a genuine gate failure, it was fixed with
`spotless:apply` (commit `aaf48f5`), and attempt 6 is the run after that fix.

A second real defect was found while waiting on the queue: two of the amended
reqstool rationales carried a colon-space inside an unquoted YAML scalar, so
`requirements.yml` no longer parsed at all. Fixed in commit `369ff45`. It would
have failed the traceability gate regardless of any container.

## Gates

### 1. `./mvnw clean verify`

```
[INFO] Tests run: 589, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:29 min
[INFO] Finished at: 2026-09-08T02:07:26+02:00
```

Zero occurrences of `proxy already running` in this run.

### 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Start at  02:07:44
   Duration  2.88s

JUNIT report written to …/src/main/frontend/test-results/storybook-junit.xml
```

### 3. `(cd src/main/frontend && pnpm e2e)`

Real chromium against the mock OIDC IdP and a real PostgreSQL, both in
containers.

```
Running 13 tests using 1 worker
  ✓   1 [chromium] › e2e/portal.spec.ts:67:1 › admin_registers_ingests_and_approves_a_marketplace_in_the_portal (6.4s)
  …
  ✓  13 [chromium] › e2e/portal.spec.ts:665:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (271ms)

  13 passed (42.7s)
normalized classnames in …/src/main/frontend/test-results/playwright-junit.xml
```

### 4. `reqstool status local -p docs/reqstool`

Run after `pnpm e2e` in the same tree, because it reads the portal suites' JUnit
output from `src/main/frontend/test-results/`. The last line is the result —
this command exits 0 even when it prints FAIL.

```
INCOMPLETE (0)
165/165 complete · 0 incomplete · PASS
```

### 5. `openspec validate --all --strict`

```
✓ change/jvm-container-release
✓ spec/git-storage
✓ spec/release-packaging
Totals: 32 passed, 0 failed (32 items)
```

### 6. `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: …/site
INFO    -  Documentation built in 1.09 seconds
```

## Artifact measurements

These are the numbers ADR 0012 asked to be measured rather than assumed. Taken
on the image this change builds, under a 1 GiB limit with a read-only root
filesystem and tmpfs at `/tmp` and `/data` — the shape the Helm chart deploys —
against a real PostgreSQL.

### Size

```console
$ docker build -t skills-gateway:jvm .              # arm64, host-native
$ docker build --platform linux/amd64 -t skills-gateway:jvm-amd64 .
$ docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep jvm
localhost/skills-gateway:jvm-amd64 185 MB
localhost/skills-gateway:jvm 195 MB

$ docker save skills-gateway:jvm-amd64 | gzip -6 -c | wc -c
amd64 image gzip: 131.2 MB
$ docker save skills-gateway:jvm | gzip -6 -c | wc -c
jvm image, gzip of docker save: 130.2 MB
```

Layer composition (arm64):

```
69.2MB  COPY … in /app/skills-gateway-server.jar
79.4MB  COPY … in /opt/java
        + gcr.io/distroless/java-base-debian12:nonroot (46.1 MB)
```

The previously published native image, for comparison — the sum of the
`0.2.0-b2` manifest's layer sizes read from GHCR, so a like-for-like compressed
figure:

```
total compressed layers: 70146175 = 70.1 MB
```

The amd64 image was cross-built under emulation. Only its *size* is quoted from
that build; ADR 0012 already records an emulated runtime measurement being
discarded as invalid.

### Startup and memory

```console
$ docker run -d --name sgw-app -p 18080:8080 --memory 1g --read-only \
    --tmpfs /tmp:rw,mode=1777 --tmpfs /data:rw,mode=1777 \
    -e SPRING_DATASOURCE_URL=… -e SKILLSGATEWAY_ROLES_ADMINS_0=smoke-test \
    -e SERVER_FORWARDHEADERSSTRATEGY=framework skills-gateway:jvm
HEALTHY after 5.0 s (wall, incl. container create)
{"groups":["liveness","readiness"],"status":"UP"}

Tomcat started on port 8080 (http) with context path '/'
Started SkillsGatewayApplication in 3.61 seconds (process running for 4.136)

$ docker stats --no-stream sgw-app          # after 45 s idle
sgw-app mem=255.3MB / 1.074GB (23.78%)
```

Against the chart's own reservation — `requests.memory: 512Mi`,
`limits.memory: 1Gi` — 255 MB idle is half the request and a quarter of the
limit. The existing sizing holds with headroom, so the chart needed no change.
Both this figure and ADR 0012's native figure are idle: JGit packing a large
upstream repository is the dominant term neither contains, and it costs the same
on either packaging.

### The forwarded-header assertion, on the real image

The one thing the JVM test suite structurally cannot see, checked against the
built container rather than a test context:

```console
$ curl -sS -o /dev/null -w '%{redirect_url}' \
    -H 'X-Forwarded-Proto: https' -H 'X-Forwarded-Host: gateway.example.com' \
    http://localhost:18080/oauth2/authorization/idp
https://idp.invalid/authorize?…&redirect_uri=https://gateway.example.com/login/oauth2/code/idp&…
```

The CI smoke test makes the same assertion, and now also runs the container with
`--read-only`.

### CDS / the Java 25 AOT cache — evaluated, not adopted

ADR 0012 asks for this to be evaluated rather than assumed. Same jar, same host,
timing context refresh with `-Dspring.context.exit=onRefresh`, three runs each:

```console
$ java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -jar …
$ ls -la app.jsa
-rw-r--r--  1 r04755  wheel  68632576  app.jsa

no-cds real 2.91      cds    real 2.35
no-cds real 2.53      cds    real 2.22
no-cds real 2.43      cds    real 2.21
```

About 0.25 s, roughly 10%. It costs a 68.6 MB archive in the image — a third
again on top of the current size — and a **training run that needs a live
PostgreSQL during `docker build`**, which the build has no access to:
`-Dspring.context.exit=onRefresh` still refreshes the context, so the DataSource
and the schema migration are on that path. Not adopted; the number is recorded
in `design.md` so the next look starts from it.

### The jlink module set, on both architectures

The module set is hand-written rather than derived from `jdeps`, so the evidence
that it is complete is that the image boots. It was built and booted on arm64
(above) and cross-built for amd64, where the `jlink` stage resolved every named
module:

```
[1/2] STEP 2/5: RUN "$JAVA_HOME/bin/jlink" --add-modules java.base,…,jdk.zipfs …
--> 98dc64028041
```

`eclipse-temurin:25-jre-noble`, the fallback ADR 0012 documents for a
troublesome `jlink`, was **not** needed and was **not** taken.
