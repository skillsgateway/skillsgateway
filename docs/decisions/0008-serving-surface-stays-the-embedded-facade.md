# ADR 0008 — The serving surface stays the embedded facade; SSO closes at the gateway

*Accepted, 2026-08-23.*

## Context

Issue [#59](https://github.com/skillsgateway/skillsgateway/issues/59) asked a
question the architecture had answered by default rather than deliberately:
should the gateway keep embedding its own git server ([ADR 0002](0002-toolchain-and-product-decisions.md)),
or serve approved content out of an external forge repository?

The case for a forge is real and it is about the two things a forge is
genuinely better at:

- **Identity.** Git access would ride forge identities — SSO-enforced tokens,
  directory-synced teams, offboarding that already works. Gateway access tokens
  are a second credential system, which is why issue #13 existed at all.
- **Visibility.** Browsing, search, rendering, notifications, all free.

The case against is about the two things that are the product:

- **Enforcement.** "Only `ApprovalService` publishes" is enforced by code. A
  forge-served repository makes the approval gate bypassable by anyone with
  write access to that repository — an admin, a CI token, a misconfigured
  webhook. The gate would become a convention.
- **Audit.** The append-only fetch ledger records identity, ref and SHA per
  fetch, and it is what powers provenance and the re-vetting blast-radius
  report. Forge audit logs do not record per-user fetches at that granularity,
  and revocation on a forge races caches, forks and mirrors instead of failing
  the next fetch loudly.

In one line: the forge option outsources authentication and visibility, which
are commodities, at the price of outsourcing enforcement and audit, which are
the product.

## Decision

**The embedded facade stays the canonical, audited, enforced serving surface.**
Architecture invariants 1–3 are unchanged: served content is an approved,
SHA-pinned snapshot; the quarantine repository is never exposed; every fetch
lands on the ledger.

The two things a forge was wanted for are addressed at the gateway instead,
as separate pieces of work:

1. **Identity — SSO-derived, short-lived git credentials.** A developer already
   authenticated to the portal can mint a git credential from that session,
   with a gateway-capped lifetime they cannot extend, distinguishable on the
   ledger from a standing personal access token. This is the natural evolution
   of issue #13 and closes the "second credential system" objection without
   moving a single fetch off the ledger. **Implemented here** (GW_0104).

2. **Visibility — a read-only forge mirror.** `ApprovalService` additionally
   pushing approved content to a forge repository, labelled a mirror, for
   humans to browse; agent installs keep pointing at the facade so fetches stay
   attributable. **Not implemented**, and deliberately sequenced after this: it
   is an outbound integration with its own failure modes (partial pushes, a
   mirror that drifts from what is served, revocation that must reach it), and
   it must never become a serving surface people install from.

## Consequences

- **The facade is critical infrastructure, and that is now explicit.** If it is
  the only door, its availability is a security property: developers cannot
  install and CI cannot build without it. The invariant that follows —
  **serving must not depend on ingestion** — already holds structurally
  (approved content is published bytes; an unreachable upstream stops
  ingestion, not serving), and is written down here so it stays a requirement
  rather than an accident.
- **A short-lived credential is a mitigation, not an identity system.** It is
  minted from an OIDC session and dies on a timer; it is not revoked when the
  session ends, because the gateway does not track session lifetime. The TTL is
  the control, and the guide says so rather than implying more.
- **Personal access tokens stay.** CI and non-interactive clients need a
  credential that survives having no browser. Short-lived session credentials
  are the better default for humans, not a replacement for PATs.
- **If the discussion ever trends to "forge as the only serving surface", this
  ADR is what has to be superseded** — that would rewrite invariants 1–3, and
  it should cost a new ADR rather than a configuration change.
