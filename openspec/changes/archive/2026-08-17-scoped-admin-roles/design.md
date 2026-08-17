# Design: scoped-admin-roles

## Context

The web chain authenticates (OIDC) but never authorizes: every `/api` endpoint
is open to any session. Controllers already take `Authentication` and the
codebase style is explicit calls over annotations (fail-closed gates like the
vetting check live as first-line service calls). The facade is out of scope —
its authorization is token scopes (GW_0064), a different credential for a
different surface.

## Goals / Non-Goals

**Goals:** per-marketplace approval rights; read-only auditor including the
ledger; DB-managed, audited grants with config-bootstrapped admins;
compatibility off-switch as the default.

**Non-Goals:** portal role-management UI (API-only slice), team/catalog-scoped
grants (needs #11's later slices), roles on PATs, denied-attempt alerting.

## Decisions

1. **Explicit authorization service, not method-security annotations.**
   `RoleService.requireAdmin(auth)` / `requireApprover(auth, marketplace)` /
   `requireAuditor(auth)` called as the first line of each privileged
   controller method, throwing 403. Explicit calls are greppable (the whole
   authorization surface is one `grep requireA`), testable without Spring AOP
   context tricks, and match how the vetting gate is wired. Snapshot-scoped
   endpoints resolve the marketplace via a `requireApproverOfSnapshot(auth,
   snapshotId)` helper inside the service.

2. **Deny-by-default matrix once enabled.** Mutations: `admin` everywhere;
   `approver(m)` additionally for ingest/approve/reject/re-vet/waive of its
   marketplace. Reads: the browsing surface (marketplaces, catalog, snapshot
   content/provenance/vetting, waiver lists, fetchers) stays open to any
   session — it is what the portal is for — while the ledger, its export, the
   delivery/sink/retention-candidate listings need `auditor` (or `admin`).
   Tokens stay purely owner-scoped for every session. Roles compose upward:
   admin ⊇ approver ⊇ nothing, admin ⊇ auditor.

   *Implementation note (visible spec addition):* the webhook subscriber
   listing (`GET /api/webhooks`) sat in neither enumeration above. It exposes
   receiver target URLs — operator infrastructure, not skill content — so it
   is classified with the delivery/sink listings as auditor-or-admin,
   following the deny-by-default principle rather than the browsing default.

3. **`enabled=false` is the default and short-circuits every check.** An
   upgrade must never lock the owner out of their own gateway; a deployment
   opts into enforcement after granting itself admin. With roles disabled the
   grants API still works (readable, writable by any session — it is
   inert data until the switch flips), which is how a deployment stages its
   grants before enabling. Documented explicitly.

4. **Bootstrap admins from configuration.** `skills-gateway.roles.admins`
   (list of principals) are admins by config, unrevocable via the API —
   the escape hatch that survives a bad grant edit. DB grants add to them.

5. **Grants: one row per (principal, role, marketplace).** `approver` requires
   a marketplace and it must exist at grant time; `admin`/`auditor` forbid
   one. Duplicate grants are 409. Revocation deletes the row (the audit trail
   carries the history; the table is current state, mirroring
   `webhook_subscribers` rather than the soft-delete pattern — a revoked
   grant has no future behavior to explain).

6. **`/api/me` exposes `{username, rolesEnabled, roles:[{role, marketplace}]}`**
   — additive, so the portal keeps working unchanged and can later adapt its
   controls. Config-bootstrapped admins appear as a synthetic
   `{role: "admin"}` entry.

## Risks / Trade-offs

- [Missing a privileged endpoint] → the enforcement matrix is written from a
  grep of every mapping annotation; the adversarial test walks every mutation
  as a no-role user and asserts 403 — a new unprotected mutation added later
  fails the SVC only if added to the walk, so the test also asserts the
  count of known mutation routes to force the walk to be maintained.
- [Lockout via bad grants] → config-bootstrapped admins cannot be revoked by
  API; disabling roles restores full access.
- [Grants API writable while disabled] → inert until enabled, and the flip is
  a deployment decision by whoever controls configuration — strictly more
  privileged than any API caller.

## Migration Plan

New table in `V1__init.sql` (house rule); `roles.enabled=false` default means
zero behavior change on upgrade. Enabling: grant admins (config or API), flip
the property, restart. Rollback: flip it back.

## Open Questions

None; catalog/team scoping and portal UI are declared follow-ups.
