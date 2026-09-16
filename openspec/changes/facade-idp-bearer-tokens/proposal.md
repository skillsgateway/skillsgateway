# Proposal: facade-idp-bearer-tokens

Implements [#392](https://github.com/skillsgateway/skillsgateway/issues/392),
options **2** and **4** only. The owner ruled out option 1 (anonymous fetch,
which breaks attribution) and option 3 (RFC 8628 device flow, which git does not
speak); neither is built here and neither is designed here.

## Why

Cloning through the facade requires a personal access token minted in the portal
after an SSO login and then pasted into a URL or a credential helper. That is
correct for the trust model — ADR 0002 settled "PATs for git clients, because git
cannot do interactive OIDC" — but it was settled when that statement was true of
every client. It no longer is: Git Credential Manager speaks a generic OAuth
flow configured entirely with `git config`, so a plain `git clone` can open a
browser, authenticate with the same SSO the portal uses, and present the
resulting bearer token — with no PAT anywhere.

The one constraint the issue puts above the rest is that **every fetch stays
attributed to an identity**. The adoption and staleness reports, the
blast-radius answer after a revocation, and the append-only ledger all rest on
it. An IdP token carries the same subject the portal already derives, so
attribution survives — and the ledger gains the one thing it cannot currently
say, which is *what kind of credential* a fetch was.

The second half is smaller and came from a real confusion the same day: a
consumer opening a marketplace whose only snapshot is `held` gets a wizard that
composes a clone command which answers 404, and nothing on screen says why.

## What Changes

- **The facade accepts an identity-provider bearer token beside a PAT.**
  `/git/**` keeps HTTP Basic and the PAT provider exactly as they are, and gains
  a second, independent path: `Authorization: Bearer <jwt>`, validated as a
  resource server validates one — signature against the identity provider's
  JWKS, issuer, audience, expiry and not-before. Both the OIDC access token and
  the ID token are accepted, because the two providers in common use disagree
  about which one a generic OAuth client receives.
- **Off by default, behind `skills-gateway.facade.idp-bearer.enabled`.** Two new
  configuration leaves in total (`enabled` and `audience`); everything else the
  validation needs — the JWKS URI and the issuer — is read from the identity
  provider the portal already trusts, so an operator cannot point the facade at
  a second, weaker issuer by accident.
- **Enabling it without a pinned issuer refuses startup.** `skills-gateway.oidc.issuer`
  is optional today and only warns. A bearer token accepted on evidence of
  signature alone would admit every tenant of a multi-tenant endpoint, so this
  feature makes the pin mandatory for itself rather than inheriting the warning.
- **A distinct credential kind on the ledger.** Every facade fetch records `pat`
  or `idp`. `token_id` stays null for an IdP fetch — there is no token row to
  name — which is exactly why the kind has to be recorded instead of inferred
  from that null. Adoption and staleness reports key on `(principal,
  marketplace)` and are untouched.
- **Authorization at the facade does not change.** An IdP token carries no
  marketplace scope list, so it permits every marketplace the gateway serves —
  identical to an unscoped PAT, which is what the portal mints today. Roles are
  not consulted at the facade for any credential kind, and this change does not
  start consulting them. See `design.md`, "Scopes an IdP token does not have".
- **`/api/**` must not start accepting these tokens**, and a test says so rather
  than the chain ordering implying it. The machine API chain authenticates a
  gateway-issued machine credential and nothing else; the web surface stays
  OIDC-session only.
- **The client wizard leads.** On a marketplace that is serving, the wizard is
  the first thing on the detail page rather than a button competing with the
  page header; the token name and lifetime carry sensible defaults (no change to
  the policy maxima); and the `git credential approve` line becomes the primary
  copy target. On a marketplace whose snapshot is still `held`, the wizard says
  plainly that a clone answers 404 until a snapshot is approved.
- **Git Credential Manager's generic OAuth recipe is documented**, worked against
  the mock identity provider the e2e suite already runs.

## Capabilities

### New Capabilities

**auth.** Four requirements, taking ids from `GW_AUTH_0040` rather than the next
free `GW_AUTH_0031`: another change (for
[#394](https://github.com/skillsgateway/skillsgateway/issues/394)) is allocating
`GW_AUTH_` ids concurrently, and starting at the current maximum plus ten leaves
that change room without a collision neither branch would see until both merged.
The gap is deliberate and is not a retirement of `0031`–`0039`.

- `GW_AUTH_0040` — Identity-provider bearer tokens on the git facade
- `GW_AUTH_0041` — Facade fetches record the kind of credential that authenticated them
- `GW_AUTH_0042` — The administrative interface refuses identity-provider bearer tokens
- `GW_AUTH_0043` — The client wizard leads on a serving marketplace and states the held-snapshot outcome

### Modified Capabilities

_None._ No existing requirement changes its meaning. `GW_AUTH_0003 — Git client
authentication via personal access tokens` still holds exactly as written: PATs
still authenticate git clients, and nothing about that path is weakened,
reordered or made conditional. `GW_AUTH_0014 — Client setup wizard` still holds;
`GW_AUTH_0043` adds placement and the held-snapshot statement beside it rather
than amending it.

## Impact

**A trust-boundary change**, and the [old-coder](../../.claude/skills/old-coder/SKILL.md)
discipline applies: failure model first, adversarial and negative tests, an
evidence report.

- **ADR 0002 is partially superseded.** Its clause "hashed personal access
  tokens for git clients (git cannot do interactive OIDC)" no longer describes
  the facade. **ADR 0019** records the new position; the rest of ADR 0002
  stands, as with ADRs 0012 and 0013. `CLAUDE.md`'s Boundaries bullet and
  `docs/manual/architecture.md`'s invariant 4 are corrected in the same PR.
- **Code.** `src/main/java/dev/skillsgateway/server/auth/` (a new bearer filter
  and provider, the facade chain), `facade/FetchAuditHook`,
  `persistence/FetchLogRepository` and `V1__init.sql` (one new enum type and
  column), `config/SkillsGatewayProperties` (two leaves — see `design.md` for the
  argument against `ConfigSurfaceBudgetTests`).
- **API contract: additive.** The ledger read gains a field; no path prefix
  moves, no field is removed or retyped. `docs/manual/reference/compatibility.md`
  is unchanged.
- **Portal.** `components/setup-wizard.tsx` and `pages/marketplace-detail.tsx`,
  with a Storybook story (axe-as-error) and a Playwright step.
- **Docs.** `guides/consuming-skills.md`, `reference/git-facade.md`,
  `reference/configuration.md`, `reference/portal.md`,
  `reference/api/audit.md`, `concepts/trust-boundaries.md`,
  `architecture.md`, and the ADR index.
- **Nothing is removed and no default changes.** A deployment that upgrades and
  sets nothing behaves exactly as it does today.
