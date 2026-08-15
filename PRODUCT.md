# Product

<!-- impeccable:product-schema 1 -->

<!--
Written by `/impeccable init` against an existing codebase, without an interactive
product interview: the session that produced it ran unattended. Every fact below is
grounded in this repository (`ARCHITECTURE.md`, `docs/manual/`, `docs/decisions/`,
`docs/reqstool/`, and the code itself) or in the written brief that commissioned it.
Nothing here is invented. Where the repository does not answer a question, the
section says so rather than filling the gap.
-->

## Platform

web

## Users

The primary user is an **enterprise platform or security engineer** who is
accountable for what AI agent skills the organization's developers are allowed to
run. They arrive at the portal to do one of a small number of jobs:

- register an upstream skill marketplace and pull a snapshot of it into quarantine;
- read a snapshot's vetting evidence and decide — approve, reject, or accept a
  specific finding with an expiring waiver;
- prove after the fact who fetched what, from where, and who approved it;
- keep the plumbing running: access tokens, webhook receivers, audit export sinks,
  retention.

The secondary user is an **auditor or reviewer** who never registers anything and
only reads: the ledger, a snapshot's provenance, the standing set of accepted risks.

The consumer of the *output* — the developer whose agent clones `/git/{marketplace}`
— never sees this portal at all. The facade is their entire interface.

## Product Purpose

Skills Gateway is an enterprise gateway for git-distributed AI agent skill
marketplaces. It ingests upstream git repositories into quarantined, SHA-pinned
snapshots; holds every snapshot until a person approves it; serves only approved
content through a read-only git smart-HTTP facade; and records every fetch and every
administrative action in an append-only audit ledger.

Success is that an organization can adopt third-party agent skills without adopting
third-party supply-chain risk: nothing reaches a developer's machine that a named
person did not approve, and every byte that was served can be traced back to the
upstream commit and the approval that released it.

## Positioning

The mechanism a neighbouring product cannot truthfully copy is the **quarantine
boundary**: ingestion and publication are two different repositories and two
different code paths. The quarantine repo is never served; only `ApprovalService`
publishes. That, plus a gateway-pinned ref (the consumer never chooses what they
get) and continuous re-vetting that can retroactively revoke content already in the
field, is the product.

## Operating Context

- Reviewers work at a desk, in a browser, usually alongside a ticket and the
  upstream repository in another tab. Sessions are short and decision-shaped.
- The portal is an internal tool behind OIDC. There is no marketing surface, no
  anonymous visitor, no onboarding funnel.
- The same capabilities are available over REST (`/api/**`, documented at `/docs`),
  and the portal is deliberately not the only way to drive the system — but it is
  the surface where *decisions* are made, because decisions need evidence rendered.
- The audience reads dense, factual screens for a living. Density is a feature.

## Capabilities and Constraints

Confirmed capabilities, as implemented today:

| Area | What exists |
| --- | --- |
| Marketplaces | Register (name + clone URL), ingest the upstream default branch, browse snapshots and their plugin/skill inventory |
| Vetting | A pluggable connector chain produces per-connector verdicts and findings; the effective outcome gates approval |
| Waivers | A blocking finding can be accepted with a scoped (snapshot or path), justified, mandatorily expiring waiver |
| Decisions | Approve / reject / re-approve a revoked snapshot; approval is what publishes |
| Re-vetting | Scheduled and on-demand re-vetting of already-approved snapshots, with retroactive quarantine |
| Retention | Soft delete with a restore window; approved-and-served snapshots refuse deletion |
| Audit | Append-only ledger, NDJSON export, and push sinks with per-sink cursors |
| Webhooks | Snapshot lifecycle events, HMAC-SHA256 signed, retried with backoff |
| Access | Personal access tokens for the git facade, shown exactly once |

Constraints that bind design work:

- **Two authentication surfaces, deliberately different.** The web surface is OIDC
  only; the facade (`/git/**`) is personal-access-token only. They never blend.
- **Registration is the trust boundary**: URL scheme allowlist, gateway-pinned ref.
- **The server is authoritative.** Client-side validation exists to keep an
  impossible request from being sent; it never substitutes for the server's check.
- **Show-once secrets.** Access tokens, webhook signing secrets, and sink signing
  secrets are returned exactly once, at creation, and never again.
- Stack: Java 25 / Spring Boot 4 backend, React + Vite portal in
  `src/main/frontend/`, PostgreSQL, JGit. The portal is a single-page app served by
  the same jar.

## Brand Commitments

- Product name: **Skills Gateway**. Sidebar mark is a `GitBranch` glyph in the
  accent colour; there is no logo asset.
- Voice: plain, factual, unhedged. Screens state what the system did and what it
  will refuse to do. No exclamation, no reassurance, no persuasion — this audience
  distrusts a security tool that sounds enthusiastic.
- Terminology is fixed by the domain and must not be softened in the UI:
  *quarantine*, *snapshot*, *held*, *approved*, *rejected*, *revoked*, *waiver*,
  *ledger*, *facade*, *provenance*.

## Evidence on Hand

- `ARCHITECTURE.md` and `docs/decisions/` (ADRs) — the system model and the decided
  stack, including ADR 0003, which decides the frontend stack and its verification
  harness.
- `docs/reqstool/` — requirements and software verification cases (`GW_*`,
  `SVC_GW_*`); the single source of truth for requirement text.
- `docs/manual/` — the published MkDocs site, including a page-by-page portal
  reference.
- A Playwright end-to-end suite that drives the real jar against a mock OIDC IdP,
  plus Storybook stories with axe violations treated as build errors.

There are **no** customers, testimonials, benchmarks, pricing, deployment
references, or usage figures. Future work must not fabricate any.

## Product Principles

1. **Nothing is served that a person did not approve.** Every screen should make the
   current gate, and who holds it, obvious.
2. **Evidence before decision.** An approve button is never offered without the
   verdicts and findings that justify pressing it.
3. **The interface refuses what the server would refuse.** A control that cannot
   succeed is disabled, with the reason stated — never a control that fails on
   press.
4. **Accepted risk stays visible.** A waived finding is never rendered as a clean
   result; it is rendered as accepted, by whom, until when.
5. **Density over decoration.** This is an operator's console. Screens are read, not
   admired.

## Accessibility & Inclusion

- Every control carries an accessible name; icon-only controls carry `aria-label`.
- The accessibility tree is the test contract: Playwright and Testing Library assert
  by role and name, and Storybook runs axe with violations as errors. A11y
  regressions fail the build, so semantics are load-bearing, not advisory.
- Light and dark themes are both first-class; no surface may be legible in only one.
