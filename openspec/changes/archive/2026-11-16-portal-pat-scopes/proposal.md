# Proposal: limit a personal access token to chosen marketplaces, in the portal

Issue: skillsgateway/skillsgateway#888. Stacked on `portal-token-homes` (#886, draft
PR #889): it builds on that change's personal tokens page, shared reveal dialog and
lifetimes and does not redo any of them.

## Why

`POST /api/v1/tokens` has accepted `scopes` since `GW_AUTH_0006` — Marketplace-scoped
access tokens, but the portal sends only `{ name, expiresAt }`. Every token minted
from the personal tokens page or the client set-up wizard therefore fetches every
marketplace the gateway serves, and the only way to ask for less is to hand-write an
API call, which the people who most need a narrow credential (a pipeline that reads
one marketplace) are least likely to do. The token list cannot tell a narrow token
from a broad one either.

## What Changes

- **A marketplace choice on both token forms.** A two-way choice: *Every marketplace*
  (the default, as the API's) or *Chosen marketplaces*, which opens a searchable
  checklist of the registered marketplaces plus the virtual catalog by its configured
  name. The request carries `scopes` for the chosen names and none for the default.
  The limited choice with nothing ticked cannot be submitted (an empty `scopes` means
  *every* marketplace, so sending it would grant the opposite of what was chosen).
  The API's refusal of a scope is shown as the API states it.
- **The wizard** keeps *Every marketplace* as its default and, on switching to
  *Chosen marketplaces*, starts with its own marketplace ticked and widenable. Its
  statement of what the token reaches follows the choice, before and after creation.
  This amends `GW_AUTH_0063` — The client wizard states what a token reaches and what
  the ledger records (the "not limited" sentence becomes conditional on no limit having
  been chosen).
- **The token list** gains a *Reach* column: "every marketplace" in words, or the names
  (long lists shortened, the whole list a hover or a read away), and the hosted
  marketplaces a token may publish to when it has any.
- **Cleartext retention.** The personal tokens page and the wizard reset the creating
  mutation once they have taken the cleartext, as `portal-token-homes` did for system
  access tokens, with a test.
- **One additive API field.** The served catalog revision (`GET /api/v1/catalog`, any
  signed-in caller) gains `name`, the configured catalog name, because that name is a
  valid scope, is configurable and is readable nowhere else.
- **Docs:** `portal.md` (personal access tokens, Connect a client) and
  `api/tokens.md` in the same PR.

Reqstool: `GW_AUTH_0079` — A personal access token can be limited to chosen
marketplaces from the portal; `GW_AUTH_0080` — The personal token list states each
token's reach; `GW_AUTH_0081` — A personal access token's cleartext is kept nowhere
once shown; `GW_AUTH_0082` — The gateway names its virtual catalog to any signed-in
reader; `GW_AUTH_0063` — The client wizard states what a token reaches and what the
ledger records is amended.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-portal`: the scope choice, the list's reach column, the cleartext rule, the
  wizard's wording.
- `virtual-catalog`: the served revision carries the catalog's name.

## Stop rule

Nothing is added that the stop rule counts: no backend package, estate object type,
grantable role, sweep or configuration leaf. The new field is one component of an
existing response record (design D2), not a route. `capability-map.md` need not absorb
anything. Push scopes are deliberately **not** built (design D6); that is an owner
decision recorded in the PR.

## Impact

Frontend: `pages/tokens.tsx`, `components/setup-wizard.tsx`, a new
`components/marketplace-scope-picker.tsx`, `api/queries.ts`, `lib/token-scope.ts`.
Backend: `CatalogService.CatalogInfo` (one component). Generated: `openapi.json`,
`types.gen.ts`. Enforcement and the token endpoints do not change. Portal change: the
PR carries before/after screenshots (left unuploaded for the owner's approval).
