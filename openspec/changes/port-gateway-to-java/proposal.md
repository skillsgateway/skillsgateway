# Proposal: port-gateway-to-java

## Why

The product language is decided (ADR 0001: Java + GraalVM; ADR 0002:
toolchain) and the scaffold builds green, but the gateway's behavior exists
only in the archived Python prototype. This change implements the product:
the validated behavior (GW_0001–GW_0010) re-established on the product stack
with JGit embedded from day 1, plus the pieces deliberately excluded from the
prototype — authentication (GW_0011–GW_0013) and the service's own SBOM
endpoint (GW_0014).

## What Changes

- Implement the full gateway loop on Spring Boot 4 + JGit: register → ingest
  into quarantine (default branch, SHA-keyed snapshots, local-only plugin
  sources fail closed) → held until reviewer approval → publish exactly the
  approved commit closure → serve read-only git smart-HTTP → append-only
  fetch audit log (GW_0001–GW_0010).
- **JGit embedded, no subprocess git** (ADR 0002): ingestion and publication
  via the JGit API, serving via `GitServlet` (upload-pack only).
- Persistence on PostgreSQL: Flyway schema + `JdbcClient` repositories
  (marketplaces, snapshots, fetch ledger, tokens).
- **Auth from the beginning** (ADR 0002): OIDC login for the web/admin
  surface (GW_0011), personal-access-token auth on the git façade with
  identity-level audit (GW_0012), token create/revoke with hash-at-rest and
  show-once (GW_0013).
- **SBOM endpoint**: CycloneDX generated at build, served via the Spring Boot
  actuator SBOM endpoint (GW_0014).
- Storage behind a seam a JGit-DFS implementation can satisfy later
  (object-storage-as-truth roadmap); filesystem implementation in this change.
- Early de-risk task: JGit upload-pack inside a GraalVM native image
  (ADR 0002's known risk).
- Out of scope: external plugin sources/rewriting, automated vetting
  connectors, risk tiers, multi-ref publication, OAuth device flow, the SPA
  portal (API-first; the web surface is minimal), kill switch.

## Capabilities

### New Capabilities

Note: `openspec/specs/` is empty in this repo (the prototype's change lives
in the archived python-mvp repository), so all capabilities are established
here as ADDED deltas. This change spans the whole service rather than a
single capability by design — it is the port that seeds the spec base.

- `marketplace-ingestion`: registration and quarantine ingestion with
  local-only source enforcement (GW_0001, GW_0002, GW_0003)
- `snapshot-approval`: held-until-approved gate, manual decisions, provenance
  (GW_0004, GW_0005, GW_0009)
- `git-facade`: read-only smart-HTTP serving of approved content with fetch
  auditing (GW_0006, GW_0007, GW_0008)
- `admin-api`: administrative HTTP interface (GW_0010)
- `auth`: OIDC web sessions, PAT-authenticated git access, token lifecycle
  (GW_0011, GW_0012, GW_0013)
- `sbom`: the service's own CycloneDX SBOM endpoint (GW_0014)

### Modified Capabilities

(none — empty spec base)

## Impact

- New code under `src/main/java/io/github/jimisola/skillsgateway/` and tests
  under `src/test/java/…` (replacing the scaffold's sanity test with real
  verifications carrying `@SVCs` annotations).
- `pom.xml` additions: spring-boot-starter-actuator, cyclonedx-maven-plugin
  (SBOM at build), spring-security-oauth2 bits already present.
- New Flyway migrations under `src/main/resources/db/migration/`.
- Tests run against Arconia-provisioned PostgreSQL; e2e tests drive the
  façade with the real `git` client.
- reqstool: GW_0001–GW_0014 all move to implemented+verified; traceability
  target is 14/14 PASS.
