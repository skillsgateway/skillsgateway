# Design: push-scope-requires-approver

## Context

See proposal.md — Why. `TokenController.create` passes `pushScopes` to
`TokenService.create`, whose `validatePushScopes` checks only that each name is
a registered hosted marketplace. `GitPublishConfiguration.resolveOrigin` then
treats `token.permitsPushTo(marketplace)` as the whole authorization for both
receive-pack and upload-pack. `RoleService.requireApprover(authentication,
name)` already answers "admin, or approver of this marketplace" from every role
source, including identity-provider claims.

## Goals / Non-Goals

- Goal: a push scope exists only on a token minted by someone with authority
  over that marketplace.
- Non-goal: re-checking the owner's standing when the token is used, or
  removing push scopes when a role is revoked. A token outliving its owner's
  standing is the general credential-lifetime question, and it covers fetch
  tokens too; it is a separate change.
- Non-goal: a publisher role. Admin or approver is enough. A new grantable role
  would have to extend `skills-gateway.estate.*` and group mapping, and nothing
  asks for it.

## Decisions

### D1 — The check is an explicit `require*` call in the controller

`TokenController.create` calls `roleService.requireApprover(authentication,
name)` for each distinct push scope before it calls `TokenService.create`, the
same shape as every other privileged endpoint (`RoleService` is "explicit
`require*` calls at the first line of every privileged controller method").

Alternative: pass the caller's roles into `TokenService.validatePushScopes`.
Rejected: the service takes a principal string, not an `Authentication`, so it
cannot see claim-derived roles, and moving role resolution into the token
service would put a second authorization surface where nobody greps for one.

### D2 — All or nothing

One unauthorized name refuses the whole request, and no token is issued.
Dropping that name and issuing the rest would hand back a credential that
differs from the one asked for, with nothing in the response saying so.

### D3 — Authorization before validation

A non-admin naming an unknown or non-hosted marketplace gets `403`, not `422`,
because `requireApprover` runs first and no approver role can name an
unregistered marketplace. An administrator still gets the existing `422`.
Answering `403` first also keeps the endpoint from telling a caller with no
standing which names are hosted. That is not secret, since the browsing surface
lists marketplaces, but there is no reason for this endpoint to be the place
that says so.

### D4 — Rotation, session and machine credentials are unchanged

- Rotation copies the old grant (`GW_AUTH_0008 — Access token rotation`). Refusing a
  rotation would leave the old, still-live token in place, so it would remove no
  authority. That is the use-time question excluded above.
- Session credentials already carry no push scope (`GW_AUTH_0018 — Session-derived short-lived git credentials`).
- Machine credentials are minted by administrators and store no push scope.

### D5 — Requirement wording

`GW_FACADE_0007 — Authenticated publication over a separate write endpoint`
says a push is authorized "only by a token scope granted explicitly per
marketplace". It is revised (revision 0.2.0) to add that the system grants such
a scope only to a requester who is an administrator or an approver of that
marketplace, and refuses the request, issuing no token, otherwise. The new
`SVC_GW_FACADE_0035` verifies it. `SVC_GW_FACADE_0007` is unchanged.

### D6 — Tests reuse existing contexts

`PushScopeMintingTests` runs on the shared `AbstractGatewayTest` context. It
uses unique principals, so the base context's `user,root,alice` administrators
are never the caller under test unless the scenario says so. Claim-derived
standing is checked in `AbstractClaimMappingTest`'s context, where `gw-admins`
maps to admin and only `root` is a configured administrator. No new context, so
`ContextBudgetTests` does not move.

## Risks / Trade-offs

- [A deployment whose publishers hold no role stops minting new push-scoped
  tokens] → Publishing requires an approver grant on that marketplace. The 403
  message says which marketplace, the docs say who may mint, and tokens already
  issued keep working.
- [Tokens minted before this change keep their push scopes] → Pre-1.0 there is
  no migration. An operator can check
  `SELECT id, principal, push_scopes FROM access_tokens WHERE push_scopes IS NOT NULL AND revoked_at IS NULL`.
