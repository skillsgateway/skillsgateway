# Design: port-gateway-to-java

## Context

The scaffold (branch `feat/scaffold`) builds green: Spring Boot 4.1, Java 25,
JGit 7.7 on the classpath, JdbcClient/Flyway/PostgreSQL, Arconia dev
services, Spotless+Checkstyle, Nisse versioning, `native` profile wired.
Behavior to implement is fixed by the reqstool SSOT (GW_0001–GW_0014) and by
the archived Python prototype (`../skills-gateway-python-mvp`) as the
reference implementation for GW_0001–GW_0010. ADR 0001/0002 constrain the
stack; see proposal.md for scope.

## Goals / Non-Goals

**Goals:**
- All 14 requirements implemented with `@Requirements` annotations and
  verified by tests with `@SVCs` annotations: `reqstool status` = 14/14 PASS.
- JGit-only git layer behind a storage seam (`GitStorage`) that a DFS
  implementation can satisfy later.
- Real-client e2e: an unmodified `git` binary clones through auth against the
  running app.
- The native-image JGit spike answered early (pass/fail informs ADR 0002's
  fallback clause).

**Non-Goals:**
- The SPA portal (API-first; OIDC login protects what exists today).
- External sources, connectors beyond manual approval, multi-ref, device
  flow, kill switch (later changes).
- Production OIDC provider setup (tests use a stub/issuer container; config
  is standard Spring properties).

## Decisions

- **Package layout by capability**, mirroring the spec structure:
  `ingestion`, `approval`, `facade`, `admin`, `auth`, plus `storage` (seam)
  and `persistence` (JdbcClient repositories). Flat-ish; no hexagonal
  ceremony for a service this size.
- **JGit usage:**
  - *Ingestion:* `Git.lsRemoteRepository` to resolve the default branch
    (symref HEAD); `FetchCommand` into the quarantine bare repo; pin
    `refs/snapshots/<sha>`; read the manifest blob via `RevWalk` +
    `TreeWalk` at the fetched commit.
  - *Publication:* copy the approved commit closure quarantine → published
    with an in-process fetch (published repo fetches
    `refs/snapshots/<sha>` from quarantine via file transport), then
    `RefUpdate` of `refs/heads/main`. Rejected/held history never becomes
    reachable in published.
  - *Serving:* `GitServlet` registered at `/git/*` with a repository
    resolver that only opens published repos (GW_0007 by construction);
    `setReceivePackFactory(null)` — receive-pack does not exist (read-only).
    Audit via JGit's `PostUploadHook` (`onPostUpload` exposes wants/stats)
    → fetch ledger with authenticated identity (GW_0008, GW_0012).
- **Storage seam:** `GitStorage` interface — `quarantine(name)`,
  `published(name)` returning JGit `Repository` handles + lifecycle.
  V1 implementation: bare repos on a configured data directory. The
  interface is what a JGit-DFS/object-storage implementation replaces
  (roadmap; ARCHITECTURE.md §12).
- **Persistence:** Flyway `V1__init.sql`: `marketplaces`, `snapshots`
  (state CHECK constraint held/approved/rejected + decision/provenance
  columns), `fetch_log` (append-only), `access_tokens` (principal, name,
  token_hash, created_at, revoked_at). JdbcClient repositories; no ORM
  (ADR 0002).
- **Auth (two filter chains, Spring Security):**
  - Web/admin chain (`/api/**`, `/`, actuator UI): `oauth2Login()` (OIDC);
    session cookie; the app is its own BFF.
  - Git chain (`/git/**`): HTTP Basic where the password is a PAT
    (standard git credential-helper flow); custom `AuthenticationProvider`
    hashes the presented token (SHA-256) and looks up `access_tokens`.
    PATs are 256-bit random values — hash lookup without salt/stretching is
    appropriate for high-entropy random tokens (they are not passwords);
    record shows-once at creation, revocation immediate.
  - PAT management endpoints under the web chain (`POST/DELETE
    /api/tokens`), authenticated by OIDC session (GW_0013).
  - Tests: OIDC via Spring Security's test support (`oidcLogin()`) for the
    web chain plus one redirect assertion for unauthenticated requests;
    the git chain is tested with real tokens end-to-end.
- **SBOM:** `cyclonedx-maven-plugin` (`makeAggregateBom` bound to
  `package`) writes the CycloneDX BOM into the jar where the actuator SBOM
  endpoint auto-detects it; expose `health,sbom` via management properties.
  Test asserts `/actuator/sbom` (and `/actuator/sbom/application`) returns
  the CycloneDX document (GW_0014). SBOM endpoint access: permitted without
  auth? No — behind the web chain like other actuator endpoints except
  `health`.
- **Testing approach:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` with
  Arconia-provisioned Postgres; upstream marketplace fixtures created with
  JGit programmatically; e2e clone/fetch through the real `git` binary with
  `http.extraHeader`/credential config for PATs; JUnit XML feeds reqstool.
- **Native spike:** a task, not a gate: `./mvnw -Pnative native:compile` on
  a minimal profile and a smoke clone. Outcome recorded in ADR 0002
  (Quarkus fallback clause stands if it fails).

## Risks / Trade-offs

- [JGit under GraalVM native with Spring AOT — unproven combination here]
  → early spike task; reachability metadata added as needed; documented
  fallback (ADR 0002).
- [GitServlet + Spring Security filter-chain integration (servlet vs
  Spring MVC)] → GitServlet is a plain servlet registered via
  `ServletRegistrationBean`; the `/git/**` security chain runs before it.
  Covered by the e2e tests.
- [OIDC in tests needs an issuer] → Spring Security test support covers the
  web chain without a live IdP; a containerized IdP (e.g. Keycloak via
  Arconia/Testcontainers) can be added later if gaps appear.
- [SHA-256 PAT hashing questioned in review] → documented rationale
  (high-entropy random tokens, not user-chosen passwords); revisit only if
  token format changes.

## Migration Plan

Greenfield port on a branch; the scaffold's `SanityTest` is replaced by real
verifications. No data to migrate. Rollback = don't merge.

## Open Questions

- Whether the SBOM endpoint should also be reachable by unauthenticated
  security scanners inside the network — default is authenticated; flipping
  it later is a one-property change and does not affect specs or tasks.
