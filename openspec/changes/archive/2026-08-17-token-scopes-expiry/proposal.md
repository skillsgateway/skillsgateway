# Token lifecycle: scopes, expiry, rotation, attribution

GitHub issue #13; stacked on `feat/virtual-catalog` (PR #61 ← PR #60).

## Why

A PAT today is all-or-nothing and immortal: any valid token fetches any
marketplace forever (until manually revoked), and the ledger attributes fetches
to a principal but not to which of their tokens — so a leaked CI token cannot
be told apart from the owner's laptop, cannot be limited in blast radius, and
never dies on its own. Issue #13 names the four gaps: scopes, expiry, rotation,
attribution.

## What Changes

- **Scoped PATs**: a token may carry a list of marketplace names (the catalog's
  name is a valid scope). The facade refuses an out-of-scope fetch with the
  same not-found answer an unknown marketplace gets — a scope never reveals
  what it does not grant. An empty scope list keeps today's all-marketplaces
  behavior, so existing tokens are untouched.
- **Expiry**: a token may carry an expiry; an expired token fails
  authentication exactly like a revoked one, decided by comparing the stamp at
  authentication time — no sweep, mirroring waiver expiry. A configurable
  `max-ttl` (default unlimited, for compatibility) caps what creation accepts.
- **Rotation**: `POST /api/tokens/{id}/rotate` issues a fresh secret with the
  same grant — name, scopes, and the same expiry deadline (rotation changes
  the secret, never widens or extends the grant) — revokes the old token in
  the same act, records the lineage, and returns the cleartext exactly once.
- **Attribution**: every facade fetch records the token used (`token_id`
  ledger column, additive) alongside the principal; token lifecycle events
  (created/revoked/rotated) are audited with the token's name and scopes.
- Out of scope (follow-ups): team entitlements (needs #26 roles), SSO-derived
  short-lived credentials (#59 hybrid — this change is its foundation),
  per-skill scoping.

## Capabilities

### New Capabilities

- `token-lifecycle`: scopes and their facade enforcement, expiry, rotation,
  and per-token attribution (GW_0064–GW_0067).

### Modified Capabilities

<!-- none: GW_0012/GW_0013 (PAT auth, hashed storage, show-once) are unchanged;
     the new requirements layer on top without altering their text -->

## Impact

- **Schema**: `access_tokens` gains `scopes TEXT`, `expires_at TIMESTAMPTZ`,
  `rotated_from BIGINT`; `fetch_log` gains `token_id BIGINT` (additive; export
  consumers see a new field).
- **Trust boundary (facade auth — old-coder Tier 3)**: scope check in the
  facade resolver, expiry check in `TokenService.authenticate`,
  `PatAuthenticationProvider` carries the authenticated token for the facade
  and the audit hook. Adversarial tests + mutants mandatory.
- **API**: create gains `scopes`/`expiresAt`; new rotate endpoint; token views
  gain the new fields. OpenAPI snapshot + TS types regenerate.
- **Config**: `skills-gateway.tokens.max-ttl` (unset = unlimited).
- **Docs**: consuming-skills, new tokens reference sections, configuration,
  trust-boundaries concept, glossary.
- **Traceability**: GW_0064–GW_0067 + SVC_GW_0064–SVC_GW_0067.
