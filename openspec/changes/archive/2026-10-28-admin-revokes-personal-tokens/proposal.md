# Proposal: an administrator can list and revoke a principal's personal tokens

## Why

Only a token's owner can list or revoke a personal access token or session
credential: the routes are scoped to the caller. When someone leaves, or their
account is compromised, their tokens keep authenticating to the facade until
they expire, and by default they never expire. Administrators already have
this reach over machine credentials, for the reason the listing's own comment
gives: a credential nobody can see is a credential nobody can revoke during an
incident.

## What Changes

- `GET /api/v1/tokens/principals/{principal}` lists a principal's personal
  tokens and session credentials, never any secret. It is admin only.
- `DELETE /api/v1/tokens/principals/{principal}` revokes every live one of them
  and returns the ids. It records `principal-tokens-revoked` on the ledger and
  is admin only.
- Machine credentials are left out of both; they keep their own routes under
  `/api/v1/tokens/machine`.
- Neither route is reachable by any machine credential, like all of
  `/api/v1/tokens/**`.
- New `GW_AUTH_0055 — An administrator lists and revokes another principal's
  personal tokens`, verified by `SVC_GW_AUTH_0055`.
- Additive API change. No new role, setting or estate object: tokens stay
  API-only, as their design says.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `token-lifecycle`: adds `GW_AUTH_0055`.

## Impact

- New `PrincipalTokenController`; `TokenService`, `TokenRepository`,
  `MachineApiRegistry` (the unreachable list).
- `openapi.json` and the generated portal types.
- Docs: `reference/api/tokens.md`, `reference/api/audit.md`.
