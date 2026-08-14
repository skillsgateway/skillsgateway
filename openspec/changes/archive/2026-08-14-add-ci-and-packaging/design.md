# Design: add-ci-and-packaging

## Context

All gates are local-only today: `./mvnw verify` (Surefire + Arconia dev-services
PostgreSQL via Docker, Spotless, Checkstyle, reqstool-maven-plugin annotations, CycloneDX
SBOM), `reqstool status local -p docs/reqstool`, and `openspec validate --all --strict`.
Versioning is git-derived through the Nisse Maven extension (`.mvn/extensions.xml`), so
builds need full git history. The native image (profile `native`, GraalVM CE 25) was
verified manually on a developer machine. There is no `.github/` directory, no container
image, and no deployment artifact.

## Goals / Non-Goals

**Goals:**
- Enforce the three local gates per PR in GitHub Actions.
- Build the native image on a slower cadence and package it as an OCI image with a smoke test.
- Publish the CycloneDX SBOM as a CI build artifact.
- Provide a Helm chart deploying the container image.
- Automate dependency updates with Renovate, grouping Spring Boot artifacts.

**Non-Goals:**
- Publishing images to a registry (no registry decided yet; the image is built and
  smoke-tested in CI, uploaded as a workflow artifact only).
- CD / environment deployment; the chart is delivered, not applied anywhere.
- Coverage thresholds or additional static analysis beyond the existing gates.

## Decisions

- **Two workflows, not one.** `ci.yml` runs the fast per-PR gates on `pull_request` and
  pushes to `main`; `native.yml` (native image + container + Helm lint + smoke) runs on
  pushes to `main`, weekly cron, and `workflow_dispatch`. Rationale: native compilation
  takes many minutes and would tax every PR (issue #4 prescribes the slower cadence).
- **`actions/checkout` with `fetch-depth: 0`** in all jobs — Nisse derives the version
  from git history/tags; a shallow clone breaks it.
- **Temurin 25 via `actions/setup-java`** for the gate job; **GraalVM CE 25 via
  `graalvm/setup-graalvm`** for the native job — mirrors the machine setup that verified
  the port (Temurin for JVM builds, GraalVM only for native).
- **reqstool CLI via `pipx install reqstool`**; OpenSpec CLI via
  `npm install -g @fission-ai/openspec` — same tools/versions as the local gates.
- **Distroless-style runtime image**: multi-stage Dockerfile is unnecessary since CI
  builds the binary; the Dockerfile `COPY`s the prebuilt `target/skills-gateway` binary
  onto a minimal base (`gcr.io/distroless/base-debian12`, glibc for the
  dynamically-linked default native binary). Alternative considered: in-Docker native
  build (multi-stage) — rejected: slower, duplicates the CI toolchain, and the CI runner
  already produces the binary.
- **Smoke test in CI**: run the image with a PostgreSQL service container, wait for
  Actuator health, assert `200` — the same signal used in the manual native verification
  (start, Flyway migrates, serves HTTP).
- **Helm chart under `helm/skills-gateway/`**: Deployment, Service, and values for
  image, external PostgreSQL coordinates (host/db/user + existing-secret reference), and
  OIDC settings. PostgreSQL itself is not templated (enterprise deployments bring their
  own); `helm lint` runs in the native workflow.
- **GW_0015 modeled as `implementation: configuration` with a build-phase SVC.**
  A post-build-only SVC was tried first, but reqstool's status verdict requires at
  least one build-phase SVC per requirement, so it can never complete without
  `--with-post-tests`. Instead: the requirement is non-code (`configuration` — the
  Dockerfile and chart), and SVC_GW_0015 is an automated packaging-consistency test
  (`PackagingTests`) asserting the Dockerfile runs the native binary and the chart
  wires image, probes, and PostgreSQL/OIDC configuration. The real container build +
  smoke test still runs in the native workflow as defense in depth.
- **Local runnability** (user request): `compose.yaml` runs the built image with
  PostgreSQL; README documents build → image → compose.
- **springdoc 3.1.0** (Boot 4 line) with `api/OpenAPI.java` (`@OpenAPIDefinition`)
  per user instruction; docs sit behind the OIDC session like the rest of the web
  surface (GW_0011 untouched).
- **Renovate over Dependabot**: issue #4 names Renovate; `renovate.json` groups
  `org.springframework.boot*` + `io.arconia*` (Boot-aligned BOM) into one PR stream and
  enables the Maven wrapper manager.

## Risks / Trade-offs

- [Native build flakiness / long runtime on ubuntu runners] → cadence limited to
  main/weekly/dispatch; `workflow_dispatch` allows on-demand retry.
- [Default dynamically-linked binary vs musl/static] → distroless *base* (glibc)
  matches the default `native-maven-plugin` output; revisit static+musl if a scratch
  image is later required.
- [reqstool/OpenSpec CLI drift between local and CI] → versions pinned in the workflow.
- [Renovate needs the GitHub App enabled on the repo] → config is inert until the app
  is installed; noted in tasks.

## Migration Plan

Pure addition: merge → workflows activate on the next PR/push. Rollback = delete the
workflow files. No application or schema changes.

## Open Questions

- Container registry + image signing (cosign?) — deferred until a registry is chosen.
