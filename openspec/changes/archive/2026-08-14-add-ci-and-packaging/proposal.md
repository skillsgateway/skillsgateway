# Proposal: add-ci-and-packaging

## Why

Every quality gate (build, tests, requirements traceability, spec validation) currently
runs only on a developer machine; nothing enforces them per PR, and there is no
deployable artifact of the gateway. CI enforcement and release packaging are the agreed
next step after the Java port merged (issue #4, step 2).

## What Changes

- GitHub Actions workflow running the per-PR gates: `./mvnw verify` (Docker available
  for Arconia dev services), `reqstool status`, and `openspec validate --all --strict`.
- GitHub Actions native-image workflow on a slower cadence (main pushes + weekly +
  manual dispatch) via `graalvm/setup-graalvm`: builds the GraalVM native binary,
  packages it into an OCI image, smoke-tests it, and uploads it.
- CycloneDX SBOM published as a build artifact from the per-PR workflow.
- Dockerfile packaging the native binary as an OCI container image.
- Helm chart for deploying the container image (gateway + PostgreSQL dependency wiring).
- Renovate configuration with Spring Boot-related dependencies grouped.
- New requirement GW_0015 (container distribution, `implementation: configuration`)
  with SVC_GW_0015 verified by an automated packaging-consistency test; the real
  container build + smoke test runs in the native CI workflow.
- Local runnability of the packaged image: `compose.yaml` (gateway + PostgreSQL)
  and README instructions (user request).
- springdoc OpenAPI exposure (`api/OpenAPI.java` with `@OpenAPIDefinition`,
  `/v3/api-docs`, Swagger UI) — user request; also the portal prerequisite from
  issue #4 step 4. No new requirement: it documents existing surfaces.

## Capabilities

### New Capabilities

- `release-packaging`: the gateway is distributable as an OCI container image running
  the native binary, deployable via a provided Helm chart (GW_0015).

### Modified Capabilities

(none — no existing requirement changes)

## Impact

- New files: `.github/workflows/`, `Dockerfile`, `helm/skills-gateway/`, `renovate.json`.
- `docs/reqstool/requirements.yml` and `software_verification_cases.yml`: GW_0015 / SVC_GW_0015.
- No application code changes; the per-PR gates codify the existing local gates.
