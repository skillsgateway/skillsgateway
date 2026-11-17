# Proposal: tokens become dynamic: scopes can be edited after creation

No tracked issue. Stacked on `portal-pat-scopes` (draft PR #893), itself stacked on
`portal-token-homes` (draft PR #889); it builds on the personal tokens page, the
marketplace picker and the System access tokens page and does not redo them.

## Why

A token's scopes are fixed when it is made. To narrow a pipeline's credential, or to
give it one more marketplace, the owner must mint a new token, hand out the new
secret and revoke the old one. That is the right path when the secret may be exposed
(it is what rotation is for) and the wrong one when only the reach has to change: the
consumer is disturbed for a change that has nothing to do with the secret, and people
leave credentials broader than needed because narrowing them is a chore.

## What Changes

- **Personal access tokens: `PATCH /api/v1/tokens/{id}`**, body either `{ "scopes": [...] }`
  (non-empty) or `{ "allMarketplaces": true }`.
  The owner replaces the marketplaces the token may fetch while it is live (neither
  revoked nor expired). Narrowing is always allowed; every name is validated exactly as
  at creation (a registered marketplace, or the catalog's name). Every marketplace is
  only ever stated: an empty list, neither field, both, or `allMarketplaces: false` are
  refused 400 (revised after the owner's decisions; see the design).
- **System access tokens: `PATCH /api/v1/tokens/machine/{id}`**, body
  `{ "apiScopes": [...] }`. An administrator replaces the API scopes of a live
  credential, under every invariant of the model: at least one scope, each checked
  against `MachineApiRegistry`, no wildcard, the credential's fetch scope untouched.
  Both routes join the unreachable list of the machine-reachability registry.
- **Nothing else changes.** Secret, expiry, name, principal, owner, the other scope
  dimensions and the last-use record are untouched. Rotation keeps copying the current
  scopes, and is made to do so even when an edit races it (design D5).
- **Strict bodies.** A body carrying any other field (`pushScopes`, `apiScopes` on a
  personal token, `scopes` on a machine credential, `expiresAt`, `name`) is refused
  `400`, and so is a body without the list (`422`). Push scopes are not editable.
- **Audit.** One entry per change, in the same transaction as the change:
  `token-scopes-changed` and `machine-credential-scopes-changed`, naming the actor, the
  token and the scopes before and after.
- **Portal.** An *Edit reach* control per live token in the personal tokens list
  (reusing `MarketplaceScopePicker`) and an *Edit scopes* control per live token on the
  System access tokens page (reusing its scope checklist, extracted). Save is disabled
  until valid and different, with the reason on screen; widening is warned about;
  refusals are shown as the API states them; the list shows the new scopes.
- **Docs:** `portal.md`, `api/tokens.md`, `api/audit.md` in the same PR.

Reqstool: `GW_AUTH_0083` — The owner of a personal access token can change the
marketplaces it fetches while it is live; `GW_AUTH_0084` — An administrator can change
the API scopes of a live system access token; `GW_AUTH_0085` — Changing a token's
scopes changes nothing else about it; `GW_AUTH_0086` — Every change of a token's scopes
is recorded on the ledger with before and after; `GW_AUTH_0087` — The scope-change
routes accept the scope list and nothing else; `GW_AUTH_0088` — A user can change the
marketplaces of a personal access token from the portal; `GW_AUTH_0089` — An
administrator can change the scopes of a system access token from the portal.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `token-lifecycle`: the two edit routes, their refusals, the ledger entries, rotation
  after an edit.
- `admin-portal`: the two edit controls.

## Stop rule

No backend package, estate object type, grantable role, sweep or configuration leaf is
added. Two routes and two audit event names on existing controllers; `capability-map.md`
need not absorb anything. No Flyway migration: the columns, their `TEXT[]` type and
the constraints that refuse an empty `api_scopes` already exist (design D9). Push
scopes are deliberately not touched.

## Impact

Backend: `TokenController`, `MachineTokenController`, `TokenService`, `TokenRepository`,
`MachineApiRegistry` (two unreachable entries). Frontend: `pages/tokens.tsx`,
`pages/system-tokens.tsx`, a new edit dialog per page, `api/queries.ts`,
`lib/token-scope.ts`. Generated: `openapi.json`, `types.gen.ts` (additive within the
major). Portal change: the PR carries before/after screenshots, left unuploaded for the
owner's approval.
