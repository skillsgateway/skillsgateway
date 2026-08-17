# skillsgateway organization and dev.skillsgateway coordinates

- **Status:** accepted
- **Date:** 2026-08-17
- **Deciders:** jimisola

## Context and Problem Statement

The project started as a personal repository (`github.com/jimisola/skills-gateway`)
with GitHub-convention Maven coordinates (`io.github.jimisola.skillsgateway:skills-gateway`).
The owner acquired the domain **skillsgateway.dev** and created the GitHub
organization **skillsgateway**, giving the project an identity independent of a
personal account. The first published artifacts (container image with attested
SBOM, issue #67) are imminent, and published identities are effectively
immutable — coordinates had to be settled before first publication.

## Decision

One name everywhere, anticipating more than one deliverable:

- **GitHub:** `github.com/skillsgateway/skillsgateway` (repo transferred and
  renamed; old URLs redirect).
- **Maven groupId:** `dev.skillsgateway` — the product umbrella, verifiable on
  Maven Central through domain ownership.
- **Maven artifactIds:** prefixed per deliverable — `skills-gateway-server` for
  the gateway service; a future CLI would be `skills-gateway-cli`. A shared
  `skills-gateway-core` is created only if sharing actually emerges, never
  pre-emptively.
- **Java packages:** package-per-artifact mirroring the artifactId —
  `dev.skillsgateway.server` for the server — so sibling artifacts can never
  produce split packages.
- **Container image:** `ghcr.io/skillsgateway/skillsgateway` (named after the
  repo, not the Maven artifact; it ships the server).
- **Unchanged:** the Helm chart name (`skills-gateway`), the reqstool URN
  (`skills-gateway`), and the Spring application name.

This amends the coordinates item of [ADR 0002](0002-toolchain-and-product-decisions.md).

## Consequences

- The native binary is now `target/skills-gateway-server` (GraalVM names it
  after the artifactId); the Dockerfile and packaging test follow.
- The docs site moves to `skillsgateway.github.io/skillsgateway` until
  skillsgateway.dev is wired up (a separate, deliberate step).
- Nothing had been published under the old coordinates, so no migration or
  relocation POM is needed.
