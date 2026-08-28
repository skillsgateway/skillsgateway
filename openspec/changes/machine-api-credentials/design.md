## Context

Four filter chains exist today in `SecurityConfig`:

| Order | Matcher | Authentication | Session |
| --- | --- | --- | --- |
| 1 | `/git/**` | `PatAuthenticationProvider` over HTTP Basic | STATELESS |
| 2 | `/publish/**` | `PatAuthenticationProvider` over HTTP Basic | STATELESS |
| 3 | `/hooks/**` | anonymous; HMAC checked in the controller | STATELESS |
| 4 | everything else | `oauth2Login()` | session cookie |

`/api/**` falls to chain 4, so the control plane is reachable only from a
browser that has completed an OIDC login. Chain 4 also carries
`csrf.ignoringRequestMatchers("/api/**")` with the comment "Session-cookie API
for the SPA; revisit CSRF with the portal" — a known, deferred gap.

`AccessToken` already models two independent scope dimensions with deliberately
opposite defaults: `scopeList()` empty means *every* marketplace (what every
pre-scoping token meant), `pushScopeList()` empty means *nowhere*. The
`permitsPushTo` javadoc states the reasoning explicitly, and it is the reasoning
this change extends rather than reopens.

`RoleService` enforces authorization by explicit `require*()` calls at the head
of privileged controller methods, from three sources — `config` (the
`skills-gateway.roles.admins` list), `grant` (`role_grants` rows) and `claim`
(`ClaimRoleMapper`). `skills-gateway.roles.enabled` defaults to **false**, and
in that state every `require*()` returns immediately: every check passes.
`ClaimRoleMapper` reads claims only from an `OidcUser` and documents that a
personal access token "has no claims and derives nothing".

## Goals / Non-Goals

**Goals:**

- A credential a Terraform provider or a CI job can hold, with no browser.
- A credential that reaches configuration and reads, and no act of human
  judgement.
- No weakening of the facade's confinement, and no weakening of the session
  path's current posture.
- One lifecycle — issue, rotate, revoke, expire — not two.

**Non-Goals:**

- Approval, rejection, waivers or deregistration by machine.
- Fixing the `/api/**` session-cookie CSRF exemption.
- An authorization server, a token endpoint, or JWT-bearer assertions.
- Portal UI.

## Decisions

### 1. Credential shape: a third scope dimension, not a new credential type

**Decision.** Extend `AccessToken` with an **API scope list**, sibling to the
fetch and push lists. A token is a machine API credential exactly when that list
is non-empty. Empty grants nothing, matching the push default, not the fetch
default.

**Why a dimension and not a boolean.** A boolean `apiEnabled` would make "may
call the API" a single grant, and the API is not one thing — reading the ledger
and rewriting policy rules are not the same authority. A list of named scope
values is what makes decision 3 (the allowlist) expressible at all.

**Why not a distinct credential type.** The tempting argument is separation of
concerns: a different secret prefix, a different table, a different provider,
so the two can never be confused. The argument fails on the thing that actually
goes wrong in production. A second type doubles the number of places that must
implement revocation, expiry, rotation-without-two-live-secrets, hash storage,
audit vocabulary and the "cleartext exactly once" rule — and every one of those
is a place a second implementation can be subtly weaker than the first. The
confusion risk it defends against is instead eliminated by construction: a
single `AccessToken` carries all three dimensions, each chain asks only the
dimension it owns, and the mutual exclusion of decision 2 is then a property of
one object rather than an invariant maintained across two tables. One record,
three questions, three chains.

The cost is honest and worth stating: the token table now serves two audiences
and `access_tokens` rows are no longer all "a human's fetch credential".
Decision 4 (the machine principal) is what keeps that from becoming an
attribution mess.

**Why not OAuth2 client-credentials.** It is the obvious alternative and it was
considered seriously.

*What it would give.* Standard shape, off-the-shelf client support, no bespoke
secret handling, and short-lived access tokens so a leaked credential expires
on its own.

*Why it was rejected.* The gateway is an OIDC **client** and its own BFF — it is
not an authorization server, and there is no external one that knows about these
principals. Client-credentials therefore means one of two things. Either the
gateway grows a token endpoint, client registry, client-secret store, JWT
signing keys, key rotation and introspection — a new trust boundary strictly
larger than the one being solved, to end up at "a secret that mints a bearer
token", which is what a personal access token already is. Or the organisation's
identity provider issues the tokens, which moves the trust root for the
gateway's control plane to a system whose client registrations the gateway does
not control, on an app registration that `SecurityConfig`'s issuer-pinning
warning already notes may be shared with other services. Neither buys revocation
better than `revoked_at` checked at authentication time, and both must still
answer decisions 2–5 in exactly the same way.

*What would reopen it.* Workload identity federation — a CI runner presenting
an OIDC token it already has, so no secret is stored in the pipeline at all.
That is a genuinely better credential and a genuinely larger change. It belongs
in a future ADR, and this design is deliberately shaped so it could later be a
second way to obtain the same API scopes rather than a second authorization
model.

### 2. The negative guarantee: a fetch credential must not reach the API

This is the core security property of the change and it must fail closed in the
authentication layer, not in an authorization check a controller could forget to
call.

**Decision.**

- A **new stateless chain** is inserted for `/api/**`, ordered before the web
  chain and matched additionally on the presence of a bearer `Authorization`
  header, so a browser request without one still falls through to the session
  chain exactly as today.
- Its authentication provider authenticates a token **only if its API scope list
  is non-empty**, and grants an authority derived from those scopes. A valid
  facade token authenticates on the facade chain and fails on this one. The
  check is a precondition of authentication, not a later `authorizeHttpRequests`
  rule, so no controller can be the single point of failure.
- **Session-derived credentials (`GW_0104`) can never hold API scope.** They are
  minted from a browser session with a gateway-set lifetime for fetching; the
  `session_derived` flag is refused API scope at issue time, not merely absent
  from it.
- The guarantee is **symmetric**: API scope confers no fetch and no push. The
  facade keeps asking `permitsMarketplace`, publication keeps asking
  `permitsPushTo`, and neither learns about API scope.

**The every-marketplace trap.** The most dangerous token in the system is the
ordinary unscoped fetch token, because empty fetch scope means *all*
marketplaces. Any implementation that reasons "no scopes means unrestricted"
would hand that token the entire control plane. The three defaults must be
stated together in the code as they are here: fetch empty = everything, push
empty = nothing, API empty = nothing.

**CSRF and the session path.** The machine chain earns its CSRF exemption the
way the facade and publication chains do, and the same comment shape should say
so: STATELESS, no session created, **no cookie honoured**, every request
carrying its own credential. To make that not merely true but enforced, the
machine chain **rejects a request that carries both a bearer header and a
`Cookie` header** rather than picking one. Ambiguity about which credential
authorised a request is where confused-deputy bugs live, and refusing the
ambiguous request costs a legitimate client nothing. The session chain is not
modified: its existing `/api/**` CSRF exemption is neither widened nor relied
upon by anything new, and the residual gap stays recorded as the pre-existing
item it is.

### 3. What a machine credential may reach: configuration, never judgement

**Decision.** API scope values name **groups of endpoints**, and reach is an
**allowlist**. Endpoints in no group are unreachable by every machine
credential, including one whose principal holds `admin`.

Reachable — configuration and reads:

- marketplace registration and sync-mode configuration
- webhook subscribers, audit sinks, audit ledger read and export
- policy rules, retention policy configuration
- estate report read and reconcile trigger
- adoption and provenance reads

Unreachable — acts of judgement, by any machine credential:

- snapshot **approval** and **rejection**, and re-vet decisions
- **waiver** creation
- marketplace **deregistration**
- retention **soft-delete** and **restore**
- minting credentials for another principal

**Why this line and not a broader one.** The held-until-approved gate is the
product. `CLAUDE.md` and `docs/manual/guides/declarative-estate.md` already draw
this exact line for the declarative estate — "everything that retracts or
publishes content stays interactive and audited" — and a machine credential that
could approve would make the gate a formality reachable by whatever holds a
secret in a CI variable. The counter-argument, that a pipeline sometimes wants
to auto-approve a snapshot that passed vetting, is real and is answered by
policy rules and vetting configuration, which *are* reachable: encode the
judgement as a rule a human approved, rather than as a machine that judges.

**Why an allowlist and not a denylist.** A denylist admits by silence. Every
endpoint added after this change would be machine-reachable by default,
including the next act of judgement someone adds, and the mistake would be
invisible in the diff that made it. An allowlist fails the other way: a new
endpoint is unreachable until someone names it, which surfaces as a bug report
rather than as an incident. The test that makes this real is the enumerating
one in task group 5 — every mapped controller method is either in a group or on
an explicit "unreachable" list, and a method in neither fails the build.

**Role grants are reachable, with a guard.** Managing grants from
infrastructure-as-code is a legitimate and common need, and `estate.grants`
already does exactly this declaratively. But a credential that can write
`role_grants` can grant itself `admin`. The guard is cheap and testable: a
machine principal may not grant a role **to itself**, and grants require their
own named scope rather than riding along with general configuration.

### 4. Audit attribution: a reserved machine principal

Today a token's `principal` is its owning human, and `AdminAuditLogger.record`
takes `authentication.getName()`. Reusing that unchanged would write `alice` on
the ledger for every Terraform apply — the attribution failure that makes an
audit ledger stop being evidence.

**Decision.**

- A machine credential is issued to a **machine principal** in a reserved
  namespace, `machine:<name>` (for example `machine:ci-terraform`). The prefix
  is reserved end to end: it is refused as an identity-provider-derived
  principal and refused as a grant principal typed by hand, so a machine
  principal can never collide with a human subject.
- The ledger's actor column then carries `machine:ci-terraform`, sitting beside
  human identity-provider subjects and beside `config-reconciler` (the estate
  reconciler's actor). Three visibly distinct kinds of actor, distinguishable by
  reading, with no schema change to the ledger.
- Each entry's detail carries the **token id**, as the token lifecycle entries
  already do, so a rotation lineage is followable from any single entry.
- The **provisioning human is recorded as the credential's owner** — on the
  `token-created` ledger entry and on the credential's administrative listing.
  There is always a responsible human, named once at provisioning rather than
  impersonated on every use.

### 5. Estate YAML versus the API

State the rule plainly, because the overlap is where operators guess wrong:

> **Declare converge-able state. Call for acts and reads.**

- **Estate YAML** is for desired state the gateway re-converges on every boot:
  marketplaces, role grants, webhook subscribers, audit sinks. It is additive,
  idempotent, never prunes, applies the same trust boundary as the API, and —
  the decisive property — needs **no credential in the pipeline at all**. Where
  estate YAML covers the object, it remains the recommendation, including for
  Terraform shops: a provider that converges the same objects is a second
  converger with a secret.
- **The API with a machine credential** is for what has no declarative form:
  acts (trigger a reconcile), reads (the ledger, the estate report, adoption),
  one-shot secrets (the webhook sync-mode secret, returned exactly once), and
  objects that are not estate types. It is also the answer where the gateway's
  configuration is genuinely not the deployer's to write — a managed platform
  where the values file belongs to another team.
- **Machine credentials are themselves API-only**, for the same reason personal
  access tokens are: a credential's secret has no declarative form, and
  inverting that (operator supplies the secret, as `estate.webhooks` does) would
  put a control-plane credential in a values file. `CLAUDE.md`'s continuous
  obligation is satisfied by stating this deliberately, not by adding an estate
  type.

### 6. Revocation, rotation, expiry

Reuse the existing token lineage; it already has the properties this needs.

- **Revocation** is the existing `revoked_at`, checked at authentication time,
  immediate and audit-logged.
- **Rotation** is the existing revoke-then-issue, so no moment has two live
  secrets. It preserves the machine principal, the expiry **deadline**, and all
  three scope dimensions — a rotation that silently widened or dropped API
  scope would be the worst possible bug in this feature, and it gets a test.
- **Expiry is mandatory**, unlike a human token where it is optional. A
  never-expiring credential sitting in a CI variable is the failure mode this
  feature would otherwise introduce, and the configured lifetime cap
  (`validateTtl`, GW_0065) applies on top. A request without an expiry is
  refused, never defaulted — the same "refuse, never clamp" posture GW_0065
  already takes.
- **Administration.** `TokenService.list` and `revoke` are scoped to
  `authentication.getName()`, so a machine credential's rows would be invisible
  to the admin who created it — nobody could list or revoke it from the portal,
  which is unacceptable during an incident. Machine credentials are therefore
  administered through an **admin-scoped** view and revocation path, separate
  from the caller's own-token listing, which keeps its current strict
  own-principal scoping unchanged.

### 7. How a machine credential acquires a role

Two of the three role sources already work for a machine principal, and the
third provably cannot.

- **`claim` cannot apply.** `ClaimRoleMapper` reads claims only from an
  `OidcUser`; a token has none and derives nothing. This is not a gap to close —
  it is the correct behaviour, and the design depends on it.
- **`config` and `grant` work unchanged**, because a machine principal is just a
  principal string. `skills-gateway.roles.admins` may list one, and
  `role_grants` may carry one — which means `estate.grants` can *declare* a
  machine principal's role with no new mechanism at all. That is the intended
  path: the credential is provisioned by API, its authority is declared in the
  estate.
- **No fourth source, and no role baked into the token.** Putting a role in the
  credential would create a second authorization model that no longer answers to
  `RoleService`, and revoking it would mean rotating a secret rather than
  deleting a row.

**Scope and role are both required, never either.** Effective authority is the
**intersection**: the credential's API scope allowlist ∧ the principal's roles.
An `admin`-granted machine principal still cannot approve a snapshot, because no
scope value reaches approval. A credential scoped for policy writes still gets
403 if its principal lacks the role, once roles are enabled.

**When `skills-gateway.roles.enabled` is false.** Today every `require*()`
passes, so if API scope were the only gate a machine credential would inherit
"everything passes" — for a flag whose default is off. The scope allowlist is
therefore enforced **independently of that flag and always**. The justification
is precise: `roles.enabled=false` is a backwards-compatibility default that
exists so upgrading does not lock out existing human sessions. There is no
existing machine credential, so there is no compatibility to preserve, and the
new path can be deny-by-default from its first release. The intersection then
degrades safely: with roles off, authority is the scope allowlist alone; with
roles on, it is the narrower of the two.

## Risks / Trade-offs

- **A control-plane credential now exists at all.** → Mandatory expiry, an
  allowlist that excludes every act of judgement, a reserved principal namespace
  so misuse is legible on the ledger, and admin-scoped revocation for incidents.
- **The allowlist drifts as endpoints are added.** → The enumerating test:
  every mapped controller method is classified, and an unclassified one fails
  the build rather than defaulting to reachable.
- **One token record serving two audiences invites a "no scopes means
  unrestricted" bug.** → The three defaults are asserted together, and the
  adversarial suite leads with the every-marketplace fetch token against
  `/api/**`.
- **The session chain's `/api/**` CSRF exemption is untouched and still
  deferred.** → Not deepened: the machine chain honours no cookie and refuses a
  request carrying one, so nothing new depends on the exemption. The gap remains
  recorded against the portal work.
- **Two convergers (estate YAML and a Terraform provider) can fight.** → The
  estate is additive and never prunes, so the failure is a repeated write rather
  than a deletion; the guidance in decision 5 says which to pick, and the
  ledger's actor column is the drift report.

## Migration Plan

Additive. New columns default to the pre-change meaning (no API scope), so every
existing token stays exactly what it is: a fetch credential that cannot reach
the API. No endpoint changes behaviour for a browser session. Rollback is
removing the chain; existing credentials lose API reach and nothing else.

## Open Questions

1. **Is the `machine:` prefix the right reservation mechanism**, or should
   machine principals be a separate column with a type discriminator? The prefix
   is cheaper and needs no ledger change; a column is harder to spoof if a
   future principal source is added that does not validate the namespace.
2. **Should a machine credential be able to write role grants at all**, even
   with the no-self-grant guard? `estate.grants` covers the declarative case,
   which may make the scope unnecessary rather than merely guarded.
3. **Does the enumerating allowlist test belong in this change or ahead of it?**
   It is the single control that keeps decision 3 true over time, and it touches
   every controller.
4. **Should the machine chain use `Bearer` or reuse HTTP Basic** as the facade
   does? Basic is what clients of the facade need and what the existing provider
   speaks; Bearer is what an API client expects and makes the chain matcher
   unambiguous. This design assumes Bearer and the trade-off is not settled.
