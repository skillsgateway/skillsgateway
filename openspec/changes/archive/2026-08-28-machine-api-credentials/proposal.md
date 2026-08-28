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
  publication chains rather than a mode on the session chain. It authenticates
  `Authorization: Bearer` — not the Basic scheme the facade uses, so the chain
  matcher is unambiguous — creates and honours no session, and refuses a request
  that also carries a `Cookie`. The existing session chain is untouched.
- **The negative guarantee.** A token holding only fetch scope — including the
  every-marketplace form, which is the most permissive fetch grant that exists
  — or only push scope reaches no `/api/**` endpoint. Session-derived
  credentials (`GW_0104`) can never hold API scope at all. The guarantee holds
  in the other direction too: API scope confers no fetch and no push.
- **Per-concern scopes, never one coarse "admin".** Around twenty named scopes
  derived from the actual controller inventory — `marketplaces:register`,
  `policy:write`, `audit:read`, `estate:reconcile` and so on. A credential
  minted for a pipeline that registers marketplaces cannot also rewrite audit
  sinks. This has to be right at first issue: narrowing a coarse scope later
  silently changes what already-issued credentials mean, which is a breaking
  change no version bump can communicate. No wildcard, no implicit "all", no
  scope implying another; scopes compose additively.
- **An allowlist on top, never a denylist.** Endpoints of human judgement and
  every endpoint that retracts content — snapshot approval and rejection,
  waivers, retention evaluate/compact/delete/restore, role granting — are in no
  scope group, so no combination of scopes reaches them and none added later is
  admitted by silence.
- **Enforced independently of `skills-gateway.roles.enabled`.** That flag
  defaults to false and currently makes every `require*()` pass. Machine scope
  enforcement does not consult it: there is no existing machine credential to
  stay compatible with, so it is strict from the first release.
- **An explicit actor type on the audit ledger.** `fetch_log` gains a
  denormalised `actor_type` (`human` / `machine` / `system`) beside the existing
  human-readable `principal`. This replaces — and repairs — today's implicit
  vocabulary, in which `config-reconciler`, `scheduler` and `system` are magic
  strings smuggled into the principal column.
- **Mandatory expiry.** A machine credential cannot be issued without one, and
  the configured lifetime cap applies. Rotation and revocation reuse the
  existing PAT lineage unchanged.
- Requirements GW_0126–GW_0131 with SVC_GW_0126–SVC_GW_0131.

## Explicitly not in this change

- **No OAuth2 client-credentials grant.** Argued in `design.md`; the short
  version is that it makes the gateway an authorization server it is not, and
  buys nothing the PAT lineage does not already give.
- **No approval by machine, and no retraction by machine.** `CLAUDE.md` and
  `docs/manual/guides/declarative-estate.md` already say everything that
  retracts or publishes content stays interactive and audited. This change
  keeps that line and does not propose a "trusted automation" exception.
- **No role granting by machine.** `estate.grants` already covers the
  declarative case with no credential in the pipeline; a machine write path
  would add escalation surface for a capability that has a safer route.
  *Reading* grants (`roles:read`) is reachable, because denying it would not
  prevent drift — the estate never prunes, so it cannot discover a grant made
  by hand — it would only make that drift undetectable. The reconnaissance cost
  is accepted and paid for: every authorized read of `/api/roles` is recorded on
  the ledger — human and machine alike, with `actor_type` telling them apart —
  which is a new behaviour, since no read on this surface is logged today.
- **The enumerating allowlist test is a prerequisite, not part of this
  change.** It touches every controller and bundling it would make a
  trust-boundary PR harder to review. See task group 0.
- **No fix for the `/api/**` session-cookie CSRF exemption.** It is a
  pre-existing gap with a "revisit with the portal" note in `SecurityConfig`
  and it needs the portal. This change must not deepen it: the machine chain
  earns its own exemption the way the facade chain does — no cookie, no ambient
  credential — rather than borrowing the session chain's.
- **No portal UI.** Provisioning a machine credential is an admin API call in
  this change; the screen can follow.
- **The first credential is still minted by a human session, and that is worth
  saying out loud.** `/api/tokens/**` is unreachable by machine by design, so
  bootstrapping a machine credential means an admin driving the admin API from a
  browser session — the same scripted-session workaround the *Why* section
  criticises. The difference is that it happens once, to create a credential
  with a stated expiry and named scopes, rather than continuously to run a
  pipeline. The portal screen is what removes it properly.
- **A Terraform provider still cannot converge a marketplace's whole lifecycle.**
  There is no `PUT` or `DELETE /api/marketplaces/{name}` — registration exists,
  deregistration does not — so `terraform destroy` has nothing to call. That is a
  pre-existing gap in the API rather than something this change introduces, but a
  provider author will meet it immediately, so the guide states what can and
  cannot be converged instead of letting them discover it.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `auth`: an access token may carry authority for the REST API as a scope
  dimension distinct from fetch and push; the API is reached by a stateless
  bearer chain that honours no cookie; and the audit ledger records an explicit
  actor type rather than encoding actor kind in the principal string.
- `admin-api`: the endpoints a machine credential may reach are per-concern
  named scopes over an allowlist that excludes every act of human judgement and
  every retraction of content, enforced regardless of whether role enforcement
  is enabled.
- `admin-roles`: a machine credential's principal acquires roles from
  configuration and declared grants only — never from claims, which it has none
  of, and never through the grants API, which it cannot reach; scope and role
  are both required, never either.
- `token-lifecycle`: a machine credential must expire, rotates preserving its
  identity and all three scope dimensions, and is administered by an admin
  rather than appearing in a human's own-token listing.

## Impact

- **DB**: `access_tokens.api_scopes TEXT` and a machine-owner column;
  `fetch_log.actor_type TEXT NOT NULL` with a backfill for the three existing
  magic-string actors. Folded into `V1__init.sql` per `CLAUDE.md` — the project
  is pre-1.0.0 and schema change is acceptable.
- **Backend**: `SecurityConfig` (a new chain), a bearer authentication provider
  that fails closed on empty API scope, `AccessToken`, `TokenService`,
  `TokenController`, `AdminAuditLogger`, `FetchLogRepository`, `RoleService`,
  and a scope-to-endpoint allowlist component.
- **API**: machine-credential provisioning endpoints; the token view gains the
  API scope list. Additive within the major.
- **Portal**: `types.gen.ts` regenerated; no UI.
- **Estate**: machine credentials are deliberately API-only, for the same
  reason PATs are — a credential's secret has no declarative form. The design
  states this rather than leaving it to inference.
- **Docs** (same PR): `reference/api/index.md` (the "OIDC session only" line is
  no longer true and must be rewritten precisely), `reference/api/tokens.md`,
  `guides/declarative-estate.md` (estate versus API), `concepts/trust-boundaries.md`,
  `reference/configuration.md`, `architecture.md`.
- **Prerequisite**: the enumerating allowlist test ships first, as its own PR.
- **Trust boundary**: this authenticates the control plane → old-coder Tier 3;
  adversarial and negative tests are required, not optional.
