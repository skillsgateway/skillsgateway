---
name: architecture
description: Skills Gateway system architecture — quarantine/approve/serve model, threat model, capabilities, and where decisions live (docs/manual/architecture.md, ADRs). Load before designing features, changing trust boundaries, or answering "why is it built this way".
---

# Architecture

Read these sources rather than reasoning from memory; they are canonical:

- `docs/manual/architecture.md` — full architecture: threat model (T1–T6), connector-based
  vetting, two-repo promotion (quarantine → published), git facade,
  observability, roadmap (§14 has the phase plan; §14.2 the vetting connectors).
- `docs/decisions/0001-use-java-for-the-product.md` — why Java.
- `docs/decisions/0002-toolchain-and-product-decisions.md` — Boot 4, Maven+Nisse,
  JGit-embedded, JdbcClient+Flyway (no JPA), GraalVM native as release profile,
  OIDC-only SSO, app-as-BFF, PATs for git clients.
- `docs/decisions/0003-agentic-first-frontend-stack.md` — portal stack and the
  closed-SDLC verification harness (the load-bearing half).
- `openspec/specs/` — the living capability specs (IDs resolve via reqstool).

## Invariants (never violate without an ADR)

1. Served content is always an approved, SHA-pinned snapshot; upstream changes
   never propagate without re-ingestion + re-approval.
2. The quarantine repository is never exposed by the facade.
3. Every fetch and every admin action lands in the append-only ledger.
4. The browser never holds tokens (app-as-BFF); git clients use hashed PATs.
5. New behavior = reqstool requirement + SVC + test before implementation ships.

## New decisions

Architecture-level choices get a new ADR in `docs/decisions/` (next number),
referenced from docs/manual/architecture.md. Feature-level decisions go in the OpenSpec
change's `design.md`.
