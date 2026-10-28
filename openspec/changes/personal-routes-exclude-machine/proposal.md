# Proposal: the personal-token routes reach personal tokens only

## Why

`TokenRepository.listByPrincipal`, `findByIdAndPrincipal` and
`revoke(id, principal)` matched rows by principal name, machine credentials
included. A machine credential's principal is a free-form name, so a person
logging in with that name could list it, revoke it, or rotate it. Rotation
rebuilt it as a personal token with no API scopes, and its empty fetch scope
then means every marketplace.

## What Changes

- The three owner-scoped queries match `api_scopes IS NULL`, so machine
  credentials are reached only through `/api/v1/tokens/machine`.
- New `GW_AUTH_0057 — The personal-token routes reach personal tokens only`,
  verified by `SVC_GW_AUTH_0057`.
- Docs: `reference/api/tokens.md`.
- Not **BREAKING**.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `token-lifecycle`: adds `GW_AUTH_0057`.

## Impact

- `TokenRepository`.
