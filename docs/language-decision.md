# Product Language: Java vs Rust

**Status:** Decided — **Java** (see `docs/decisions/0001-use-java-for-the-product.md`) · **Date:** 2026-08-13

Comparison of the two finalist languages for the Skills Gateway *product*
(the Python MVP on `feat/mvp-planning` is a validated prototype, not the
product codebase). Candidates TypeScript and Python were eliminated earlier:
TypeScript has no embedded git server option and a weaker enterprise story;
Python (dulwich aside) is weakest at the pack-serving layer.

## Agreed constraints

These were settled in discussion and frame every row below:

- **SSO is OIDC (OAuth2) only** — no SAML, ever.
- **Auth must work for both clients:** browser OIDC flow for the web portal,
  and token auth (PAT and/or OAuth device flow) for git clients — git cannot
  do interactive OIDC, so token validation must live in-app; edge-only
  delegation (e.g. ALB OIDC) covers the web path only.
- **GraalVM native-image is assumed** for Java — classic JVM-with-runtime
  deployment is eliminated (the VM/air-gap install path requires a
  single-artifact install).
- **reqstool support is not a differentiator** — Rust tooling
  (`reqstool-rust-tags`) would be implemented if Rust is chosen.
- **Hiring/human-maintainability is disregarded** — development is
  AI-agent-driven (precedent: CitoRoute, a 100%-conformant Kubernetes
  Gateway API implementation in Rust written by AI agents under human
  direction).
- **Product horizon, not MVP** — headroom rows weigh double.
- **Deployment form factors:** OCI container everywhere; Helm chart for
  K8s (EKS/GKE/AKS/on-prem); serverless containers (ECS Fargate / Cloud
  Run / Container Apps); VM/air-gap via single artifact + systemd. FaaS
  (Lambda etc.) is explicitly not a target. Performance note: the data
  plane is git's own pack machinery (C) in both languages — the app is a
  broker/streamer, so language CPU speed is surplus; what matters is
  streaming, concurrency, and ecosystem.

## Comparison matrix

| # | Dimension | Java (Quarkus / Spring Boot 3 AOT, GraalVM + JGit) | Rust (axum/tower, subprocess git → gitoxide) | Edge |
|---|---|---|---|---|
| 1 | Git layer, day one | Subprocess `git upload-pack` (same as MVP) | Subprocess `git upload-pack` (same as MVP) | Tie |
| 2 | Git layer, product horizon | **JGit**: embedded upload-pack server, DFS/pluggable storage, Gerrit-proven at scale | gitoxide fast, but server-side protocol still maturing | **Java** |
| 3 | Broker performance | Virtual threads — surplus for this load | Tokio — higher ceiling, also surplus | Tie |
| 4 | Artifact / cold start | Native image ~60–90 MB, ~50 ms | Static binary ~15 MB, ~5 ms | Rust (cosmetic) |
| 5 | Deploy AWS + K8s + serverless containers | One container everywhere | Same | Tie |
| 6 | Auth: web OIDC + git-client tokens (PAT/device flow) | Two filter chains in one audited framework (Spring Security / Quarkus OIDC) | Audited crates (`openidconnect`, `jsonwebtoken`) + self-composed middleware | Java (meaningful, not decisive) |
| 7 | reqstool traceability | Tooling exists today | Build `reqstool-rust-tags` (accepted) | Tie |
| 8 | Web/API ecosystem (OpenAPI, validation, metrics) | Included | Assembled from good parts | Java (slight) |
| 9 | DB layer & migrations | JDBC/Flyway | sqlx/refinery | Tie |
| 10 | AI-agent dev velocity | Heavily represented in training data | CitoRoute-proven; compiler as agent feedback loop | Tie |
| 11 | Enterprise buyer perception | Default enterprise furniture | Modern low-ops infra credibility | Tie |
| 12 | Memory per pod at idle | ~80–150 MB native | ~10–30 MB | Rust |
| 13 | GraalVM tax | Slow native builds, reflection metadata, dev(JIT)/prod(native) split; JGit-native de-risked by Quarkus's official JGit extension | No equivalent tax — release build is the dev build | **Rust** |
| 14 | Concurrency correctness | Runtime discipline | Data races excluded at compile time | Rust |
| 15 | Observability (OTel) | Micrometer/OTel mature, but native image blocks the classic auto-instrument agent | `tracing` + OTel, manual but idiomatic | Tie |
| 16 | Own supply-chain surface (Security scans this product) | Large transitive Maven tree, mature scanning integration | Small dependency tree, cargo-audit | Rust (slight) |
| 17 | Dev inner loop | Quarkus dev-mode hot reload (JIT); native only at release | Moderate compile times, always native | Java (slight) |
| 18 | Connector ecosystem | Out-of-process by contract — language-neutral | Same | Tie |
| 19 | VM / air-gap install path | Native image = single file + systemd unit | Static binary = same, marginally simpler | Tie |
| 20 | Repos-as-cache / object-storage-as-truth | **JGit DFS is literally this pattern**, production-proven in Gerrit | Filesystem-level rehydration (e.g. S3 tarball on start) — simpler but bespoke | **Java** |

## Tally

- **Java** holds two heavyweight rows (2, 20 — the same bet stated twice:
  embedded git capabilities) plus one meaningful row (6).
- **Rust** holds one heavyweight row (13) plus several small operational
  rows (4, 12, 14, 16).
- Everything else ties — including, notably, performance (3): both are far
  above what a git-brokering gateway requires.

## The decisive question

> **Will the product ever serve git from anywhere other than
> bare-repos-on-disk via subprocess git?**

- **Yes** → **Java.** Object-storage-native repos (JGit DFS), in-process
  upload-pack, and fetch-time composition of virtual marketplaces exist
  today, production-proven, in JGit/Gerrit. Choosing Rust means betting the
  roadmap on gitoxide's server side maturing on schedule, or building it.
- **No** → **Rust.** A permanent Gitaly-style subprocess architecture is
  respectable, and then Rust's operational rows win uncontested:
  `cargo build` is the release process, ~15 MB artifact, no runtime tax.

Fetch-time virtual-marketplace composition is the Phase 3 feature most
likely to force embedded git — and one of the product's most
differentiating ideas. If it is core to the vision, that is the tiebreak.

## Once decided

Record the choice as an ADR (or a `decide-product-language` OpenSpec
change) referencing this document, then plan the port: the requirements
(`GW_*`), SVCs (`SVC_GW_*`), and OpenSpec specs are language-agnostic by
design, so porting means re-implementing ~600 lines against an existing
spec-and-test contract, with `@Requirements`/`@SVCs` annotations in the
target language closing the traceability loop again.
