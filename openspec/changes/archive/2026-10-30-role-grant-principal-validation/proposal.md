# Proposal: a role grant names its principal, and a claim its value, exactly

## Why

A role grant is matched against the session's principal name by exact string
equality, and a claim mapping against the token's claim values. Two places let
what was written differ from what is matched:

- The grants API and declared estate grants store any non-blank principal. A
  principal with surrounding whitespace, control or invisible format characters,
  or of unbounded length is stored and listed, although no session will ever
  present it, and it reads differently from what it is.
- The claim matcher trims each value from the token before comparing it, so a
  value the identity provider emits with surrounding whitespace matches a
  mapping that names the unpadded value. `GW_AUTH_0015.1 — Claim-derived roles
  are matched exactly and compose without ever widening privilege` asks for an
  exact match; trimming is a normalisation, not a match.

## What Changes

- New `GW_AUTH_0060 — A role grant names its principal exactly as a session
  presents it`. The grants API refuses, with **422** like a missing principal, a
  principal that is blank, has leading or trailing whitespace, contains a
  control, format (bidi controls, zero-width characters), line or paragraph
  separator or unpaired surrogate code point, or is longer than 255 code points.
  It is refused, never trimmed. A declared estate grant passes the same rule and
  fails as an isolated entry, also when a stored grant already carries the
  malformed principal.
- New `GW_AUTH_0061 — Claim values are matched as the identity provider delivers
  them`. A claim value is compared without trimming or any other normalisation.
  A lone string remains one value; any other non-list type grants nothing. A
  configured mapping claim value with surrounding whitespace or such characters
  refuses startup instead of being trimmed.
- New `SVC_GW_AUTH_0063` … `SVC_GW_AUTH_0067`.
- Two existing assertions that expected a padded claim value to match are
  tightened to expect no role (`SVC_GW_AUTH_0015.1` — Claim-derived roles
  compose, scope correctly, and never widen past an exact match). That SVC's own text already
  lists whitespace near-misses as conferring nothing.
- Not **BREAKING** for the API contract: the 422 response already exists and
  only its description widens. A deployment whose provider pads group names
  would lose the derived role; no mainstream provider does.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-roles`: adds `GW_AUTH_0060` — A role grant names its principal exactly
  as a session presents it, and `GW_AUTH_0061` — Claim values are matched as
  the identity provider delivers them.

## Impact

- `roles`: `RoleService.grant`, `ClaimRoleMapper`, a new `RolePrincipals`
  rule beside them; `EstateReconciler.reconcileGrant`.
- API description of `POST /api/v1/roles` (`openapi.json`, `types.gen.ts`).
- Docs: `reference/api/roles.md`, `reference/configuration.md`,
  `guides/identity-providers.md`, `guides/declarative-estate.md`,
  `guides/delegated-administration.md`.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
