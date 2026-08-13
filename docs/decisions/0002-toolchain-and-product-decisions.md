# Toolchain and product decisions

- **Status:** accepted
- **Date:** 2026-08-13
- **Deciders:** jimisola

## Context and Problem Statement

ADR 0001 fixed the language (Java + GraalVM native-image). This ADR records
the concrete toolchain and the product decisions settled in the same
discussion, so the scaffold and the upcoming port have one reference.

## Decisions

- **Java 25+ (LTS).** Greenfield; pairs with Spring Boot 4's baseline.
- **Spring Boot 4 / Spring Framework 7.** Two-mode auth via Spring Security;
  JSpecify null-safety (pairs with native-image's closed world and with
  AI-agent development); first-class API versioning for the admin API.
- **Maven + wrapper.** Declarative and boring suits AI-agent-driven
  development; reqstool and GraalVM support are equivalent in Maven and
  Gradle, so this is convention, not capability.
- **Maveniverse Nisse for versioning.** Core extension
  (`.mvn/extensions.xml`), jgit property source feeding CI-friendly
  `${revision}` — the version is derived from git state, never hand-edited.
- **Formatting/analysis: Spotless (palantir-java-format) + Checkstyle.**
  Spotless owns formatting; Checkstyle carries rules only (no formatting
  overlap). Both enforced in the build.
- **JGit from day 1 — no subprocess-git phase.** Java was chosen for JGit's
  embedded server and DFS storage abstraction; carrying a temporary
  subprocess architecture would pay Java's costs without its payoff.
  Week-1 port task: prove JGit upload-pack inside a native image
  (fallback: Quarkus's official JGit extension).
- **Dev/test containers: Arconia Dev Services** (`arconia-dev-services-postgresql`,
  Arconia BOM) — Quarkus-style zero-config Testcontainers: Postgres
  auto-starts and wires via service connections in dev and test; no manual
  `@Testcontainers`/`@Container` plumbing.
- **Data access: JdbcClient + Flyway on PostgreSQL. JPA/Hibernate rejected:**
  supported under native-image but adds build-time enhancement, metadata
  weight, and lazy-proxy edge cases — for a three-table schema it buys
  nothing.
- **GraalVM native-image is the release profile, not the dev loop.** Daily
  development and tests run on JIT; the `native` Maven profile is wired from
  day 1; native builds run in CI/at release.
- **License: Apache-2.0** (explicit patent grant, contribution terms §5,
  enterprise-lawyer-familiar). Open source now; a commercial/dual license
  remains possible later — copyright stays consolidated (DCO enforced on
  every commit; CLA to be considered when outside contributors appear).
- **Auth is in the port's scope from the beginning:** OIDC (OAuth2) login
  for the web portal + hashed personal access tokens for git clients
  (git cannot do interactive OIDC). OAuth device flow deferred. No SAML,
  ever. New requirements (GW_0011+) to be added with the port change.
- **Frontend: SPA served by the app acting as its own BFF** (token-handler
  pattern: browser holds only a session cookie, tokens stay server-side;
  same-origin `/api`). No separate BFF service until more frontends or
  backends exist. SPA framework chosen when portal work starts.
- **Frontend placement: monorepo, `ui/` top-level directory** — the pattern
  of comparable single-product infra (Keycloak's `js/` admin/account UIs in
  the server monorepo; agentgateway's `ui/` beside its Rust crates; Gitea,
  Grafana likewise). Maven orchestrates the SPA build
  (frontend-maven-plugin pinning node/pnpm) and bundles the output into the
  jar's static resources, so `./mvnw package` still yields **one
  deployable** (native image included). Dev loop: Vite dev server proxying
  `/api` to the running app. If the build graph grows, promote to a Maven
  multi-module (`server/`, `ui/`) — the layout anticipates that split.
- **Maven coordinates:** `io.github.jimisola.skillsgateway:skills-gateway`.

## Consequences

- The scaffold (this branch) wires all of the above with a minimal
  compilable application and one sanity test.
- Next change: `port-gateway-to-java` — JGit-based ingestion/approval/façade
  against the existing GW_*/SVC_GW_* contract, auth requirements GW_0011+,
  and a storage seam JGit-DFS can implement later (local disk in v1,
  object-storage-as-truth on the roadmap; see ARCHITECTURE.md §12).
- The Python prototype is archived with full history in the sibling
  `skills-gateway-python-mvp` repository.
