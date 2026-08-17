# Design: token-scopes-expiry

## Context

PAT auth: `PatAuthenticationProvider` (Basic password = token) →
`TokenService.authenticate` (SHA-256 lookup, revoked excluded) → an
`Authentication` carrying only the principal name and `ROLE_GIT`. The facade
resolver (`GitFacadeConfiguration.resolvePublished`) serves any well-formed
name that is publishing, and `FetchAuditHook` reads the principal off the
`SecurityContextHolder`. Scoping therefore needs exactly one new piece of
plumbing: the authenticated `AccessToken` must travel with the
`Authentication` so the resolver and the audit hook can see it.

## Goals / Non-Goals

**Goals:** marketplace-scoped tokens enforced at the facade; expiry with no
sweep; rotation that cannot widen a grant; per-token fetch attribution.

**Non-Goals:** team entitlements (blocked on #26 roles), SSO-derived
short-lived credentials (#59 — this change is the substrate), scoping the web
API (PATs never authenticate the web surface), per-plugin/skill scopes.

## Decisions

1. **The token rides as `Authentication` details.**
   `PatAuthenticationProvider` sets the authenticated `AccessToken` as the
   token's details object. The facade resolver and `FetchAuditHook` read it
   from the security context. No second DB lookup per request, no thread-local
   of our own.

2. **Scope = list of marketplace names; empty = all.** Stored comma-delimited
   (`webhook_subscribers.events` precedent). Empty/null keeps today's
   behavior, so every existing token and every test remains valid — expanding
   scope semantics later (e.g. `team:` prefixes) stays possible because names
   are validated at creation. Creation validates each scope against registered
   marketplaces plus the catalog name (422 otherwise): a typo'd scope must
   fail loudly at issue time, not silently never match. A marketplace deleted
   later leaves a scope that matches nothing — fail-closed.

3. **Enforcement point: the facade resolver, answering not-found.** After
   resolving the name, an out-of-scope request throws
   `RepositoryNotFoundException` — byte-identical to the unknown-marketplace
   answer, so a scoped token cannot probe which marketplaces exist. The check
   sits in `resolvePublished`, which both info-refs and upload-pack route
   through. The dev-insecure-auth escape hatch never applies: `/git/**` keeps
   requiring PATs regardless.

4. **Expiry is a comparison, not a state.** `TokenService.authenticate`
   filters `expires_at <= now` exactly like `revoked_at IS NOT NULL` — the
   waiver-expiry philosophy: no sweep can be late, no scheduler can open a
   hole. `skills-gateway.tokens.max-ttl` (unset = unlimited, the compatible
   default) caps creation; a request beyond the cap is 422, never silently
   clamped.

5. **Rotation = same grant, new secret, one act.** New row copies name,
   scopes, and the *same* `expires_at` deadline (rotation must never extend or
   widen anything); `rotated_from` records lineage; the old token is revoked
   in the same transaction-shaped operation, so there is no window with two
   live secrets… deliberately wrong: the old token is revoked *first*, then
   the new issued — if the process dies between, the failure mode is "no live
   secret" (fail-closed), never "two live secrets". Only the owner can rotate
   their own token (same rule as revoke). Rotating a revoked or expired token
   is refused (409): a dead grant is not a template for a live one.

6. **Attribution: `fetch_log.token_id` (additive column).** The audit hook
   records the token id with every facade entry; admin entries leave it null.
   `AuditEntry` (export) and the audit listing carry the new field — additive
   for SIEM consumers. Portal joins are a later concern; the id is the stable
   key, and `GET /api/tokens` already gives the owner the id→name mapping.

## Risks / Trade-offs

- [Scope check depends on details plumbing] → mutants on both the scope check
  and the details wiring; a missing details object is treated as unscoped-deny?
  No: a missing token on an authenticated facade request cannot happen (the
  chain only authenticates via the provider), but the check still fails closed
  — no token details → out-of-scope for scoped enforcement is moot since
  scopes live on the token; absence of details simply means no scope data and
  the request is refused for scoped decisions. Tests pin the behavior.
- [Comma-delimited scopes] → names already match `^[a-z0-9][a-z0-9_-]*$`, so
  the delimiter cannot appear in a name.
- [Export schema gains a field] → additive JSON field; documented in the
  audit reference.

## Migration Plan

Columns folded into `V1__init.sql` (house rule). Existing rows: `scopes` null
(= all), `expires_at` null (= never), `token_id` null on old ledger entries.
No behavior change for existing tokens. Rollback = revert.

## Open Questions

None; entitlements and SSO credentials are declared follow-ups.
