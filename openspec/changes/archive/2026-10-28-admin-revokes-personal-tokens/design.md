# Design: admin-revokes-personal-tokens

## Context

See proposal.md, Why. `TokenRepository.revoke(id, principal)` and
`listByPrincipal` are the only paths to a personal token, and both are keyed to
the caller. The machine-credential routes (`GW_AUTH_0024 — A machine credential
must expire and is administered centrally`) are the existing pattern for administrative reach.

## Decisions

### D1 — Per principal, not per token id

Offboarding is "stop everything this person holds", and that is one call. An
administrator who needs to see what that would stop lists first. A per-id admin
revoke would add a route without adding a capability the bulk one lacks.

### D2 — Personal tokens only

Both queries filter `api_scopes IS NULL`. A machine credential has a
provisioning owner and its own lifecycle, so revoking it as a side effect of
offboarding whoever shares its principal name would be a surprise. It keeps its
own routes.

### D3 — Under `/api/v1/tokens/**`

The machine registry classifies all of `/api/v1/tokens/**` unreachable, and the
docs say so. Placing the routes there keeps that sentence true, and the routes
are listed explicitly in the unreachable set.

### Not in this change

Binding a token's validity to its owner's standing (an inactivity window, an
IdP deprovisioning signal, `max-ttl` enforced at authentication) is a broader
lifetime question that also covers session-derived roles, so it is left for a
separate change.

## Risks / Trade-offs

- [Principal names are free-form strings] → An unknown name lists and revokes
  nothing. That is the correct answer, and the ledger records the attempt.
