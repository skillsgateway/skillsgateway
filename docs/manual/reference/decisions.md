# Architecture decisions

Architecture-level choices are recorded as ADRs in
[`docs/decisions/`](https://github.com/skillsgateway/skillsgateway/tree/main/docs/decisions)
in the repository. This page indexes them; the linked files are authoritative.

Feature-level decisions live in the relevant OpenSpec change's `design.md`
rather than here.

## Index

### [ADR 0001 — Use Java for the product](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0001-use-java-for-the-product.md)

*Accepted, 2026-08-13.*

**Java**, compiled with GraalVM native-image. The finalists were Java and Rust,
and the deciding factor was git capability at the product horizon: embedded
upload-pack serving, object-storage-native repositories, and fetch-time
composition of virtual marketplaces exist today, production-proven, in **JGit**
and Gerrit. Choosing Rust would have bet the roadmap on gitoxide's server side
maturing on schedule.

Native-image also shrinks Java's classic dynamic attack surface through
closed-world compilation, and gives single-artifact installs.

### [ADR 0002 — Toolchain and product decisions](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0002-toolchain-and-product-decisions.md)

*Accepted, 2026-08-13.*

The concrete stack that follows from 0001:

| Area | Decision |
| --- | --- |
| Runtime | Java 25, Spring Boot 4 / Spring Framework 7 |
| Build | Maven + wrapper; Maveniverse Nisse derives the version from git state |
| Quality | Spotless (palantir-java-format) owns formatting; Checkstyle carries rules only |
| Git | **JGit from day one** — no subprocess-git phase, ever |
| Data | JdbcClient + Flyway on PostgreSQL; **JPA/Hibernate rejected** as weight a small schema does not need |
| Dev/test | Arconia Dev Services — zero-config Testcontainers for PostgreSQL |
| Release | GraalVM native-image as the release profile |
| Auth | OIDC-only SSO for humans; the app is its own BFF; PATs for git clients |

The JGit decision is the one with teeth: Java was chosen *for* JGit, so carrying
a temporary subprocess architecture would have paid Java's costs without its
payoff.

### [ADR 0003 — Agentic-first frontend stack](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0003-agentic-first-frontend-stack.md)

*Accepted, 2026-08-13.*

The portal is developed agent-first, so the design constraint is a **closed SDLC
loop**: an agent must be able to build, run, inspect, interact, screenshot,
test, diagnose and modify without a human acting as eyes and hands.

| Layer | Choice |
| --- | --- |
| Language / framework | TypeScript (strict), React |
| Build | Vite — **no Next.js**; the app-as-BFF serves static assets, so SSR is dead weight |
| Styling / components | Tailwind CSS, shadcn/ui copy-in source on exactly **one** headless primitive layer |
| Server state | TanStack Query against same-origin `/api` with a session cookie |
| Forms | React Hook Form + Zod |
| API mocking | MSW, **contract-derived only** — generated from the backend's OpenAPI, never hand-authored, and never in the acceptance path |

The verification harness is described in the ADR as "the load-bearing half":
Playwright is the agent's browser and the outer loop, asserting against
accessibility-tree snapshots. A hand-written mock is a second API implementation
that drifts silently, which is why the acceptance path forbids them.

### [ADR 0004 — skillsgateway organization and coordinates](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0004-skillsgateway-org-and-coordinates.md)

*Accepted, 2026-08-17.*

One name everywhere, settled before the first published artifact: GitHub org and
repo **skillsgateway/skillsgateway**, Maven groupId **`dev.skillsgateway`**
(backed by the skillsgateway.dev domain), prefixed artifactIds per deliverable
(**`skills-gateway-server`** for the gateway), and package-per-artifact Java
roots (**`dev.skillsgateway.server`**). The container image is
`ghcr.io/skillsgateway/skillsgateway`; the Helm chart name and reqstool URN
stay `skills-gateway`. Amends the coordinates item of ADR 0002.

## Related

- [`ARCHITECTURE.md`](https://github.com/skillsgateway/skillsgateway/blob/main/ARCHITECTURE.md)
  — the full architectural narrative: threat model, connector-based vetting,
  two-repo promotion, observability and the phase roadmap.
- [`docs/language-decision.md`](https://github.com/skillsgateway/skillsgateway/blob/main/docs/language-decision.md)
  — the Java-versus-Rust comparison behind ADR 0001.
- `docs/reqstool/` — requirements and verification cases, the single source of
  truth for requirement text.

## Adding one

New architecture-level choices get a new numbered ADR in `docs/decisions/`,
referenced from `ARCHITECTURE.md`, and an entry on this page.
