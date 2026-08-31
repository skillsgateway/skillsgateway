# Design: remove-roles-enabled-toggle

## Context

Authorization on the web surface is expressed as explicit `require*()` calls at
the first line of every privileged controller method
(`RoleService`, greppable as `requireA`). Every one of them begins with the same
short-circuit:

```java
public void requireAuditor(Authentication authentication) {
    if (!enabled()) {
        return;
    }
    ...
}
```

`enabled()` reads `skills-gateway.roles.enabled`, whose default is set in the
compact constructor of `SkillsGatewayProperties.Roles`:

```java
if (enabled == null) {
    enabled = false;
}
```

`application.yaml` never mentions the property, so `false` is the default for
every profile, production included. The consequence is documented rather than
mitigated: `RoleService`'s class javadoc, `GW_0068`'s rationale
("defaulting the switch to off means an upgrade can never lock an existing
deployment out of its own gateway"), a `!!! danger` admonition in the
Kubernetes guide, and an `IMPORTANT:` comment in the Helm chart all say the same
thing — a gateway installed and left alone grants full administrative access to
anyone who can complete an OIDC login.

Two constraints shape the replacement:

1. **The compatibility argument has expired.** `0.1.0` is tagged, but its
   release run was cancelled: no artifacts were published, so there is no
   deployed consumer whose upgrade could be locked out. This is the only window
   in the product's life in which the argument for defaulting off is simply
   false rather than merely weak.
2. **A precedent already exists in this codebase for how a dangerous switch
   should behave.** `skills-gateway.dev-insecure-auth` names the danger in the
   property, logs a loud startup warning, and has `DevInsecureAuthGuard`, a bean
   whose only job is to throw when the flag is on somewhere it cannot belong,
   with a refusal message that lists the evidence and both ways to resolve it.
   That guard is the model for everything below.

The repo owner has already made the decision: delete the property, enforce
always, fail closed at startup, and keep local development on the *existing*
escape hatch. This document designs it; it does not re-argue it.

## Goals / Non-Goals

**Goals:**

- Authorization on the web surface is unconditional. No property, profile or
  deployment shape can switch it off.
- A gateway that nobody could administer refuses to start, and its refusal
  states exactly that and exactly how to fix it.
- A deployment that still sets the removed property learns at startup, not by
  discovering later that its intent was silently reversed.
- Local development through `skills-gateway.dev-insecure-auth` keeps working
  end-to-end, including administrative operations, with no new property.
- Every `require*` in `RoleService` becomes one code path instead of two, so
  there is no longer a mode in which the authorization tests are vacuous.

**Non-Goals:**

- Changing the role model: `admin ⊇ approver ⊇ (scope)` and
  `admin ⊇ auditor` are untouched, as are the grant lifecycle, claim mapping
  and approver scoping.
- Changing the facade (`/git/**`) or the machine-credential chain. Both already
  authorize without consulting the flag (token scopes, GW_0064; the
  `MachineApiRegistry` allowlist, GW_0129); this change only deletes the
  javadoc sentences that explained *why* they did not consult it.
- Introducing a first-run bootstrap UI, an invite flow, or a "first login
  becomes admin" rule. Those are designs of their own; a configured admin is
  the bootstrap.
- Reading the database to decide whether the gateway may start (see
  Decision 3).

## Decisions

### Decision 1 — Delete the property; do not flip its default

Flipping the default to `true` would leave a supported way to turn
authorization off. Every subsequent incident review would then have to ask
whether the flag was set, every deployment template would have to assert it,
and the property would keep its dishonest name. Deleting it removes the
question permanently: there is one posture, so the tests exercise the only
posture there is.

*Naming rationale, recorded because it is the reason for deletion rather than a
flip.* `enabled` is positively named for the secure state, which means absence —
the state every unconfigured deployment is in — yields the insecure state. It
also reads as a feature toggle for an optional capability ("do we want the roles
feature?") when what `false` actually means is "authorization is off for this
gateway". A property that meant what it did would be named the way its sibling
is: `dev-insecure-auth` puts the danger in the identifier, warns loudly, and is
policed by a guard. Rather than rename this one to something equally honest
(`insecure-authorization-disabled`, say) and keep an off switch nobody should
ever use, the switch goes.

*Alternative considered:* keep the property, default it to `true`, and have a
guard refuse to start when it is explicitly `false` on a deployment with a
configured identity provider — the exact `DevInsecureAuthGuard` shape. Rejected:
the guard's own justification is that the escape hatch it polices serves a real
local-development need that nothing else covers. An off switch for authorization
has no such need — `dev-insecure-auth` already covers the local case — so the
property would exist only to be refused.

### Decision 2 — `RoleService` loses its two-path shape entirely

`enabled()` is deleted along with the `if (!enabled()) return;` prologue in
`requireAdmin`, `requireAuditor`, `requireApprover` and `requireApproverOf`.

`requireAdminRegardlessOfEnforcement` (GW_0130) collapses into `requireAdmin`
and the method disappears. It exists only to draw a distinction against a
default that is going away; keeping it would leave a name asserting a contrast
with nothing. Its callers (`MachineTokenController`'s minting, listing and
revocation paths) switch to `requireAdmin`, which is now strictly stronger than
what the old method promised. The requirement text for GW_0130 keeps the
*substance* — minting requires the administrative role — and loses only the
"whether or not role enforcement is enabled" qualifier.

### Decision 3 — The bootstrap check reads configuration only, never the database

A new guard bean refuses to start when **no configured principal could hold the
administrative role**. Three configuration paths satisfy it:

1. a non-empty `skills-gateway.roles.admins`;
2. at least one `skills-gateway.roles.mappings[*]` with `role: admin`;
3. at least one `skills-gateway.estate.grants[*]` with `role: admin`.

It deliberately does **not** query `role_grants`. Reasons, in order of weight:

- The stable property is "somebody can administer this gateway *by
  configuration*", which is exactly what `roles.admins` was introduced to
  guarantee — `RoleService` calls it "the escape hatch that survives a bad grant
  edit", and GW_0071 makes config admins unrevokable by any API call. A stored
  grant is revocable, so a startup check satisfied by one can be invalidated
  while the process runs; it would prove nothing after the first `DELETE`.
- A database-reading guard has an ordering problem against `EstateReconciler`,
  which writes declared grants at startup: the guard would either race it or
  have to be sequenced behind it, coupling a security check to reconciliation
  timing.
- A startup path that fails when the database is unreachable turns a transient
  dependency outage into a refusal to boot, on a check that has nothing to do
  with the database.

**Consequence, and it is a real one:** a deployment whose only admin is a stored
`role_grants` row would be refused. Today that costs nothing (no published
artifact, no deployment), and the migration step is one line of configuration.
It is called out under Open Questions because it is the one place where a
reasonable owner might prefer the looser check.

Ordering and skips:

- The guard skips entirely when `skills-gateway.dev-insecure-auth=true`
  (Decision 5 makes the escape hatch's principal an admin, so the estate *is*
  administerable). `DevInsecureAuthGuard` independently refuses that
  combination on anything that looks like a real deployment, so the skip cannot
  become a production bypass.
- The refusal follows the `DevInsecureAuthGuard` message shape: what was
  detected, why it matters, and each way to resolve it, quoting property names
  in full.

Draft refusal text (final wording lives in the implementation):

```
Authorization is always enforced, and this gateway has no administrator.

Nothing in this deployment's configuration grants the admin role, so no
identity could administer the gateway once it started -- no marketplace could
be registered, no snapshot approved and no role granted. Rather than start in
that state, the gateway refuses.

Grant the admin role in one of these ways and restart:
  * skills-gateway.roles.admins: a list of principals that are admins by
    configuration and that no API call can revoke;
  * skills-gateway.roles.mappings: a mapping from one of your identity
    provider's claim values to the admin role;
  * skills-gateway.estate.grants: a declared grant of the admin role.

For local development with no identity provider, set
skills-gateway.dev-insecure-auth=true instead; its "dev" principal
administers, and it refuses to start on a configured deployment.
```

### Decision 4 — The removed property is actively rejected, not ignored

Spring ignores unknown properties, which produces two silent outcomes:
`roles.enabled=true` becomes a no-op (harmless), and `roles.enabled=false`
becomes "authorization is enforced" (safe, but the *opposite* of what the
operator wrote). The safe direction is exactly the problem: an operator who
deliberately switched authorization off — for a reason, good or bad — would have
their configuration reversed with no signal anywhere. Configuration that is
read and configuration that is ignored are indistinguishable in a manifest, and
this is the property that decides whether the gateway has authorization at all.

So the guard also refuses to start when `skills-gateway.roles.enabled` is set,
with a message naming the removal and the release it happened in. Relaxed forms
come for free: `Environment.containsProperty("skills-gateway.roles.enabled")`
resolves `SKILLSGATEWAY_ROLES_ENABLED`, `skills-gateway.roles.enabled` and
`skills_gateway.roles.enabled` alike, because Boot's system-environment property
source does the relaxed lookup itself. This also matches the precedent the
project already accepts for configuration mistakes: GW_0111 has the gateway
refuse to start on a storage backend it cannot honour and "state in every such
refusal which setting it was decided by".

*Lifetime.* The refusal is a migration aid, not a permanent feature: it is
removed in the next major, and `reference/compatibility.md` records both that it
exists and that it is scheduled to go. Keeping it forever would leave the
deleted property named in the code indefinitely.

*Alternative considered:* a metadata-only deprecation
(`additional-spring-configuration-metadata.json` with
`deprecation.level: error`). Rejected: that reaches IDE completion and the
`spring-boot-properties-migrator` module, neither of which is in the runtime
path of a Kubernetes deployment, so the operator who most needs the message is
the one who would not see it.

### Decision 5 — The development escape hatch's principal administers

With enforcement unconditional, `skills-gateway.dev-insecure-auth=true` would
otherwise break local development completely. Its synthetic principal is
`UsernamePasswordAuthenticationToken.authenticated("dev", null,
ROLE_USER)` — not an `OidcUser`, so `ClaimRoleMapper` derives nothing from it by
construction (deliberately: "a personal access token, the `dev-insecure-auth`
principal and an anonymous request have no claims and derive nothing"), and
`rolesOf("dev")` finds nothing unless `dev` happens to be in `roles.admins`.
Every mutation in the portal would 403.

The interaction must therefore be stated, and it is: **while
`skills-gateway.dev-insecure-auth=true`, the synthetic `dev` principal holds the
global `admin` role**, contributed by `RoleService` as an `EffectiveRole` with a
new source constant alongside `config`, `grant` and `claim` — proposed value
`"dev-insecure-auth"`. Using its own source rather than `config` matters: it is
what makes `/api/me` say *why* the session is an admin, so a developer looking
at the portal can see the escape hatch is what is privileging them, and so a
screenshot from such a session can never be mistaken for a configured one.

Three constraints keep this from being a new hole:

1. It applies only while `properties.devInsecureAuth()` is true. With the flag
   off, a principal literally named `dev` gets nothing (adversarial test).
2. It applies only to a principal that is **not** an `OidcUser` and whose name
   is exactly the synthetic name. A real OIDC session named `dev` gets nothing
   from this path (adversarial test).
3. It grants no new authority in practice. The flag already opens the entire web
   surface to an unauthenticated caller and `DevInsecureAuthGuard` already
   refuses to start on anything resembling a deployment, so the escape hatch's
   blast radius is unchanged — it is the guard, not the role model, that keeps
   this out of production.

Placement: in `RoleService`, not in the filter that mints the principal.
`RoleService` is the single place that answers "what may this session do", so
`/api/me`, every `require*` and `effectiveRoles` agree by construction. Adding
`ROLE_ADMIN`-ish authorities in the filter instead would leave `effectiveRoles`
disagreeing with the checks.

### Decision 6 — `/api/me` drops `rolesEnabled` (and this is the breaking API bit)

`MeView.rolesEnabled` becomes a constant `true`. A field with one possible value
is a field that answers a question nobody can ask, and leaving it would recreate
in the API the dishonesty this change removes from the configuration. It goes,
and `msw-handlers.ts` and the regenerated `openapi.json` / `types.gen.ts` follow.
No portal component reads it today.

Removing a response field is explicitly non-additive under
`reference/compatibility.md` → "The API contract" ("Removing or renaming an
endpoint or field … **No.** New prefix, and a major"). Two things follow, and
both are deliberate:

- The **API contract** workflow's oasdiff step will classify a breaking change
  and require the PR title to carry `!` or `BREAKING CHANGE`, plus the
  `⚠️ BREAKING CONTRACT` label.
- The compatibility page's "new prefix, and a major" clause cannot be honoured
  literally: there is no `/api/v1` prefix yet (the page says so itself), and the
  product is pre-1.0 where a `!` bump lands on the minor. The PR body should say
  that plainly rather than pretend the prefix rule was satisfied.

*Alternative considered:* keep `rolesEnabled`, always `true`, `@Schema`-marked
deprecated, and remove it at the next major. It costs one field, keeps oasdiff
green, and makes the PR non-breaking. Recorded here as the owner's fallback if
they would rather not spend a breaking declaration on this change (see Open
Questions); the tasks assume removal.

### Decision 7 — Is the configuration surface covered by the compatibility promise?

`reference/compatibility.md` → "The API contract" is scoped to `/api/**`: "what
its own HTTP surface *promises*". Nothing on that page, or anywhere else in the
manual, extends the additive-within-a-major promise to
`skills-gateway.*` configuration properties, the Helm values, or the declarative
estate. Issue #121 flags that this is *undecided* rather than deliberately
excluded.

This change does not settle #121 — that is a page-level decision of its own —
but it cannot leave the question implicit either, because it deletes a property.
So: the change proceeds on the reading that the configuration surface is **not
currently covered** by the API-contract promise, and the docs task adds one
sentence to the compatibility page saying so explicitly and pointing at #121 as
the open decision. That converts an implicit assumption into a stated one, which
is the least this change can honestly do.

Either way, the PR title declares a break, because Decision 6 removes an API
field. Concretely:

```
fix!: always enforce authorization and refuse to start without an administrator
```

If the owner takes the Decision 6 fallback (keep `rolesEnabled`), the API break
disappears and the property removal alone remains. The title should still carry
`!` in that case — a deployment whose configuration stops being accepted is a
breaking change to that deployment whatever the compatibility page currently
promises, and the `!` is the only place that fact reaches the release notes.

## Risks / Trade-offs

- **A shared test-base admin masks a missing check.** With enforcement always
  on, roughly 40 test classes that call `mockMvc.perform(... .with(oidcLogin()))`
  as the default `user` principal would 403 unless `AbstractGatewayTest`
  bootstraps an admin (`skills-gateway.roles.admins=user`). Doing so makes every
  inherited context administratively privileged, which could hide a controller
  method that forgot its `require*` call. → Mitigation: the armor already
  exists and must be kept honest. SVC_GW_0068's walk asserts its route set
  **complete against the running application's own route table** with a no-role
  session, so a forgotten check surfaces there rather than in the suites that
  inherit the admin. The revision of SVC_GW_0068 must preserve that
  completeness assertion word for word in substance; a task calls that out.
- **The bootstrap guard refuses a deployment administered only by stored
  grants** (Decision 3). → Mitigation: no such deployment exists yet; the
  refusal names the three configuration paths; documented as a migration step.
- **The guard's dev-insecure-auth skip is a bypass if `DevInsecureAuthGuard`
  ever weakens.** The bootstrap check is skipped on the strength of another
  guard's refusal. → Mitigation: an adversarial test asserts that
  `dev-insecure-auth=true` plus a configured identity provider still refuses to
  start (SVC_GW_0110 already covers this; the new SVC must not create a path
  around it), and the two guards stay in the same package so the coupling is
  visible.
- **A real OIDC principal named `dev`.** → Mitigation: constraint 2 of
  Decision 5, with a negative test.
- **The e2e suite is the only place the whole chain is proven.** It runs the
  real jar against a mock IdP whose token carries
  `groups: ["sg-gateway-admins"]`, mapped to `admin`. After this change that
  mapping is not a nicety, it is what satisfies the bootstrap check — so an
  accidental removal of the mapping turns into a startup refusal in CI rather
  than a silent loss of coverage. That is the desired direction, and it should
  be noted in the script's comment so the next reader knows why the mapping
  cannot simply be deleted.
- **A native-image consideration.** The new guard reads `Environment` and
  `SkillsGatewayProperties` only — no reflection, no resource scanning — so the
  GraalVM native release profile needs no hints. Confirmed by the native
  compile in the gate list rather than asserted.

## Migration Plan

For any deployment that exists by the time this ships (today: none):

1. Before upgrading, add one of `skills-gateway.roles.admins`, an `admin` claim
   mapping, or a declared `admin` estate grant. Naming a principal that already
   holds admin through a stored grant is the smallest step.
2. Remove `skills-gateway.roles.enabled` from all configuration — the
   application refuses to start while it is set, in any form, including
   `SKILLSGATEWAY_ROLES_ENABLED`.
3. Restart. Sessions that previously passed every check by default now hold
   exactly the roles they are granted; `/api/me` reports them with the source of
   each.

Rollback is the previous image plus restoring the property; nothing in this
change writes to the database or alters the schema, so a rollback needs no data
migration.

## Open Questions

1. **Decision 3's strictness.** Should a stored `role_grants` admin row satisfy
   the bootstrap check, at the cost of a database read at startup and an
   ordering relationship with `EstateReconciler`? The design says no; the owner
   may prefer yes for operational forgiveness.
2. **Decision 6.** Remove `/api/me.rolesEnabled` (breaking, declared) or keep it
   deprecated and always `true` (non-breaking)? The tasks assume removal.
3. **Issue #121.** This change states that the configuration surface is not
   covered by the API-contract promise and points at #121; it does not decide
   #121. Should it instead settle the question in the same PR?
4. **The refusal's lifetime** (Decision 4). Removed at the next major is
   proposed; the owner may prefer to keep it indefinitely as a permanent
   tripwire.
