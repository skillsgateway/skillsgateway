# Tasks: operations-readiness

## 1. The chart stops arranging the two-writer case

- [x] 1.1 `strategy: {type: Recreate}` when `storage.backend != "object-store"`, with the
      reason and the pointer to `replicaGate` in a comment.

## 2. Liveness and readiness become different questions

- [x] 2.1 Health probe groups in `application.yaml`: liveness `livenessState` only,
      readiness `readinessState,db,gitStorage`.
- [x] 2.2 Permit the two probe paths in `SecurityConfig`, **enumerated** rather than
      `/actuator/health/**` — see `design.md — Decision 3`.
- [x] 2.3 Point the chart's two probes at the two paths, and delete the comment that
      explained why they both used the aggregate.

## 3. A rollout drains

- [x] 3.1 `server.shutdown: graceful`.
- [x] 3.2 `terminationGracePeriodSeconds` value, defaulting to 60, wired into the pod.

## 4. Metrics can leave

- [x] 4.1 `otel.enabled` / `endpoint` / `protocol` / `extraEnv` values.
- [x] 4.2 The env block in the deployment, setting `ARCONIA_OTEL_ENABLED`, the endpoint,
      the protocol and `OTEL_SERVICE_NAME`.
- [x] 4.3 `fail` the render when enabled without an endpoint.

## 5. Requirements

- [x] 5.1 Mint `GW_FACADE_0033` and `GW_FACADE_0034` with their SVCs; both are
      `implementation: configuration`, which is what reqstool needs for a requirement no
      Java method carries.
- [x] 5.2 A packaging test reading the files: the drain, the grace period, the two groups,
      the two probe paths, the unauthenticated exemptions, and the conditional `Recreate`.

## 6. Docs

- [x] 6.1 `guides/backup-and-upgrade.md` — quiesce, copy both halves together, restore the
      pair, and a verification step that catches a split restore.
- [x] 6.2 What startup does to the schema, and the pre-1.0 reality that editing the single
      migration in place means an upgrade recreates the database.
- [x] 6.3 Link it from both deploying guides, where the hazard is already stated.
- [x] 6.4 The observability reference gains the chart's half of export.

## 7. Gates

- [x] 7.1 reqstool 251/251 · PASS; `mkdocs build --strict`; `openspec validate`.
- [x] 7.2 `./mvnw clean verify` 702 Java + 104 UI; e2e 20 passed; story tests 45 passed
      on the second attempt (the known cold-cache flake, recorded in the evidence).
- [ ] 7.3 `evidence.md`, tracker, archive.

## 8. Not done, and why

- [ ] 8.1 A Prometheus registry and scrape endpoint. A second export mechanism is a second
      surface to document, secure and keep working; OTLP reaches every collector that
      matters now that the chart can turn it on. Deliberately left for its own argument.
- [ ] 8.2 `helm lint` / `helm template` was not run — helm is not installed here. CI runs
      `helm lint` in `container-image.yml`, and the packaging test asserts on the template
      text, but neither renders it. **The first CI run is the real check on the template
      syntax.**
