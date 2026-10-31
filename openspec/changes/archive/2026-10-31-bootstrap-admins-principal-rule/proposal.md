# Proposal: a configured administrator names its principal exactly

## Why

Since `GW_AUTH_0060 — A role grant names its principal exactly as a session
presents it`, grant principals and claim values are matched byte for byte and a
malformed one is refused. The bootstrap administrators in
`skills-gateway.roles.admins` were left out, but they are matched the same way:
an entry such as `"bob "` or one carrying a zero-width character never equals
any session's principal, so the gateway starts, the bootstrap guard counts the
entry as an administrator, and the operator is locked out without a word.

## What Changes

- `GW_AUTH_0060 — A role grant names its principal exactly as a session
  presents it` is widened: the gateway refuses to start when an entry of the
  configured administrator list breaks the same principal rule (blank,
  surrounding whitespace, control / invisible format / line or paragraph
  separator / unpaired surrogate code point, over 255 code points). The refusal
  names the entry by its position, never its value, like the mapping refusal of
  `GW_AUTH_0061 — Claim values are matched as the identity provider delivers
  them`. The entry is refused, never trimmed.
- New `SVC_GW_AUTH_0085 — A malformed configured administrator refuses startup,
  naming its position`.
- Not **BREAKING** for the API contract. A deployment with a malformed entry
  stops starting; that entry never matched anyone.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-roles`: `GW_AUTH_0060` — A role grant names its principal exactly as a
  session presents it — gains the configured administrator list and
  `SVC_GW_AUTH_0085`.

## Impact

- `roles`: `RoleBootstrapGuard` applies the existing `RolePrincipals` rule to
  each configured administrator; `RolePrincipals` exposes the rule with its
  length bound so the grants API and the guard share it.
- Docs: `reference/configuration.md` (`skills-gateway.roles.admins`).

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf, and no new requirement.
