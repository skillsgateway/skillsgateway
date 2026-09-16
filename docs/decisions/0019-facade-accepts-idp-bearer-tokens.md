# ADR 0019 — The facade accepts identity-provider bearer tokens beside PATs

*Accepted, 2026-09-16. Supersedes the git-client half of the auth decision in
[ADR 0002](0002-toolchain-and-product-decisions.md); the rest of that ADR
stands. Decides option 2 of
[#392](https://github.com/skillsgateway/skillsgateway/issues/392) and records
the rejection of options 1 and 3.*

## Context

ADR 0002 settled authentication in one line: "OIDC (OAuth2) login for the web
portal + hashed personal access tokens for git clients (**git cannot do
interactive OIDC**). OAuth device flow deferred."

The parenthesis was the whole argument, and it was true when it was written. It
is no longer true of every client. Git Credential Manager implements a generic
OAuth authorization-code flow configured entirely through `git config`
(`credential.<url>.oauthClientId`, `oauthAuthorizeEndpoint`, `oauthTokenEndpoint`,
`oauthScopes`). A plain `git clone` against a gateway can therefore open a
browser, authenticate against the organisation's own SSO, and present the
resulting token — with no PAT in existence and no client modification.

Meanwhile the PAT step is friction on every consumer's first day, and the owner
asked outright whether a token should be required at all.

The constraint that outranks the friction: **every fetch stays attributed to an
identity.** The adoption report, the staleness report, the blast-radius answer
after a revocation and the append-only ledger all rest on it. A gateway that
cannot say who holds retracted content has given up the thing it exists to
provide.

## Options

**1. Anonymous fetch, globally or per marketplace.** Cheapest, and it breaks
attribution for exactly the marketplaces most likely to be widely adopted. If it
ever ships, the ledger must record the fetch as anonymous — never as a
fabricated identity — and the adoption page must show the marketplace as
unattributed.

**2. Identity-provider bearer tokens on the facade.** The facade accepts an
OIDC token from the provider the portal already trusts, validated by signature,
issuer, audience and time. The identity is the provider's own subject, so
attribution is not merely preserved but improved: it is the same subject the
portal derives, rather than a PAT principal that happens to match.

**3. Device-authorization flow (RFC 8628) minting a PAT.** The gateway shows a
code, the user confirms in a browser after SSO, the gateway mints a PAT. Git does
not speak the flow, so this helps only through a helper or a CLI — and the CLI
issue (#16) is closed.

**4. Better use of what exists.** The portal's set-up wizard already mints a PAT
seconds after an SSO login. Make it lead, default its fields sensibly, and make
the credential-helper line the primary copy target.

## Decision

**Accepted: option 2 as the target, option 4 now. Options 1 and 3 are rejected.**

Option 1 is rejected as a default and not adopted in any form here: the single
thing this product must be able to say is who fetched what, and a marketplace
flagged public would be precisely the one whose adoption data mattered most.
Revisiting it needs its own ADR, not a configuration flag added quietly.

Option 3 is rejected as redundant rather than wrong. It exists to reach clients
that cannot run a browser flow, and the client that could not — git — now can.
A device flow would be a second interactive path to the same place, with a
gateway-side authorization server to build and maintain.

### What option 2 actually commits to

- **PATs are unchanged and remain the default.** Nothing about
  `GW_AUTH_0003 — Git client authentication via personal access tokens` is
  weakened, reordered or made conditional. CI, air-gapped runners and anything
  without a browser keep the credential they have.
- **Off by default**, behind `skills-gateway.facade.idp-bearer.enabled`. A
  gateway that upgrades and sets nothing registers no filter, no provider and no
  decoder — the capability is absent, not disabled.
- **One identity provider, not two.** The key set comes from the existing `idp`
  client registration and the issuer from `skills-gateway.oidc.issuer`. Neither
  is separately configurable here, so the facade cannot be made to trust what the
  portal would refuse.
- **The issuer pin becomes mandatory for this capability.** Unset it is today's
  default and warns (`GW_AUTH_0017 — Enterprise identity-provider session
  integrity`); enabling bearer tokens without it refuses startup. On a
  multi-tenant authorization endpoint every tenant's tokens verify against the
  same keys, so `iss` is the only claim that says which organisation a holder
  belongs to — and at login the gateway has provenance a bearer token arriving
  cold does not.
- **The challenge stays `WWW-Authenticate: Basic`.** This is why the bearer path
  is a filter of the project's own rather than `oauth2ResourceServer()`: that
  configurer installs a bearer entry point, and the Basic challenge is what makes
  git's credential helper offer a password at all. A refused bearer token falls
  through to the same 401 an anonymous request gets, so an expired SSO token is a
  prompt rather than a dead end.
- **The ledger gains a credential kind.** `pat` or `idp`, on the entry itself
  (`GW_AUTH_0041 — Facade fetches record the kind of credential that
  authenticated them`). It cannot be inferred from `token_id` being null, because
  that null already means an administrative entry and an entry older than
  per-token attribution.
- **Authorization at the facade does not change.** An identity-provider token
  carries no marketplace scope list, so it permits exactly what an unscoped PAT
  permits. Roles are not consulted at the facade for any credential kind —
  [ADR 0017](0017-virtual-catalogs-are-derived-views.md) already recorded that
  the facade's authorization is token scopes and that role mapping gives it
  nothing. Making an SSO token role-gated would leave it *more* restricted than
  the PAT the portal mints for the same person, which nobody asked for.
- **`/api/**` does not follow.** The machine API chain authenticates a
  gateway-issued machine credential and nothing else, and the rest of the web
  surface stays OIDC-session only
  (`GW_AUTH_0042 — The administrative interface refuses identity-provider bearer
  tokens`). A token good enough to clone is not good enough to approve, and a
  test says so rather than the chain ordering implying it.

## Consequences

**ADR 0002's invariant 4 — "git clients use hashed PATs" — is no longer the
whole truth**, and `docs/manual/architecture.md` and `CLAUDE.md` are corrected
alongside this ADR. The invariant that survives, and matters, is the one
underneath it: *the browser never holds a gateway token, and every fetch is
attributed to an identity.* Both hold here; the second holds more exactly.

**A bearer token cannot be revoked at the gateway.** Revocation belongs to the
identity provider, and the gateway's only lever is the token's own expiry —
typically minutes to an hour, against a PAT's default of forever. This is
honestly a mixed trade rather than a pure win: the blast radius of a leak is far
smaller, and the gateway's ability to *act* on one is smaller too. An operator
who needs gateway-side revocation keeps using PATs, which is why both credentials
exist rather than one replacing the other.

**Authentication now depends on a network call.** A JWKS fetch on an unknown key
id means a provider outage can refuse fetches that a PAT would have served. The
key set is cached and refreshes only on an unknown `kid`, and PATs are unaffected
— so the documented fallback for every client is the credential that already
works.

**The configuration surface grew by two leaves**, `enabled` and `audience`, and
the test suite by one Spring context. Both ratchets were raised with the argument
written where the number lives.

**If this decision is revisited** — because a provider's tokens turn out to be
opaque rather than JWTs, or because gateway-side revocation becomes a hard
requirement — this ADR is what has to be superseded. Turning the capability off
is a supported operation and restores the ADR 0002 posture exactly; ledger rows
already written keep their credential kind and stay readable.
