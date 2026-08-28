# Proposal: session-git-credentials

## Why

Issue [#59](https://github.com/skillsgateway/skillsgateway/issues/59) weighed
serving approved content out of an external forge instead of the gateway's own
facade. The forge case rested on two things a forge is genuinely better at —
identity and visibility — against two things that are the product: the approval
gate being enforced by code rather than by repository permissions, and the
append-only per-fetch ledger.

**ADR 0008** settles it: the facade stays canonical, and the identity half is
closed at the gateway instead. This change is that half.

Today a human who wants to `git clone` from the gateway mints a personal access
token. That token is a second credential system beside the OIDC login they
already have — it outlives the session, outlives the laptop, and is
indistinguishable on the ledger from a token deliberately provisioned for CI.
That is the objection the forge option was answering, and it does not need a
forge to answer it.

## What Changes

- **`POST /api/tokens/session`** mints a git credential from the caller's
  existing browser session. The caller does not choose its lifetime: the
  gateway caps it at `skills-gateway.tokens.session-ttl` (default 8 hours) and
  a request cannot extend it, because a credential you can extend is a personal
  access token with extra steps.
- **Session credentials cannot publish.** They may narrow to marketplaces like
  any fetch scope, and they carry no push scope at all — a
  [hosted marketplace](../../../docs/manual/guides/publishing-first-party-skills.md)
  is published to with a credential somebody deliberately provisioned.
- **They are distinguishable.** `access_tokens.session_derived` marks them, the
  token listing reports it, and the ledger entry says so — so an auditor can
  tell "this fetch used a credential derived from an SSO login that had just
  happened" from "this fetch used a standing token".
- Requirement GW_0104 with SVC_GW_0104.

## Explicitly not in this change

- **No forge mirror.** ADR 0008's other half, deliberately sequenced after this
  one: it is an outbound integration with its own failure modes, and it must
  never become a surface people install from.
- **No credential-helper or device-code flow.** A short-lived credential minted
  from the portal is the increment that removes the standing PAT for humans; a
  non-interactive helper is a client-side feature that can build on the same
  endpoint later.
- **No session-lifetime binding.** The credential dies on its timer, not when
  the browser session ends — the gateway does not track session lifetime. The
  TTL is the control, and the guide says exactly that rather than implying the
  stronger property.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `token-lifecycle`: a credential may be derived from an authenticated browser
  session with a gateway-capped lifetime the caller cannot extend, carrying no
  publication authority and marked as session-derived wherever it appears.

## Impact

- **DB**: `access_tokens.session_derived BOOLEAN NOT NULL DEFAULT FALSE`,
  folded into `V1__init.sql` per `CLAUDE.md`.
- **Backend**: `TokenService` (a session-credential path that ignores any
  requested expiry), `TokenController` (the new endpoint),
  `SkillsGatewayProperties.Tokens` (`sessionTtl`), `AccessToken`,
  `TokenRepository`.
- **API**: one new endpoint; the token view gains `sessionDerived`.
- **Portal**: `types.gen.ts` regenerated. No UI in this change — the endpoint is
  what a "get a git credential" button will call.
- **Docs** (same PR): `guides/consuming-skills.md`, `reference/api/tokens.md`,
  `reference/configuration.md`, `concepts/trust-boundaries.md`,
  `architecture.md`, and ADR 0008 indexed from `reference/decisions.md`.
- **Trust boundary**: this issues a facade credential → old-coder Tier 3,
  negative tests required.
