# Proposal: machine-api-credentials

## Why

Every `/api/**` endpoint requires an interactive OIDC session. `SecurityConfig`
wires `PatAuthenticationProvider` into the facade chain (order 1) and the
publication chain (order 2) only; the web chain (order 4) has `oauth2Login()`
and nothing else. `docs/manual/reference/api/index.md` states the consequence
plainly: "Every `/api/**` endpoint requires an authenticated OIDC session."

So the gateway cannot be configured by anything that does not have a browser.
Terraform cannot register a marketplace. A CI job cannot trigger a reconcile,
read the estate report, or pull the ledger. The declarative estate closes part
of this — it is the answer whenever the estate is the gateway's own
configuration to ship — but it converges only on boot or on an API-triggered
reconcile, it covers only four object types, and in shops where the estate is
owned by a Terraform state rather than by the gateway's values file it is not
reachable at all. The gap is filled today by the worst available workaround: a
human's SSO session driven by a script, or a shared human account.

Meanwhile the credential the gateway already has — the PAT — is deliberately
confined to git. That confinement is a property worth keeping, not an oversight
to relax. This change adds machine credentials for the REST API **without**
letting an existing facade credential become one.

## What Changes

- **A third scope dimension on the access token.** Beside the fetch scope list
  (empty grants every marketplace) and the push scope list (empty grants
  nowhere), an **API scope list** whose empty value grants nothing — the push
  default, deliberately, because reaching the control plane is a grant and
  never a baseline. A token is a machine API credential exactly when that list
  is non-empty. No new credential type, no new secret store, no second
  revocation story.
- **A stateless machine-API filter chain**, a sibling of the facade and
  publication chains rather than a mode on the session chain. It is matched on
  a bearer `Authorization` header, creates and honours no session, and refuses
  a request that also carries a `Cookie`. The existing session chain is
  untouched and continues to ignore `Authorization`.
- **The negative guarantee.** A token holding only fetch scope — including the
  every-marketplace form, which is the most permissive fetch grant that exists
  — or only push scope reaches no `/api/**` endpoint. Session-derived
  credentials (`GW_0104`) can never hold API scope at all. The guarantee holds
  in the other direction too: API scope confers no fetch and no push.
- **An allowlist, never a denylist.** Named API scope values map to named
  groups of endpoints. Endpoints of human judgement — snapshot approval and
  rejection, waiver creation, deregistration, retention soft-delete and restore
  — are in no group, so no machine credential reaches them and none added later
  is admitted by silence.
- **Enforced independently of `skills-gateway.roles.enabled`.** That flag
  defaults to false and currently makes every `require*()` pass. Machine scope
  enforcement does not consult it: there is no existing machine credential to
  stay compatible with, so it is strict from the first release.
- **A machine principal.** Credentials are issued to a reserved `machine:`
  principal namespace that no identity-provider subject can occupy, so the
  ledger's actor column distinguishes machine from human from
  `config-reconciler` on sight, and the human who provisioned the credential is
  recorded as its owner.
- **Mandatory expiry.** A machine credential cannot be issued without one, and
  the configured lifetime cap applies. Rotation and revocation reuse the
  existing PAT lineage unchanged.
- Requirements GW_0115–GW_0120 with SVC_GW_0115–SVC_GW_0120.

## Explicitly not in this change

- **No OAuth2 client-credentials grant.** Argued in `design.md`; the short
  version is that it makes the gateway an authorization server it is not, and
  buys nothing the PAT lineage does not already give.
- **No approval by machine.** `CLAUDE.md` and
  `docs/manual/guides/declarative-estate.md` already say everything that
  retracts or publishes content stays interactive and audited. This change
  keeps that line and does not propose a "trusted automation" exception.
- **No fix for the `/api/**` session-cookie CSRF exemption.** It is a
  pre-existing gap with a "revisit with the portal" note in `SecurityConfig`
  and it needs the portal. This change must not deepen it: the machine chain
  earns its own exemption the way the git chain does — no cookie, no ambient
  credential — rather than borrowing the session chain's.
- **No portal UI.** Provisioning a machine credential is an admin API call in
  this change; the screen can follow.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `auth`: an access token may carry authority for the REST API as a scope
  dimension distinct from fetch and push; the API is reached by a stateless
  chain that honours no cookie; and a machine credential's principal is a
  reserved namespace that appears as itself on the ledger.
- `admin-api`: the endpoints a machine credential may reach are an allowlist
  that excludes every act of human judgement, enforced regardless of whether
  role enforcement is enabled.
- `admin-roles`: a machine principal acquires roles from configuration and
  grants only — never from claims, which it has none of — and cannot grant to
  itself; scope and role are both required, never either.
- `token-lifecycle`: a machine credential must expire, rotates preserving its
  principal and all three scope dimensions, and is administered by an admin
  rather than appearing in a human's own-token listing.

## Impact

- **DB**: `access_tokens.api_scopes TEXT` and a machine-owner column, folded
  into `V1__init.sql` per `CLAUDE.md`.
- **Backend**: `SecurityConfig` (a new chain), `PatAuthenticationProvider` (or a
  sibling that fails closed on empty API scope), `AccessToken`, `TokenService`,
  `TokenController`, `RoleService`, and an allowlist component.
- **API**: machine-credential provisioning endpoints; the token view gains the
  API scope list.
- **Portal**: `types.gen.ts` regenerated; no UI.
- **Docs** (same PR): `reference/api/index.md` (the "OIDC session only" line is
  no longer true and must be rewritten precisely), `reference/api/tokens.md`,
  `guides/declarative-estate.md` (estate versus API), `concepts/trust-boundaries.md`,
  `reference/configuration.md`, `architecture.md`.
- **Estate**: machine credentials are deliberately API-only, for the same
  reason PATs are — a credential's secret has no declarative form. The design
  states this rather than leaving it to inference.
- **Trust boundary**: this authenticates the control plane → old-coder Tier 3;
  adversarial and negative tests are required, not optional.
