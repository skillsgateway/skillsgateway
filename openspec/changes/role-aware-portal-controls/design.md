# Design: role-aware-portal-controls

## Context

`RoleService` decides every refusal from the session's effective roles:
`requireAdmin` (any `admin`), `requireAuditor` (`admin` or `auditor`),
`requireApprover(name)` (`admin`, or `approver` scoped to exactly that name),
and the snapshot- and waiver-id variants, which resolve the owning marketplace
and apply the approver rule. `GET /api/v1/me` reports the same roles. The portal
already reads it once per load (`useMe`) and uses it in two places: `useIsAdmin`
hides the estate-wide vetting chain, and the marketplace Settings section hides
its administrator controls.

## Decisions

### D1 — One client mirror of the role rule

`src/api/access.ts` holds `accessOf(roles)`. It returns `isAdmin`, `isAuditor`
and `approves(marketplace)`, each written as the matching `RoleService` check
(an `admin` role passes all three, and an `approver` passes only for its exact
marketplace name). `useAccess()` wraps it around `useMe`, and `useIsAdmin`
delegates to it. The rule is copied once, so a later change to `RoleService` has
one place to follow.

Each surface maps to the check its endpoint makes:

| Surface | Endpoint check | Portal gate |
| --- | --- | --- |
| Ingest | `requireApprover(name)` | approves(name) |
| Approve, Reject, Waive, Revoke waiver, Re-vet, re-run chain | `requireApproverOfSnapshot` / `OfWaiver` | approves(marketplace of the page) |
| File changes (diff), fetchers of a revoked snapshot | `requireApproverOfSnapshot` | approves(...) |
| Delete / Restore snapshot, Register marketplace | `requireAdmin` | isAdmin |
| Audit log, export, adoption, webhooks list, sinks list, a marketplace's activity | `requireAuditor` | isAuditor |
| Webhook / sink create, delete, replay | `requireAdmin` | isAdmin (unchanged) |

The snapshot components learn the marketplace's rights from a React context that
`MarketplaceLayout` provides, the only place snapshots are decided. Outside a
provider (stories and single-component tests) nothing is narrowed, which is what
those surfaces render today.

### D2 — Hide the control, explain only where its absence would mislead

A control the session cannot use is not rendered. This matches what the portal
already does for administrator controls. Design-conventions forbids a pressable
control the server would refuse. A disabled button for every role-gated action
would add a reason line to each one, for controls the reader can never enable.

A missing control needs an explanation in one place: a held snapshot's decision
row, where the reader came to decide. There the row says "Approving or
rejecting a snapshot of `<name>` needs the approver role for it." A Review
section with nothing ingested says "Nothing has been ingested yet." rather than
telling the reader to ingest.

### D3 — One shared "needs role X" state for pages and sections

`RoleRequired` renders the page heading (when it is a page), an alert line
"This page needs the auditor role." (or "administrative role", or "approver role
for `<name>`"), and the page's own one-line explanation. `RoleGate` wraps a page:
while the session is being read it shows the loading line. When the role is
missing it shows `RoleRequired` and mounts nothing else, so a refused read is
never sent. `VettingRefusal` becomes an instance of it, and its existing alert
text is kept.

The snapshot explorer keeps its own 403 state (`Forbidden`), because that is
driven by the server's answer per read, and `GW_INGEST_0045 — Addressable
snapshot file inspection` already specifies it.

### D4 — Navigation leaves out what the session cannot read

The estate-wide sidebar already leaves out the vetting chain for anyone but an
administrator. The same rule now covers the Oversight entries and Integrations
(auditor). A group with no entries left is not drawn. A marketplace's Activity
section follows the same rule. This keeps `GW_AUTH_0048 — Portal navigation
separates records from configuration and keeps moved addresses`: the grouping is
unchanged for a session that sees the entries, and every address still resolves,
now to the role-required state. The Overview's ledger card is shown only to a
session that can read the ledger, so its count always resolves.

### D5 — A 403 re-reads the session

`createQueryClient()` (used by `main.tsx`) installs `QueryCache` and
`MutationCache` `onError` hooks. An `ApiError` with status 403 invalidates
`["me"]`, unless the query that failed is `me` itself. TanStack refetches the
active session read. Structural sharing keeps the same object when nothing
changed, so an unchanged answer re-renders nothing and cannot loop. A 403 is
also not retried: the role will not appear between two attempts.

### D6 — Copy

- The no-role user menu: "No role. You can browse the marketplaces and their
  snapshots' vetting, and manage your own access tokens. Anything else needs a
  role an administrator grants." This matches `GW_AUTH_0010 — Scoped admin roles
  with deny-by-default enforcement`, under which the browsing surface and token
  self-service stay open.
- The vetting chain refusal now names what an approver can read: each snapshot's
  Vetting tab, which shows the vetters that ran on it and what each found.
  `GET /api/v1/snapshots/{id}/vetting` is open to every session. The
  per-marketplace chain read is administrator-only.

## Risks / Trade-offs

- **The portal mirror can drift from `RoleService`.** If it drifts, the result is
  a hidden control the server would allow, or a shown one it refuses. D5
  corrects the second at the first refusal. The server stays the authority.
- **No e2e for a non-admin principal.** The mock IdP puts the admin group claim
  in every token (`compose.e2e.yaml`), so a non-admin login needs a second IdP
  mapping. Vitest covers each persona against the real components instead.
