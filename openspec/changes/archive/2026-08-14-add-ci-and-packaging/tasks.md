# Tasks: add-ci-and-packaging

## 1. Requirements SSOT

- [x] 1.1 Add GW_0015 (container distribution, implementation: configuration) to docs/reqstool/requirements.yml
- [x] 1.2 Add SVC_GW_0015 (automated packaging-consistency test) to docs/reqstool/software_verification_cases.yml

## 2. Per-PR gate workflow

- [x] 2.1 Add .github/workflows/ci.yml: pull_request + push(main); checkout fetch-depth 0, Temurin 25, `./mvnw verify`, `reqstool status local -p docs/reqstool` (pipx), `openspec validate --all --strict` (npm)
- [x] 2.2 Upload the CycloneDX SBOM (target/classes/META-INF/sbom/application.cdx.json) as a build artifact from ci.yml

## 3. Container packaging (SVC_GW_0015)

- [x] 3.1 Add Dockerfile: distroless base copying the prebuilt native binary `target/skills-gateway`
- [x] 3.2 Add .github/workflows/native.yml: push(main) + weekly cron + workflow_dispatch; graalvm/setup-graalvm CE 25, `./mvnw -Pnative -DskipTests package`, docker build, smoke test (PostgreSQL service, /actuator/health UP), helm lint, upload image + SBOM
- [x] 3.3 Add PackagingTests with @SVCs(SVC_GW_0015) validating Dockerfile/chart/compose consistency
- [x] 3.4 Add compose.yaml for local run of the image with PostgreSQL; document in README

## 4. Helm chart (SVC_GW_0015)

- [x] 4.1 Add helm/skills-gateway chart: Chart.yaml, values.yaml (image, external PostgreSQL coords + existingSecret, OIDC settings), Deployment, Service, helpers
- [x] 4.2 Run `helm lint helm/skills-gateway` in native.yml

## 5. Renovate

- [x] 5.1 Add renovate.json: config:recommended, group org.springframework.boot* + io.arconia*, maven-wrapper manager; README notes the Renovate app must be enabled

## 6. OpenAPI exposure (user request; portal prerequisite)

- [x] 6.1 Add springdoc-openapi-starter-webmvc-ui 3.1.0 and api/OpenAPI.java with @OpenAPIDefinition

## 7. Verification

- [x] 7.1 Run the full local gates (`./mvnw verify`, reqstool status, openspec validate --all --strict) and `helm lint`
