# Proposal: a push scope is granted only by authority over the marketplace

## Why

A push scope is the only thing `/publish/{name}` checks, and today
`POST /api/v1/tokens` issues one to any authenticated session that asks: the
scope is checked for naming a hosted marketplace, never for who is asking.
Publication authority over a marketplace should come from authority over that
marketplace, the same standing the REST API already requires to ingest it or
read its pinned files.

## What Changes

- `POST /api/v1/tokens` with a non-empty `pushScopes` succeeds only when the
  caller is an administrator or an approver of **every** marketplace named
  there. Otherwise the request is refused with `403`, as every other role check
  is, and no token is issued. Roles count from every source the role model
  already honours: configuration, grants and identity-provider claims.
- A request without push scopes is unchanged: any session still mints its own
  fetch tokens (`GW_AUTH_0010 — Scoped admin roles with deny-by-default enforcement`).
- Rotation is unchanged. It copies the existing grant and widens nothing
  (`GW_AUTH_0008 — Access token rotation`).
- Not **BREAKING**: the request and response shapes are the same, and `403` is
  added to the documented responses. A caller who could publish before and holds
  a role keeps working.
- No new configuration leaf, role or Spring test context:
  `ConfigSurfaceBudgetTests` and `ContextBudgetTests` do not move.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `git-facade`: `GW_FACADE_0007 — Authenticated publication over a separate
  write endpoint` is revised to say who may be granted a push scope; new
  `SVC_GW_FACADE_0035` verifies it.

## Impact

- `TokenController.create`, `RoleService` (called, not changed).
- `openapi.json` and the generated portal API types (one added `403` response).
- Docs: `reference/api/tokens.md`, `guides/publishing-first-party-skills.md`,
  `concepts/trust-boundaries.md`.
