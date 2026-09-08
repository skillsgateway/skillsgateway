# Tasks: jvm-container-release

## 1. Requirements (SSOT first)

- [x] 1.1 Amend `GW_RELEASE_0001 — Container distribution` in
      `docs/reqstool/requirements.yml`: the image runs the application on a
      runtime it carries, over a base with no shell and no package manager, as a
      non-root user, on a root filesystem it never writes to (revision 0.2.0)
- [x] 1.2 Amend `GW_RELEASE_0002 — Container image publication by digest`: both
      platforms from one build, smoke-tested before publication, one tagged
      multi-arch index per release, SBOM attested against the published digest
      (revision 0.5.0)
- [x] 1.3 Amend `GW_FACADE_0013 — Verified migration between storage backends`:
      whether a start is a migration is decided from the configuration the
      running process is given (revision 0.2.0)
- [x] 1.4 Amend SVC_GW_RELEASE_0001, SVC_GW_RELEASE_0002 and SVC_GW_FACADE_0013 to match

## 2. The image (SVC_GW_RELEASE_0001)

- [x] 2.1 `Dockerfile`: a `jlink` stage on `eclipse-temurin:25-jdk` producing a
      Java 25 runtime, an explicit module set, the jar staged out of its
      versioned filename with a check that there is exactly one
- [x] 2.2 Final stage on `gcr.io/distroless/java-base-debian12:nonroot`:
      runtime at `/opt/java`, jar at `/app`, nonroot-owned `/data`, `USER
      nonroot`, no `RUN` in the final stage
- [x] 2.3 `-XX:MaxRAMPercentage=75.0` on the entrypoint, with the reason in the
      file
- [x] 2.4 `PackagingTests`: assertions move from the native binary to the jar,
      and gain the base image, the non-root user and the absence of a shell
- [x] 2.5 Build it and measure image size, startup and idle RSS under a 1 GiB
      limit with a read-only root filesystem (design.md, evidence.md)
- [x] 2.6 Evaluate Spring Boot CDS / the Java 25 AOT cache and record the number
      rather than an opinion (design.md)

## 3. Publication (SVC_GW_RELEASE_0002)

- [x] 3.1 `native.yml`: one job, `actions/setup-java`, `./mvnw package` instead
      of `native:compile`
- [x] 3.2 One `buildx` multi-arch build with QEMU; the smoke-test build stays
      single-platform and `--load`ed
- [x] 3.3 The publish becomes one tagged push; the digest-handoff artifacts and
      the `publish` job are removed; the SBOM is attested against the published
      index digest
- [x] 3.4 The smoke test runs the container with `--read-only` and tmpfs at
      `/tmp` and `/data`, which is the shape the chart deploys
- [x] 3.5 `PackagingTests`: the publication contract re-asserted against the new
      shape — no matrix, both platforms in one build, one tag, no arch-suffixed
      tag, no second combine job, smoke test before push

## 4. The frozen migration runner (#308, SVC_GW_FACADE_0013)

- [x] 4.1 Audit every `@ConditionalOnProperty` and `@Profile` on an application
      bean in `src/main/java` — one site, the migration runner
- [x] 4.2 Remove the condition; the runner returns immediately when the flag is
      unset
- [x] 4.3 Tests: the bean is present with no migration configured, does nothing
      there, and still refuses a migration that names no destination

## 5. Documentation

- [x] 5.1 `guides/storage-backends.md`: the migration procedure runs the
      container or the jar, not a native binary, with a note on what the old
      instruction did
- [x] 5.2 `reference/container-image.md`: the artifact, the base, one build for
      both platforms, what the smoke test covers, measured size/startup/memory,
      the attestation subject
- [x] 5.3 `reference/compatibility.md`, `reference/configuration.md`,
      `reference/observability.md`, `reference/decisions.md`
- [x] 5.4 `guides/deploying-without-kubernetes.md`: a "Memory" section and the
      prerequisite that the container needs a limit
- [x] 5.5 `guides/deploying-on-kubernetes.md`, `guides/local-development.md`,
      `guides/releasing.md`
- [x] 5.6 `README.md`, `CONTRIBUTING.md`, `compose.yaml`,
      `helm/skills-gateway/values.yaml`, and the `release-packaging` capability's
      Purpose

## 6. Gates and evidence

- [x] 6.1 `./mvnw clean verify`
- [x] 6.2 `pnpm test:stories`, `pnpm e2e`
- [x] 6.3 `reqstool status local -p docs/reqstool` ends PASS
- [x] 6.4 `openspec validate --all --strict`, `mkdocs build --strict`
- [x] 6.5 `evidence.md` with the commands, the tails and the commit SHA
