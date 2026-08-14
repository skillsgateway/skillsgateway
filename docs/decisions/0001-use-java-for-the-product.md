# Use Java for the product

- **Status:** accepted
- **Date:** 2026-08-13
- **Deciders:** jimisola

## Context and Problem Statement

The Skills Gateway product needs a long-term implementation language. The
Python codebase on `feat/mvp-planning` is a validated prototype, not the
product. Finalists were Java and Rust; the full comparison, agreed
constraints (OIDC-only, dual git/web auth, GraalVM assumed, AI-agent
development, product horizon, container/K8s/VM form factors), and tally
live in [`docs/language-decision.md`](../language-decision.md).

## Decision

**Java**, compiled with **GraalVM native-image** (single-artifact installs;
closed-world build also shrinks Java's classic dynamic attack surface).

The deciding factor: the product-horizon git capabilities — embedded
upload-pack serving, object-storage-native repos (DFS), fetch-time
composition of virtual marketplaces — exist today, production-proven, in
**JGit**/Gerrit. Choosing Rust would have bet the roadmap on gitoxide's
server side maturing on schedule.

Initial toolchain (revisitable, see open challenges):

- **Build: Maven** — declarative and boring, which suits AI-agent-driven
  development (Gradle builds tend to accrete bespoke build logic).
  reqstool support is equivalent in both (maven and gradle plugins are on
  par) and GraalVM Native Build Tools ships both plugins, so neither is a
  tiebreaker; Gradle's incremental-build edge is irrelevant to the long
  pole (native-image compilation, not incremental either way). A close
  call on convention rather than capability.
- **Framework: Spring Boot 4 (AOT)** — chosen for Spring Security's
  two-mode auth (OIDC web login + token resource-server) and ecosystem.
  Spring Boot 4 / Framework 7 additionally brings JSpecify null-safety
  and first-class API versioning (useful for the admin API contract).
  *Known risk:* JGit-under-native reachability metadata is community-solved
  in Quarkus's official JGit extension, not in Spring; we own that metadata
  work. Mitigation: early native spike; Quarkus remains the fallback.
- **Formatter: palantir-java-format via Spotless**, enforced in CI.

## Consequences

- Port the gateway against the existing language-agnostic contract:
  requirements `GW_*`, SVCs `SVC_GW_*`, OpenSpec specs. Traceability via
  reqstool-java-annotations + maven plugin.
- Week-1 de-risk task: JGit serving upload-pack inside a native image.
- Rust remains documented as the runner-up; revisit only if the JGit bet
  fails (see decisive question in language-decision.md).
