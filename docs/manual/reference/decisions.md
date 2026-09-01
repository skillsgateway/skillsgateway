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

### [ADR 0005 — Signed provenance stays in Phase 3, with named pull-forward triggers](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0005-signed-provenance-stays-phase-3.md)

*Proposed, 2026-08-18.*

Signed in-toto/Sigstore attestations remain a Phase-3 item. The recorded
provenance chain (content-addressed snapshots + vetting verdicts + approval
records + append-only ledger) already binds upstream SHA → scan → approval →
published artifact inside one trust domain; signing pays off only when
verification happens outside it. Four pull-forward triggers are named — OCI
re-publication/federation, an operator-independent audit demand,
cross-gateway promotion, and consumer-side verification tooling — any one of
which reopens the decision.

### [ADR 0006 — Embedded CEL for policy rules, not an OPA sidecar](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0006-embedded-cel-for-policy-rules.md)

*Accepted, 2026-08-18.*

Policy deny rules are **embedded cel-java expressions** — compiled to a
boolean at write time, evaluated fail-closed inside the approval gate — rather
than an OPA/Rego sidecar: no second deployment unit, no network hop inside the
gate, and CEL's non-Turing-complete, terminating semantics are the right blast
radius for user-authored rules and a playground pointed at real snapshots.
Scope is deny-only on purpose; auto-approval would delegate the human gate and
stays parked as a product decision.

### [ADR 0007 — First-party hosting: a write path, deliberately somewhere else](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0007-first-party-hosting-and-the-publish-endpoint.md)

*Accepted, 2026-08-23.*

The gateway accepts git pushes for marketplaces it hosts itself — on a
**separate endpoint** (`/publish/**`, so the serving facade keeps its null
receive-pack factory), into a **separate repository** (the publisher's origin,
so quarantine keeps exactly one writer), under a **separate token scope** that
no existing token holds and that has no every-marketplace form. One lineage,
forward only unless the marketplace says otherwise, and the approval gate is
untouched: pushed content is quarantined, vetted and held like anything
fetched. Auto-approval for trusted internal publishers stays parked per ADR
0006.

### [ADR 0008 — The serving surface stays the embedded facade; SSO closes at the gateway](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0008-serving-surface-stays-the-embedded-facade.md)

*Accepted, 2026-08-23.*

Serving approved content out of an external forge was weighed and declined: it
would outsource **authentication and visibility**, which are commodities, at the
price of outsourcing **enforcement and audit**, which are the product — a
forge-served repository makes the approval gate bypassable by anyone with
repository write, and forge logs do not record per-user fetches. The two things
a forge was wanted for are addressed at the gateway instead: **SSO-derived
short-lived git credentials** (GW_0104, implemented) close the
second-credential-system gap without moving a fetch off the ledger, and a
read-only **forge mirror for browsing** is sequenced after, never as a serving
surface. Availability of the facade is now explicitly a security property, with
serving independent of ingestion.

### [ADR 0009 — The external vetting connector contract: configured, synchronous, fail-closed](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0009-external-vetting-connector-contract.md)

*Proposed, 2026-09-01.*

An operator can add their own vetting connector — an LLM reviewer, a sandbox, a
corporate scanner — as **configuration** (`skills-gateway.vetting.external[*]`),
not API-managed state, so every chain run stays attributable to the exact
connector and version that produced it (GW_0049). It is a **synchronous** HTTP
participant in the existing chain: the gateway POSTs the snapshot bundle and reads
back the normalized `{state, report-url, findings[]}`. The load-bearing half is
**fail-closed** — an unreachable, slow, oversized, unparseable, unrecognised or
partial answer is an `error` verdict that blocks, never a pass (GW_0143) — plus
**worst-of** aggregation so an endpoint cannot pass content its own findings
condemn (GW_0144). A `pending` answer is the **asynchronous seam** and blocks
until resolved (GW_0145); the inbound resolution callback is deliberately a
separate, sequenced piece of work.

## Related

- [Architecture](../architecture.md)
  — the full architectural narrative: threat model, connector-based vetting,
  two-repo promotion, observability and the phase roadmap.
- [`docs/language-decision.md`](https://github.com/skillsgateway/skillsgateway/blob/main/docs/language-decision.md)
  — the Java-versus-Rust comparison behind ADR 0001.
- `docs/reqstool/` — requirements and verification cases, the single source of
  truth for requirement text.

## Adding one

New architecture-level choices get a new numbered ADR in `docs/decisions/`,
referenced from the [architecture document](../architecture.md), and an entry
on this page.
