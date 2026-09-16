# Design: facade-idp-bearer-tokens

See `proposal.md` for motivation and scope. This document is the **executable
specification** the [old-coder](../../../.claude/skills/old-coder/SKILL.md)
discipline asks for: the failure model first, then the decisions, then the
acceptance criteria each test has to earn. Tier 3 — this changes facade
authentication, which is a trust boundary.

**Spec approval: not obtained (autonomous run).** The owner approved the two
options in the issue, not this document. Confidence is claimed accordingly in
`evidence.md`; this file is the artifact to review after the fact.

## Context

Today `/git/**` is one filter chain (`SecurityConfig.gitChain`, `@Order(1)`):
HTTP Basic, a `ProviderManager` whose only provider is `PatAuthenticationProvider`,
`STATELESS`, CSRF disabled because nothing ambient can reach it. The
authenticated `UsernamePasswordAuthenticationToken` carries the `AccessToken`
row as its `details`, and two things read it from there:

- `GitFacadeConfiguration.resolvePublished` — marketplace scope enforcement
  (`GW_AUTH_0006`), which answers an out-of-scope request with the same
  `RepositoryNotFoundException` a nonexistent marketplace gets;
- `FetchAuditHook` — `token_id` and `actor_type` on the ledger row.

`details == null` is therefore already a meaningful state in both: no token row,
no scope restriction, `actor_type = human`. An IdP-authenticated request lands
in exactly that state, which is why this change is small in the facade and
concentrated in authentication.

The identity provider is configured once, for the portal, as the
`idp` OAuth2 client registration in `application.yaml`
(`jwk-set-uri`, `client-id`, `user-name-attribute`), plus
`skills-gateway.oidc.issuer` which `OidcIdTokenValidation` already uses to pin
the ID token's issuer at login.

## Goals / Non-Goals

**Goals:**

- A `git clone` authenticated by an SSO bearer token, with no PAT in existence.
- Validation no weaker than a resource server's: JWKS signature, issuer,
  audience, `exp`, `nbf`.
- The same principal string the portal derives for the same person, so the
  ledger, the adoption report and the staleness report keep working with no
  changes at all.
- A ledger that can say *which kind* of credential fetched.
- Off by default; a deployment that sets nothing is byte-for-byte unchanged.

**Non-Goals:**

- Role-scoped facade authorization (see "Scopes an IdP token does not have").
- Token introspection (RFC 7662) for opaque access tokens. This change accepts
  JWTs only; a provider that issues opaque access tokens is served by its ID
  token, which is always a JWT.
- Refresh-token handling, a token endpoint, or any OAuth client role for the
  gateway beyond the one it already plays at login. Git Credential Manager is
  the OAuth client here; the gateway is only a resource server.
- Anonymous fetch (issue option 1) and the device flow (option 3).

## Failure model

The ways *this* change can hurt, each paired with the layer that catches it.

| # | Failure | Why it is plausible here | Caught by |
|---|---|---|---|
| F1 | A token from a different issuer is accepted | A multi-tenant endpoint signs every tenant's tokens with the same keys, so signature alone proves nothing about *which organisation* | Mandatory issuer pin + `wrongIssuerIsRefused` |
| F2 | A token minted for a different application is accepted | Any app registered at the same IdP can hand its access token to the gateway; without an audience check that is a full-fetch credential | Audience validator + `wrongAudienceIsRefused` |
| F3 | An expired or not-yet-valid token is accepted | `JwtTimestampValidator` is not on by default on a hand-built decoder | `expiredTokenIsRefused`, `notYetValidTokenIsRefused` |
| F4 | A tampered token is accepted | Payload edited, signature left alone — the classic | `tamperedSignatureIsRefused` |
| F5 | The feature leaks when disabled | A filter registered unconditionally and gated on a boolean deep inside is one refactor away from fail-open | Bean is conditional on the property; `idpTokenIsRefusedWhenDisabled` runs in a context with the feature off |
| F6 | The bearer path leaks onto `/api/**` | Chains are ordered, and an ordering mistake is invisible | `idpTokenReachesNoAdminEndpoint` |
| F7 | The 401 challenge changes and every existing git client stops prompting | Adding an authentication mechanism to a Spring Security chain rewrites the entry point by default | Explicit `BasicAuthenticationEntryPoint`; `anonymousFetchStillGetsBasicChallenge` asserts the header verbatim |
| F8 | A PAT presented as a Bearer is accepted, or vice versa | The two credential kinds share a chain now | `patShapedStringInBearerHeaderIsRefused` |
| F9 | Attribution silently degrades — a fetch recorded with no principal | `details == null` already means "no token"; adding a second null-details path could mean "no identity" too | `credentialKind` is `NOT NULL` for facade rows; `idpFetchIsAttributedToTheIdpPrincipal` asserts principal and kind together |
| F10 | Enabling the feature on a deployment with no pinned issuer | The issuer is optional today and only warns | Startup refusal; `enablingWithoutAPinnedIssuerRefusesStartup` |

F1–F4 and F8 are the adversarial pass: each is a token this code must refuse,
constructed in the test from a real key pair rather than described.

## Decisions

### D1 — A dedicated filter and provider, not `oauth2ResourceServer()`

`http.oauth2ResourceServer(...)` on the facade chain would be one line, and it
is the wrong line. It registers `BearerTokenAuthenticationEntryPoint`, and the
chain's 401 then advertises `WWW-Authenticate: Bearer`. Git's credential helper
flow depends on the `Basic` challenge: that header is how `git` knows to ask for
a username and password at all. Silently converting every existing PAT user's
first request into a non-prompting failure to save one line is not a trade.

So: `FacadeBearerAuthenticationFilter` (modelled directly on
`MachineApiAuthenticationFilter`, which solved the same problem on `/api/**`)
plus `IdpBearerAuthenticationProvider` wrapping a `JwtDecoder`. The decoder is
Spring Security's `NimbusJwtDecoder` with the validators named below — the
validation is a resource server's, only the plumbing is ours. The entry point
stays `BasicAuthenticationEntryPoint`, stated rather than inherited.

*Alternative considered:* `oauth2ResourceServer` plus a
`DelegatingAuthenticationEntryPoint` restoring the Basic challenge. Same amount
of code, and it depends on a configurer's internal ordering to stay correct.

### D2 — The identity provider is the portal's, not a second one

The JWKS URI and the audience default come from the existing `idp`
`ClientRegistration`; the issuer comes from `skills-gateway.oidc.issuer`. No new
leaf names an issuer or a key set.

This is a security decision, not an economy. Two independently configured
issuer settings on one gateway is a configuration in which the facade can be
made to trust something the portal does not, and nothing in the system would
report the divergence. One source means the question "whose tokens does this
gateway accept?" has one answer.

*Alternative considered:* `skills-gateway.facade.idp-bearer.{issuer,jwks-uri}`.
Rejected: four leaves instead of two, and it buys only the ability to make a
mistake.

### D3 — Enabling without a pinned issuer refuses startup

`skills-gateway.oidc.issuer` unset is the default and only logs a warning
(`GW_AUTH_0017`), because at login Spring Security has already exchanged a code
over TLS with a configured endpoint. A bearer token arriving cold at the facade
has no such provenance: the only thing tying it to an organisation is `iss`. So
enabling `idp-bearer` without an issuer throws at startup with a message naming
both properties.

Failing closed at startup rather than at request time is deliberate: a
misconfiguration that only manifests as "tokens are accepted too broadly" is one
nobody observes.

### D4 — Audience defaults to the OAuth2 client id, and is overridable

Providers differ. An ID token's `aud` is the client id by construction; an
access token's `aud` is whatever the provider was told the resource is — an API
identifier, a URI, sometimes a list. So the default is the `idp` registration's
`client-id` (correct for the ID-token case and for providers that reuse it), and
`skills-gateway.facade.idp-bearer.audience` overrides it for everything else.

The validator accepts a token whose `aud` **contains** the expected value, per
RFC 7519 §4.1.3 — `aud` is a list. A token with no `aud` at all is refused.

### D5 — Both the ID token and the access token are accepted

They are both JWTs from the same issuer, validated identically, and the caller
does not choose which one the credential manager hands over. Refusing one kind
would make the feature work or not work depending on the provider, for no
security gain: the checks that matter (issuer, audience, signature, time) are
the same checks.

The principal is read from the claim the portal already uses —
`user-name-attribute` on the `idp` provider, `sub` by default. **This is the
whole attribution argument**: the same person fetching through the portal-minted
PAT and through an SSO bearer token produces the same `principal` string, so the
adoption report cannot tell them apart and does not need to.

### D6 — Scopes an IdP token does not have

A PAT may name marketplaces (`GW_AUTH_0006`); an unscoped PAT permits every
marketplace. An IdP token has no such list and no place to put one.

**It is treated exactly as an unscoped PAT: every marketplace the gateway
serves.** The issue asked for "all marketplaces the principal's roles allow",
and the honest answer is that today that set is *all of them*, because the
facade consults no roles — for any credential kind. ADR 0017 already recorded
why: "Role enforcement lives at the REST API. The facade's authorization is
token scopes." Inventing role-gated fetch here would mean an IdP token is
**more** restricted than the PAT the portal mints for the same person, which is
a difference nobody asked for and which would make the feature a downgrade.

So the negative case "a principal who maps to no role fetches a marketplace"
resolves to **allowed**, and `idpTokenWithNoMappedRoleStillFetches` pins it so a
future change to facade authorization has to be deliberate rather than
accidental. Role-scoped facade authorization remains the open item ADR 0017
describes, and it is not this change.

### D7 — `credential_kind` is a column, not an inference

`token_id IS NULL` cannot mean "IdP token", because it already means "an entry
older than per-token attribution" and "an administrative entry". A ledger is
append-only history; overloading a null is how history stops meaning what it
said.

So: `CREATE TYPE fetch_log_credential_kind AS ENUM ('pat', 'idp')`, a nullable
`credential_kind` column (null on every non-facade entry, exactly as `token_id`
is), folded into `V1__init.sql` per the project's single-migration rule. It
follows `actor_type`'s precedent in every respect — including that it is
denormalised on purpose and is not a foreign key.

Surfaced on the ledger read (`SELECT *`, so `GET /api/audit` carries it) and
documented in the entry-field table beside `actorType`. The export record
`AuditEntry` is left alone, exactly as `actor_type` leaves it alone; changing
the export schema is a separate decision with SIEM consumers attached.

### D8 — Two configuration leaves, against a budget of 104

`ConfigSurfaceBudgetTests` is a ratchet whose docstring says raising it "means
writing down why a deployment cannot express what it needs with the leaves that
already exist". Here is that writing-down.

- **`skills-gateway.facade.idp-bearer.enabled`** — cannot be expressed by an
  existing leaf. The nearest candidate is "infer it from whether an issuer and
  audience are set", which is precisely the fail-open shape D3 exists to avoid:
  a feature that switches itself on as a side effect of an unrelated setting.
  A trust-boundary widening has to be something an operator typed.
- **`skills-gateway.facade.idp-bearer.audience`** — cannot be expressed by an
  existing leaf either. The OAuth2 client id is the default (D4) and covers the
  ID-token case; it is provably wrong for providers whose access-token audience
  is a separate resource identifier, and there is no other leaf that names one.

Both are genuinely needed and neither has an existing home. `Facade` is a new
nested properties record holding one nested record holding those two components,
so the count rises by exactly two. If the measured count then exceeds 104, the
ratchet is raised to the measured number with this section as the argument —
never pre-emptively, and the `evidence.md` records the before and after figures
from `target/config-surface.txt`.

### D9 — What the wizard changes, and what it does not

The token request stays `{name}` only for the default path; the wizard gains a
**lifetime** select whose options are bounded by the existing
`skills-gateway.tokens.max-ttl` policy and whose default is a bounded value
rather than "never expires". No policy maximum changes — the server refuses what
it always refused; the client now mirrors it (design-conventions: "The client
mirrors the server, exactly").

Placement: on a marketplace with an approved snapshot, a lead panel above the
Upstream card. On one without, the same slot carries the held-snapshot
statement, and the wizard itself repeats it — a consumer who opens the wizard
from the header must see it too, not only one who reads the page top-down.

The portal has no `serving` snapshot state; "serving" on the detail page is
derived exactly as the adoption page derives it, from an approved snapshot
existing. That derivation is stated in the component rather than left implicit,
because it is the condition the whole panel is gated on.

## Acceptance criteria

Each line is one test. `evidence.md` maps them to their names and results.

**Facade, positive**

1. With the feature on, `git clone` carrying `-c http.extraHeader="Authorization: Bearer <valid token>"` against a serving marketplace succeeds and checks out the approved SHA.
2. The resulting ledger rows name the principal from the configured username claim, carry `credential_kind = 'idp'`, and carry `token_id = null`.
3. A PAT clone against the same gateway still succeeds and still records `credential_kind = 'pat'` with a non-null `token_id`.
4. An IdP token whose principal maps to no role fetches a marketplace successfully (D6).

**Facade, negative** — each answers 401 and transfers nothing

5. Wrong issuer. 6. Wrong audience. 7. Expired. 8. Not yet valid (`nbf` in the future). 9. Tampered signature (valid header/payload, signature from another key). 10. A well-formed PAT string in a `Bearer` header. 11. A valid IdP token while the feature is disabled. 12. A token with no `aud` claim.

**Posture**

13. An unauthenticated facade request still answers `401` with `WWW-Authenticate: Basic` (F7).
14. A valid IdP token reaches no `/api/**` endpoint (F6) — asserted against an endpoint an admin session can reach.
15. Enabling the feature with no `skills-gateway.oidc.issuer` refuses startup, naming both properties (F10, D3).

**Invariants that must survive**

16. Every existing `SVC_GW_AUTH_*` and `SVC_GW_FACADE_*` test passes unchanged; none is weakened or deleted.
17. `ConfigSurfaceBudgetTests` passes, with the budget raised only if measured and only with D8 as the argument.
18. The default configuration is unchanged: with nothing set, no bearer filter is registered at all.

**Portal**

19. On a marketplace with an approved snapshot the lead panel renders and the `git credential approve` snippet is the primary copy target (Storybook story, axe clean).
20. On a marketplace whose snapshot is `held` the panel and the wizard both state that a clone answers 404 until a snapshot is approved (Storybook story, axe clean; Playwright step).

## Risks / Trade-offs

- **A bearer token is a bearer token.** An access token pasted into a shell
  history or a CI log is as good as a PAT until it expires, and unlike a PAT it
  cannot be revoked at the gateway — revocation is the identity provider's.
  → Mitigated only partially, and honestly: the lifetime is the provider's
  (typically minutes to an hour against a PAT's default of forever), and the
  feature is off by default. Documented as a stated limit in
  `reference/git-facade.md` rather than left for an operator to discover.
- **JWKS fetches are network calls on the authentication path.** A provider
  outage or a key rotation makes fetches fail. → `NimbusJwtDecoder` caches the
  key set and refreshes on an unknown `kid`; PATs are unaffected, so the
  fallback for every client is the credential that already works.
- **Clock skew.** `JwtTimestampValidator`'s default 60-second leeway is kept
  rather than tightened; a token rejected for a skewed clock is an outage that
  looks like a security control working.
- **The mock identity provider in the e2e suite is not a real one.** The worked
  Git Credential Manager recipe is exercised against it; a real provider may
  name its endpoints differently. → The documented recipe marks which values are
  deployment-specific.
- **Raising the config ratchet is a real cost**, and D8 is the argument rather
  than an excuse. Two leaves is the smallest surface that can express "on" and
  "for this audience" without inferring either.

## Migration Plan

None required. The column is added to the single `V1__init.sql`, which
Testcontainers recreates per run and which the project's convention keeps as one
file until the owner says otherwise. No default changes, nothing is removed, and
the feature is inert until an operator sets `enabled: true` and pins an issuer.
Rollback is setting `enabled: false`; the ledger rows already written keep their
`credential_kind` and remain readable.
