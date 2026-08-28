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

The append-only ledger is `fetch_log`. Its columns include `source TEXT NOT
NULL` — which types the *surface* (`admin` from `AdminAuditLogger`, the facade's
own value from `FetchAuditHook`), not the actor — a nullable `principal`, and
`token_id BIGINT`, which the schema comment says is "deliberately not a foreign
key: the ledger is append-only history and must outlive any token row."

That last comment matters for decision 4, because the codebase has already made
the argument once.

## Goals / Non-Goals

**Goals:**

- A credential a Terraform provider or a CI job can hold, with no browser.
- A credential that reaches configuration and reads, and no act of human
  judgement.
- Per-concern scopes, right at first issue — narrowing later would silently
  change what already-issued credentials mean.
- No weakening of the facade's confinement, and no weakening of the session
  path's current posture.
- One lifecycle — issue, rotate, revoke, expire — not two.

**Non-Goals:**

- Approval, rejection, waivers, retention deletion or role granting by machine.
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
call the API" a single grant, and the API is emphatically not one thing —
see decision 3, where it becomes seventeen. A list of named scope values is what
makes that granularity expressible at all.

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
Decision 4 (the explicit actor type) is what keeps that from becoming an
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
  chain and matched additionally on the presence of an `Authorization: Bearer`
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

**Bearer, not Basic (decided).** The facade and publication chains use HTTP
Basic because that is what clients of a smart-HTTP remote speak — the username
is ignored and the password is the token. The machine API has no such
constraint, and `Bearer` is what an API client library expects. Two concrete
benefits beyond convention:

- **The chain matcher becomes unambiguous.** Matching on the presence of a
  `Bearer` scheme separates the machine chain from the session chain by a
  positive, single-valued signal. Matching on Basic would collide with the
  scheme the facade already uses, so a misrouted request would be a plausible
  credential on the wrong chain rather than an obvious non-match.
- **The both-credentials rule becomes clean to state.** "Reject a request
  carrying a `Bearer` header and a `Cookie` header" is a single unambiguous
  sentence, testable as written. With Basic it would have to distinguish which
  Basic credential was meant for which chain, which is the ambiguity the rule
  exists to eliminate.

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

### 3. Scopes: per-concern, never one coarse "admin"

A single configuration scope would mean a credential minted for a pipeline that
registers marketplaces could also rewrite audit sinks and policy rules.
Narrowing it afterwards is a **breaking change to the meaning of
already-issued credentials** — the same secret would silently do less — so the
granularity has to be right at first issue.

The set below is derived from the actual `@RequestMapping` inventory, not from a
sketch. Reading the controllers changed two of the classifications in the first
draft of this design, which is the argument for deriving it this way.

**Reachable scopes.**

| Scope | Endpoints |
| --- | --- |
| `marketplaces:read` | `GET /api/marketplaces`, `GET /api/catalog`, `GET /api/snapshots/{id}/content`, `/licenses`, `/provenance`, `/release-age` |
| `snapshots:read` | `GET /api/snapshots/{id}/diff`, `/file`, `/files`, `/vetting`, `/fetchers` |
| `marketplaces:register` | `POST /api/marketplaces` |
| `marketplaces:ingest` | `POST /api/marketplaces/{name}/ingest` |
| `vetting:run` | `POST /api/marketplaces/{name}/revet`, `POST /api/snapshots/{id}/revet` |
| `waivers:read` | `GET /api/marketplaces/{name}/waivers` |
| `sync:write` | `PUT /api/marketplaces/{name}/sync` |
| `catalog:rebuild` | `POST /api/catalog/rebuild` |
| `webhooks:read` | `GET /api/webhooks`, `/deliveries`, `/events` |
| `webhooks:write` | `POST /api/webhooks`, `DELETE /api/webhooks/{id}` |
| `audit:read` | `GET /api/audit`, `GET /api/audit/export` |
| `audit-sinks:read` | `GET /api/audit/sinks` |
| `audit-sinks:write` | `POST /api/audit/sinks`, `DELETE /api/audit/sinks/{id}`, `PUT /api/audit/sinks/{id}/cursor` |
| `policy:read` | `GET /api/policy/rules`, `POST /api/policy/playground` |
| `policy:write` | `POST /api/policy/rules`, `PUT /api/policy/rules/{name}`, `DELETE /api/policy/rules/{name}` |
| `retention:read` | `GET /api/retention/candidates` |
| `estate:read` | `GET /api/estate` |
| `estate:reconcile` | `POST /api/estate/reconcile` |
| `adoption:read` | `GET /api/adoption`, `GET /api/adoption/staleness` |
| `roles:read` | `GET /api/roles` |

**Unreachable — no scope value reaches these, and none may be added.**

| Endpoint | Why |
| --- | --- |
| `POST /api/snapshots/{id}/approve` | publishes content; human judgement |
| `POST /api/snapshots/{id}/reject` | human judgement |
| `POST /api/snapshots/{id}/waivers` | overrides the vetting chain; human judgement |
| `DELETE /api/waivers/{id}` | withdraws an override; human judgement |
| `POST /api/retention/evaluate` | **soft-deletes** candidates: retracts content |
| `POST /api/retention/compact` | **purges** permanently: retracts content |
| `DELETE /api/snapshots/{id}` | retracts content |
| `POST /api/snapshots/{id}/restore` | republishes content |
| `POST /api/roles`, `DELETE /api/roles/{id}` | privilege granting; see below |
| all of `/api/tokens/**` | credential minting, including session credentials |
| `GET /api/me` | a session identity page; a machine has no session |

**Corrections the controller inventory forced.** Two, both worth recording
because they were wrong in the first draft:

- **Re-vetting is reachable, and was wrongly listed as judgement.** `POST
  /snapshots/{id}/revet` re-runs the vetting chain and records evidence. It
  publishes and retracts nothing — the schema comment on `vetting_runs` says a
  snapshot "stays `held` whatever the chain says", because the chain gates the
  approval rather than being it. Generating fresh evidence on a schedule is a
  legitimate and valuable machine job, so it gets `vetting:run`.
- **Retention `evaluate` and `compact` are unreachable, and were wrongly
  treated as mere configuration.** `evaluate` soft-deletes every candidate it
  finds and `compact` purges permanently; both retract content, which is the
  exact line `CLAUDE.md` and the estate guide draw. Only the `candidates`
  preview is reachable. A first draft that had said "retention configuration" as
  a scope would have handed a pipeline the ability to purge snapshots.

**Composition rules.**

- **No implicit all.** An empty API scope list grants nothing — the push-scope
  default, deliberately, and the opposite of the fetch-scope default. There is
  no wildcard value and no `*`; a credential that needs three concerns names
  three scopes.
- **Scopes compose additively.** Effective reach is the union of the named
  scopes' endpoint sets, with no scope implying another. `policy:write` does not
  confer `policy:read`; naming both is the explicit way to have both. Implication
  chains are how coarse scopes grow back.
- **The allowlist applies on top, always.** No combination of scopes — not all
  twenty at once, not held by a principal with `admin` — reaches anything in the
  unreachable table. Reach is the intersection of the allowlist and the named
  scopes, never their union.

**Why an allowlist and not a denylist.** A denylist admits by silence. Every
endpoint added after this change would be machine-reachable by default,
including the next act of judgement someone adds, and the mistake would be
invisible in the diff that made it. An allowlist fails the other way: a new
endpoint is unreachable until someone names it, which surfaces as a bug report
rather than as an incident. The enumerating test that keeps this true is a
**prerequisite PR**, not part of this change — see task group 0.

**Role grants are not machine-reachable (settled).** Writing `role_grants` from
infrastructure-as-code is a real need, but `estate.grants` already serves it
declaratively, with the same validation, attributed to `config-reconciler`, and
**with no credential in the pipeline at all**. A machine write path would add
attack surface — a credential that can write grants can escalate whatever it can
reach — for a capability that already has a safer route. It is easy to add later
and hard to remove once issued credentials depend on it, so it stays out.

`GET /api/roles` **is** reachable as `roles:read`. This is a deliberate narrow
reading of "role grants should not be machine-reachable": reading who holds
what is not granting, and it is what lets a pipeline detect drift between
`estate.grants` and reality — the one thing the estate's additive,
never-pruning model cannot tell you by itself. Flagged for the owner as the
single place this design does not take the instruction at its widest.

### 4. Audit attribution: an explicit actor type on the ledger row

**The current state is worse than it looks.** `AdminAuditLogger.record` takes
`authentication.getName()` and writes it to `fetch_log.principal`. But three
non-human actors already exist, and each is a magic string smuggled into that
same column:

- `EstateReconciler.ACTOR = "config-reconciler"`
- `SyncService.SCHEDULER_ACTOR = "scheduler"`
- `"system"`, hard-coded in `WaiverService`

So the ledger *already has* an actor-type vocabulary. It is implicit, undeclared,
enforced by nothing, and distinguishable only by string comparison against
values that also look like ordinary principal names. The `source` column does
not help: it types the surface (`admin` versus the facade), not the actor.

Adding a fourth magic string — a `machine:` prefix — would extend that mistake
and require the defensive parsing rule the first draft proposed ("refuse an
identity-provider subject literally named `machine:x`"). The need for that rule
was the smell.

**Decision.** Add an explicit `actor_type` column to `fetch_log`, denormalised,
`NOT NULL`, alongside the existing human-readable `principal`:

| `actor_type` | `principal` | Meaning |
| --- | --- | --- |
| `human` | the identity-provider subject | an interactive session |
| `machine` | the credential's name | a machine API credential |
| `system` | `config-reconciler`, `scheduler`, `system` | the gateway acting on its own |

- **Denormalised, never a join.** A ledger row written years ago must still say
  what it meant, standing alone, after the credential it names has been revoked
  and its row deleted. A join to `access_tokens` would make the ledger's meaning
  depend on mutable state, which is precisely what an append-only ledger must not
  do. **The codebase has already made this argument**: `fetch_log.token_id` is
  "deliberately not a foreign key: the ledger is append-only history and must
  outlive any token row." `actor_type` is that same reasoning applied to the
  actor rather than the credential, and consistency with an existing, documented
  decision is the strongest form this argument can take.
- **Queryable without parsing.** `WHERE actor_type = 'machine'` is an index-able
  predicate. `WHERE principal LIKE 'machine:%'` is string parsing that a
  legitimate principal could collide with and that no constraint protects.
- **The prefix rule disappears.** No reserved namespace, no refusing an
  identity-provider subject for how it is spelled, no defensive parsing anywhere.
  A machine credential's name is just a name.
- **Existing non-human actors become explicit.** `config-reconciler`,
  `scheduler` and `system` get `actor_type = 'system'` at their call sites,
  turning today's implicit vocabulary into a declared one. This is a strict
  improvement to the ledger independent of machine credentials.
- **`token_id` is populated on machine-API entries.** It is NULL on admin entries
  today. Setting it gives a leak trace the same per-credential resolution the
  facade already has, and rotation lineage is followable from any single row.
- **The provisioning human is recorded as the credential's owner** — on the
  `token-created` entry and on the administrative listing. There is always a
  responsible human, named once at provisioning rather than impersonated on
  every use.

Schema change is acceptable: the project is pre-1.0.0 and `CLAUDE.md` has new
columns folded into `V1__init.sql`. Existing rows get `actor_type` backfilled
from the three known magic strings, defaulting to `human`.

**Where this argument could be wrong, stated honestly.** `actor_type` is
denormalised data that can disagree with the token table if a future call site
sets it incorrectly — a normalised join could never disagree. That trade is
accepted deliberately: a ledger that is *consistent with mutable state* is worse
than a ledger that is *self-contained and possibly stale*, because the ledger's
whole job is to say what was true when the row was written. The mitigation is
that `actor_type` is set in one place, `AdminAuditLogger`, from the
authentication itself rather than passed by each caller.

### 5. Estate YAML versus the API

State the rule plainly, because the overlap is where operators guess wrong:

> **Declare converge-able state. Call for acts and reads.**

- **Estate YAML** is for desired state the gateway re-converges on every boot:
  marketplaces, role grants, webhook subscribers, audit sinks. It is additive,
  idempotent, never prunes, applies the same trust boundary as the API, and —
  the decisive property — needs **no credential in the pipeline at all**. Where
  estate YAML covers the object, it remains the recommendation, including for
  Terraform shops: a provider that converges the same objects is a second
  converger with a secret. Role grants are the sharpest case: the estate is the
  *only* route, by decision 3.
- **The API with a machine credential** is for what has no declarative form:
  acts (trigger a reconcile, rebuild the catalog, run a re-vet), reads (the
  ledger, the estate report, adoption, drift), one-shot secrets (the webhook
  sync-mode secret, returned exactly once), and objects that are not estate
  types (policy rules). It is also the answer where the gateway's configuration
  is genuinely not the deployer's to write — a managed platform where the values
  file belongs to another team.
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
  secrets. It preserves the machine identity, the expiry **deadline**, and all
  three scope dimensions — a rotation that silently widened or dropped API
  scope would be the worst possible bug in this feature, and it gets a test per
  scope value, not one test for "scopes".
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

Two of the three role sources already work for a machine credential's principal,
and the third provably cannot.

- **`claim` cannot apply.** `ClaimRoleMapper` reads claims only from an
  `OidcUser`; a token has none and derives nothing. This is not a gap to close —
  it is the correct behaviour, and the design depends on it.
- **`config` and `grant` work unchanged**, because a principal is just a string.
  `skills-gateway.roles.admins` may list a machine credential's principal, and
  `role_grants` may carry one — which means `estate.grants` can *declare* it
  with no new mechanism at all. Since decision 3 makes the grants API
  unreachable by machine, the estate is the **only** way a machine credential
  acquires a role, which is the intended and safer path.
- **No fourth source, and no role baked into the token.** Putting a role in the
  credential would create a second authorization model that no longer answers to
  `RoleService`, and revoking it would mean rotating a secret rather than
  deleting a row.

**Scope and role are both required, never either.** Effective authority is the
**intersection**: the allowlist ∧ the credential's named API scopes ∧ the
principal's roles. An `admin`-granted machine credential still cannot approve a
snapshot, because no scope value reaches approval. A credential scoped
`policy:write` still gets 403 if its principal lacks the role, once roles are
enabled.

**When `skills-gateway.roles.enabled` is false.** Today every `require*()`
passes, so if roles were the only gate a machine credential would inherit
"everything passes" — for a flag whose default is off. Scope and allowlist
enforcement is therefore **independent of that flag and always on**. The
justification is precise: `roles.enabled=false` is a backwards-compatibility
default that exists so upgrading does not lock out existing human sessions.
There is no existing machine credential, so there is no compatibility to
preserve, and the new path can be deny-by-default from its first release. The
intersection then degrades safely: with roles off, authority is allowlist ∧
scopes; with roles on, it narrows further.

## Risks / Trade-offs

- **A control-plane credential now exists at all.** → Mandatory expiry, an
  allowlist that excludes every act of judgement and every retraction, explicit
  actor typing so misuse is legible on the ledger, and admin-scoped revocation
  for incidents.
- **Twenty scopes is more surface to get right than one.** → Accepted
  deliberately: coarse-to-fine is a breaking change to issued credentials,
  fine-to-coarse is not. The enumerating test (task group 0) is what keeps the
  mapping honest, and no scope implies another, so there are no chains to
  reason about.
- **The allowlist drifts as endpoints are added.** → The prerequisite
  enumerating test: every mapped controller method is classified, and an
  unclassified one fails the build rather than defaulting to reachable.
- **One token record serving two audiences invites a "no scopes means
  unrestricted" bug.** → The three defaults are asserted together, and the
  adversarial suite leads with the every-marketplace fetch token against
  `/api/**`.
- **`actor_type` is denormalised and could disagree with the token table.** →
  Accepted, argued in decision 4; set in one place from the authentication
  itself.
- **The session chain's `/api/**` CSRF exemption is untouched and still
  deferred.** → Not deepened: the machine chain honours no cookie and refuses a
  request carrying one, so nothing new depends on the exemption.
- **Two convergers (estate YAML and a Terraform provider) can fight.** → The
  estate is additive and never prunes, so the failure is a repeated write rather
  than a deletion; decision 5 says which to pick, and the ledger's actor column
  is the drift report.

## Migration Plan

Additive. The new API scope column defaults to the pre-change meaning (no API
scope), so every existing token stays exactly what it is: a fetch credential
that cannot reach the API. `actor_type` is backfilled from the three known
magic strings and otherwise defaults to `human`. No endpoint changes behaviour
for a browser session. Rollback is removing the chain; existing credentials lose
API reach and nothing else.

## Open Questions

1. **`roles:read` as `GET /api/roles`** — the one place this design narrows the
   owner's "role grants should not be machine-reachable" to writes only, on the
   argument that drift detection needs it. Easy to drop.
2. **`GET /api/me` is unreachable**, so a machine credential has no "does my
   token work and what can it do" endpoint. A dedicated introspection endpoint
   would be genuinely useful and is deliberately not proposed here.
3. **Should `estate:reconcile` and `catalog:rebuild` be one `operations:run`
   scope?** They are both "trigger a job that publishes nothing". Kept separate
   on the no-implicit-widening principle, but the granularity may be finer than
   any operator wants.
