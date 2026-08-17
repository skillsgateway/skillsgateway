# Delegated administration: scoped admin roles

GitHub issue #26; stacked on `feat/token-scopes-expiry` (PR #63 ← #61 ← #60).

## Why

Any authenticated browser session can today register, ingest, approve, revoke,
waive, delete, and read the full ledger — the portal's OIDC login is the only
privilege there is. An organization delegating marketplace curation needs
approval rights **per marketplace**, a read-only audit role, and a way to grant
and revoke these without redeploying — while a small deployment must keep
working exactly as before.

## What Changes

- **Three roles**: `admin` (global — everything), `approver` (scoped to one
  marketplace — ingest, approve/reject, re-vet, waive for it), `auditor`
  (read-only including the ledger and export). "Catalog manager" from the
  issue folds into `admin` for this slice; team catalogs don't exist yet.
- **Grants in the database**, managed by admins via a new `/api/roles` API,
  every grant and revocation audit-logged. **Bootstrap admins from
  configuration** (`skills-gateway.roles.admins`) so the first admin needs no
  API access to exist.
- **Off by default** (`skills-gateway.roles.enabled=false`): every check
  passes and today's behavior is untouched — an upgrade never locks anyone
  out. Enforcement is deny-by-default once enabled.
- **Enforcement uniformly at the REST API** — which is what the portal (BFF)
  and the future admin CLI (#16) both speak. A user with no grant keeps the
  browsing surface (marketplaces, catalog, snapshot review reads) and their
  own tokens; the ledger and every mutation need a role.
- `/api/me` gains the caller's effective roles, so the portal (and CLI) can
  adapt; no portal UI changes in this slice.
- Out of scope: per-team catalog scoping (needs #11's later slices), portal
  role-management UI, PAT-side roles (the facade's authorization is token
  scopes, unchanged).

## Capabilities

### New Capabilities

- `admin-roles`: the role model and grants, deny-by-default enforcement, the
  auditor's read-only guarantee, and the grant audit trail (GW_0068–GW_0071).

### Modified Capabilities

<!-- none: GW_0011 (OIDC-only web surface) is unchanged; authorization layers
     on top of the unchanged authentication -->

## Impact

- **Schema**: new `role_grants` table.
- **Backend**: new `roles` package (`RoleService`, `RoleController`,
  `RoleGrantRepository`); one authorization call at the top of every
  privileged controller method (admin, sync, catalog, retention, webhook,
  audit, revet, waiver controllers); `MeController` gains roles;
  `SkillsGatewayProperties.Roles`.
- **Trust boundary**: authorization on the approval path and the web surface —
  old-coder Tier 3, adversarial privilege-escalation tests and mutants
  mandatory.
- **API**: `/api/roles` CRUD; `/api/me` extended. OpenAPI + TS types
  regenerate.
- **Docs**: new guide, configuration + API reference, trust-boundaries
  concept (replacing its "no role model yet" section), glossary,
  ARCHITECTURE note.
- **Traceability**: GW_0068–GW_0071 + SVC_GW_0068–SVC_GW_0071.
