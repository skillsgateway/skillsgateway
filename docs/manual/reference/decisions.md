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
partial answer is an `error` verdict that blocks, never a pass (GW_0145) — plus
**worst-of** aggregation so an endpoint cannot pass content its own findings
condemn (GW_0146). A `pending` answer is the **asynchronous seam** and blocks
until resolved (GW_0147); the inbound resolution callback is deliberately a
separate, sequenced piece of work.

### [ADR 0010 — Admin override of vetting automation (the cockpit model)](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0010-admin-override-of-vetting-automation.md)

*Accepted, 2026-09-01.*

The airline-cockpit principle applied to vetting: automation can be
**disconnected by the captain**, deliberately and audibly. Two stances taken on
purpose are narrowly reversed for **admin-only, audited** acts. An administrator
may **enable or disable a built-in connector**, globally or per marketplace
(GW_0149) — a disabled connector is recorded as a distinct `disabled` verdict
(fail-loud) and disabling every connector still leaves a run **blocked**, never
cleared. An administrator may also **approve a snapshot over a blocked outcome**
(GW_0148) with a required reason — the override lifts only the vetting gate (the
policy, release-age and four-eyes gates still run), writes a distinct
`snapshot-approved-over-vetting-failure` ledger event, and marks the snapshot so
it is never indistinguishable from a clean approval. The override does not
replace scoped, expiring **waivers**; it is the rarer administrative act for a
whole blocked outcome.

### [ADR 0011 — External plugin sources: admission before resolution](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0011-external-plugin-sources.md)

*Accepted, 2026-09-02.*

The staged reversal of the local-only stance. Support for external plugin
sources lands in increments — **admission** (a typed source model and a
configuration gate, disabled by default) before **resolution** (fetching and
rewriting into a gateway-local composite snapshot) — separated by a standing
invariant: a snapshot is **held only when every plugin source it declares
resolves inside the snapshot the gateway serves** (GW_0152), so T4 stays closed
for the whole reversal rather than reopening between increments. When the
rewriter lands, snapshot identity becomes the **served composite SHA**, which is
what leaves vetting, approval, the facade and retention unchanged by
construction. Egress isolation — not URL validation — is named as the primary
SSRF control for the resolution increment; the allowlists are defence in depth.
`npm` and `archive` sources are refused permanently, above the configuration
gate.

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


### [ADR 0012 — The release artifact is a JVM container, not a native image](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0012-native-image-as-the-release-artifact.md)

*Accepted.* The release artifact becomes a JVM container on a `jlink` runtime over
`distroless/java-base`; the native image is dropped. Supersedes the release-profile
decision in ADR 0002. Records the measurement that settled it — roughly 100 MB per
instance, at a sizing where nothing is memory-bound — against three defects
possible only on the native image, two of which shipped.

### [ADR 0013 — Data access stays on JdbcClient; JPA is not adopted](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0013-data-access-stays-on-jdbcclient.md)

*Accepted.* Re-decides the data-access question after ADR 0012 removed the
native-image argument that ADR 0002 had rested it on. Concurrency correctness here
lives in twenty guarded `UPDATE … RETURNING *` statements rather than in
transactions or locking, and those stay native SQL under any ORM — so JPA would
take over only the part that was never the problem, at the cost of entities that
cannot be records and a re-verification campaign at a trust boundary.

### [ADR 0014 — Estate export: content and its attestations leave together; an import can only fill quarantine](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0014-estate-import-export.md)

*Proposed, 2026-09-08.* Answers how an estate leaves a gateway, and on what
terms one may enter. An export is **git bundles plus JSON and NDJSON in a
directory**, readable by a plain git client and a text editor without a running
gateway, in the same vocabulary the object-store backend already uses; content
and attestation are one artefact by construction, because either half alone is
worthless. One format, two selections — a marketplace to hand over, or the
estate to archive. Secrets never leave: not tokens, and not the two HMAC keys
that hide in `marketplaces.webhook_secret` and `webhook_subscribers.secret`.
Revocations may never be omitted, because an omitted revocation reads as content
that was never withdrawn. Verification inherits `StorageMigration`'s discipline —
re-read both sides and compare, refuse rather than default to success — plus an
offline mode that opens no database.

The constraint that shapes the rest: **allow-shaped state never imports,
deny-shaped state may.** An imported approval would be a way around the approval
gate, so an approved snapshot imports as `held`, waivers and overrides and grants
do not import at all, foreign verdicts are evidence rather than verdicts, and
another instance's ledger is never grafted onto this one's. The consequence is
that a round trip is lossy by design, which is why this ADR recommends building
**export only** for now and leaving import to its own change — and why it says
plainly that an export is not a backup.
